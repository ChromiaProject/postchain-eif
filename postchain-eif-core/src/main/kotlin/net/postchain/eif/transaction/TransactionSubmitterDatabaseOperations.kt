package net.postchain.eif.transaction

import net.postchain.core.EContext
import java.math.BigInteger

interface TransactionSubmitterDatabaseOperations {

    fun initialize(ctx: EContext)

    fun recordTransaction(ctx: EContext, transactionRequest: EvmSubmitTransactionRequest, gasPrice: BigInteger, gasLimit: BigInteger, txHash: String, networkId: Long)

    fun recordFailedTransaction(ctx: EContext, transactionRequest: EvmSubmitTransactionRequest, gasPrice: BigInteger, gasLimit: BigInteger, errorMessage: String, networkId: Long)

    fun updateTransactionStatus(ctx: EContext, requestId: Long, status: TransactionStatus)
}