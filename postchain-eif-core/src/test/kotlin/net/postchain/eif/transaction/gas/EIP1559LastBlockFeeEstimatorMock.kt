package net.postchain.eif.transaction.gas

import mockWeb3jRequestHandler
import net.postchain.eif.transaction.EvmSubmitTxRequest
import java.math.BigInteger

class EIP1559LastBlockFeeEstimatorMock(
        override val blockNumber: BigInteger = 1.toBigInteger(),
        override val baseFeePerGas: BigInteger,
        override val maxPriorityFeePerGas: BigInteger,
        override val maxFeePerGas: BigInteger,
        val gasUsed: Long? = 1L,
        val gasLimit: Long,
        val maxGasPrice: Long,
        val walletBalance: Long? = 10000000L
) : EIP1559FeeEstimator {

    private val feeEstimator: EIP1559LastBlockFeeEstimator

    init {

        val web3jRequestHandler = mockWeb3jRequestHandler(walletBalance, gasUsed, blockNumber, maxPriorityFeePerGas)

        feeEstimator = EIP1559LastBlockFeeEstimator(web3jRequestHandler, gasLimit.toBigInteger(), maxGasPrice.toBigInteger())
    }

    override fun validateRequestFees(request: EvmSubmitTxRequest) {
        feeEstimator.validateRequestFees(request)
    }

    override fun estimateAndValidateRequestGasAndBalance(
            txRequest: EvmSubmitTxRequest,
            functionData: String,
            fromAddress: String,
            chainId: Long
    ) {
        feeEstimator.estimateAndValidateRequestGasAndBalance(txRequest, functionData, fromAddress, chainId)
    }
}