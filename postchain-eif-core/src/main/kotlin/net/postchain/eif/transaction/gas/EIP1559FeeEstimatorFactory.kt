package net.postchain.eif.transaction.gas

import net.postchain.eif.web3j.Web3jRequestHandler
import net.postchain.eif.transaction.EvmSubmitTxRequest
import java.math.BigDecimal
import java.math.BigInteger

interface EIP1559FeeEstimator {

    val blockNumber: BigInteger             // EVM block number fees are based on
    val baseFeePerGas: BigInteger           // Latest block base fee + margin
    val maxPriorityFeePerGas: BigInteger    // Estimated max priority fee + margin
    val maxFeePerGas: BigInteger            // Max total fee per gas to spend on this transaction (including base fee + priority fee)
    val estimatedGasUsage: BigInteger       // Estimated gas usage for this transaction
    val estimatedTotalGasFee: BigInteger    // Estimated total gas cost (estimatedGasUsage * maxFeePerGas)
    val estimatedGasLimit: BigInteger       // Estimated gas limit (estimatedTotalGasFee + margin).
                                            // Wallet needs funds to cover for this * maxFeePerGas

    fun validateRequestFees(txRequest: EvmSubmitTxRequest)
}

open class EIP1559FeeEstimatorFactory(
        private val web3jRequestHandler: Web3jRequestHandler,
        open val gasLimit: BigInteger,           // TX submitter hard max gas limit
        open val maxGasPrice: BigInteger,        // TX submitter hard max gas price (base + priority)
        open val gasLimitMargin: BigDecimal,     // TX submitter gas limit margin to add to estimated gas limit for a transaction
        open val baseFeePerGasMargin: BigDecimal,         // TX submitter base fee per gas margin: last_block_price + last_block_price * margin
        open val priorityFeePerGasMargin: BigDecimal,     // TX submitter priority fee per gas margin: estimated_priority_fee + estimated_priority_fee * margin
) {
    // Creates instance based on latest evm block fees
    open fun createEstimate(
            contractAddress: String,
            functionData: String,
            fromAddress: String,
            chainId: Long,
            txMaxPriorityFeePerGas: BigInteger,
            txMaxFeePerGas: BigInteger
    ): EIP1559FeeEstimator {
        return EIP1559LastBlockFeeEstimator(web3jRequestHandler, gasLimit, maxGasPrice, gasLimitMargin,
                baseFeePerGasMargin, priorityFeePerGasMargin, contractAddress, functionData, fromAddress,
                chainId, txMaxPriorityFeePerGas, txMaxFeePerGas)
    }
}
