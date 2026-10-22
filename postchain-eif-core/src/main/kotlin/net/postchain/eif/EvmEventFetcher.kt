package net.postchain.eif

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withTimeoutOrNull
import mu.KLogging
import mu.withLoggingContext
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.common.types.WrappedByteArray
import net.postchain.common.wrap
import net.postchain.concurrent.util.get
import net.postchain.core.BlockchainEngine
import net.postchain.eif.EvmEventProcessor.EvmBlock
import net.postchain.eif.web3j.Web3jRequestHandler
import net.postchain.gtv.GtvBigInteger
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvInteger
import net.postchain.gtv.GtvNull
import org.web3j.abi.EventEncoder
import org.web3j.abi.datatypes.Event
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.methods.request.EthFilter
import org.web3j.protocol.core.methods.response.EthLog
import org.web3j.protocol.core.methods.response.Log
import org.web3j.tx.Contract
import java.math.BigInteger
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

/**
 * Reads events from evm chain.
 *
 * @param evmReadOffset We will read this number of blocks from the block head on the evm chain, to avoid issues with chain reorg
 * @param readCursorStore Node-local persistence of the read position, so that a restarted reader resumes where it left off
 * @param cursorPersistInterval How often the read position is persisted while reading (ms)
 */
class EvmEventFetcher(
        private val networkId: Long,
        private val staticContracts: List<Pair<WrappedByteArray, Long>>,
        private val hasLegacyDynamicContracts: Boolean,
        private val hasDynamicContacts: Boolean,
        private val staticEvents: List<Event>,
        private val hasDynamicEvents: Boolean,
        private val evmReadOffset: BigInteger,
        private val maxReadAhead: Long,
        private val networkSkipToHeight: Long,
        private val blockchainEngine: BlockchainEngine,
        private val web3jRequestHandler: Web3jRequestHandler,
        private val delayWhenNoNewBlocks: Long,
        private val hasLastEvmEventHeightQuery: Boolean,
        private val evmEventProcessor: EvmEventProcessor,
        private val readCursorStore: EvmReadCursorStore,
        private val cursorPersistInterval: Long = DEFAULT_CURSOR_PERSIST_INTERVAL,
        private val clock: Clock = Clock.systemUTC(),
) : EventFetcher {
    companion object : KLogging() {
        const val DEFAULT_CURSOR_PERSIST_INTERVAL = 10_000L
        private const val SHUTDOWN_JOIN_TIMEOUT = 2_000L
    }

    /** Where reading starts for one contract and why */
    private data class ContractStartPoint(
            val contract: WrappedByteArray,
            val height: BigInteger,
            val usedSkipToHeightFallback: Boolean,
            val usedPersistedCursor: Boolean,
    ) {
        /** The contract, its start height and where that height came from, for log messages */
        fun describe(): String = "contract 0x${contract.data.toHex()} at height $height (" + when {
            usedPersistedCursor -> "persisted read cursor"
            usedSkipToHeightFallback -> "no committed events, using skip-to-height"
            else -> "last committed event height"
        } + ")"
    }

    private var job: Job

    // Read position: what the read cursor covers and how it was initialized

    /** False until the read position has been initialized from the persisted read cursor and the on-chain heights */
    @Volatile
    private var readPositionInitialized = false

    /** Contracts currently in the filter, with the effective skip-to-height each one had when it entered the filter */
    @Volatile
    private var filterContracts = mapOf<WrappedByteArray, Long>()

    /** Set when the read position was moved backwards, reported once the EVM chain head is known */
    @Volatile
    private var pendingRewindReason: String? = null

    // Cursor persistence: when the read cursor was written and what it said

    /** Set when the read cursor must be persisted before the next fetch, regardless of [cursorPersistInterval] */
    @Volatile
    private var cursorPersistRequested = false

    /** Time of the last successful read cursor persist, in [clock] millis, 0 before the first one */
    @Volatile
    private var lastCursorPersistTime = 0L

    // Exposed for EifMetricsRegistry, not used by the reader itself

    /** Last EVM chain head height reported by the RPC node, before any read offset is applied */
    @Volatile
    var lastSeenEvmHeadHeight: BigInteger = BigInteger.ZERO
        private set

    /** Number of readable EVM blocks (head minus the EVM read offset) not read yet, 0 when caught up */
    @Volatile
    var readLagBlocks: BigInteger = BigInteger.ZERO
        private set

    /** Height of the last persisted read cursor, null if nothing has been persisted by this reader yet */
    @Volatile
    var lastPersistedCursorHeight: BigInteger? = null
        private set

    /** Times the read position was moved backwards because a contract entered the filter */
    val newContractFlushes = AtomicLong()

    /** Times the read position was moved backwards because a block proposal did not match, on a replica or a syncing validator */
    val validationConflictFlushes = AtomicLong()

    /** Block proposals this node, as a signer, rejected because their EVM blocks differ from the ones it read */
    val signerValidationConflicts = AtomicLong()

    init {
        job = launchEventFetching()
    }

    private fun launchEventFetching() =
            CoroutineScope(Dispatchers.IO).launch(CoroutineName("$networkId-event-processor") + MDCContext()) {
                withLoggingContext(NETWORK_ID_TAG to networkId.toString()) {
                    while (isActive) {
                        try {
                            fetchEvents()
                        } catch (_: CancellationException) {
                            break
                        } catch (e: Exception) {
                            logger.error("Parsing of EVM logs unexpectedly failed: $e", e)
                            delay(500.milliseconds) // Delay a bit and hope that we can recover
                        }
                    }
                }
            }

    /**
     * Producer thread will read events from ethereum ond add to queue in this action. Main thread will consume them.
     */
    private suspend fun fetchEvents() {
        withLoggingContext(NETWORK_ID_TAG to networkId.toString()) {
            if (cursorPersistRequested) {
                cursorPersistRequested = false
                persistReadCursor()
            }

            // Contracts
            val contractsConfig = fetchDynamicContractConfigs().union(staticContracts)
            val contracts = resolveContractsSkipToHeight(contractsConfig)
            logger.debug { "Contracts: $contracts" }

            if (contracts.isEmpty()) {
                logger.warn { "No contracts configured, trying again later" }
                filterContracts = mapOf()
                delay(delayWhenNoNewBlocks.milliseconds)
                return
            }

            if (readPositionInitialized) {
                handleNewContracts(contracts)
            } else {
                initializeReadPosition(contracts)
            }

            val from = evmEventProcessor.lastReadLogBlockHeight + BigInteger.ONE

            val evmHeadHeight = web3jRequestHandler.sendWeb3jRequestWithRetry { it.ethBlockNumber() }.blockNumber
            lastSeenEvmHeadHeight = evmHeadHeight
            val currentBlockHeight = evmHeadHeight - evmReadOffset
            reportPendingRewind(from, currentBlockHeight)
            // Pacing the reading of logs
            val to = minOf(currentBlockHeight, from + BigInteger.valueOf(maxReadAhead))

            if (to < from) {
                readLagBlocks = BigInteger.ZERO
                logger.debug { "No new blocks to read. We are at height: $to" }
                persistReadCursorIfDue()
                // Sleep a bit until next attempt
                delay(delayWhenNoNewBlocks.milliseconds)
                return
            }

            // Events
            val eventMap = fetchEventConfigs()
            logger.debug { "Events: ${eventMap.values.map { it.name }}" }
            if (eventMap.isEmpty()) {
                logger.warn { "No events configured, trying again later" }
                delay(delayWhenNoNewBlocks.milliseconds)
                return
            }

            // Fetch events
            val filter = EthFilter(
                    DefaultBlockParameter.valueOf(from),
                    DefaultBlockParameter.valueOf(to),
                    contracts.map { "0x${it.first.toHex()}" }.toList()
            )
            filter.addOptionalTopics(*eventMap.keys.toTypedArray())

            val logResponse = web3jRequestHandler.sendWeb3jRequestWithRetry { it.ethGetLogs(filter) }

            // Ensure events are sorted on txIndex + logIndex, blocks sorted on block number
            val sortedEncodedLogs = logResponse.logs
                    .map { (it as EthLog.LogObject).get() }
                    .groupBy { EvmBlock(it.blockNumber, it.blockHash) }
                    .mapValues { it.value.sortedWith(compareBy({ event -> event.transactionIndex }, { event -> event.logIndex })) }
                    .mapValues {
                        it.value.filter { event ->
                            val contractAddress = parseEvmAddress(event.address)
                            val skipToHeight = getLastCommittedEvmEventHeight(networkId, contractAddress.data)
                                    ?: BigInteger.valueOf(contracts.find { contract -> contract.first == contractAddress }?.second
                                            ?: networkSkipToHeight)
                            event.blockNumber > skipToHeight
                        }
                    }
                    .toList()
                    .filter { it.second.isNotEmpty() }
                    .sortedBy { it.first.number }
                    .map { eventBlockToOp(eventMap, it) }
            evmEventProcessor.processLogEventsAndUpdateOffsets(sortedEncodedLogs, to)
            readLagBlocks = currentBlockHeight - to
            persistReadCursorIfDue()

            // If we just saw one new block, we can probably sleep
            if (to == from) {
                delay(delayWhenNoNewBlocks.milliseconds)
            }

            while (evmEventProcessor.isQueueFull()) {
                logger.debug("Wait for events to be consumed until we read more")
                delay(500.milliseconds)
                persistReadCursorIfDue()
            }
        }
    }

    /**
     * Decides where to start reading when the reader starts. Every contract gets the highest start point known to be
     * safe for it:
     * - the persisted read cursor, if it covers the contract (same address and skip-to-height): everything up to that
     *   height has already been read with the contract in the filter and either committed or found empty;
     * - otherwise (no cursor persisted yet, or the contract was added or its skip-to-height changed since the cursor
     *   was recorded) the height of the contract's last committed event, falling back to its skip-to-height.
     *   This is the on-chain height, always safe on its own, but it means re-reading everything the
     *   contract has been quiet for;
     * - never below the on-chain height, so a stale cursor can not move the start point below what the chain
     *   already has.
     * Reading starts at the lowest start point over all contracts.
     *
     * The contracts recorded in the cursor are never compared with the configured ones as a set; the per-contract
     * lookup above covers every difference between them:
     * - a configured contract missing from the cursor was added while the reader was down. The lookup finds no
     *   skip-to-height for it, so it gets its on-chain height, and since the lowest start point wins, the reader
     *   rewinds to it;
     * - a cursor contract that is no longer configured was removed while the reader was down. Nothing consults its
     *   entry and nothing needs to: the cursor guarantees that every block up to its height was read with all of its
     *   contracts in the filter, and reading with one contract more cannot have missed anything for the ones that
     *   remain. Requiring equal sets would only force a needless rewind here.
     */
    private fun initializeReadPosition(contracts: List<Pair<WrappedByteArray, Long>>) {
        val persistedCursor = try {
            readCursorStore.load(networkId)
        } catch (e: Exception) {
            logger.warn(e) { "Unable to load persisted EVM read cursor, resuming from the on-chain heights: $e" }
            null
        }

        val startPoints = contracts.map { (contract, skipToHeight) ->
            val lastCommittedEventHeight = getLastCommittedEvmEventHeight(networkId, contract.data)
            val fallbackStartHeight = lastCommittedEventHeight ?: skipToHeight.toBigInteger()
            if (persistedCursor != null && persistedCursor.contracts[contract] == skipToHeight && persistedCursor.height > fallbackStartHeight) {
                ContractStartPoint(contract, persistedCursor.height, usedSkipToHeightFallback = false, usedPersistedCursor = true)
            } else {
                ContractStartPoint(contract, fallbackStartHeight, usedSkipToHeightFallback = lastCommittedEventHeight == null, usedPersistedCursor = false)
            }
        }
        val lowest = startPoints.minBy { it.height }

        logger.info {
            "Starting to read EVM events from height ${lowest.height + BigInteger.ONE}, start point set by ${lowest.describe()}. " +
                    (persistedCursor?.let { "Persisted read cursor: $it" } ?: "No persisted read cursor")
        }
        evmEventProcessor.flushEvents(lowest.height)
        filterContracts = contracts.toMap()
        readPositionInitialized = true
        pendingRewindReason = "start point set by ${lowest.describe()}"
        persistReadCursor()
    }

    /**
     * A contract that was not in the filter before has to be read from its last committed event height, or from its
     * skip-to-height if it has no committed events. If that is below the current read position we have to go back.
     */
    private fun handleNewContracts(contracts: List<Pair<WrappedByteArray, Long>>) {
        val newContracts = contracts.filter { !filterContracts.containsKey(it.first) }
        if (newContracts.isNotEmpty()) {
            val lowest = newContracts.map { (contract, skipToHeight) ->
                val lastCommittedEventHeight = getLastCommittedEvmEventHeight(networkId, contract.data)
                ContractStartPoint(contract, lastCommittedEventHeight ?: skipToHeight.toBigInteger(),
                        usedSkipToHeightFallback = lastCommittedEventHeight == null, usedPersistedCursor = false)
            }.minBy { it.height }
            if (lowest.height < evmEventProcessor.lastReadLogBlockHeight) {
                logger.info { "New contracts detected, reading from height ${lowest.height}" }
                newContractFlushes.incrementAndGet()
                evmEventProcessor.flushEvents(lowest.height)
                filterContracts = rememberContracts(contracts)
                pendingRewindReason = "new ${lowest.describe()}"
                persistReadCursor()
                return
            }
        }
        filterContracts = rememberContracts(contracts)
    }

    /**
     * A contract keeps the skip-to-height it entered the filter with: changing it on a contract that is already in
     * the filter does not change the read position while running, but it must be noticed at the next start.
     */
    private fun rememberContracts(contracts: List<Pair<WrappedByteArray, Long>>): Map<WrappedByteArray, Long> =
            contracts.associate { (contract, skipToHeight) ->
                contract to (filterContracts[contract] ?: skipToHeight)
            }

    /**
     * Logs how far behind the EVM chain head the reader is after its read position has been (re)set, now that the head
     * is known. More than one read window behind means a noticeable catch-up and is logged as a warning.
     */
    private fun reportPendingRewind(from: BigInteger, currentBlockHeight: BigInteger) {
        val reason = pendingRewindReason ?: return
        pendingRewindReason = null
        val blocksBehind = currentBlockHeight - from + BigInteger.ONE
        val message = "Reading EVM events from height $from, $blocksBehind blocks behind the readable EVM chain head $currentBlockHeight, $reason"
        if (blocksBehind > BigInteger.valueOf(maxReadAhead)) {
            logger.warn { message }
        } else {
            logger.info { message }
        }
    }

    private fun persistReadCursorIfDue() {
        if (clock.millis() - lastCursorPersistTime >= cursorPersistInterval) {
            persistReadCursor()
        }
    }

    /**
     * Persists the height up to which everything has either been committed or found to contain no events for the
     * current contracts, so that a restarted reader can resume from there. Synchronized so that concurrent callers
     * (the fetch loop and [shutdown]) can not persist an older snapshot after a newer one.
     */
    @Synchronized
    private fun persistReadCursor() {
        if (!readPositionInitialized) return
        val height = evmEventProcessor.safeResumeHeight() ?: return
        val cursor = EvmReadCursor(height, filterContracts)
        try {
            readCursorStore.save(networkId, cursor)
            lastCursorPersistTime = clock.millis()
            lastPersistedCursorHeight = height
            logger.debug { "Persisted EVM read cursor $cursor" }
        } catch (e: Exception) {
            logger.warn(e) { "Unable to persist EVM read cursor $cursor: $e" }
        }
    }

    private fun fetchDynamicContractConfigs(): Set<Pair<WrappedByteArray, Long>> {
        val legacyDynamicContracts = if (hasLegacyDynamicContracts) {
            try {
                blockchainEngine.getBlockQueries().query(
                        EIF_CONFIG_CONTRACTS_QUERY, gtv(mapOf("network_id" to gtv(networkId)))).get().asArray()
                        .map { it.asByteArray().wrap() to 0L }
            } catch (e: Exception) {
                logger.warn(e) { "Unable to fetch legacy dynamic contracts with query $EIF_CONFIG_CONTRACTS_QUERY: $e" }
                listOf()
            }
        } else {
            listOf()
        }

        val dynamicContracts = if (hasDynamicContacts) {
            try {
                blockchainEngine.getBlockQueries().query(
                        EIF_CONFIG_CONTRACTS_TO_FETCH_QUERY, gtv(mapOf("network_id" to gtv(networkId)))).get().asArray()
                        .map {
                            it["address"]!!.asByteArray().wrap() to (it["skip_to_height"]?.asInteger() ?: 0L)
                        }
            } catch (e: Exception) {
                logger.warn(e) { "Unable to fetch dynamic contracts with query $EIF_CONFIG_CONTRACTS_TO_FETCH_QUERY: $e" }
                listOf()
            }
        } else {
            listOf()
        }

        return legacyDynamicContracts.union(dynamicContracts)
    }

    private fun resolveContractsSkipToHeight(contractsConfig: Set<Pair<WrappedByteArray, Long>>): List<Pair<WrappedByteArray, Long>> =
            contractsConfig.mapNotNull { (address, contractSkipToHeight) ->
                when {
                    contractSkipToHeight < 0 -> {
                        logger.warn { "skip-to-height for contract $address is negative, skipping it" }
                        null
                    }

                    contractSkipToHeight == 0L -> address to networkSkipToHeight

                    contractSkipToHeight < networkSkipToHeight -> {
                        logger.warn { "skip-to-height for contract $address is lower than skip-to-height for EVM network: $networkId, skipping it" }
                        null
                    }

                    else -> address to contractSkipToHeight
                }
            }

    private fun fetchEventConfigs(): Map<String, Event> = if (hasDynamicEvents) {
        try {
            val dynamicEvents = blockchainEngine.getBlockQueries().query(
                    EIF_CONFIG_EVENTS_QUERY, gtv(mapOf("network_id" to gtv(networkId)))
            ).get().asArray().map(GtvToEventMapper::map)
            staticEvents.associateBy(EventEncoder::encode).toMutableMap().apply {
                putAll(dynamicEvents.associateBy(EventEncoder::encode))
            }
        } catch (e: Exception) {
            logger.warn(e) { "Unable to fetch dynamic events: $e" }
            staticEvents.associateBy(EventEncoder::encode)
        }
    } else {
        staticEvents.associateBy(EventEncoder::encode)
    }

    private fun eventBlockToOp(eventMap: Map<String, Event>, eventBlock: Pair<EvmBlock, List<Log>>): EvmBlockOp {
        val events = eventBlock.second.map { event ->
            val matchingEvent = eventMap[event.topics[0]] ?: throw ProgrammerMistake("No matching event")
            val parameters = Contract.staticExtractEventParameters(matchingEvent, event)
            gtv(listOf(
                    gtv(event.transactionHash.substring(2).hexStringToByteArray()),
                    gtv(event.logIndex),
                    gtv(event.topics[0].substring(2).hexStringToByteArray()),
                    gtv(event.address.substring(2).hexStringToByteArray()),
                    gtv(matchingEvent.name),
                    gtv(parameters.indexedValues.map(TypeToGtvMapper::map)),
                    gtv(parameters.nonIndexedValues.map(TypeToGtvMapper::map))
            ))
        }
        return EvmBlockOp(
                networkId,
                eventBlock.first.number,
                eventBlock.first.hash.substring(2).hexStringToByteArray().wrap(),
                events
        )
    }

    override fun flushEvents(resetToHeight: BigInteger) {
        withLoggingContext(NETWORK_ID_TAG to networkId.toString()) {
            // We first need to stop the ongoing fetching to avoid conflicts
            runBlocking {
                job.cancelAndJoin()
            }
            logger.info("Flushing EVM events from network $networkId and re-fetching from height: $resetToHeight")
            validationConflictFlushes.incrementAndGet()
            evmEventProcessor.flushEvents(resetToHeight)
            pendingRewindReason = "after flushing events"
            // The read position went backwards, so the persisted cursor must be lowered before anything else is read.
            // This is called from the block validation thread, which must not write to the database itself, so the
            // relaunched fetch loop persists first.
            cursorPersistRequested = true
            job = launchEventFetching()
        }
    }

    /**
     * A signer keeps the EVM blocks it read itself when it rejects a proposal, so its read position keeps advancing
     * past the disagreement. The persisted read cursor would follow it and a restart would never re-read the
     * conflicting height, so the cursor is held below it until this node has committed EVM blocks at or beyond it,
     * see [EvmEventProcessor.holdResumeHeightBelow]. The cursor is persisted by the fetch loop and not here, since this is
     * called from the block validation thread.
     */
    override fun recordValidationConflict(conflictingHeight: BigInteger) {
        signerValidationConflicts.incrementAndGet()
        evmEventProcessor.holdResumeHeightBelow(conflictingHeight)
        cursorPersistRequested = true
    }

    override fun shutdown() {
        runBlocking {
            withTimeoutOrNull(SHUTDOWN_JOIN_TIMEOUT.milliseconds) { job.cancelAndJoin() }
        }
        try {
            persistReadCursor()
        } catch (e: Exception) {
            logger.warn(e) { "Unable to persist EVM read cursor on shutdown: $e" }
        }
        web3jRequestHandler.close()
    }

    fun getLastCommittedEvmEventHeight(networkId: Long, contractAddress: ByteArray): BigInteger? = if (hasLastEvmEventHeightQuery)
        getLastCommittedEvmEventHeightQuery(networkId, contractAddress)
    else
        getLastCommittedEvmBlockHeightQuery(networkId)

    fun getLastCommittedEvmEventHeightQuery(networkId: Long, contractAddress: ByteArray): BigInteger? {
        val eventHeight = blockchainEngine.getBlockQueries().query(EIF_LAST_EVM_EVENT_HEIGHT_QUERY,
                gtv("network_id" to gtv(networkId), "contract_address" to gtv(contractAddress))).get()
        if (eventHeight == GtvNull) {
            return null
        }

        // Trying to be flexible here, don't care what the query gives us as long as it's a number
        return when (eventHeight) {
            is GtvBigInteger -> {
                eventHeight.asBigInteger()
            }

            is GtvInteger -> {
                BigInteger.valueOf(eventHeight.asInteger())
            }

            else -> throw ProgrammerMistake("Unexpected block height type: ${eventHeight.type}")
        }
    }

    fun getLastCommittedEvmBlockHeightQuery(networkId: Long): BigInteger? {
        val block = blockchainEngine.getBlockQueries().query(EIF_LAST_EVM_BLOCK_QUERY, gtv("network_id" to gtv(networkId))).get()
        if (block == GtvNull) {
            return null
        }

        val blockHeight = block.asDict()["evm_block_height"]
                ?: throw ProgrammerMistake("Last evm block has no height stored")

        // Trying to be flexible here, don't care what the query gives us as long as it's a number
        return when (blockHeight) {
            is GtvBigInteger -> {
                blockHeight.asBigInteger()
            }

            is GtvInteger -> {
                BigInteger.valueOf(blockHeight.asInteger())
            }

            else -> throw ProgrammerMistake("Unexpected block height type: ${blockHeight.type}")
        }
    }
}
