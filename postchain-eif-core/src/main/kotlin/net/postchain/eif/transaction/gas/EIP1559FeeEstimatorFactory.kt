package net.postchain.eif.transaction.gas

import net.postchain.eif.Web3jRequestHandler
import net.postchain.eif.transaction.EvmSubmitTxRequest
import java.math.BigInteger

interface EIP1559FeeEstimator {

    val blockNumber: BigInteger             // EVM block number fees are based on
    val baseFeePerGas: BigInteger           // Latest block base fee
    val maxPriorityFeePerGas: BigInteger    // Estimated max priority fee to be included in next block
    val maxFeePerGas: BigInteger            // Max total fee per gas to spend on this transaction

    fun validateRequestFees(request: EvmSubmitTxRequest)

    fun estimateAndValidateRequestGasAndBalance(
            txRequest: EvmSubmitTxRequest,
            functionData: String,
            fromAddress: String,
            chainId: Long
    )
}

open class EIP1559FeeEstimatorFactory(
        private val web3jRequestHandler: Web3jRequestHandler,
        open val gasLimit: BigInteger,           // TX submitter hard max gas limit
        open val maxGasPrice: BigInteger,        // TX submitter hard max gas price (base + priority)
) {
    // Creates instance based on latest evm block fees
    open fun createEstimate(): EIP1559FeeEstimator {
        return EIP1559LastBlockFeeEstimator(web3jRequestHandler, gasLimit, maxGasPrice)
    }
}
