package net.postchain.eif

import mu.KLogging
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.concurrent.util.get
import net.postchain.core.BlockchainEngine
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvArray
import net.postchain.gtv.GtvBigInteger
import net.postchain.gtv.GtvByteArray
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvInteger
import net.postchain.gtv.GtvNull
import net.postchain.gtv.GtvString
import java.math.BigInteger
import java.util.LinkedList
import java.util.Queue

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
    fun getEventData(): List<EvmBlockOp>
    fun numberOfNewEvents(): Long
    fun isValidEventData(ops: List<EvmBlockOp>): EventValidationResult
    fun markAsProcessed(ops: List<EvmBlockOp>)
    fun flushEvents(resetToHeight: BigInteger)
}

class EventValidationResult(
        val valid: Boolean,
        val conflictingHeight: BigInteger? = null,
)

/**
 * This event processor is used for nodes that are not connected to a network.
 * No EIF special operations will be produced and no operations will be validated.
 */
class NoOpEventProcessor : EventProcessor {
    companion object : KLogging()

    override fun getEventData(): List<EvmBlockOp> = emptyList()

    override fun numberOfNewEvents(): Long = 0L

    /**
     * We can at least validate structure
     */
    override fun isValidEventData(ops: List<EvmBlockOp>): EventValidationResult {
        for (op in ops) {
            if (!op.events.all { isValidEvmEventFormat(it.asArray()) }) {
                return EventValidationResult(false)
            }
        }
        return EventValidationResult(true)
    }

    override fun markAsProcessed(ops: List<EvmBlockOp>) {}
    override fun flushEvents(resetToHeight: BigInteger) {}

    private fun isValidEvmEventFormat(opArgs: Array<out Gtv>) = opArgs.size == 7 &&
            opArgs[EncodedEvent.TX_HASH.index] is GtvByteArray &&
            opArgs[EncodedEvent.LOG_INDEX.index] is GtvBigInteger &&
            opArgs[EncodedEvent.SIGNATURE.index] is GtvByteArray &&
            opArgs[EncodedEvent.CONTRACT.index] is GtvByteArray &&
            opArgs[EncodedEvent.NAME.index] is GtvString &&
            opArgs[EncodedEvent.INDEXED_VALUES.index] is GtvArray &&
            opArgs[EncodedEvent.NON_INDEXED_VALUES.index] is GtvArray
}

/**
 * @param readOffset Will return events from blocks with this specified offset from the last block we have seen from evm chain
 * (so that slower nodes may have a chance to validate the events)
 */
class EvmEventProcessor(
        private val readOffset: BigInteger,
        private val maxQueueSize: Long,
        private val blockchainEngine: BlockchainEngine,
) : EventProcessor {

    companion object : KLogging()

    data class EvmBlock(val number: BigInteger, val hash: String)

    private val eventBlocks: Queue<EvmBlockOp> = LinkedList()

    @Volatile
    var lastReadLogBlockHeight: BigInteger = BigInteger.ZERO

    @Synchronized
    override fun isValidEventData(ops: List<EvmBlockOp>): EventValidationResult {
        // We are strict here, if we have not seen something we will not try to go and fetch it.
        // We simply verify that the same blocks and events are coming in the same order that we have seen them
        // If there are too many rejections, readOffset should be increased
        if (ops.size > eventBlocks.size) {
            // We don't have all these blocks
            logger.warn("Received unexpected blocks")
            val conflictingHeight = if (lastReadLogBlockHeight >= ops.last().evmBlockHeight) {
                val firstReceivedEventHeight = ops.last().evmBlockHeight
                eventBlocks.peek()?.evmBlockHeight?.min(firstReceivedEventHeight) ?: firstReceivedEventHeight
            } else null
            return EventValidationResult(false, conflictingHeight)
        }
        for ((index, eventBlock) in eventBlocks.withIndex()) {
            if (index >= ops.size) break

            val op = ops[index]

            if (op.networkId != eventBlock.networkId || op.evmBlockHeight != eventBlock.evmBlockHeight || op.evmBlockHash != eventBlock.evmBlockHash) {
                logger.warn(
                        "Received unexpected block ${op.evmBlockHeight} with hash ${op.evmBlockHash} in network ${op.networkId}." +
                                " Expected block ${eventBlock.evmBlockHeight} with hash ${eventBlock.evmBlockHash} in network ${eventBlock.networkId}"
                )
                return EventValidationResult(false, op.evmBlockHeight.min(eventBlock.evmBlockHeight))
            }

            if (op.events != eventBlock.events) {
                logger.warn("Events in received block ${op.evmBlockHeight} do not match expected events")
                return EventValidationResult(false, op.evmBlockHeight)
            }
        }
        return EventValidationResult(true)
    }

    override fun markAsProcessed(ops: List<EvmBlockOp>) {
        if (ops.isNotEmpty()) {
            pruneEvents(ops.maxOf { it.evmBlockHeight })
        }
    }

    fun isQueueFull() = numberOfNewEvents() > maxQueueSize

    @Synchronized
    override fun numberOfNewEvents(): Long = eventBlocks.stream()
            .takeWhile { it.evmBlockHeight <= lastReadLogBlockHeight - readOffset }
            .count()

    @Synchronized
    override fun getEventData(): List<EvmBlockOp> = eventBlocks.stream()
            .takeWhile { it.evmBlockHeight <= lastReadLogBlockHeight - readOffset }
            .toList()

    fun getLastCommittedEvmBlockHeight(networkId: Long): BigInteger? {
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
    fun processLogEventsAndUpdateOffsets(
            logs: List<EvmBlockOp>,
            newLastReadLogBlockHeight: BigInteger
    ) {
        eventBlocks.addAll(logs)
        lastReadLogBlockHeight = newLastReadLogBlockHeight
    }

    @Synchronized
    override fun flushEvents(
            resetToHeight: BigInteger,
    ) {
        val currentQueue = eventBlocks.toList()
        eventBlocks.clear()
        for (event in currentQueue) {
            if (event.evmBlockHeight > resetToHeight) break
            eventBlocks.add(event)
        }

        lastReadLogBlockHeight = resetToHeight
    }

    @Synchronized
    private fun pruneEvents(pruneHeight: BigInteger) {
        var nextLogEvent = eventBlocks.peek()
        while (nextLogEvent != null && nextLogEvent.evmBlockHeight <= pruneHeight) {
            eventBlocks.poll()
            nextLogEvent = eventBlocks.peek()
        }
    }
}
