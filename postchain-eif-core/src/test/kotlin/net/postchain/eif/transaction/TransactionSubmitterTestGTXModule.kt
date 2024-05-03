package net.postchain.eif.transaction

import net.postchain.base.data.DatabaseAccess
import net.postchain.core.EContext
import net.postchain.core.TxEContext
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.EVM_TX_NO_OP
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.FETCH_OLDEST_QUEUED_TRANSACTIONS_PER_CONTRACT
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.GET_PENDING_TRANSACTIONS
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.GET_TRANSACTION
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.GET_TRANSACTION_STATUS
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.UPDATE_EVM_TRANSACTION_RECEIPT
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.UPDATE_EVM_TRANSACTION_STATUS
import net.postchain.eif.transaction.anchoring.EvmAnchoringSpecialTxExtension
import net.postchain.eif.transaction.anchoring.EvmAnchoringSpecialTxExtension.Companion.GET_SYSTEM_ANCHORING_BLOCKCHAIN_RID_QUERY
import net.postchain.eif.transaction.signerupdate.EvmSignerUpdate
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import net.postchain.gtv.mapper.GtvObjectMapper
import net.postchain.gtx.GTXOperation
import net.postchain.gtx.SimpleGTXModule
import net.postchain.gtx.data.ExtOpData
import net.postchain.gtx.special.GTXSpecialTxExtension
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DSL.table
import java.math.BigInteger

data class TransactionSubmitterTestContext(
        val transactionsAvailableToTake: MutableList<EvmSubmitTxRellRequest>,
        val transactions: MutableList<EvmSubmitTxRellRequest>, // Mocks all transactions on BC
        val taken: MutableSet<Long>,
        val successfulTxs: MutableSet<Long>,
        val queuedTxs: MutableSet<Long>,
        val failedTxs: MutableSet<Long>,
        val operations: MutableList<ExtOpData>,
        val signerUpdates: MutableList<EvmSignerUpdate>
)

class TransactionSubmitterQueuedTransactionTestGTXModule : TransactionSubmitterTestGTXModule() {
    override fun initializeDB(ctx: EContext) {
        val transactionSubmitterDatabaseOperations = TransactionSubmitterDatabaseOperationsImpl()
        transactionSubmitterDatabaseOperations.initialize(ctx)
        val transactionRequest = mkEvmSubmitTxRequest(
                status = RellTransactionStatus.TAKEN
        )
        transactionSubmitterDatabaseOperations.queueTransaction(ctx, transactionRequest, 1337L)
        addTransaction(transactionRequest)
    }
}

class TransactionSubmitterPendingTransactionTestGTXModule : TransactionSubmitterTestGTXModule() {
    override fun initializeDB(ctx: EContext) {
        val transactionSubmitterDatabaseOperations = TransactionSubmitterDatabaseOperationsImpl()
        transactionSubmitterDatabaseOperations.initialize(ctx)

        DatabaseAccess.of(ctx).apply {
            val jooq = DSL.using(ctx.conn, SQLDialect.POSTGRES)

            // Submit a pending tx to be removed
            jooq.insertInto(table(tableEvmTxSubmit(ctx)))
                    .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_REQUEST_ID, 0)
                    .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_CONTRACT, "")
                    .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_FUNCTION, "")
                    .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_PARAMETER_TYPES, "")
                    .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_PARAMETER_VALUES, GtvEncoder.encodeGtv(gtv(listOf())))
                    .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_CREATED, System.currentTimeMillis())
                    .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_MAX_PRIORITY_FEE_PER_GAS, 1)
                    .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_MAX_FEE_PER_GAS, 4)
                    .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_NETWORK_ID, 1337)
                    .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_SENDER, "".toByteArray())
                    .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_HASH, "00")
                    .set(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_BC_PERSISTED, true)
                    .execute()
        }

        this.addTransaction(mkEvmSubmitTxRellRequest(0, "", RellTransactionStatus.PENDING, txHash = "tx-hash"))
    }
}

