package net.postchain.eif.transaction

import net.postchain.core.EContext
import java.math.BigInteger

interface TransactionSubmitterDatabaseOperations {

    fun initialize(ctx: EContext)

    fun queueTransaction(ctx: EContext, transactionRequest: EvmSubmitTxRequest, networkId: Long)

    fun recordTransactionHash(ctx: EContext, requestId: Long, txHash: String)

    fun recordTransactionGas(
        ctx: EContext,
        requestId: Long,
        gasPrice: BigInteger,
        gasLimit: BigInteger,
    )

    fun setSubmitTxBCPersisted(ctx: EContext, requestId: Long)

    fun recordTransactionError(
        ctx: EContext,
        requestId: Long,
        rpcUrl: String?,
        message: String,
        stackTrace: String? = null
    )

    fun getQueuedTransactions(ctx: EContext, networkId: Long): List<EvmSubmitTxRequest>

    fun cleanupDb(ctx: EContext, networkId: Long, dbRetentionTime: Long)
}