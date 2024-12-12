package net.postchain.eif

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext
import mu.KLogging
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.common.wrap
import net.postchain.concurrent.util.get
import net.postchain.core.BlockchainEngine
import net.postchain.core.Shutdownable
import net.postchain.eif.EvmEventProcessor.EvmBlock
import net.postchain.eif.web3j.Web3jRequestHandler
import net.postchain.gtv.GtvFactory.gtv
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
 * @param evmReadOffset We will read this amount of blocks from the block head on the evm chain, to avoid issues with chain reorg
 */
class EvmEventFetcher(
        private val networkId: Long,
        private val staticContracts: List<String>,
        private val hasDynamicContacts: Boolean,
        private val staticEvents: List<Event>,
        private val hasDynamicEvents: Boolean,
        private val evmReadOffset: BigInteger,
        private val maxReadAhead: Long,
        skipToHeight: BigInteger,
        lastEvmBlockHeight: BigInteger,
        private val blockchainEngine: BlockchainEngine,
        private val web3jRequestHandler: Web3jRequestHandler,
        private val delayWhenNoNewBlocks: Long,
        private val evmEventProcessor: EvmEventProcessor
) : Shutdownable {
    companion object : KLogging()

    private val job: Job

    init {
        job = CoroutineScope(Dispatchers.IO).launch(CoroutineName("$networkId-event-processor") + MDCContext()) {
            var hasInitiatedHeights = false
            while (isActive) {
                try {
                    if (!hasInitiatedHeights) {
                        evmEventProcessor.lastReadLogBlockHeight = evmEventProcessor.getLastCommittedEvmBlockHeight(networkId)
                                ?: getSkipToHeight(skipToHeight)
                        if (lastEvmBlockHeight > evmEventProcessor.lastReadLogBlockHeight) {
                            evmEventProcessor.lastReadLogBlockHeight = lastEvmBlockHeight
                        }
                        hasInitiatedHeights = true
                    }

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

        val contracts = if (hasDynamicContacts) {
            try {
                val dynamicContracts = blockchainEngine.getBlockQueries().query(
                        EIF_CONFIG_CONTRACTS_QUERY, gtv(mapOf("network_id" to gtv(networkId)))).get().asArray()
                        .map { "0x${it.asByteArray().toHex()}" }
                staticContracts.union(dynamicContracts)
            } catch (e: Exception) {
                logger.warn(e) { "Unable to fetch dynamic contracts: $e" }
                staticContracts
            }
        } else {
            staticContracts
        }
        logger.debug { "Contracts: $contracts" }
        if (contracts.isEmpty()) {
            logger.warn { "No contracts configured, trying again later" }
            delay(delayWhenNoNewBlocks)
            return
        }

        val eventMap = if (hasDynamicEvents) {
            try {
                val dynamicEvents = blockchainEngine.getBlockQueries().query(
                        EIF_CONFIG_EVENTS_QUERY, gtv(mapOf("network_id" to gtv(networkId)))).get().asArray().map(GtvToEventMapper::map)
                staticEvents.associateBy(EventEncoder::encode).toMutableMap().apply { putAll(dynamicEvents.associateBy(EventEncoder::encode)) }
            } catch (e: Exception) {
                logger.warn(e) { "Unable to fetch dynamic events: $e" }
                staticEvents.associateBy(EventEncoder::encode)
            }
        } else {
            staticEvents.associateBy(EventEncoder::encode)
        }
        logger.debug { "Events: ${eventMap.values}" }
        if (eventMap.isEmpty()) {
            logger.warn { "No events configured, trying again later" }
            delay(delayWhenNoNewBlocks)
            return
        }

        val filter = EthFilter(
                DefaultBlockParameter.valueOf(from),
                DefaultBlockParameter.valueOf(to),
                contracts.toList()
        )
        filter.addOptionalTopics(*eventMap.keys.toTypedArray())

        val logResponse = web3jRequestHandler.sendWeb3jRequestWithRetry { it.ethGetLogs(filter) }

        // Ensure events are sorted on txIndex + logIndex, blocks sorted on block number
        val sortedEncodedLogs = logResponse.logs
                .map { (it as EthLog.LogObject).get() }
                .groupBy { EvmBlock(it.blockNumber, it.blockHash) }
                .mapValues { it.value.sortedWith(compareBy({ event -> event.transactionIndex }, { event -> event.logIndex })) }
                .toList()
                .sortedBy { it.first.number }
                .map { eventBlockToOp(eventMap, it) }
        evmEventProcessor.processLogEventsAndUpdateOffsets(sortedEncodedLogs, to)

        // If we just saw one new block we can probably sleep
        if (to == from) {
            delay(delayWhenNoNewBlocks)
        }

        while (evmEventProcessor.isQueueFull()) {
            logger.debug("Wait for events to be consumed until we read more")
            delay(500)
        }
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

    private fun getSkipToHeight(skipToHeight: BigInteger): BigInteger {
        if (skipToHeight < BigInteger.ZERO) {

            val blockNumberReply = web3jRequestHandler.ethBlockNumber()
            return blockNumberReply.blockNumber.plus(skipToHeight)
        }

        return skipToHeight
    }

    override fun shutdown() {
        job.cancel()
        web3jRequestHandler.close()
    }

}