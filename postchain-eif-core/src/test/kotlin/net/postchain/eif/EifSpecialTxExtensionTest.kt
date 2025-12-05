package net.postchain.eif

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import net.postchain.base.SpecialTransactionPosition
import net.postchain.core.BlockEContext
import net.postchain.eif.config.EifEventReceiverConfig
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.mapper.toObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class EifSpecialTxExtensionTest {

    @Test
    fun `should create no operations when event processor has no events`() {
        // Mocks
        val eventProcessor: EventProcessor = mock {
            on { getEventData() } doReturn emptyList()
        }
        val blockCtx: BlockEContext = mock {
            on { height } doReturn 1L
        }

        // Initialize EifSpecialTxExtension
        val sut = EifSpecialTxExtension().apply {
            addEventProcessor(1L, eventProcessor, NoOpEventFetcher(eventProcessor))
        }

        // No operations should be created
        val res = sut.createSpecialOperations(SpecialTransactionPosition.Begin, blockCtx)
        assertThat(res).isEmpty()
    }

    @Test
    fun `should add all events when max-events-per-block is disabled`() {
        // Mocks
        val eventProcessor: EventProcessor = mock {
            on { getEventData() } doReturn listOf(
                    evmBlockOp(1L, "10", events(0, 1, 2, 3)),
                    evmBlockOp(2L, "20", events(4, 5, 6)),
            )
        }
        val blockCtx: BlockEContext = mock {
            on { height } doReturn 1L
        }

        // Initialize EifSpecialTxExtension
        val sut = EifSpecialTxExtension().apply {
            config = config(maxEventsPerBlock = 0L)
            addEventProcessor(1L, eventProcessor, NoOpEventFetcher(eventProcessor))
        }

        // All events should be added
        val res = sut.createSpecialOperations(SpecialTransactionPosition.Begin, blockCtx)
        assertThat(res).isEqualTo(listOf(
                expectedOpData(1L, "10", 0, 1, 2, 3),
                expectedOpData(2L, "20", 4, 5, 6),
        ))
    }

    @Test
    fun `should add no more than max-events-per-block events`() {
        // Mocks
        val eventProcessor: EventProcessor = mock()
        whenever(eventProcessor.getEventData()).thenReturn(listOf(
                evmBlockOp(1L, "10", events(0, 1, 2, 3)),
                evmBlockOp(2L, "20", events(4, 5, 6)),
                evmBlockOp(3L, "30", events(7, 8)),
                evmBlockOp(4L, "40", events(9, 10)),
        )).thenReturn(listOf(
                evmBlockOp(2L, "20", events(4, 5, 6)),
                evmBlockOp(3L, "30", events(7, 8)),
                evmBlockOp(4L, "40", events(9, 10)),
        )).thenReturn(listOf(
                evmBlockOp(4L, "40", events(9, 10)),
        ))

        val blockCtx: BlockEContext = mock {
            on { height } doReturn 1L
        }

        // Initialize EifSpecialTxExtension
        val sut = EifSpecialTxExtension().apply {
            config = config(maxEventsPerBlock = 5L)
            addEventProcessor(1L, eventProcessor, NoOpEventFetcher(eventProcessor))
        }

        // Only events from the first block are included
        val res = sut.createSpecialOperations(SpecialTransactionPosition.Begin, blockCtx)
        assertThat(res).isEqualTo(listOf(
                expectedOpData(1L, "10", 0, 1, 2, 3),
        ))

        // The next 5 events should be added
        val res2 = sut.createSpecialOperations(SpecialTransactionPosition.Begin, blockCtx)
        assertThat(res2).isEqualTo(listOf(
                expectedOpData(2L, "20", 4, 5, 6),
                expectedOpData(3L, "30", 7, 8),
        ))

        // The next 2 events should be added
        val res3 = sut.createSpecialOperations(SpecialTransactionPosition.Begin, blockCtx)
        assertThat(res3).isEqualTo(listOf(
                expectedOpData(4L, "40", 9, 10),
        ))
    }

    @Test
    fun `should add all events from the first block even if max-events-per-block is exceeded`() {
        // Mocks
        val eventProcessor: EventProcessor = mock {
            on { getEventData() } doReturn listOf(
                    evmBlockOp(1L, "10", events(0, 1, 2, 3, 4, 5, 6)),
                    evmBlockOp(2L, "20", events(7, 8)),
            )
        }
        val blockCtx: BlockEContext = mock {
            on { height } doReturn 1L
        }

        // Initialize EifSpecialTxExtension with max 3 events per block
        val sut = EifSpecialTxExtension().apply {
            config = config(maxEventsPerBlock = 3L)
            addEventProcessor(1L, eventProcessor, NoOpEventFetcher(eventProcessor))
        }

        // All 7 events from the first block should be added despite maxEventsPerBlock of 3
        val res = sut.createSpecialOperations(SpecialTransactionPosition.Begin, blockCtx)
        assertThat(res).isEqualTo(listOf(
                expectedOpData(1L, "10", 0, 1, 2, 3, 4, 5, 6)
        ))
    }

    @Test
    fun `validation should allow all events when max-events-per-block is disabled`() {
        // Mocks
        val eventProcessor: EventProcessor = mock {
            on { isValidEventData(any()) } doReturn EventValidationResult(true)
        }
        val blockCtx: BlockEContext = mock {
            on { height } doReturn 1L
        }

        // Initialize EifSpecialTxExtension
        val sut = EifSpecialTxExtension().apply {
            config = config(maxEventsPerBlock = 0L)
            isSigner = { true }
            addEventProcessor(1L, eventProcessor, NoOpEventFetcher(eventProcessor))
        }

        // All operations should be allowed
        val ops = listOf(
                expectedOpData(1L, "10", 0, 1, 2, 3),
                expectedOpData(2L, "20", 4, 5, 6),
                expectedOpData(3L, "30", 7, 8, 9)
        )
        assertTrue(sut.validateSpecialOperations(SpecialTransactionPosition.Begin, blockCtx, ops))
    }

    @Test
    fun `signer must not allow more than max-events-per-block events`() {
        // Mocks
        val eventProcessor: EventProcessor = mock {
            on { isValidEventData(any()) } doReturn EventValidationResult(true)
        }
        val blockCtx: BlockEContext = mock {
            on { height } doReturn 1L
        }

        // Initialize EifSpecialTxExtension with max 5 events per block
        val sut = EifSpecialTxExtension().apply {
            config = config(maxEventsPerBlock = 5L)
            isSigner = { true }
            addEventProcessor(1L, eventProcessor, NoOpEventFetcher(eventProcessor))
        }

        // Should fail when events from multiple blocks exceed the limit
        val tooManyOps = listOf(
                expectedOpData(1L, "10", 0, 1, 2),
                expectedOpData(2L, "20", 3, 4, 5)
        )
        assertFalse(sut.validateSpecialOperations(SpecialTransactionPosition.Begin, blockCtx, tooManyOps))

        // Should pass when all events are from the same block
        val sameBlockOps = listOf(
                expectedOpData(1L, "10", 0, 1, 2, 3, 4, 5, 6)
        )
        assertTrue(sut.validateSpecialOperations(SpecialTransactionPosition.Begin, blockCtx, sameBlockOps))
    }
    
    @Test
    fun `replica skips max-events-per-block validation`() {
        // Mocks
        val eventProcessor: EventProcessor = mock {
            on { isValidEventData(any()) } doReturn EventValidationResult(true)
        }
        val blockCtx: BlockEContext = mock {
            on { height } doReturn 1L
        }

        // Initialize EifSpecialTxExtension with max 5 events per block
        val sut = EifSpecialTxExtension().apply {
            config = config(maxEventsPerBlock = 5L)
            isSigner = { false }
            addEventProcessor(1L, eventProcessor, NoOpEventFetcher(eventProcessor))
        }

        // Should pass even when events from multiple blocks exceed the limit
        val ops = listOf(
                expectedOpData(1L, "10", 0, 1, 2),
                expectedOpData(2L, "20", 3, 4, 5)
        )
        assertTrue(sut.validateSpecialOperations(SpecialTransactionPosition.Begin, blockCtx, ops))
    }

    private fun config(maxEventsPerBlock: Long): EifEventReceiverConfig =
            gtv(mapOf(
                    "max_events_per_block" to gtv(maxEventsPerBlock),
                    "chains" to gtv(emptyMap())
            )).toObject<EifEventReceiverConfig>()
}