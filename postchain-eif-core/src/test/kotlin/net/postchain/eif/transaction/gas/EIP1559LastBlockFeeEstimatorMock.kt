package net.postchain.eif.transaction.gas

import net.postchain.eif.transaction.mockWeb3jRequestHandler
import net.postchain.eif.transaction.EvmSubmitTxRequest
import java.math.BigDecimal
import java.math.BigInteger

class EIP1559LastBlockFeeEstimatorMock(
        override val blockNumber: BigInteger = 1.toBigInteger(),
        override val baseFeePerGas: BigInteger,
        override val maxPriorityFeePerGas: BigInteger,
        override val maxFeePerGas: BigInteger,
        val gasUsed: Long? = 1L,
        val gasLimit: Long,
        maxGasPrice: Long,
        val walletBalance: Long? = 10000000L,
        override val estimatedGasUsage: BigInteger = 1.toBigInteger(),
        override val estimatedTotalGasFee: BigInteger = 1.toBigInteger(),
        override val estimatedGasLimit: BigInteger = 1.toBigInteger(),
        gasLimitMargin: BigDecimal = 0.1.toBigDecimal(),
        baseFeePerGasMargin: BigDecimal = 5.toBigDecimal(),
        priorityFeePerGasMargin: BigDecimal = 0.001.toBigDecimal(),
) : EIP1559FeeEstimator {

    private val feeEstimator: EIP1559LastBlockFeeEstimator

    init {

        val web3jRequestHandler = mockWeb3jRequestHandler(walletBalance, gasUsed, blockNumber, maxPriorityFeePerGas)

        feeEstimator = EIP1559LastBlockFeeEstimator(
                web3jRequestHandler,
                gasLimit.toBigInteger(),
                maxGasPrice.toBigInteger(),
                gasLimitMargin,
                baseFeePerGasMargin,
                priorityFeePerGasMargin,
                "contractAddress",
                "contractData",
                "fromAddress",
                0L,
                BigInteger.valueOf(Long.MAX_VALUE),
                BigInteger.valueOf(Long.MAX_VALUE),
        )
    }

    override fun validateRequestFees(txRequest: EvmSubmitTxRequest) {
        feeEstimator.validateRequestFees(txRequest)
    }
}