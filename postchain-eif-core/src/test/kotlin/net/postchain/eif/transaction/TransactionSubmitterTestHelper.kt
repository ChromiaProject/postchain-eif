package net.postchain.eif.transaction

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import net.postchain.base.data.DatabaseAccess
import net.postchain.base.withReadConnection
import net.postchain.common.BlockchainRid
import net.postchain.common.toHex
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

fun assertNoQueuedTxs(txSubmitterTestModule: TransactionSubmitterTestGTXModule) {

    assertThat(txSubmitterTestModule.conf.transactions.
    filter { it.status == RellTransactionStatus.QUEUED }
        .count()).isEqualTo(0)
}

fun assertTransactionsByStatus(txSubmitterTestModule: TransactionSubmitterTestGTXModule, status: RellTransactionStatus, count: Int) {

    assertThat(txSubmitterTestModule.conf.transactions.
    filter { it.status == status }
        .count()).isEqualTo(count)
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

fun mkEvmSubmitTxRellRequest(
    rowId: Long = 0,
    contractAddress: String,
    status: RellTransactionStatus = RellTransactionStatus.QUEUED,
    created: Long = System.currentTimeMillis(),
    txHash: String? = null,
    functionName: String = "updateValidators",
) = EvmSubmitTxRellRequest(
        rowId,
        contractAddress,
        functionName,
        listOf("address[]"),
        listOf(GtvFactory.gtv(listOf(GtvFactory.gtv(ByteArray(20) { 1 })))),
        1337,
        BigInteger.ONE,
        BigInteger.valueOf(4000000000),
        BlockchainRid.ZERO_RID.data,
        created,
        txHash,
        status
    )

fun mkEvmSubmitTxRequest(maxFeePerGas: Long = 4000000000) = EvmSubmitTxRequest(
    EvmSubmitTxRequest(
        EvmSubmitTxRellRequest(
            0L,
            "",
            "function_name",
            listOf(),
            listOf(),
            0L,
            BigInteger.ONE,
            BigInteger.valueOf(maxFeePerGas),
            "".toByteArray(),
            System.currentTimeMillis(),
            null,
            null,
        )
    )
)

fun mkEvmPendingDbTx(blockNumber: Long? = null) = EvmPendingTx(
    0,
    1337,
    "contractAddress",
    "functionName",
    listOf("address[]"),
    listOf(GtvFactory.gtv(listOf(GtvFactory.gtv(ByteArray(20) { 1 })))),
    "0x" + ByteArray(32){123}.toHex(),
    System.currentTimeMillis(),
    blockNumber = blockNumber?.let { BigInteger.valueOf(blockNumber) })

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