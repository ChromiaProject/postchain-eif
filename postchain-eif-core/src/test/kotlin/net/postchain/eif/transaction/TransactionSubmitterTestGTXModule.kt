package net.postchain.eif.transaction

import net.postchain.base.data.DatabaseAccess
import net.postchain.common.BlockchainRid
import net.postchain.core.EContext
import net.postchain.core.TxEContext
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.EVM_TX_NO_OP
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.FETCH_OLDEST_QUEUED_TRANSACTIONS_PER_CONTRACT
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.GET_PENDING_TRANSACTIONS
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.GET_TRANSACTION_STATUS
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.UPDATE_EVM_TRANSACTION_RECEIPT
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.UPDATE_EVM_TRANSACTION_STATUS
import net.postchain.eif.transaction.anchoring.EvmAnchoringSpecialTxExtension
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvString
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtx.GTXOperation
import net.postchain.gtx.SimpleGTXModule
import net.postchain.gtx.data.ExtOpData
import net.postchain.gtx.special.GTXSpecialTxExtension
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DSL.table
import java.math.BigInteger
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.LinkedBlockingQueue

data class TransactionSubmitterTestContext(
    val queue: LinkedBlockingQueue<EvmSubmitTxRellRequest>,
    val pending: LinkedBlockingQueue<EvmPendingRellTx>,
    val taken: MutableSet<Long>,
    val successfulTxs: MutableSet<Long>,
    val queuedTxs: MutableSet<Long>,
    val failedTxs: MutableSet<Long>,
    val getTransactionStatus: MutableMap<Long, RellTransactionStatus>, // Used to mock bc statuses returned by get_transaction_status
    val operations: MutableList<ExtOpData>
)

class TransactionSubmitterQueuedTransactionTestGTXModule : TransactionSubmitterTestGTXModule(){
    override fun initializeDB(ctx: EContext) {
        val transactionSubmitterDatabaseOperations = TransactionSubmitterDatabaseOperationsImpl()
        transactionSubmitterDatabaseOperations.initialize(ctx)
        transactionSubmitterDatabaseOperations.queueTransaction(ctx, EvmSubmitTxRequest(
            EvmSubmitTxRellRequest(
                0,
                "6936b1761eafc2116650b6593bbc86bd79a339a5", // TODO: Fetch contract address instead of hardcoding
                "updateValidators",
                listOf("address[]"),
                listOf(gtv(listOf(gtv(ByteArray(20) { 1 })))),
                1337,
                BigInteger.ONE,
                BigInteger.valueOf(4000000000),
                BlockchainRid.ZERO_RID.data,
                System.currentTimeMillis()
            )
        ), 1337L)
    }
}

class TransactionSubmitterPendingTransactionTestGTXModule : TransactionSubmitterTestGTXModule(){
    override fun initializeDB(ctx: EContext) {
        val transactionSubmitterDatabaseOperations = TransactionSubmitterDatabaseOperationsImpl()
        transactionSubmitterDatabaseOperations.initialize(ctx)

        DatabaseAccess.of(ctx).apply {
            val jooq = DSL.using(ctx.conn, SQLDialect.POSTGRES)

            // Submit and pending to be removed
            jooq.insertInto(table(tableEvmTxSubmit(ctx)))
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_REQUEST_ID, 0)
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_CONTRACT, "")
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_FUNCTION, "")
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_PARAMETER_TYPES, "")
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_PARAMETER_VALUES, GtvEncoder.encodeGtv(gtv(listOf())))
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_TIMESTAMP, System.currentTimeMillis())
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_MAX_PRIORITY_FEE_PER_GAS, 1)
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_MAX_FEE_PER_GAS, 4)
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_NETWORK_ID, 1337)
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_SENDER, "".toByteArray())
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_HASH, "00")
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_BC_PERSISTED, true)
                .execute()
        }
    }
}

class TransactionSubmitterCleanupTransactionTestGTXModule : TransactionSubmitterTestGTXModule(){
    override fun initializeDB(ctx: EContext) {
        val transactionSubmitterDatabaseOperations = TransactionSubmitterDatabaseOperationsImpl()
        transactionSubmitterDatabaseOperations.initialize(ctx)

        val time15DaysAgo = Instant.now().minus(15, ChronoUnit.DAYS).toEpochMilli()

        DatabaseAccess.of(ctx).apply {
            val jooq = DSL.using(ctx.conn, SQLDialect.POSTGRES)

            // Submit and pending to be removed
            jooq.insertInto(table(tableEvmTxSubmit(ctx)))
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_REQUEST_ID, 0)
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_CONTRACT, "")
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_FUNCTION, "")
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_PARAMETER_TYPES, "")
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_PARAMETER_VALUES, GtvEncoder.encodeGtv(gtv(listOf())))
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_TIMESTAMP, time15DaysAgo)
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_NETWORK_ID, 1337)
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_MAX_PRIORITY_FEE_PER_GAS, 1)
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_MAX_FEE_PER_GAS, 4)
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_SENDER, "".toByteArray())
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_HASH, "00")
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_BC_PERSISTED, true)
                .execute()

            jooq.insertInto(table(tableEvmTxErrors(ctx)))
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_ERRORS_COLUMN_TIMESTAMP, DSL.currentTimestamp())
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_ERRORS_COLUMN_REQUEST_ID, 0)
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_ERRORS_COLUMN_RPC_URL, "")
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_ERRORS_COLUMN_MESSAGE, "")
                .execute()

            // Submit and pending to be kept
            jooq.insertInto(table(tableEvmTxSubmit(ctx)))
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_REQUEST_ID, 1)
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_CONTRACT, "")
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_FUNCTION, "")
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_PARAMETER_TYPES, "")
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_PARAMETER_VALUES, GtvEncoder.encodeGtv(gtv(listOf())))
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_TIMESTAMP, System.currentTimeMillis())
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_MAX_PRIORITY_FEE_PER_GAS, 1)
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_MAX_FEE_PER_GAS, 4)
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_NETWORK_ID, 1337)
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_SENDER, "".toByteArray())
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_HASH, "00")
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_BC_PERSISTED, true)
                .execute()

            jooq.insertInto(table(tableEvmTxErrors(ctx)))
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_ERRORS_COLUMN_TIMESTAMP, DSL.currentTimestamp())
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_ERRORS_COLUMN_REQUEST_ID, 1)
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_ERRORS_COLUMN_RPC_URL, "")
                .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_ERRORS_COLUMN_MESSAGE, "")
                .execute()
        }
    }
}

