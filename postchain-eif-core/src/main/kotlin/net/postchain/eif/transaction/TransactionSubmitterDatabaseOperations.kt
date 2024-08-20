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

    fun getQueuedTransactions(ctx: EContext, networkId: Long): List<EvmSubmitTxRequest>

    fun removeTransaction(bctx: EContext, requestId: Long): Boolean
}