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
        const val UPDATE_EVM_TRANSACTION_RECEIPT = "__update_evm_transaction_receipt"

        const val FETCH_QUEUED_TXS_QUERY = "fetch_queued_evm_transaction"
    }

    private val transactionSubmitters = mutableMapOf<Long, TransactionSubmitter>()
    private lateinit var module: GTXModule

    override fun createSpecialOperations(position: SpecialTransactionPosition, bctx: BlockEContext): List<OpData> {
        val entities = module.query(bctx, FETCH_QUEUED_TXS_QUERY, gtv(listOf()))
        val queuedTransactions = entities.asArray().map {
            it.toObject<EvmSubmitTransactionRequest>()
        }
        val operations = mutableListOf<OpData>()
        queuedTransactions.forEach {
            val submitter = transactionSubmitters[it.networkId]
            if (submitter == null) {
                logger.warn("Ignoring tx since there is no submitter for ${it.networkId}")
            } else if (!submitter.isHealthy()) {
                logger.warn("Ignoring tx since the submitter for ${it.networkId} is unhealthy")
            } else {
                bctx.addAfterCommitHook { submitter.enqueue(it) }
                operations.add(OpData(UPDATE_EVM_TRANSACTION_STATE, arrayOf(gtv(it.rowId), gtv(RellTransactionStatus.TAKEN.ordinal.toLong()))))
            }
        }

        transactionSubmitters.forEach { submitter ->
            submitter.value.fetchCompletedTransactions().forEach { (rowId, result) ->
                operations.add(OpData(UPDATE_EVM_TRANSACTION_STATE, arrayOf(gtv(rowId), gtv(result.status.ordinal.toLong()))))
                if (result.status == RellTransactionStatus.SUCCESS) {
                    if (result.blockHash == null || result.effectiveGasPrice == null || result.gasUsage == null) {
                        logger.error { "Transaction $rowId is SUCCESS but has not a full receipt. Blockchain: ${result.blockHash}, effectiveGasPrice: ${result.effectiveGasPrice}, gasUsage: ${result.gasUsage}" }
                    } else {
                        operations.add(OpData(
                                UPDATE_EVM_TRANSACTION_RECEIPT, arrayOf(
                                    gtv(rowId),
                                    gtv(result.blockHash),
                                    gtv(result.effectiveGasPrice),
                                    gtv(result.gasUsage),
                                )))
                    }
                }
                bctx.addAfterCommitHook { submitter.value.deactivateTransaction(rowId) }
            }
        }

        return operations
    }

    override fun getRelevantOps(): Set<String> {
        return setOf(UPDATE_EVM_TRANSACTION_STATE, UPDATE_EVM_TRANSACTION_RECEIPT)
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

    fun getTransactionSubmitter(networkId: Long) = transactionSubmitters[networkId]
}
