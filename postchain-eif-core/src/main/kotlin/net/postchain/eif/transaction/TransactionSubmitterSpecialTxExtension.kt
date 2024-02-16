package net.postchain.eif.transaction

import mu.KLogging
import net.postchain.base.SpecialTransactionPosition
import net.postchain.common.BlockchainRid
import net.postchain.core.BlockEContext
import net.postchain.crypto.CryptoSystem
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.mapper.toObject
import net.postchain.gtx.GTXModule
import net.postchain.gtx.data.OpData
import net.postchain.gtx.special.GTXSpecialTxExtension

class TransactionSubmitterSpecialTxExtension : GTXSpecialTxExtension {
    companion object : KLogging() {
        const val UPDATE_EVM_TRANSACTION_STATE = "__update_evm_transaction_state"
    }

    private val transactionSubmitters = mutableMapOf<Long, TransactionSubmitter>()
    private lateinit var module: GTXModule

    override fun createSpecialOperations(position: SpecialTransactionPosition, bctx: BlockEContext): List<OpData> {
        val entities = module.query(bctx, "fetch_queued_evm_transaction", gtv(listOf()))
        val queuedTransactions = entities.asArray().map {
            it.toObject<EvmSubmitTransactionRequest>()
        }
        val operations = mutableListOf<OpData>()
        queuedTransactions.forEach {
            val submitter = transactionSubmitters[it.networkId]
            if (submitter == null) {
                logger.warn("There is no submitter for ${it.networkId} .")
            } else {
                bctx.addAfterCommitHook { submitter.enqueue(it) }
                operations.add(OpData(UPDATE_EVM_TRANSACTION_STATE, arrayOf(gtv(it.rowId), gtv(TRANSACTION_STATUS.TAKEN.ordinal.toLong()))))
            }
        }

        transactionSubmitters.forEach { submitter ->
            submitter.value.fetchCompletedTransactions().forEach { (rowId, status) ->
                operations.add(OpData(UPDATE_EVM_TRANSACTION_STATE, arrayOf(gtv(rowId), gtv(status.ordinal.toLong()))))
                bctx.addAfterCommitHook { submitter.value.removeCompletedTransaction(rowId) }
            }
        }

        return operations
    }

    override fun getRelevantOps(): Set<String> {
        return setOf(UPDATE_EVM_TRANSACTION_STATE)
    }

    override fun init(module: GTXModule, chainID: Long, blockchainRID: BlockchainRid, cs: CryptoSystem) {
        this.module = module
    }

    override fun needsSpecialTransaction(position: SpecialTransactionPosition): Boolean {
        return when (position) {
            SpecialTransactionPosition.Begin -> true
            SpecialTransactionPosition.End -> false
        }
    }

    override fun validateSpecialOperations(
            position: SpecialTransactionPosition,
            bctx: BlockEContext,
            ops: List<OpData>
    ) = true

    fun addTransactionSubmitter(transactionSubmitter: TransactionSubmitter, networkId: Long) {

        transactionSubmitters[networkId] = transactionSubmitter
    }
}
