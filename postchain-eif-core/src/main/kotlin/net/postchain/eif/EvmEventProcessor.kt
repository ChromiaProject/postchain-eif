package net.postchain.eif

import mu.KLogging
import java.math.BigInteger
import java.util.LinkedList
import java.util.Queue
import java.util.TreeSet

/**
 * @param readOffset Will return events from blocks with this specified offset from the last block we have seen from evm chain
 * (so that slower nodes may have a chance to validate the events)
 */
class EvmEventProcessor(
        private val networkId: Long,
        private val readOffset: BigInteger,
        private val maxQueueSize: Long,
) : EventProcessor {

    companion object : KLogging()

    data class EvmBlock(val number: BigInteger, val hash: String)

    private val eventBlocks: Queue<EvmBlockOp> = LinkedList()

    @Volatile
    var lastReadLogBlockHeight: BigInteger = BigInteger.ZERO

    /**
     * EVM block heights that [safeResumeHeight] must stay below until a durably committed block covers them, that is
     * until [blockCommitted] is called with that height or a later one. Two kinds of heights end up here:
     * - blocks taken out of the queue by [markAsProcessed]: the block they went into is being committed but is not
     *   durable yet, so a crash could still lose it
     * - heights at which another signer's proposal disagreed with what this node read, see [holdResumeHeightBelow]:
     *   this node does not know yet whether its own view of that height is the right one
     */
    private val unconfirmedHeights = TreeSet<BigInteger>()

    /** Highest EVM block height included in a block that has been durably committed on this node, null if none seen */
    @Volatile
    var lastConfirmedEvmBlockHeight: BigInteger? = null
        private set

    /**
     * Highest EVM block height such that every block at or below it has been read and either contained no events for
     * the current contract set or is included in a block that has been durably committed on this node, and none of
     * them is held by [holdResumeHeightBelow]. A restarted reader can safely resume from the block after this height.
     * Returns null until anything has been read.
     */
    @Synchronized
    fun safeResumeHeight(): BigInteger? {
        if (lastReadLogBlockHeight == BigInteger.ZERO) return null
        var safeHeight = lastReadLogBlockHeight
        eventBlocks.peek()?.let { safeHeight = safeHeight.min(it.evmBlockHeight - BigInteger.ONE) }
        unconfirmedHeights.firstOrNull()?.let { safeHeight = safeHeight.min(it - BigInteger.ONE) }
        return safeHeight
    }

    /**
     * Holds [safeResumeHeight] below [height] until a block including EVM block [height] or a later one has been durably
     * committed on this node. Used by a signer that rejected a block proposal whose EVM blocks differ from the ones
     * this node read: the blocks in memory are kept, but a restart must re-read from before the disagreement. Ignored
     * if such a block has already been committed, since the proposal is then behind this node.
     */
    @Synchronized
    fun holdResumeHeightBelow(height: BigInteger) {
        val confirmed = lastConfirmedEvmBlockHeight
        if (confirmed != null && confirmed >= height) {
            logger.info { "Not holding the read cursor below EVM height $height, EVM blocks up to $confirmed are already committed" }
            return
        }
        unconfirmedHeights.add(height)
    }

    @Synchronized
    override fun isValidEventData(ops: List<EvmBlockOp>): EventValidationResult {
        // We are strict here, if we have not seen something we will not try to go and fetch it.
        // We simply verify that the same blocks and events are coming in the same order that we have seen them
        // If there are too many rejections, readOffset should be increased
        if (ops.size > eventBlocks.size) {
            // We don't have all these blocks
            val conflictingHeight = if (lastReadLogBlockHeight >= ops.last().evmBlockHeight) {
                val firstReceivedEventHeight = ops.last().evmBlockHeight
                eventBlocks.peek()?.evmBlockHeight?.min(firstReceivedEventHeight) ?: firstReceivedEventHeight
            } else null
            return EventValidationResult(false, conflictingHeight, errorMessage = "Received unexpected blocks")
        }
        for ((index, eventBlock) in eventBlocks.withIndex()) {
            if (index >= ops.size) break

            val op = ops[index]

            if (op.networkId != eventBlock.networkId || op.evmBlockHeight != eventBlock.evmBlockHeight || op.evmBlockHash != eventBlock.evmBlockHash) {
                return EventValidationResult(false, op.evmBlockHeight.min(eventBlock.evmBlockHeight), errorMessage =
                        "Received unexpected block ${op.evmBlockHeight} with hash ${op.evmBlockHash} in network ${op.networkId}." +
                                " Expected block ${eventBlock.evmBlockHeight} with hash ${eventBlock.evmBlockHash} in network ${eventBlock.networkId}")
            }

            if (op.events != eventBlock.events) {
                return EventValidationResult(false, op.evmBlockHeight, errorMessage =
                        "Events in received block ${op.evmBlockHeight} do not match expected events")
            }
        }
        return EventValidationResult(true)
    }

    @Synchronized
    override fun markAsProcessed(ops: List<EvmBlockOp>) {
        if (ops.isNotEmpty()) {
            ops.forEach { unconfirmedHeights.add(it.evmBlockHeight) }
            pruneEvents(ops.maxOf { it.evmBlockHeight })
        }
    }

    @Synchronized
    override fun blockCommitted(upToEvmBlockHeight: BigInteger?) {
        if (upToEvmBlockHeight == null) return
        unconfirmedHeights.headSet(upToEvmBlockHeight, true).clear()
        lastConfirmedEvmBlockHeight = lastConfirmedEvmBlockHeight?.max(upToEvmBlockHeight) ?: upToEvmBlockHeight
    }

    @Synchronized
    fun isQueueFull() = newBlocks().size > maxQueueSize

    /** The queued blocks that are at least [readOffset] blocks behind the last read one, so they may be proposed now */
    @Synchronized
    private fun newBlocks(): List<EvmBlockOp> = eventBlocks.takeWhile { it.evmBlockHeight <= lastReadLogBlockHeight - readOffset }

    @Synchronized
    override fun numberOfNewEvents(): Long = newBlocks().sumOf { it.events.size }.toLong()

    @Synchronized
    override fun getEventData(): List<EvmBlockOp> = newBlocks()

    @Synchronized
    fun processLogEventsAndUpdateOffsets(logs: List<EvmBlockOp>, newLastReadLogBlockHeight: BigInteger) {
        eventBlocks.addAll(logs)
        lastReadLogBlockHeight = newLastReadLogBlockHeight
    }

    @Synchronized
    override fun flushEvents(resetToHeight: BigInteger) {
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
