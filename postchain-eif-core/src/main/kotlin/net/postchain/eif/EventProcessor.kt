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
import net.postchain.concurrent.util.get
import net.postchain.core.BlockchainEngine
import net.postchain.core.Shutdownable
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvArray
import net.postchain.gtv.GtvBigInteger
import net.postchain.gtv.GtvByteArray
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvInteger
import net.postchain.gtv.GtvNull
import net.postchain.gtv.GtvString
import net.postchain.gtx.data.OpData
import org.web3j.abi.EventEncoder
import org.web3j.abi.datatypes.Event
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.methods.request.EthFilter
import org.web3j.protocol.core.methods.response.EthLog
import org.web3j.protocol.core.methods.response.Log
import org.web3j.tx.Contract
import java.math.BigInteger
import java.util.LinkedList
import java.util.Queue
import java.util.stream.Collectors

enum class EncodedBlock(val index: Int) {
    NETWORK_ID(0),
    NUMBER(1),
    HASH(2),
    EVENTS(3)
}

enum class EncodedEvent(val index: Int) {
    TX_HASH(0),
    LOG_INDEX(1),
    SIGNATURE(2),
    CONTRACT(3),
    NAME(4),
    INDEXED_VALUES(5),
    NON_INDEXED_VALUES(6)
}

interface EventProcessor {
    fun shutdown()
    fun getEventData(): List<Array<Gtv>>
    fun isValidEventData(ops: List<OpData>): Boolean
    fun markAsProcessed(ops: List<OpData>)
}

/**
 * This event processor is used for nodes that are not connected to a network.
 * No EIF special operations will be produced and no operations will be validated.
 */
class NoOpEventProcessor : EventProcessor {
    companion object : KLogging()

    override fun shutdown() {}

    override fun getEventData(): List<Array<Gtv>> = emptyList()

    /**
     * We can at least validate structure
     */
    override fun isValidEventData(ops: List<OpData>): Boolean {
        for (op in ops) {
            if (op.opName == OP_EVM_BLOCK) {
                if (!isValidEvmBlockFormat(op.args)) {
                    logger.error("Received malformed operation of type $OP_EVM_BLOCK")
                    return false
                }
            } else {
                logger.error("Unknown operation: ${op.opName}")
                return false
            }
        }
        return true
    }

    override fun markAsProcessed(ops: List<OpData>) {}

    private fun isValidEvmEventFormat(opArgs: Array<out Gtv>) = opArgs.size == 7 &&
            opArgs[EncodedEvent.TX_HASH.index] is GtvByteArray &&
            opArgs[EncodedEvent.LOG_INDEX.index] is GtvBigInteger &&
            opArgs[EncodedEvent.SIGNATURE.index] is GtvByteArray &&
            opArgs[EncodedEvent.CONTRACT.index] is GtvByteArray &&
            opArgs[EncodedEvent.NAME.index] is GtvString &&
            opArgs[EncodedEvent.INDEXED_VALUES.index] is GtvArray &&
            opArgs[EncodedEvent.NON_INDEXED_VALUES.index] is GtvArray

    private fun isValidEvmBlockFormat(opArgs: Array<out Gtv>) = opArgs.size == 4 &&
            opArgs[EncodedBlock.NETWORK_ID.index] is GtvInteger &&
            opArgs[EncodedBlock.NUMBER.index] is GtvBigInteger &&
            opArgs[EncodedBlock.HASH.index] is GtvByteArray &&
            opArgs[EncodedBlock.EVENTS.index].asArray().all { isValidEvmEventFormat(it.asArray()) }
}

/**
 * Reads events from evm chain
 *
 * @param evmReadOffset We will read this amount of blocks from the block head on the evm chain, to avoid issues with chain reorg
 * @param readOffset Will return events from blocks with this specified offset from the last block we have seen from evm chain
 * (so that slower nodes may have a chance to validate the events)
 */
