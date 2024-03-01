package net.postchain.eif.transaction

import assertk.assertThat
import assertk.assertions.contains
import net.postchain.base.data.DatabaseAccess
import net.postchain.base.withReadConnection
import net.postchain.devtools.PostchainTestNode
import net.postchain.devtools.PostchainTestNode.Companion.DEFAULT_CHAIN_IID
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_ERRORS_COLUMN_REQUEST_ID
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_PENDING_COLUMN_REQUEST_ID
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.ADD_EVM_TRANSACTION_ERRORS
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.UPDATE_EVM_TRANSACTION_RECEIPT
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.UPDATE_EVM_TRANSACTION_STATUS
import net.postchain.gtx.data.ExtOpData
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.Assert.fail
import java.math.BigInteger
import java.sql.Timestamp

data class TxReceiptUpdateOpArg(val blockHash: String, val effectiveGasPrice: Long, val gasUsage: Long)
data class TxErrorOpArg(val rowId: Long, val rpcUrl: String?, val message: String, val stackTrace: String?)

// Evaluate sent receipt operations
fun withUpdateEvmTransactionReceipt(txSubmitterTestModule: TransactionSubmitterTestGTXModule, rowId: Long, op: (List<TxReceiptUpdateOpArg>) -> Unit) {
    withTxOperations(txSubmitterTestModule, UPDATE_EVM_TRANSACTION_RECEIPT) { operations ->
        val receiptOperations = operations
            .filter { it.args[0].asInteger() == rowId }
            .map {
                val blockHash = it.args[1].asString()
                val effectiveGasPrice = it.args[2].asBigInteger().toLong()
                val gasUsage = it.args[3].asBigInteger().toLong()
                TxReceiptUpdateOpArg(blockHash, effectiveGasPrice, gasUsage)
            }

        op(receiptOperations)
    }
}

// Evaluate sent error operations
fun withAddEvmTransactionError(txSubmitterTestModule: TransactionSubmitterTestGTXModule, rowId: Long, op: (List<List<EvmSubmitTransactionError>>) -> Unit) {
    withTxOperations(txSubmitterTestModule, ADD_EVM_TRANSACTION_ERRORS) { operations ->
        val addErrorOperations = operations
            .filter { it.args[0].asInteger() == rowId }
            .map {op ->
                op.args[1].asArray()
                    .map {
                        val timestamp = it[0].asInteger()
                        val serviceUrl = it[2].asString()
                        val message = it[3].asString()

                        EvmSubmitTransactionError(rowId, Timestamp(timestamp), serviceUrl, message)
                    }
            }

        op(addErrorOperations)
    }
}

// Evaluate sent transaction status
fun assertStatusOperation(txSubmitterTestModule: TransactionSubmitterTestGTXModule, rowId: Long, expectedStatus: RellTransactionStatus) {
    withTxOperations(txSubmitterTestModule,
        UPDATE_EVM_TRANSACTION_STATUS
    ) { operations ->
        val statusOperations = operations
            .filter { it.args[0].asInteger() == rowId }
            .map { RellTransactionStatus.values()[it.args[1].asInteger().toInt()] }

        assertThat(statusOperations).contains(expectedStatus)
    }
}

// Evaluate sent error operations
fun assertErrorOperation(txSubmitterTestModule: TransactionSubmitterTestGTXModule, rowId: Long, op: (List<List<TxErrorOpArg>>) -> Unit) {
    withTxOperations(txSubmitterTestModule,
        ADD_EVM_TRANSACTION_ERRORS
    ) { operations ->
        op(operations
            .filter { it.args[0].asInteger() == rowId }
            .map {opData ->
                opData.args[1].asArray()
                    .map {
                        TxErrorOpArg(rowId, it[2].asString(), it[3].asString(), null)
                    }
            })
    }
}

// Evaluate sent operations
fun <RT> withTxOperations(txSubmitterTestModule: TransactionSubmitterTestGTXModule, operationName: String, op: (List<ExtOpData>) -> RT?): RT? {

    val operations = txSubmitterTestModule.conf.operations
        .filter { it.opName == operationName }

    return op(operations)
}

fun countDbSubmit(node: PostchainTestNode): Int {
    return withReadConnection(node.getBlockchainInstance().blockchainEngine.sharedStorage, DEFAULT_CHAIN_IID) {
        val jooq = DSL.using(it.conn, SQLDialect.POSTGRES)

        val tableName = DatabaseAccess.of(it).tableEvmTxSubmit(it)

        jooq
            .select()
            .from(tableName)
            .count()

    }
}

fun countDPending(node: PostchainTestNode): Int {
    return withReadConnection(node.getBlockchainInstance().blockchainEngine.sharedStorage, DEFAULT_CHAIN_IID) {
        val jooq = DSL.using(it.conn, SQLDialect.POSTGRES)

        val tableName = DatabaseAccess.of(it).tableEvmTxPending(it)

        jooq
            .select()
            .from(tableName)
            .count()

    }
}

fun countDErrors(node: PostchainTestNode): Int {
    return withReadConnection(node.getBlockchainInstance().blockchainEngine.sharedStorage, DEFAULT_CHAIN_IID) {
        val jooq = DSL.using(it.conn, SQLDialect.POSTGRES)

        val tableName = DatabaseAccess.of(it).tableEvmTxErrors(it)

        jooq
            .select()
            .from(tableName)
            .count()

    }
}

// Evaluate pending transactions in DB
fun <RT> withDbPending(node: PostchainTestNode, rowId: Long, op: (org.jooq.Record) -> RT?): RT? {

    return withReadConnection(node.getBlockchainInstance().blockchainEngine.sharedStorage, DEFAULT_CHAIN_IID) {
        val jooq = DSL.using(it.conn, SQLDialect.POSTGRES)

        val tableName = DatabaseAccess.of(it).tableEvmTxPending(it)

        val fetch = jooq
                .select()
                .from(tableName)
                .where(EVM_TX_PENDING_COLUMN_REQUEST_ID.eq(rowId))
                .fetchOne()

        try {
            op(fetch)
        } catch (e: Exception) {
            fail("Failed to get pending rows")
        }
        null
    }
}

// Evaluate errors in DB
fun withDbErrors(node: PostchainTestNode, rowId: Long, op: (List<org.jooq.Record>) -> Unit) {

    withReadConnection(node.getBlockchainInstance().blockchainEngine.sharedStorage, DEFAULT_CHAIN_IID) {
        val jooq = DSL.using(it.conn, SQLDialect.POSTGRES)

        val tableName = DatabaseAccess.of(it).tableEvmTxErrors(it)

        val fetch = jooq
            .select()
            .from(tableName)
            .where(EVM_TX_ERRORS_COLUMN_REQUEST_ID.eq(rowId))
            .fetch()

        try {
            op(fetch)
        } catch (e: Exception) {
            fail("Failed to get pending rows")
        }
    }
}

fun mkEvmPendingDbTx(blockNumber: Long? = null) = EvmPendingDbTx(
    EvmPendingRellTx(
        0,
        1337,
        "contract-address",
        "function_name",
        listOf(),
        listOf(),
        "tx-hash"
    ),
    System.currentTimeMillis(),

    blockNumber?.let { BigInteger.valueOf(blockNumber) }
)
