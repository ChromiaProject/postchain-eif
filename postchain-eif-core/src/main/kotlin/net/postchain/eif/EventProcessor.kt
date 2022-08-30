package net.postchain.eif

import mu.KLogging
import net.postchain.core.BlockchainEngine
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.hexStringToByteArray
import net.postchain.core.framework.AbstractBlockchainProcess
import net.postchain.gtv.*
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtx.data.OpData
import org.web3j.abi.EventEncoder
import org.web3j.abi.datatypes.Event
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.Response
import org.web3j.protocol.core.methods.request.EthFilter
import org.web3j.protocol.core.methods.response.EthLog
import org.web3j.protocol.core.methods.response.Log
import org.web3j.tx.Contract
import java.lang.Thread.sleep
import java.math.BigInteger
import java.util.*
import kotlin.streams.toList

enum class EncodedBlock(val index: Int) {
    NUMBER(0),
    HASH(1),
    EVENTS(2)
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
    fun isValidEventData(ops: Array<OpData>): Boolean
}

/**
 * This event processor is used for nodes that are not connected to ethereum.
 * No EIF special operations will be produced and no operations will be validated against ethereum.
 */
class NoOpEventProcessor : EventProcessor {
    companion object : KLogging()

    override fun shutdown() {}

    override fun getEventData(): List<Array<Gtv>> = emptyList()

    /**
     * We can at least validate structure
     */
    override fun isValidEventData(ops: Array<OpData>): Boolean {
        for (op in ops) {
            if (op.opName == OP_ETH_BLOCK) {
                if (!isValidEthereumBlockFormat(op.args)) {
                    logger.error("Received malformed operation of type $OP_ETH_BLOCK")
                    return false
                }
            } else {
                logger.error("Unknown operation: ${op.opName}")
                return false
            }
        }
        return true
    }

    private fun isValidEthereumEventFormat(opArgs: Array<out Gtv>) = opArgs.size == 7 &&
            opArgs[EncodedEvent.TX_HASH.index] is GtvByteArray &&
            opArgs[EncodedEvent.LOG_INDEX.index] is GtvBigInteger &&
            opArgs[EncodedEvent.SIGNATURE.index] is GtvByteArray &&
            opArgs[EncodedEvent.CONTRACT.index] is GtvByteArray &&
            opArgs[EncodedEvent.NAME.index] is GtvString &&
            opArgs[EncodedEvent.INDEXED_VALUES.index] is GtvArray &&
            opArgs[EncodedEvent.NON_INDEXED_VALUES.index] is GtvArray

    private fun isValidEthereumBlockFormat(opArgs: Array<Gtv>) = opArgs.size == 3 &&
            opArgs[EncodedBlock.NUMBER.index] is GtvBigInteger &&
            opArgs[EncodedBlock.HASH.index] is GtvByteArray &&
            opArgs[EncodedBlock.EVENTS.index].asArray().all { isValidEthereumEventFormat(it.asArray()) }
}

/**
 * Reads events from ethereum.
 *
 * @param ethereumReadOffset We will read this amount of blocks from the block head on ethereum, to avoid issues with chain reorg
 * @param readOffset Will return events from blocks with this specified offset from the last block we have seen from ethereum
 * (so that slower nodes may have a chance to validate the events)
 */
