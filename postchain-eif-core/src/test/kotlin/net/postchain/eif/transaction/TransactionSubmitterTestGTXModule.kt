package net.postchain.eif.transaction

import net.postchain.common.BlockchainRid
import net.postchain.core.EContext
import net.postchain.core.TxEContext
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.FETCH_OLDEST_QUEUED_TRANSACTIONS_PER_CONTRACT
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.ADD_EVM_TRANSACTION_ERRORS
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.UPDATE_EVM_TRANSACTION_RECEIPT
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.UPDATE_EVM_TRANSACTION_STATE
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtx.GTXOperation
import net.postchain.gtx.SimpleGTXModule
import net.postchain.gtx.data.ExtOpData
import net.postchain.gtx.special.GTXSpecialTxExtension
import java.math.BigInteger
import java.util.concurrent.LinkedBlockingQueue

data class TransactionSubmitterTestContext(
        val queue: LinkedBlockingQueue<EvmSubmitTransactionRequest>,
        val successfulTxs: MutableSet<Long>,
        val queuedTxs: MutableSet<Long>,
        val failedTxs: MutableSet<Long>,
        val operations: MutableList<ExtOpData>
)

class TransactionSubmitterQueuedTransactionTestGTXModule : TransactionSubmitterTestGTXModule(){
    override fun initializeDB(ctx: EContext) {
        val transactionSubmitterDatabaseOperations = TransactionSubmitterDatabaseOperationsImpl()
        transactionSubmitterDatabaseOperations.initialize(ctx)
        transactionSubmitterDatabaseOperations.queueTransaction(ctx, EvmSubmitTransactionRequest(
                0,
                "6936b1761eafc2116650b6593bbc86bd79a339a5", // TODO: Fetch contract address instead of hardcoding
                "updateValidators",
                listOf("address[]"),
                listOf(gtv(listOf(gtv(ByteArray(20) { 1 })))),
        1337,
        BlockchainRid.ZERO_RID.data,
        RellTransactionStatus.QUEUED,
        System.currentTimeMillis()
        ), 1337L)
    }
}

class TransactionSubmitterPendingTransactionTestGTXModule : TransactionSubmitterTestGTXModule(){
    companion object {
        var TRANSACTION_HASH = ""
        var TIMESTAMP = System.currentTimeMillis()
    }

    override fun initializeDB(ctx: EContext) {
        val transactionSubmitterDatabaseOperations = TransactionSubmitterDatabaseOperationsImpl()
        transactionSubmitterDatabaseOperations.initialize(ctx)
        transactionSubmitterDatabaseOperations.queueTransaction(ctx, EvmSubmitTransactionRequest(
                0,
                "6936b1761eafc2116650b6593bbc86bd79a339a5",
                "updateValidators",
                listOf("address[]"),
                listOf(gtv(listOf(gtv(ByteArray(20) { 1 })))),
                1337,
                BlockchainRid.ZERO_RID.data,
                RellTransactionStatus.QUEUED,
                TIMESTAMP
        ), 1337L)
        transactionSubmitterDatabaseOperations.recordTransactionGas(ctx, 0, BigInteger.ONE, BigInteger.TEN)
        transactionSubmitterDatabaseOperations.pendTransaction(ctx,0, TRANSACTION_HASH)
    }
}


class TransactionSubmitterSuccessfulTransactionTestGTXModule : TransactionSubmitterTestGTXModule(){

    override fun initializeDB(ctx: EContext) {
        val transactionSubmitterDatabaseOperations = TransactionSubmitterDatabaseOperationsImpl()
        transactionSubmitterDatabaseOperations.initialize(ctx)
        transactionSubmitterDatabaseOperations.queueTransaction(ctx, EvmSubmitTransactionRequest(
                0,
                "6936b1761eafc2116650b6593bbc86bd79a339a5",
                "updateValidators",
                listOf("address[]"),
                listOf(gtv(listOf(gtv(ByteArray(20) { 1 })))),
                1337,
                BlockchainRid.ZERO_RID.data,
                RellTransactionStatus.QUEUED,
                System.currentTimeMillis()
        ), 1337L)
        transactionSubmitterDatabaseOperations.recordTransactionGas(ctx, 0, BigInteger.ONE, BigInteger.TEN)
        transactionSubmitterDatabaseOperations.succeedTransaction(ctx,0,BigInteger.valueOf(4100000000),BigInteger.valueOf(21332),"0x499450bc3d3a1028d7c86cfaf04ccc4e8080bbf1b32c95bab819cb8abe9abfb3")
        transactionSubmitterDatabaseOperations.deactivateTransaction(ctx, 0)
    }
}

class TransactionSubmitterFailTransactionTestGTXModule : TransactionSubmitterTestGTXModule(){
    override fun initializeDB(ctx: EContext) {
        val transactionSubmitterDatabaseOperations = TransactionSubmitterDatabaseOperationsImpl()
        transactionSubmitterDatabaseOperations.initialize(ctx)
        transactionSubmitterDatabaseOperations.queueTransaction(ctx, EvmSubmitTransactionRequest(
                0,
                "6936b1761eafc2116650b6593bbc86bd79a339a5",
                "updateValidators",
                listOf("uint"),
                listOf(gtv(1)), // wrong type
                1337,
                BlockchainRid.ZERO_RID.data,
                RellTransactionStatus.QUEUED,
                System.currentTimeMillis()
        ), 1337L)
    }
}

open class TransactionSubmitterTestGTXModule : SimpleGTXModule<TransactionSubmitterTestContext>(
        TransactionSubmitterTestContext(LinkedBlockingQueue(), mutableSetOf(), mutableSetOf(), mutableSetOf(), mutableListOf()),
        mapOf(UPDATE_EVM_TRANSACTION_STATE to { conf, opData ->
            ModifyTxStateOperation(conf, opData)
        }, UPDATE_EVM_TRANSACTION_RECEIPT to { conf, opData ->
            CaptureTxOperation(conf, opData)
        }, ADD_EVM_TRANSACTION_ERRORS to { conf, opData ->
            CaptureTxOperation(conf, opData)
        }),
        mapOf(FETCH_OLDEST_QUEUED_TRANSACTIONS_PER_CONTRACT to { conf, _, _ ->
            gtv(conf.queue.filter { it.status == RellTransactionStatus.QUEUED }.map { GtvObjectMapper.toGtvDictionary(it) })
        })
) {

    private val specialTxExtensions = listOf(TransactionSubmitterSpecialTxExtension())

    override fun initializeDB(ctx: EContext) {

        val transactionSubmitterDatabaseOperations = TransactionSubmitterDatabaseOperationsImpl()
        transactionSubmitterDatabaseOperations.initialize(ctx)
    }

    override fun getSpecialTxExtensions(): List<GTXSpecialTxExtension> {
        return specialTxExtensions
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

        when (status) {
            RellTransactionStatus.TAKEN -> conf.queue.removeIf { it.rowId == rowId }
            RellTransactionStatus.SUCCESS -> conf.successfulTxs.add(rowId)
            RellTransactionStatus.QUEUED -> conf.queuedTxs.add(rowId)
            RellTransactionStatus.FAILURE -> conf.failedTxs.add(rowId)
            else -> {}
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