open class TransactionSubmitterTestGTXModule(
        opOverrides: Map<String, (TransactionSubmitterTestContext, ExtOpData) -> net.postchain.core.Transactor> = mapOf(),
        queryOverrides: Map<String, (TransactionSubmitterTestContext, EContext, Gtv) -> Gtv> = mapOf()
) : SimpleGTXModule<TransactionSubmitterTestContext>(
        TransactionSubmitterTestContext(LinkedBlockingQueue(), LinkedBlockingQueue(), mutableSetOf(), mutableSetOf(), mutableSetOf(), mutableSetOf(), mutableMapOf(), mutableListOf()),
        mapOf(UPDATE_EVM_TRANSACTION_STATUS to { conf: TransactionSubmitterTestContext, opData: ExtOpData ->
            ModifyTxStatusOperation(conf, opData)
        }, UPDATE_EVM_TRANSACTION_RECEIPT to { conf: TransactionSubmitterTestContext, opData: ExtOpData ->
            CaptureTxOperation(conf, opData)
        }, EVM_TX_NO_OP to { conf: TransactionSubmitterTestContext, opData: ExtOpData ->
            CaptureTxOperation(conf, opData)
        }) + opOverrides,
        mapOf(
            FETCH_OLDEST_QUEUED_TRANSACTIONS_PER_CONTRACT to { conf: TransactionSubmitterTestContext, _: EContext, _: Gtv ->
                gtv(conf.queue.map { GtvObjectMapper.toGtvDictionary(it) }) },
            GET_PENDING_TRANSACTIONS to { conf: TransactionSubmitterTestContext, _, _ ->
                val pending = gtv(conf.pending.map { GtvObjectMapper.toGtvDictionary(it) })
                conf.pending.clear()
                pending
                                        },
            GET_TRANSACTION_STATUS to { conf: TransactionSubmitterTestContext, _, args: Gtv ->
                val rowId = args["row_id"]!!.asInteger()
                GtvString(conf.getTransactionStatus[rowId]!!.name)
            }
        ) + queryOverrides
) {

    private val specialTxExtensions = listOf(TransactionSubmitterSpecialTxExtension(), EvmAnchoringSpecialTxExtension())

    override fun initializeDB(ctx: EContext) {

        val transactionSubmitterDatabaseOperations = TransactionSubmitterDatabaseOperationsImpl()
        transactionSubmitterDatabaseOperations.initialize(ctx)
    }

    override fun getSpecialTxExtensions(): List<GTXSpecialTxExtension> {
        return specialTxExtensions
    }

    fun addTxToQueue(tx: EvmSubmitTxRellRequest) {
        addGetTransactionStatus(tx.rowId, RellTransactionStatus.QUEUED)
        conf.queue.offer(tx)
    }

    fun addGetPendingTransactions(tx: EvmPendingRellTx) {
        addGetTransactionStatus(tx.rowId, RellTransactionStatus.PENDING)
        conf.pending.offer(tx)
    }

    fun addGetTransactionStatus(requestId: Long, status: RellTransactionStatus) {

        conf.getTransactionStatus[requestId] = status
    }
}

class ModifyTxStatusOperation(
    private val conf: TransactionSubmitterTestContext,
    private val extOpData: ExtOpData,
) : GTXOperation(extOpData) {
    override fun checkCorrectness() {}
    override fun apply(ctx: TxEContext): Boolean {
        val rowId = extOpData.args[0].asInteger()
        val status = RellTransactionStatus.values()[extOpData.args[1].asInteger().toInt()]

        when (status) {
            RellTransactionStatus.TAKEN -> {
                conf.queue.removeIf { it.rowId == rowId }
                conf.taken.add(rowId)
            }
            RellTransactionStatus.SUCCESS -> conf.successfulTxs.add(rowId)
            RellTransactionStatus.QUEUED -> conf.queuedTxs.add(rowId)
            RellTransactionStatus.FAILURE -> conf.failedTxs.add(rowId)
            else -> {}
        }

        conf.getTransactionStatus[rowId] = status
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