class EthereumEventProcessor(
        private val web3j: Web3j,
        private val contractAddresses: List<String>,
        events: List<Event>,
        private val ethereumReadOffset: BigInteger,
        private val readOffset: BigInteger,
        skipToHeight: BigInteger,
        blockchainEngine: BlockchainEngine
) : EventProcessor, AbstractBlockchainProcess("ethereum-event-processor", blockchainEngine) {

    data class EthereumBlock(val number: BigInteger, val hash: String)

    private val eventBlocks: Queue<Array<Gtv>> = LinkedList()
    private var lastReadLogBlockHeight = skipToHeight

    private val eventMap = events.associateBy(EventEncoder::encode)
    private val eventSignatures = eventMap.keys.toTypedArray()

    companion object {
        // The idea here is to avoid too big log queries and
        // also potentially filling up our event queue too much
        private const val MAX_READ_AHEAD = 10_000L
        private const val MAX_QUEUE_SIZE = 1_000L
    }

    /**
     * Producer thread will read events from ethereum ond add to queue in this action. Main thread will consume them.
     */
    override fun action() {
        val lastCommittedBlock = getLastCommittedEthereumBlockHeight()
        val from = if (lastCommittedBlock != null) {
            // Skip ahead if we are behind last committed block
            maxOf(lastReadLogBlockHeight, lastCommittedBlock) + BigInteger.ONE
        } else {
            lastReadLogBlockHeight + BigInteger.ONE
        }

        val currentBlockHeight = sendWeb3jRequestWithRetry(web3j.ethBlockNumber()).blockNumber - ethereumReadOffset
        // Pacing the reading of logs
        val to = minOf(currentBlockHeight, from + BigInteger.valueOf(MAX_READ_AHEAD))

        if (to < from) {
            logger.debug { "No new blocks to read. We are at height: $to" }
            // Sleep a bit until next attempt
            sleep(500)
            return
        }

        val filter = EthFilter(
            DefaultBlockParameter.valueOf(from),
            DefaultBlockParameter.valueOf(to),
            contractAddresses
        )
        filter.addOptionalTopics(*eventSignatures)

        val logResponse = sendWeb3jRequestWithRetry(web3j.ethGetLogs(filter))

        // Ensure events are sorted on txIndex + logIndex, blocks sorted on block number
        val sortedEncodedLogs = logResponse.logs
                .map { (it as EthLog.LogObject).get() }
                .groupBy { EthereumBlock(it.blockNumber, it.blockHash) }
                .mapValues { it.value.sortedWith(compareBy({ event -> event.transactionIndex }, { event -> event.logIndex })) }
                .toList()
                .sortedBy { it.first.number }
                .map(::eventBlockToGtv)
        processLogEventsAndUpdateOffsets(sortedEncodedLogs, to)

        while (isQueueFull()) {
            logger.debug("Wait for events to be consumed until we read more")
            sleep(500)
        }
    }

    override fun cleanup() {
        web3j.shutdown()
    }

    @Synchronized
    override fun isValidEventData(ops: Array<OpData>): Boolean {
        val lastCommittedBlock = getLastCommittedEthereumBlockHeight()
        // If we have any old events in the queue we may prune them
        if (lastCommittedBlock != null) {
            pruneEvents(lastCommittedBlock)
        }

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
            if (op.opName == OP_ETH_BLOCK) {
                val opBlockNumber = op.args[EncodedBlock.NUMBER.index]
                val eventBlockNumber = eventBlock[EncodedBlock.NUMBER.index]
                val opBlockHash = op.args[EncodedBlock.HASH.index]
                val eventBlockHash = eventBlock[EncodedBlock.HASH.index]

                if (opBlockNumber != eventBlockNumber || opBlockHash != eventBlockHash) {
                    logger.error(
                        "Received unexpected block $opBlockNumber with hash $opBlockHash." +
                                " Expected block $eventBlockNumber with hash $eventBlockHash"
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

    @Synchronized
    override fun getEventData(): List<Array<Gtv>> {
        val lastCommittedBlock = getLastCommittedEthereumBlockHeight()
        // If we have any old events in the queue we may prune them
        if (lastCommittedBlock != null) {
            pruneEvents(lastCommittedBlock)
        }

        return eventBlocks.stream()
            .takeWhile { it[EncodedBlock.NUMBER.index].asBigInteger() <= lastReadLogBlockHeight - readOffset }
            .toList()
    }

    private fun eventBlockToGtv(eventBlock: Pair<EthereumBlock, List<Log>>): Array<Gtv> {
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
            gtv(eventBlock.first.number),
            gtv(eventBlock.first.hash.substring(2).hexStringToByteArray()),
            gtv(events)
        )
    }

    private fun getLastCommittedEthereumBlockHeight(): BigInteger? {
        val block = blockchainEngine.getBlockQueries().query("get_last_eth_block", gtv(mutableMapOf())).get()
        if (block == GtvNull) {
            return null
        }

        val blockHeight = block.asDict()["eth_block_height"]
            ?: throw ProgrammerMistake("Last eth block has no height stored")

        return blockHeight.asBigInteger()
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
        }.size > MAX_QUEUE_SIZE
    }

    private fun pruneEvents(lastCommittedBlock: BigInteger) {
        var nextLogEvent = eventBlocks.peek()
        while (nextLogEvent != null && nextLogEvent[EncodedBlock.NUMBER.index].asBigInteger() <= lastCommittedBlock) {
            eventBlocks.poll()
            nextLogEvent = eventBlocks.peek()
        }
    }

    private fun <T : Response<*>> sendWeb3jRequestWithRetry(
        request: Request<*, T>,
        retryTimeout: Long = 500
    ): T {
        val response = try {
            val response = request.send()
            if (response.hasError()) {
                logger.error("Web3j request failed with error code: ${response.error.code} and message: ${response.error.message}")
            }
            response
        } catch (e: Exception) {
            logger.error("Web3j request failed", e)
            null
        }

        if (response == null || response.hasError()) {
            if (retryTimeout > 0) {
                sleep(retryTimeout)
            }
            return sendWeb3jRequestWithRetry(request, retryTimeout)
        }
        return response
    }
}
