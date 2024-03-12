package net.postchain.eif.transaction

import assertk.assertThat
import assertk.assertions.contains
import net.postchain.base.data.DatabaseAccess
import net.postchain.base.withReadConnection
import net.postchain.devtools.PostchainTestNode
import net.postchain.devtools.PostchainTestNode.Companion.DEFAULT_CHAIN_IID
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_ERRORS_COLUMN_REQUEST_ID
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.UPDATE_EVM_TRANSACTION_RECEIPT
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.UPDATE_EVM_TRANSACTION_STATUS
import net.postchain.gtv.GtvFactory
import net.postchain.gtx.data.ExtOpData
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.Assert.fail
import java.math.BigInteger

data class TxReceiptUpdateOpArg(val blockHash: String, val effectiveGasPrice: Long, val gasUsage: Long)

// Evaluate sent receipt operations
fun withUpdateEvmTransactionReceipt(
    txSubmitterTestModule: TransactionSubmitterTestGTXModule,
    rowId: Long,
    op: (List<TxReceiptUpdateOpArg>) -> Unit
) {
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

// Evaluate sent transaction status
fun assertStatusOperation(
    txSubmitterTestModule: TransactionSubmitterTestGTXModule,
    rowId: Long,
    expectedStatus: RellTransactionStatus
) {
    withTxOperations(
        txSubmitterTestModule,
        UPDATE_EVM_TRANSACTION_STATUS
    ) { operations ->
        val statusOperations = operations
            .filter { it.args[0].asInteger() == rowId }
            .map { RellTransactionStatus.values()[it.args[1].asInteger().toInt()] }

        assertThat(statusOperations).contains(expectedStatus)
    }
}

// Evaluate sent operations
fun <RT> withTxOperations(
    txSubmitterTestModule: TransactionSubmitterTestGTXModule,
    operationName: String,
    op: (List<ExtOpData>) -> RT?
): RT? {

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

fun mkEvmPendingRellTx(
    txHash: String,
    contractAddress: String,
    functionName: String = "updateValidators",
) = EvmPendingRellTx(
    0,
    1337,
    contractAddress,
    functionName,
    listOf("address[]"),
    listOf(GtvFactory.gtv(listOf(GtvFactory.gtv(ByteArray(20) { 1 })))),
    txHash
)

fun mkEvmPendingDbTx(blockNumber: Long? = null) = EvmPendingTx(
    mkEvmPendingRellTx("tx-hash", "contractAddress"),
    System.currentTimeMillis(),

    blockNumber?.let { BigInteger.valueOf(blockNumber) }
)

fun <T> withTxSubmitter(
    txSubmitterTestModule: TransactionSubmitterTestGTXModule,
    requestId: Long,
    action: (TransactionSubmitter, EvmPendingTx) -> T
): T? = withTxSubmitter(listOf(txSubmitterTestModule), requestId, action)

fun <T> withTxSubmitter(
    txSubmitterTestModules: List<TransactionSubmitterTestGTXModule>,
    requestId: Long,
    action: (TransactionSubmitter, EvmPendingTx) -> T
): T? {

    txSubmitterTestModules.forEach { txSubmitterTestModule ->
        val txInfraExtension = txSubmitterTestModule.getSpecialTxExtensions().filterIsInstance<TransactionSubmitterSpecialTxExtension>().first()

        val result = txInfraExtension.withTxPending(requestId, action)
        if (result != null) {
            return result
        }
    }

    return null
}