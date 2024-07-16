package net.postchain.eif.transaction.gas

import assertk.assertThat
import assertk.assertions.isEqualTo
import mockWeb3jRequestHandler
import org.junit.jupiter.api.Test

class EIP1559LastBlockFeeEstimatorTest {

    @Test
    fun `test fee margins`() {

        val web3jRequestHandler = mockWeb3jRequestHandler(
                walletBalance = 0,
                gasUsed = 100,
                maxPriorityFeePerGasValue = 10.toBigInteger(),
                baseFeePerGas = 100.toBigInteger()
        )

        val estimator = EIP1559LastBlockFeeEstimator(
                web3jRequestHandler,
                1000.toBigInteger(),
                1000000.toBigInteger(),
                gasLimitMargin = 0.2.toBigDecimal(),
                baseFeePerGasMargin = 5.toBigDecimal(),
                priorityFeePerGasMargin = 0.1.toBigDecimal(),
                "", "", "", 0
        )

        assertThat(estimator.estimatedGasLimit.longValueExact()).isEqualTo(120)
        assertThat(estimator.baseFeePerGas.longValueExact()).isEqualTo(600)
        assertThat(estimator.maxPriorityFeePerGas.longValueExact()).isEqualTo(11)
        assertThat(estimator.maxFeePerGas.longValueExact()).isEqualTo(611) // base fee + margin + priority fee + margin
        assertThat(estimator.estimatedGasUsage.longValueExact()).isEqualTo(100)
        assertThat(estimator.estimatedGasLimit.longValueExact()).isEqualTo(120) // estimated gas + margin
    }

    @Test
    fun `test no fee margins`() {

        val web3jRequestHandler = mockWeb3jRequestHandler(
                walletBalance = 0,
                gasUsed = 100,
                maxPriorityFeePerGasValue = 10.toBigInteger(),
                baseFeePerGas = 100.toBigInteger()
        )

        val estimator = EIP1559LastBlockFeeEstimator(
                web3jRequestHandler,
                1000.toBigInteger(),
                1000000.toBigInteger(),
                gasLimitMargin = 0.0.toBigDecimal(),
                baseFeePerGasMargin = 0.toBigDecimal(),
                priorityFeePerGasMargin = 0.0.toBigDecimal(),
                "", "", "", 0
        )

        assertThat(estimator.estimatedGasLimit.longValueExact()).isEqualTo(100)
        assertThat(estimator.baseFeePerGas.longValueExact()).isEqualTo(100)
        assertThat(estimator.maxPriorityFeePerGas.longValueExact()).isEqualTo(10)
        assertThat(estimator.maxFeePerGas.longValueExact()).isEqualTo(110)
        assertThat(estimator.estimatedGasUsage.longValueExact()).isEqualTo(100)
        assertThat(estimator.estimatedGasLimit.longValueExact()).isEqualTo(100)
    }
}
