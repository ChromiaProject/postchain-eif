package net.postchain.eif.transaction

import net.postchain.core.EContext
import net.postchain.core.TxEContext
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.FETCH_QUEUED_TXS_QUERY
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.UPDATE_EVM_TRANSACTION_STATE
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtx.GTXOperation
import net.postchain.gtx.SimpleGTXModule
import net.postchain.gtx.data.ExtOpData
import net.postchain.gtx.special.GTXSpecialTxExtension
import java.util.concurrent.LinkedBlockingQueue

data class TransactionSubmitterTestContext(val queue: LinkedBlockingQueue<EvmSubmitTransactionRequest>, val completedTxs: MutableSet<Long>)

class TransactionSubmitterTestGTXModule : SimpleGTXModule<TransactionSubmitterTestContext>(
        TransactionSubmitterTestContext(LinkedBlockingQueue(), mutableSetOf()),
        mapOf(UPDATE_EVM_TRANSACTION_STATE to { conf, opData ->
            ModifyTxStateOperation(conf, opData)
        }),
        mapOf(FETCH_QUEUED_TXS_QUERY to { conf, ctxt, args ->
            // TODO Return mocked queued txs
            gtv(conf.queue.filter { it.status == TRANSACTION_STATUS.QUEUED }.map { GtvObjectMapper.toGtvDictionary(it) })
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
        val status = TRANSACTION_STATUS.values()[extOpData.args[1].asInteger().toInt()]

        if (status == TRANSACTION_STATUS.TAKEN) {
            return conf.queue.removeIf { it.rowId == rowId }
        } else if (status == TRANSACTION_STATUS.SUCCESS) {
            conf.completedTxs.add(rowId)
        }
        return true
    }
}
