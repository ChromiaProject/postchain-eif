package net.postchain.eif.transaction

import net.postchain.common.BlockchainRid
import net.postchain.core.EContext
import net.postchain.core.TxEContext
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.FETCH_QUEUED_TXS_QUERY
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.UPDATE_EVM_TRANSACTION_RECEIPT
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.UPDATE_EVM_TRANSACTION_STATE
import net.postchain.gtv.GtvArray
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
    val completedTxs: MutableSet<Long>,
    val operations: MutableList<ExtOpData>
)

class TransactionSubmitterQueuedTransactionTestGTXModule : TransactionSubmitterTestGTXModule(){
    override fun initializeDB(ctx: EContext) {
        val transactionSubmitterDatabaseOperations = TransactionSubmitterDatabaseOperationsImpl()
        transactionSubmitterDatabaseOperations.initialize(ctx)
        transactionSubmitterDatabaseOperations.queueTransaction(ctx, EvmSubmitTransactionRequest(
                0,
                "659e4a3726275edFD125F52338ECe0d54d15BD99",
        "addValidator",
        listOf("uint", "address"),
        listOf(gtv(1), gtv(ByteArray(20))),
        1337,
        BlockchainRid.ZERO_RID.data,
        RellTransactionStatus.QUEUED
        ), 1337L)
    }
}

class TransactionSubmitterPendingTransactionTestGTXModule : TransactionSubmitterTestGTXModule(){
    companion object {
        var TRANSACTION_HASH = ""
    }

    override fun initializeDB(ctx: EContext) {
        val transactionSubmitterDatabaseOperations = TransactionSubmitterDatabaseOperationsImpl()
        transactionSubmitterDatabaseOperations.initialize(ctx)
        transactionSubmitterDatabaseOperations.queueTransaction(ctx, EvmSubmitTransactionRequest(
                0,
                "659e4a3726275edFD125F52338ECe0d54d15BD99",
                "addValidator",
                listOf("uint", "address"),
                listOf( gtv(1), gtv(ByteArray(20))),
                1337,
                BlockchainRid.ZERO_RID.data,
                RellTransactionStatus.QUEUED
        ), 1337L)
        transactionSubmitterDatabaseOperations.pendTransaction(ctx,0, BigInteger.ONE, BigInteger.TEN, TRANSACTION_HASH)
    }
}


class TransactionSubmitterSuccessfulTransactionTestGTXModule : TransactionSubmitterTestGTXModule(){

    override fun initializeDB(ctx: EContext) {
        val transactionSubmitterDatabaseOperations = TransactionSubmitterDatabaseOperationsImpl()
        transactionSubmitterDatabaseOperations.initialize(ctx)
        transactionSubmitterDatabaseOperations.queueTransaction(ctx, EvmSubmitTransactionRequest(
                0,
                "659e4a3726275edFD125F52338ECe0d54d15BD99",
                "addValidator",
                listOf("uint", "address"),
                listOf( gtv(1), gtv(ByteArray(20))),
                1337,
                BlockchainRid.ZERO_RID.data,
                RellTransactionStatus.QUEUED
        ), 1337L)
        transactionSubmitterDatabaseOperations.pendTransaction(ctx,0, BigInteger.ONE, BigInteger.TEN, "")
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
                "659e4a3726275edFD125F52338ECe0d54d15BD99",
                "addValidator",
                listOf("uint", "address"),
                listOf( gtv(1000000000), gtv(ByteArray(20))),
                1337,
                BlockchainRid.ZERO_RID.data,
                RellTransactionStatus.QUEUED
        ), 1337L)
    }
}

open class TransactionSubmitterTestGTXModule : SimpleGTXModule<TransactionSubmitterTestContext>(
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
