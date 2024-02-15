package net.postchain.eif.transaction

import net.postchain.core.EContext
import net.postchain.gtv.Gtv
import java.math.BigInteger

interface TransactionSubmitterDatabaseOperations {

    fun initialize(ctx: EContext)

    fun recordTransaction(ctx: EContext, contractAddress: String, functionName: String, parameterTypes: List<String>, parameterValues: List<Gtv>, gasPrice: BigInteger, gasLimit: BigInteger, txHash: String, networkId: Long)

    fun recordFailedTransaction(ctx: EContext, contractAddress: String, functionName: String, parameterTypes: List<String>, parameterValues: List<Gtv>, gasPrice: BigInteger, gasLimit: BigInteger, errorMessage: String, networkId: Long)
}