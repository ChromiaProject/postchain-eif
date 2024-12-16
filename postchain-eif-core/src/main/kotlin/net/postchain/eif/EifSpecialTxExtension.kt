package net.postchain.eif

import mu.KLogging
import net.postchain.base.SpecialTransactionPosition
import net.postchain.common.BlockchainRid
import net.postchain.core.BlockEContext
import net.postchain.core.block.BlockData
import net.postchain.crypto.CryptoSystem
import net.postchain.eif.config.EifEventReceiverConfig
import net.postchain.gtx.GTXModule
import net.postchain.gtx.data.OpData
import net.postchain.gtx.special.GTXBlockBuildingAffectingSpecialTxExtension
import java.time.Clock

class EifSpecialTxExtension(private val clock: Clock = Clock.systemUTC()) : GTXBlockBuildingAffectingSpecialTxExtension {

    companion object : KLogging()

    lateinit var config: EifEventReceiverConfig

    private var needEifTnx: Boolean = false
    internal val processors = mutableMapOf<Long, EventProcessor>()

    override fun getRelevantOps() = setOf(EvmBlockOp.OP_NAME)

    fun addEventProcessor(networkID: Long, processor: EventProcessor) {
        processors[networkID] = processor
    }

    override fun init(module: GTXModule, chainID: Long, blockchainRID: BlockchainRid, cs: CryptoSystem) {
        needEifTnx = module.getOperations().contains(EvmBlockOp.OP_NAME)
    }

    override fun needsSpecialTransaction(position: SpecialTransactionPosition): Boolean {
        return when (position) {
            SpecialTransactionPosition.Begin -> needEifTnx
            SpecialTransactionPosition.End -> false
        }
    }

    override fun createSpecialOperations(position: SpecialTransactionPosition, bctx: BlockEContext): List<OpData> {
        if (position == SpecialTransactionPosition.Begin && processors.isNotEmpty()) {
            val index = bctx.height.mod(processors.size)
            val proc = processors.values.toList()[index]
            val data = proc.getEventData()
            return data.map { it.toOpData() }.also {
                bctx.addAfterCommitHook { proc.markAsProcessed(data) }
            }
        }
        return listOf()
    }

    override fun validateSpecialOperations(position: SpecialTransactionPosition, bctx: BlockEContext, ops: List<OpData>): Boolean {
        if (position == SpecialTransactionPosition.Begin && processors.isNotEmpty()) {
            val index = bctx.height.mod(processors.size)
            val proc = processors.values.toList()[index]
            val decodedOps = ops.map {
                if (it.opName != EvmBlockOp.OP_NAME) {
                    logger.error("Unknown operation: ${it.opName}")
                    null
                } else {
                    EvmBlockOp.fromOpData(it)
                }
            }
            bctx.addAfterCommitHook { proc.markAsProcessed(decodedOps.filterNotNull()) }
            if (decodedOps.contains(null)) return false
            return proc.isValidEventData(decodedOps.filterNotNull())
        }
        return ops.isEmpty()
    }

    private var firstEventBlockTime = 0L

    override fun blockCommitted(blockData: BlockData) {
        firstEventBlockTime = 0
    }

    override fun shouldBuildBlock(): Boolean {
        if (!::config.isInitialized) return false

        val numberOfEvents = fetchNumberOfEvents()
        if (numberOfEvents == 0L) return false

        val now = clock.millis()
        if (firstEventBlockTime > 0 && now - firstEventBlockTime > config.maxEventDelay) return true

        if (numberOfEvents >= config.numberOfEventsToTriggerBlockBuilding) return true
        if (firstEventBlockTime == 0L) {
            firstEventBlockTime = now
        }

        return false
    }

    private fun fetchNumberOfEvents(): Long = processors.values.sumOf { it.numberOfNewEvents() }
}
