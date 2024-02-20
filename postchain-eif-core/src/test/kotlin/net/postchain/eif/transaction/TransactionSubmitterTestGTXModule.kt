package net.postchain.eif.transaction

import net.postchain.core.EContext
import net.postchain.core.TxEContext
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.FETCH_QUEUED_TXS_QUERY
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.UPDATE_EVM_TRANSACTION_RECEIPT
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.UPDATE_EVM_TRANSACTION_STATE
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtx.GTXOperation
import net.postchain.gtx.SimpleGTXModule
import net.postchain.gtx.data.ExtOpData
import net.postchain.gtx.special.GTXSpecialTxExtension
import java.util.concurrent.LinkedBlockingQueue

data class TransactionSubmitterTestContext(
    val queue: LinkedBlockingQueue<EvmSubmitTransactionRequest>,
    val completedTxs: MutableSet<Long>,
    val operations: MutableList<ExtOpData>
)

class TransactionSubmitterTestGTXModule : SimpleGTXModule<TransactionSubmitterTestContext>(
        TransactionSubmitterTestContext(LinkedBlockingQueue(), mutableSetOf(), mutableListOf()),
        mapOf(UPDATE_EVM_TRANSACTION_STATE to { conf, opData ->
            ModifyTxStateOperation(conf, opData)
        }, UPDATE_EVM_TRANSACTION_RECEIPT to { conf, opData ->
            CaptureTxOperation(conf, opData)
        }),
        mapOf(FETCH_QUEUED_TXS_QUERY to { conf, _, _ ->
            gtv(conf.queue.filter { it.status == RellTransactionStatus.QUEUED }.map { GtvObjectMapper.toGtvDictionary(it) })
        })
) {
    override fun initializeDB(ctx: EContext) {

        val transactionSubmitterDatabaseOperations = TransactionSubmitterDatabaseOperationsImpl()
        transactionSubmitterDatabaseOperations.initialize(ctx)
    }

    override fun getSpecialTxExtensions(): List<GTXSpecialTxExtension> {

        return listOf(TransactionSubmitterSpecialTxExtension())
    }

    fun addTxToQueue(tx: EvmSubmitTransactionRequest) {
        conf.queue.offer(tx)
    }
}

class ModifyTxStateOperation(private val conf: TransactionSubmitterTestContext, private val extOpData: ExtOpData) : GTXOperation(extOpData) {
    override fun checkCorrectness() {}
    override fun apply(ctx: TxEContext): Boolean {
        val rowId = extOpData.args[0].asInteger()
        val status = RellTransactionStatus.values()[extOpData.args[1].asInteger().toInt()]

        if (status == RellTransactionStatus.TAKEN) {
            return conf.queue.removeIf { it.rowId == rowId }
        } else if (status == RellTransactionStatus.SUCCESS) {
            conf.completedTxs.add(rowId)
        }

        conf.operations.add(extOpData)
        return true
    }
}


class CaptureTxOperation(private val conf: TransactionSubmitterTestContext, private val extOpData: ExtOpData) : GTXOperation(extOpData) {
    override fun checkCorrectness() {}
    override fun apply(ctx: TxEContext): Boolean {

        conf.operations.add(extOpData)
        return true
    }
}
