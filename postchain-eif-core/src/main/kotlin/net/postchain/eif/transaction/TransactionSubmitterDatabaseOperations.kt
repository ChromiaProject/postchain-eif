package net.postchain.eif.transaction

import net.postchain.core.EContext
import java.math.BigInteger

interface TransactionSubmitterDatabaseOperations {

    fun initialize(ctx: EContext)

    fun queueTransaction(ctx: EContext, transactionRequest: EvmSubmitTransactionRequest, networkId: Long)

    fun recordTransactionGas(
        ctx: EContext,
        requestId: Long,
        gasPrice: BigInteger,
        gasLimit: BigInteger,
    )

    fun setSubmitTxBCPersisted(ctx: EContext, requestId: Long)

    fun isSubmitTxBCPersisted(ctx: EContext, requestId: Long): Boolean

    fun recordTransactionError(
        ctx: EContext,
        requestId: Long,
        rpcUrl: String?,
        message: String,
        stackTrace: String? = null
    )

    fun setPendingTransactionReceiptBlockNumber(
        ctx: EContext,
        requestId: Long,
        receiptHeight: BigInteger,
    )

    fun setPendingTransactionReceipt(
        ctx: EContext,
        requestId: Long,
        receiptHeight: BigInteger,
        statusOK: Boolean,
        effectiveGasPrice: BigInteger,
        gasUsed: BigInteger,
        blockHash: String
    )

    fun getQueuedTransactions(ctx: EContext, networkId: Long): List<EvmSubmitTransactionRequest>

    fun addPendingTransaction(ctx: EContext, networkId: Long, txPending: EvmPendingDbTx)

    fun getPendingTransactions(ctx: EContext, networkId: Long): Map<String, EvmPendingRellTx>

    fun setPendingTransactionSuccess(ctx: EContext, requestId: Long, status: PendingTxStatus)

    fun getVerifiedTransactions(ctx: EContext, networkId: Long, minMsSinceUpdate: Long): List<EvmPendingDbTx>

    fun setPendingTxBCPersisted(ctx: EContext, requestId: Long)

    fun getTransactionErrors(ctx: EContext, requestId: Long): List<EvmSubmitTransactionError>

    fun cleanupDb(ctx: EContext, networkId: Long, dbRetentionTime: Long)
}