package net.postchain.eif

import mu.KLogging
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvArray
import net.postchain.gtv.GtvBigInteger
import net.postchain.gtv.GtvByteArray
import net.postchain.gtv.GtvString
import java.math.BigInteger

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

    /**
     * Called after a block has been durably committed. [markAsProcessed] for that block runs before the commit is durable.
     *
     * @param upToEvmBlockHeight the highest EVM block height of this network included in the committed block, null if none
     */
    fun blockCommitted(upToEvmBlockHeight: BigInteger?) {}
}

class EventValidationResult(
        val valid: Boolean,
        val conflictingHeight: BigInteger? = null,
        val errorMessage: String? = null,
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
    override fun isValidEventData(ops: List<EvmBlockOp>): EventValidationResult =
            EventValidationResult(ops.all { op -> op.events.all { isValidEvmEventFormat(it.asArray()) } })

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

