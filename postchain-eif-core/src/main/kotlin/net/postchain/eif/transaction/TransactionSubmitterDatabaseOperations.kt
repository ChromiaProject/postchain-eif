package net.postchain.eif.transaction

import net.postchain.core.EContext
import java.math.BigInteger

interface TransactionSubmitterDatabaseOperations {

    fun initialize(ctx: EContext)

    fun queueTransaction(ctx: EContext, transactionRequest: EvmSubmitTransactionRequest, networkId: Long)

    fun pendTransaction(ctx: EContext, requestId: Long, transactionHash: String)

    fun failTransaction(ctx: EContext, requestId: Long)

    fun recordTransactionGas(
        ctx: EContext,
        requestId: Long,
        gasPrice: BigInteger,
        gasLimit: BigInteger,
    )

    fun recordTransactionFailure(
        ctx: EContext,
        requestId: Long,
        rpcUrl: String?,
        message: String,
        stackTrace: String?
    )

    fun succeedTransaction(ctx: EContext, requestId: Long, effectiveGasPrice: BigInteger, gasUsed: BigInteger, blockHash: String)

    fun deactivateTransaction(ctx: EContext, requestId: Long)

    fun getQueuedTransactions(ctx: EContext, networkId: Long): List<EvmSubmitTransactionRequest>

    fun getPendingTransactions(ctx: EContext, networkId: Long): MutableMap<String, EvmSubmitTransactionRequest>

    fun getCompletedTransactions(ctx: EContext, networkId: Long): MutableMap<Long, EvmSubmitTransactionResult>

    fun getTransactionErrors(ctx: EContext, requestId: Long): List<EvmSubmitTransactionError>
}