open class TransactionSubmitterTestGTXModule(
        opOverrides: Map<String, (TransactionSubmitterTestContext, ExtOpData) -> net.postchain.core.Transactor> = mapOf(),
        queryOverrides: Map<String, (TransactionSubmitterTestContext, EContext, Gtv) -> Gtv> = mapOf()
) : SimpleGTXModule<TransactionSubmitterTestContext>(
        TransactionSubmitterTestContext(mutableListOf(), mutableListOf(), mutableSetOf(), mutableSetOf(), mutableSetOf(), mutableSetOf(), mutableListOf(), mutableListOf()),
        mapOf(UPDATE_EVM_TRANSACTION_STATUS to { conf: TransactionSubmitterTestContext, opData: ExtOpData ->
            ModifyTxStatusOperation(conf, opData, this.updateTxStatus)
        }, UPDATE_EVM_TRANSACTION_RECEIPT to { conf: TransactionSubmitterTestContext, opData: ExtOpData ->
            CaptureTxOperation(conf, opData)
        }, EVM_TX_NO_OP to { conf: TransactionSubmitterTestContext, opData: ExtOpData ->
            CaptureTxOperation(conf, opData)
        }) + opOverrides,
        mapOf(
                FETCH_OLDEST_QUEUED_TRANSACTIONS_PER_CONTRACT to { conf: TransactionSubmitterTestContext, _: EContext, _: Gtv ->
                    gtv(conf.transactionsAvailableToTake.map { GtvObjectMapper.toGtvDictionary(it) })
                },
                GET_TRANSACTION to { conf: TransactionSubmitterTestContext, _, args: Gtv ->
                    val obj = conf.transactions.first { it.rowId == args["row_id"]!!.asInteger() }
//                    GtvObjectMapper.toGtvDictionary(obj) - does not map inherited attributes?
                    gtv(mapOf<String, Gtv>(
                            "status" to gtv(obj.status!!.name),
                            "processed_by" to gtv(obj.processed_by!!)
                    ))
                },
                GET_PENDING_TRANSACTIONS to { conf: TransactionSubmitterTestContext, _, _ ->
                    gtv(conf.transactions
                            .filter { it.status == RellTransactionStatus.PENDING }
                            .map { GtvObjectMapper.toGtvDictionary(it) })
                },
                GET_TRANSACTION_STATUS to { conf: TransactionSubmitterTestContext, _, args: Gtv ->
                    gtv(conf.transactions.first { it.rowId == args["row_id"]!!.asInteger() }.status!!.name)
                },
                GET_SYSTEM_ANCHORING_BLOCKCHAIN_RID_QUERY to { _, _, _ ->
                    GtvNull
                }
        ) + queryOverrides
) {

    companion object {

        var updateTxStatus: Boolean = true
    }

    private val specialTxExtensions = listOf(TransactionSubmitterSpecialTxExtension(), EvmAnchoringSpecialTxExtension())

    override fun initializeDB(ctx: EContext) {

        val transactionSubmitterDatabaseOperations = TransactionSubmitterDatabaseOperationsImpl()
        transactionSubmitterDatabaseOperations.initialize(ctx)
    }

    override fun getSpecialTxExtensions(): List<GTXSpecialTxExtension> {
        return specialTxExtensions
    }

    /** Adds a transaction to be mocked by rell query operations. The status is tracked and updated once updated by a node. */
    fun addTransaction(tx: EvmSubmitTxRellRequest) {
        if (conf.transactions.any { it.rowId == tx.rowId }) {
            conf.transactions.replaceAll {
                if (it.rowId == tx.rowId) {
                    tx
                } else {
                    it
                }
            }
        } else {
            conf.transactions.add(tx)
        }
    }

    // Adds a transaction to be mocked by rell query operations, but also makes it available for the query operation to select transactions available to be taken
    fun addTransactionsAvailableToTake(tx: EvmSubmitTxRellRequest) {
        conf.transactionsAvailableToTake.add(tx)
        addTransaction(tx)
    }
}

class ModifyTxStatusOperation(
        private val conf: TransactionSubmitterTestContext,
        private val extOpData: ExtOpData,
        private val updateTxStatus: Boolean,
) : GTXOperation(extOpData) {
    override fun checkCorrectness() {}
    override fun apply(ctx: TxEContext): Boolean {
        val rowId = extOpData.args[0].asInteger()
        val status = RellTransactionStatus.entries[extOpData.args[1].asInteger().toInt()]
        val signer = extOpData.args[3].asByteArray()

        if (updateTxStatus) {
            conf.transactions.replaceAll {

                if (it.rowId == rowId) {
                    EvmSubmitTxRellRequest(
                            it.rowId,
                            it.contractAddress,
                            it.functionName,
                            it.parameterTypes,
                            it.parameterValues,
                            it.networkId,
                            BigInteger.ONE,
                            BigInteger.valueOf(4000000000),
                            it.sender,
                            it.created,
                            it.txHash,
                            status,
                            if (status == RellTransactionStatus.TAKEN) signer else it.processed_by,
                    )
                } else {
                    it
                }
            }
        }

        when (status) {
            RellTransactionStatus.TAKEN -> {
                conf.taken.add(rowId)
                conf.transactionsAvailableToTake.removeIf { it.rowId == rowId }
            }

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
