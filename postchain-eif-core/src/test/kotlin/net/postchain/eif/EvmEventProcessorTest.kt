package net.postchain.eif

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.jupiter.api.Test
import java.math.BigInteger

class EvmEventProcessorTest {

    @Test
    fun `should consume events partially across iterations`() {
        val sut = EvmEventProcessor(1L, BigInteger.ZERO, 2000L)

        // Add a new block with events
        val events1 = listOf(
                evmBlockOp(1L, "10", events(1, 2, 3, 4)),
                evmBlockOp(2L, "20", events(5, 6)),
                evmBlockOp(3L, "30", events(7, 8, 9, 10, 11, 12)),
        )
        sut.processLogEventsAndUpdateOffsets(events1, 3.toBigInteger())

        // getEventData should return all event blocks
        val eventData = sut.getEventData()
        assertThat(eventData).isEqualTo(events1)

        // Consume all returned block events nad mark them processed
        sut.markAsProcessed(eventData)

        // getEventData should return nothing
        assertThat(sut.getEventData()).isEqualTo(listOf())

        // Add a new block with events
        val events2 = listOf(
                evmBlockOp(4L, "40", events(13, 14, 15, 16)),
                evmBlockOp(5L, "50", events(17, 18, 19, 20)),
                evmBlockOp(6L, "60", events(21, 22, 23, 24, 25, 26)),
                evmBlockOp(7L, "70", events(27, 28, 29, 30, 31, 32)),
                evmBlockOp(8L, "80", events(33, 34, 35, 36, 37, 38)),
        )
        sut.processLogEventsAndUpdateOffsets(events2, 8.toBigInteger())

        // Consume only three first blocks
        sut.markAsProcessed(events2.take(3))

        // getEventData should return the remaining block events
        assertThat(sut.getEventData()).isEqualTo(events2.drop(3))
    }

    @Test
    fun `safe resume height is unknown before anything has been read`() {
        val sut = EvmEventProcessor(1L, BigInteger.ZERO, 2000L)

        assertThat(sut.safeResumeHeight()).isNull()
    }

    @Test
    fun `safe resume height follows the last read height while the queue is empty`() {
        val sut = EvmEventProcessor(1L, BigInteger.TWO, 2000L)

        sut.processLogEventsAndUpdateOffsets(listOf(), 10.toBigInteger())
        assertThat(sut.safeResumeHeight()).isEqualTo(10.toBigInteger())

        sut.processLogEventsAndUpdateOffsets(listOf(), 25.toBigInteger())
        assertThat(sut.safeResumeHeight()).isEqualTo(25.toBigInteger())
    }

    @Test
    fun `safe resume height stays below the oldest queued block until it is consumed and committed`() {
        val sut = EvmEventProcessor(1L, BigInteger.ZERO, 2000L)
        val blocks = listOf(
                evmBlockOp(12L, "10", events(1)),
                evmBlockOp(15L, "20", events(2)),
                evmBlockOp(18L, "30", events(3)),
        )
        sut.processLogEventsAndUpdateOffsets(blocks, 20.toBigInteger())
        assertThat(sut.safeResumeHeight()).isEqualTo(11.toBigInteger())

        // Consumed into a block that is not yet committed: still not safe to skip
        sut.markAsProcessed(blocks.take(1))
        assertThat(sut.safeResumeHeight()).isEqualTo(11.toBigInteger())

        sut.blockCommitted(12.toBigInteger())
        assertThat(sut.safeResumeHeight()).isEqualTo(14.toBigInteger())

        sut.markAsProcessed(blocks.drop(1).take(1))
        sut.blockCommitted(15.toBigInteger())
        assertThat(sut.safeResumeHeight()).isEqualTo(17.toBigInteger())

        sut.markAsProcessed(blocks.drop(2))
        sut.blockCommitted(18.toBigInteger())
        assertThat(sut.safeResumeHeight()).isEqualTo(20.toBigInteger())
    }

    @Test
    fun `safe resume height waits for consumed blocks to be committed`() {
        val sut = EvmEventProcessor(1L, BigInteger.ZERO, 2000L)
        val blocks = listOf(
                evmBlockOp(10L, "10", events(1)),
                evmBlockOp(20L, "20", events(2)),
                evmBlockOp(30L, "30", events(3)),
        )
        sut.processLogEventsAndUpdateOffsets(blocks, 35.toBigInteger())

        // All consumed into a block that is being built: the lowest consumed block still bounds the safe height
        sut.markAsProcessed(blocks)
        assertThat(sut.getEventData()).isEqualTo(listOf())
        assertThat(sut.safeResumeHeight()).isEqualTo(9.toBigInteger())
        assertThat(sut.lastConfirmedEvmBlockHeight).isNull()

        // A committed block without EVM blocks confirms nothing
        sut.blockCommitted(null)
        assertThat(sut.safeResumeHeight()).isEqualTo(9.toBigInteger())
        assertThat(sut.lastConfirmedEvmBlockHeight).isNull()

        // Only the blocks up to the committed height are confirmed
        sut.blockCommitted(20.toBigInteger())
        assertThat(sut.safeResumeHeight()).isEqualTo(29.toBigInteger())
        assertThat(sut.lastConfirmedEvmBlockHeight).isEqualTo(20.toBigInteger())

        sut.blockCommitted(30.toBigInteger())
        assertThat(sut.safeResumeHeight()).isEqualTo(35.toBigInteger())
        assertThat(sut.lastConfirmedEvmBlockHeight).isEqualTo(30.toBigInteger())

        sut.blockCommitted(null)
        assertThat(sut.lastConfirmedEvmBlockHeight).isEqualTo(30.toBigInteger())
    }

    @Test
    fun `safe resume height never exceeds the last read height`() {
        val sut = EvmEventProcessor(1L, BigInteger.TWO, 2000L)
        val blocks = listOf(evmBlockOp(20L, "10", events(1)))

        // A block with events at the read height itself
        sut.processLogEventsAndUpdateOffsets(blocks, 20.toBigInteger())
        assertThat(sut.safeResumeHeight()).isEqualTo(19.toBigInteger())

        sut.markAsProcessed(blocks)
        assertThat(sut.safeResumeHeight()).isEqualTo(19.toBigInteger())

        sut.blockCommitted(20.toBigInteger())
        assertThat(sut.safeResumeHeight()).isEqualTo(20.toBigInteger())
    }

    @Test
    fun `safe resume height follows a flush`() {
        val sut = EvmEventProcessor(1L, BigInteger.ZERO, 2000L)
        val blocks = listOf(
                evmBlockOp(12L, "10", events(1)),
                evmBlockOp(15L, "20", events(2)),
                evmBlockOp(18L, "30", events(3)),
        )
        sut.processLogEventsAndUpdateOffsets(blocks, 20.toBigInteger())

        // Flush inside the queue: block 12 is kept, the rest is dropped
        sut.flushEvents(14.toBigInteger())
        assertThat(sut.lastReadLogBlockHeight).isEqualTo(14.toBigInteger())
        assertThat(sut.getEventData()).isEqualTo(blocks.take(1))
        assertThat(sut.safeResumeHeight()).isEqualTo(11.toBigInteger())

        // Flush below the queue: queue is empty and the flush height is the safe height
        sut.flushEvents(5.toBigInteger())
        assertThat(sut.getEventData()).isEqualTo(listOf())
        assertThat(sut.safeResumeHeight()).isEqualTo(5.toBigInteger())
    }
}
