package net.postchain.eif

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test
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
}