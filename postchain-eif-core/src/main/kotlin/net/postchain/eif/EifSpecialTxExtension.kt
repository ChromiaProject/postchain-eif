package net.postchain.eif

import net.postchain.base.SpecialTransactionPosition
import net.postchain.common.BlockchainRid
import net.postchain.core.BlockEContext
import net.postchain.crypto.CryptoSystem
import net.postchain.gtx.GTXModule
import net.postchain.gtx.data.OpData
import net.postchain.gtx.special.GTXSpecialTxExtension

const val OP_EVM_BLOCK = "__evm_block"

class EifSpecialTxExtension : GTXSpecialTxExtension {

    private var needEifTnx: Boolean = false
    private val processors = mutableMapOf<Long, EventProcessor>()

    override fun getRelevantOps() = setOf(OP_EVM_BLOCK)

    fun addEventProcessor(networkID: Long, processor: EventProcessor) {
        processors[networkID] = processor
    }

    override fun init(module: GTXModule, chainID: Long, blockchainRID: BlockchainRid, cs: CryptoSystem) {
        needEifTnx = module.getOperations().contains(OP_EVM_BLOCK)
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
            return data.map { OpData(OP_EVM_BLOCK, it) }.also {
                bctx.addAfterCommitHook { proc.markAsProcessed(it) }
            }
        }
        return listOf()
    }


    override fun validateSpecialOperations(position: SpecialTransactionPosition, bctx: BlockEContext, ops: List<OpData>): Boolean {
        if (position == SpecialTransactionPosition.Begin && processors.isNotEmpty()) {
            val index = bctx.height.mod(processors.size)
            val proc = processors.values.toList()[index]
            bctx.addAfterCommitHook { proc.markAsProcessed(ops) }
            return proc.isValidEventData(ops)
        }
        return ops.isEmpty()
    }
}