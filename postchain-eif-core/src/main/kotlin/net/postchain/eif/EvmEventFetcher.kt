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
import mu.KLogging
import mu.withLoggingContext
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.hexStringToByteArray
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

/**
 * Reads events from evm chain.
 *
 * @param evmReadOffset We will read this number of blocks from the block head on the evm chain, to avoid issues with chain reorg
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
        private val evmEventProcessor: EvmEventProcessor
) : EventFetcher {
    companion object : KLogging()

    private var job: Job

    @Volatile
    private var previousContracts = setOf<WrappedByteArray>()

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
            // Contracts
            val contractsConfig = fetchDynamicContractConfigs().union(staticContracts)
            val contracts = resolveContractsSkipToHeight(contractsConfig)
            logger.debug { "Contracts: $contracts" }

            if (contracts.isEmpty()) {
                logger.warn { "No contracts configured, trying again later" }
                previousContracts = setOf()
                delay(delayWhenNoNewBlocks)
                return
            }

            val newContracts = contracts.filter { !previousContracts.contains(it.first) }
            if (newContracts.isNotEmpty()) {
                val newHeightToReadFrom = newContracts.minOf {
                    getLastCommittedEvmEventHeight(networkId, it.first.data) ?: it.second.toBigInteger()
                }
                if (newHeightToReadFrom < evmEventProcessor.lastReadLogBlockHeight || evmEventProcessor.lastReadLogBlockHeight == BigInteger.ZERO) {
                    logger.info { "New contracts detected, reading from height $newHeightToReadFrom" }
                    evmEventProcessor.flushEvents(newHeightToReadFrom)
                }
            }
            previousContracts = contracts.map { it.first }.toSet()

            val from = evmEventProcessor.lastReadLogBlockHeight + BigInteger.ONE

            val blockNumberReply = web3jRequestHandler.sendWeb3jRequestWithRetry { it.ethBlockNumber() }
            val currentBlockHeight = blockNumberReply.blockNumber - evmReadOffset
            // Pacing the reading of logs
            val to = minOf(currentBlockHeight, from + BigInteger.valueOf(maxReadAhead))

            if (to < from) {
                logger.debug { "No new blocks to read. We are at height: $to" }
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

            // If we just saw one new block, we can probably sleep
            if (to == from) {
                delay(delayWhenNoNewBlocks)
            }

            while (evmEventProcessor.isQueueFull()) {
                logger.debug("Wait for events to be consumed until we read more")
                delay(500)
            }
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
            evmEventProcessor.flushEvents(resetToHeight)
            job = launchEventFetching()
        }
    }

    override fun shutdown() {
        job.cancel()
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
