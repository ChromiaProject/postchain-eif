package net.postchain.eif.transaction

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import net.postchain.common.BlockchainRid
import net.postchain.common.toHex
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.UPDATE_EVM_TRANSACTION_RECEIPT
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.UPDATE_EVM_TRANSACTION_STATUS
import net.postchain.gtv.GtvFactory
import net.postchain.gtx.data.ExtOpData
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

    assertThat(txSubmitterTestModule.conf.transactions.filter { it.status == RellTransactionStatus.QUEUED }
        .count()).isEqualTo(0)
}

fun assertTransactionsByStatus(txSubmitterTestModule: TransactionSubmitterTestGTXModule, status: RellTransactionStatus, count: Int) {

    assertThat(txSubmitterTestModule.conf.transactions.filter { it.status == status }
        .count()).isEqualTo(count)
}

// Evaluate sent transaction status
fun assertStatusOperation(
    txSubmitterTestModule: TransactionSubmitterTestGTXModule,
    rowId: Long,
    expectedStatus: RellTransactionStatus
): String? {
    return withTxOperations(
        txSubmitterTestModule,
        UPDATE_EVM_TRANSACTION_STATUS
    ) { operations ->
        val requestOps = operations
            .filter { it.args[0].asInteger() == rowId }
        val statusOperations = requestOps
            .map { RellTransactionStatus.values()[it.args[1].asInteger().toInt()] }

        assertThat(statusOperations).contains(expectedStatus)

        var txHash: String? = null
        if (expectedStatus == RellTransactionStatus.PENDING) {
            for (requestOp in requestOps) {
                if (RellTransactionStatus.values()[requestOp.args[1].asInteger().toInt()] == RellTransactionStatus.PENDING) {
                    txHash = requestOp.args[2].asString()
                }
            }
        }
        txHash
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