class EvmEventProcessor(
        private val networkId: Long,
        private val contractAddresses: List<String>,
        events: List<Event>,
        private val evmReadOffset: BigInteger,
        private val readOffset: BigInteger,
        private val maxReadAhead: Long,
        private val maxQueueSize: Long,
        skipToHeight: BigInteger,
        lastEvmBlockHeight: BigInteger,
        private val blockchainEngine: BlockchainEngine,
        private val web3jRequestHandler: Web3jRequestHandler,
        private val delayWhenNoNewBlocks: Long
) : EventProcessor, Shutdownable {

    private val job: Job

    companion object : KLogging()

    data class EvmBlock(val number: BigInteger, val hash: String)

    private val eventBlocks: Queue<Array<Gtv>> = LinkedList()
    private val eventMap = events.associateBy(EventEncoder::encode)
    private val eventSignatures = eventMap.keys.toTypedArray()

    var lastReadLogBlockHeight = getLastCommittedEvmBlockHeight(networkId) ?: skipToHeight
        private set

    init {
        if (lastEvmBlockHeight > lastReadLogBlockHeight) {
            lastReadLogBlockHeight = lastEvmBlockHeight
        }

        job = CoroutineScope(Dispatchers.IO).launch(CoroutineName("$networkId-event-processor") + MDCContext()) {
            while (isActive) {
                try {
                    fetchEvents()
                } catch (e: CancellationException) {
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
        val from = lastReadLogBlockHeight + BigInteger.ONE

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

        val filter = EthFilter(
                DefaultBlockParameter.valueOf(from),
                DefaultBlockParameter.valueOf(to),
                contractAddresses
        )
        filter.addOptionalTopics(*eventSignatures)


        val logResponse = web3jRequestHandler.sendWeb3jRequestWithRetry { it.ethGetLogs(filter) }

        // Ensure events are sorted on txIndex + logIndex, blocks sorted on block number
        val sortedEncodedLogs = logResponse.logs
                .map { (it as EthLog.LogObject).get() }
                .groupBy { EvmBlock(it.blockNumber, it.blockHash) }
                .mapValues { it.value.sortedWith(compareBy({ event -> event.transactionIndex }, { event -> event.logIndex })) }
                .toList()
                .sortedBy { it.first.number }
                .map(::eventBlockToGtv)
        processLogEventsAndUpdateOffsets(sortedEncodedLogs, to)

        while (isQueueFull()) {
            logger.debug("Wait for events to be consumed until we read more")
            delay(500)
        }
    }

    override fun shutdown() {
        job.cancel()
        web3jRequestHandler.close()
    }

    @Synchronized
    override fun isValidEventData(ops: List<OpData>): Boolean {
        // We are strict here, if we have not seen something we will not try to go and fetch it.
        // We simply verify that the same blocks and events are coming in the same order that we have seen them
        // If there are too many rejections, readOffset should be increased
        if (ops.size > eventBlocks.size) {
            // We don't have all these blocks
            logger.error("Received unexpected blocks")
            return false
        }
        for ((index, eventBlock) in eventBlocks.withIndex()) {
            if (index >= ops.size) break

            val op = ops[index]
            if (op.opName == OP_EVM_BLOCK) {
                val opNetworkId = op.args[EncodedBlock.NETWORK_ID.index]
                val eventNetworkId = eventBlock[EncodedBlock.NETWORK_ID.index]
                val opBlockNumber = op.args[EncodedBlock.NUMBER.index]
                val eventBlockNumber = eventBlock[EncodedBlock.NUMBER.index]
                val opBlockHash = op.args[EncodedBlock.HASH.index]
                val eventBlockHash = eventBlock[EncodedBlock.HASH.index]

                if (opNetworkId != eventNetworkId || opBlockNumber != eventBlockNumber || opBlockHash != eventBlockHash) {
                    logger.error(
                            "Received unexpected block $opBlockNumber with hash $opBlockHash in network $opNetworkId." +
                                    " Expected block $eventBlockNumber with hash $eventBlockHash in network $eventNetworkId"
                    )
                    return false
                }

                if (op.args[EncodedBlock.EVENTS.index] != eventBlock[EncodedBlock.EVENTS.index]) {
                    logger.error("Events in received block $opBlockNumber do not match expected events")
                    return false
                }
            } else {
                logger.error("Unknown operation: ${op.opName}")
                return false
            }
        }
        return true
    }

    override fun markAsProcessed(ops: List<OpData>) {
        if (ops.isNotEmpty()) {
            pruneEvents(ops.maxOf { it.args[EncodedBlock.NUMBER.index].asBigInteger() })
        }
    }

    @Synchronized
    override fun getEventData(): List<Array<Gtv>> {
        return eventBlocks.stream()
                .takeWhile { it[EncodedBlock.NUMBER.index].asBigInteger() <= lastReadLogBlockHeight - readOffset }
                .collect(Collectors.toList())
    }

    private fun eventBlockToGtv(eventBlock: Pair<EvmBlock, List<Log>>): Array<Gtv> {
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
        return arrayOf(
                gtv(networkId),
                gtv(eventBlock.first.number),
                gtv(eventBlock.first.hash.substring(2).hexStringToByteArray()),
                gtv(events)
        )
    }

    private fun getLastCommittedEvmBlockHeight(networkId: Long): BigInteger? {
        val block = blockchainEngine.getBlockQueries().query("get_last_evm_block", gtv("network_id" to gtv(networkId))).get()
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

    @Synchronized
    private fun processLogEventsAndUpdateOffsets(
            logs: List<Array<Gtv>>,
            newLastReadLogBlockHeight: BigInteger
    ) {
        eventBlocks.addAll(logs)
        lastReadLogBlockHeight = newLastReadLogBlockHeight
    }

    @Synchronized
    private fun isQueueFull(): Boolean {
        // Just check against the events that we can actually consume
        return eventBlocks.filter {
            it[EncodedBlock.NUMBER.index].asBigInteger() <= lastReadLogBlockHeight - readOffset
        }.size > maxQueueSize
    }

    @Synchronized
    private fun pruneEvents(pruneHeight: BigInteger) {
        var nextLogEvent = eventBlocks.peek()
        while (nextLogEvent != null && nextLogEvent[EncodedBlock.NUMBER.index].asBigInteger() <= pruneHeight) {
            eventBlocks.poll()
            nextLogEvent = eventBlocks.peek()
        }
    }
}
