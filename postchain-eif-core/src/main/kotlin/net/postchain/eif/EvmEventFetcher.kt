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
import java.security.MessageDigest
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

/**
 * Reads events from evm chain.
 *
 * @param evmReadOffset We will read this number of blocks from the block head on the evm chain, to avoid issues with chain reorg
 * @param readCursorStore Node-local persistence of the read position, so that a restarted reader resumes where it left off
 * @param minPollInterval Minimum duration of one fetch cycle while the reader is caught up with the EVM chain head (ms), 0 disables pacing
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
        private val minPollInterval: Long,
        private val cursorPersistInterval: Long = DEFAULT_CURSOR_PERSIST_INTERVAL,
        private val clock: Clock = Clock.systemUTC(),
        private val pacingDelay: suspend (Long) -> Unit = { delay(it) },
) : EventFetcher {
    companion object : KLogging() {
        const val DEFAULT_CURSOR_PERSIST_INTERVAL = 10_000L
        private const val SHUTDOWN_JOIN_TIMEOUT = 2_000L

        /** Identifies the set of event signatures a read cursor was recorded with */
        fun hashEventSignatures(eventSignatures: Collection<String>): WrappedByteArray =
                MessageDigest.getInstance("SHA-256").digest(eventSignatures.sorted().joinToString(",").toByteArray()).wrap()
    }

    /** Where reading starts for one contract and why */
    private data class ContractStartPoint(
            val contract: WrappedByteArray,
            val height: BigInteger,
            val usedSkipToHeightFallback: Boolean,
            val usedPersistedCursor: Boolean,
    ) {
        fun describe(): String = "contract 0x${contract.data.toHex()} (" + when {
            usedPersistedCursor -> "persisted read cursor"
            usedSkipToHeightFallback -> "no committed events, using skip-to-height"
            else -> "last committed event height"
        } + ")"
    }

    private var job: Job

    /** Contracts currently in the filter, with the effective skip-to-height each one had when it entered the filter */
    @Volatile
    private var previousContracts = mapOf<WrappedByteArray, Long>()

    /** Hash of the event signatures configured when the read position was initialized */
    @Volatile
    private var eventSignaturesHash: WrappedByteArray? = null

    /** False until the read position has been initialized from the persisted read cursor and the chain state */
    @Volatile
    private var readPositionInitialized = false

    @Volatile
    private var lastCursorPersistTime = 0L

    /** Set when the read cursor must be persisted before the next fetch, regardless of [cursorPersistInterval] */
    @Volatile
    private var cursorPersistRequested = false

    /** Upper bound for the persisted read cursor, set when a block proposal did not match the fetched events */
    @Volatile
    private var persistedHeightCap: BigInteger? = null

    /** Height of the last persisted read cursor, null if nothing has been persisted by this reader yet */
    @Volatile
    var lastPersistedCursorHeight: BigInteger? = null
        private set

    /** Set when the read position was moved backwards, reported once the EVM chain head is known */
    @Volatile
    private var pendingRewindReason: String? = null

    val newContractFlushes = AtomicLong()
    val validationConflictFlushes = AtomicLong()
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
                            delay(500) // Delay a bit and hope that we can recover
                        }
                    }
                }
            }

    /**
     * Producer thread will read events from ethereum ond add to queue in this action. Main thread will consume them.
     */
    private suspend fun fetchEvents() {
        withLoggingContext(NETWORK_ID_TAG to networkId.toString()) {
            val cycleStart = clock.millis()

            if (cursorPersistRequested) {
                cursorPersistRequested = false
                persistReadCursor()
            }

            // Contracts
            val dynamicContractsConfig = fetchDynamicContractConfigs()
            if (dynamicContractsConfig == null) {
                // Keep the read position and the known contract set as they are and try again later
                delay(delayWhenNoNewBlocks)
                return
            }
            val contracts = resolveContractsSkipToHeight(dynamicContractsConfig.union(staticContracts))
            logger.debug { "Contracts: $contracts" }

            if (contracts.isEmpty()) {
                logger.warn { "No contracts configured, trying again later" }
                previousContracts = mapOf()
                delay(delayWhenNoNewBlocks)
                return
            }

            if (readPositionInitialized) {
                handleNewContracts(contracts)
            } else {
                initializeReadPosition(contracts)
            }

            val from = evmEventProcessor.lastReadLogBlockHeight + BigInteger.ONE

            val evmHeadHeight = web3jRequestHandler.sendWeb3jRequestWithRetry { it.ethBlockNumber() }.blockNumber
            evmEventProcessor.lastSeenEvmHeadHeight = evmHeadHeight
            val currentBlockHeight = evmHeadHeight - evmReadOffset
            reportPendingRewind(from, currentBlockHeight)
            // Pacing the reading of logs
            val to = minOf(currentBlockHeight, from + BigInteger.valueOf(maxReadAhead))

            if (to < from) {
                evmEventProcessor.readLagBlocks = BigInteger.ZERO
                logger.debug { "No new blocks to read. We are at height: $to" }
                persistReadCursorIfDue()
                // Sleep a bit until next attempt
                delay(delayWhenNoNewBlocks)
                return
            }

            // Events
            val eventMap = fetchEventConfigs()
            logger.debug { "Events: ${eventMap.values.map { it.name }}" }
            if (eventMap.isEmpty()) {
                logger.warn { "No events configured, trying again later" }
                delay(delayWhenNoNewBlocks)
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
            evmEventProcessor.readLagBlocks = currentBlockHeight - to
            persistReadCursorIfDue()

            if (minPollInterval > 0) {
                if (to == currentBlockHeight && !evmEventProcessor.isQueueFull()) {
                    pauseAtHead(cycleStart)
                }
            } else if (to == from) {
                // If we just saw one new block, we can probably sleep
                pacingDelay(delayWhenNoNewBlocks)
            }

            while (evmEventProcessor.isQueueFull()) {
                logger.debug("Wait for events to be consumed until we read more")
                delay(500)
                persistReadCursorIfDue()
            }
        }
    }

    /**
     * We have just read up to the EVM chain head (minus the EVM read offset). Make sure a fetch cycle at the head takes
     * at least [minPollInterval], so that the RPC node is polled at a bounded rate on networks that produce blocks faster
     * than we poll. Catching up is not affected since this is only called when the last read ended at the head.
     */
    private suspend fun pauseAtHead(cycleStart: Long) {
        val remaining = minPollInterval - (clock.millis() - cycleStart)
        if (remaining > 0) pacingDelay(remaining)
    }

    /**
     * Decides where to start reading when the reader starts. Every contract gets the highest start point known to be
     * safe for it:
     * - the persisted read cursor, if it covers the contract (same address and skip-to-height, and the configured
     *   events are unchanged): everything up to that height has already been read with the contract in the filter and
     *   either committed or found empty;
     * - otherwise (no cursor persisted yet, or the contract was added, its skip-to-height or the events changed since
     *   the cursor was recorded) the height of the contract's last committed event, falling back to its skip-to-height.
     *   This is the start point that is always safe from chain state alone, but it means re-reading everything the
     *   contract has been quiet for;
     * - never below the chain state start point, so a stale cursor can not move the start point below what the chain
     *   already has.
     * Reading starts at the lowest start point over all contracts.
     */
    private fun initializeReadPosition(contracts: List<Pair<WrappedByteArray, Long>>) {
        val currentEventSignaturesHash = hashEventSignatures(fetchEventConfigs().keys)
        val persistedCursor = try {
            readCursorStore.load(networkId)
        } catch (e: Exception) {
            logger.warn(e) { "Unable to load persisted EVM read cursor, resuming from chain state: $e" }
            null
        }?.takeIf { cursor ->
            (cursor.eventSignatures == currentEventSignaturesHash).also { unchanged ->
                if (!unchanged) logger.info { "Configured EVM events changed since the read cursor was persisted, resuming from chain state" }
            }
        }

        val startPoints = contracts.map { (contract, skipToHeight) ->
            val lastCommittedEventHeight = getLastCommittedEvmEventHeight(networkId, contract.data)
            val chainStateStartPoint = lastCommittedEventHeight ?: skipToHeight.toBigInteger()
            if (persistedCursor != null && persistedCursor.contracts[contract] == skipToHeight && persistedCursor.height > chainStateStartPoint) {
                ContractStartPoint(contract, persistedCursor.height, usedSkipToHeightFallback = false, usedPersistedCursor = true)
            } else {
                ContractStartPoint(contract, chainStateStartPoint, usedSkipToHeightFallback = lastCommittedEventHeight == null, usedPersistedCursor = false)
            }
        }
        val lowest = startPoints.minBy { it.height }

        logger.info {
            "Starting to read EVM events from height ${lowest.height + BigInteger.ONE}, start point set by ${lowest.describe()}. " +
                    (persistedCursor?.let { "Persisted read cursor: $it" } ?: "No persisted read cursor")
        }
        evmEventProcessor.flushEvents(lowest.height)
        previousContracts = contracts.toMap()
        eventSignaturesHash = currentEventSignaturesHash
        readPositionInitialized = true
        pendingRewindReason = "start point set by ${lowest.describe()}"
        persistReadCursor()
    }

    /**
     * A contract that was not in the filter before has to be read from its last committed event height, or from its
     * skip-to-height if it has no committed events. If that is below the current read position we have to go back.
     */
    private fun handleNewContracts(contracts: List<Pair<WrappedByteArray, Long>>) {
        val newContracts = contracts.filter { !previousContracts.containsKey(it.first) }
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
                previousContracts = rememberContracts(contracts)
                pendingRewindReason = "new ${lowest.describe()}"
                persistReadCursor()
                return
            }
        }
        previousContracts = rememberContracts(contracts)
    }

    /**
     * A contract keeps the skip-to-height it entered the filter with: changing it on a contract that is already in
     * the filter does not change the read position while running, but it must be noticed at the next start.
     */
    private fun rememberContracts(contracts: List<Pair<WrappedByteArray, Long>>): Map<WrappedByteArray, Long> =
            contracts.associate { (contract, skipToHeight) -> contract to (previousContracts[contract] ?: skipToHeight) }

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
        val safeHeight = evmEventProcessor.safeResumeHeight() ?: return
        persistedHeightCap?.let { cap ->
            val confirmed = evmEventProcessor.lastConfirmedEvmBlockHeight
            if (confirmed != null && confirmed > cap) {
                // This node has committed EVM blocks beyond the conflict since, so its events agree with the chain again
                logger.info { "EVM blocks up to height $confirmed have been committed since the last validation conflict, lifting the read cursor cap at $cap" }
                persistedHeightCap = null
            }
        }
        val height = persistedHeightCap?.min(safeHeight) ?: safeHeight
        val cursor = EvmReadCursor(height, previousContracts, eventSignaturesHash ?: return)
        lastCursorPersistTime = clock.millis()
        try {
            readCursorStore.save(networkId, cursor)
            lastPersistedCursorHeight = height
            logger.debug { "Persisted EVM read cursor $cursor" }
        } catch (e: Exception) {
            logger.warn(e) { "Unable to persist EVM read cursor $cursor: $e" }
        }
    }

    /**
     * @return configured dynamic contracts, or null if they could not be fetched right now
     */
    private fun fetchDynamicContractConfigs(): Set<Pair<WrappedByteArray, Long>>? {
        val legacyDynamicContracts = if (hasLegacyDynamicContracts) {
            try {
                blockchainEngine.getBlockQueries().query(
                        EIF_CONFIG_CONTRACTS_QUERY, gtv(mapOf("network_id" to gtv(networkId)))).get().asArray()
                        .map { it.asByteArray().wrap() to 0L }
            } catch (e: Exception) {
                logger.warn(e) { "Unable to fetch legacy dynamic contracts with query $EIF_CONFIG_CONTRACTS_QUERY, trying again later: $e" }
                return null
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
                logger.warn(e) { "Unable to fetch dynamic contracts with query $EIF_CONFIG_CONTRACTS_TO_FETCH_QUERY, trying again later: $e" }
                return null
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

    override fun recordValidationConflict(conflictingHeight: BigInteger) {
        signerValidationConflicts.incrementAndGet()
        val confirmed = evmEventProcessor.lastConfirmedEvmBlockHeight
        if (confirmed != null && confirmed >= conflictingHeight) {
            // We have already committed EVM blocks at or beyond the conflicting height, the proposal is behind us
            logger.info { "Ignoring validation conflict at EVM height $conflictingHeight, EVM blocks up to $confirmed are already committed" }
            return
        }
        val cap = conflictingHeight - BigInteger.ONE
        persistedHeightCap = persistedHeightCap?.min(cap) ?: cap
        cursorPersistRequested = true
    }

    override fun shutdown() {
        runBlocking {
            withTimeoutOrNull(SHUTDOWN_JOIN_TIMEOUT) { job.cancelAndJoin() }
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
