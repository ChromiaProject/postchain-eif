package net.postchain.eif.transaction

import assertk.assertThat
import assertk.assertions.isEqualTo
import mockWeb3jRequestHandler
import net.postchain.eif.Web3jRequestHandler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.methods.response.EthGetBalance
import java.math.BigInteger

class TransactionSubmitterSubmitTest : MockedTestBaseTransactionSubmitter() {

    @Test
    fun `fail getting balance`() {

        val web3jRequestHandler = mock<Web3jRequestHandler> {
            on { sendWeb3jRequest(requestFactory = any<(Web3j) -> Request<*, EthGetBalance>>()) } doThrow RuntimeException(
                    "Oh dear"
            )
        }

        val ts = createTransactionSubmitter(
                web3jRequestHandler,
                mapOf(createTransactionManager("http://127.0.0.1:9999", "0xfrom", exception = "Oh dear")),
                mockFeeEstimatorFactory(10, 5, gasUsed = 1, walletBalance = null)
        )

        mockTakenBy()

        val exception = assertThrows<RuntimeException>("Expected thrown exception") {
            ts.submitTransaction(
                    mkEvmSubmitTxRequest()
            )
        }
        assertThat(exception.message).isEqualTo("Failed to get balance for request: Failed to get wallet balance")
        testLogAppender.assertError("Failed to get wallet balance")
    }

    @Test
    fun `fail getting estimated gas`() {

        val web3jRequestHandler = mockWeb3jRequestHandler(200, null)
        val transactionManagers =
                mapOf(createTransactionManager("http://127.0.0.1:9999", "0xfrom", exception = "Oh dear"))

        val ts = createTransactionSubmitter(
                web3jRequestHandler,
                transactionManagers,
                mockFeeEstimatorFactory(10, 5, gasUsed = null)
        )

        mockTakenBy()

        val exception = assertThrows<RuntimeException>("Expected thrown exception") {
            ts.submitTransaction(
                    mkEvmSubmitTxRequest()
            )
        }
        assertThat(exception.message).isEqualTo("Failed to estimate gas usage: Failed to get gas estimate")

        verify(databaseOperations).recordTransactionGas(
                any(),
                eq(0L),
                eq(BigInteger.valueOf(5)),
                eq(BigInteger.valueOf(10))
        )
        testLogAppender.assertError("Failed to estimate gas usage: Failed to get gas estimate")
    }

    @Test
    fun `fail send transaction max fee per gas exceeds limit`() {

        val web3jRequestHandler = mockWeb3jRequestHandler(200, 10)
        val transactionManagers =
                mapOf(createTransactionManager("http://127.0.0.1:9999", "0xfrom", exception = "Oh dear"))

        val ts = createTransactionSubmitter(
                web3jRequestHandler,
                transactionManagers,
                mockFeeEstimatorFactory(10, 3, gasUsed = 1)
        )

        mockTakenBy()

        val exception = assertThrows<RuntimeException>("Expected thrown exception") {
            ts.submitTransaction(
                    mkEvmSubmitTxRequest(4)
            )
        }
        assertThat(exception.message).isEqualTo("Estimated total gas fee 4 for tx exceeds configured limit of 3")

        verify(databaseOperations).recordTransactionGas(
                any(),
                eq(0L),
                eq(BigInteger.valueOf(3)),
                eq(BigInteger.valueOf(10))
        )
        testLogAppender.assertError("Estimated total gas fee 4 for tx exceeds configured limit of 3")
    }

    @Test
    fun `fail send transaction for all 1 nodes`() {

        val web3jRequestHandler = mockWeb3jRequestHandler(200, 10)
        val transactionManagers =
                mapOf(createTransactionManager("http://127.0.0.1:9999", "0xfrom", exception = "Oh dear"))

        val ts = createTransactionSubmitter(
                web3jRequestHandler,
                transactionManagers,
                mockFeeEstimatorFactory(10, 5)
        )

        mockTakenBy()

        val exception = assertThrows<RuntimeException>("Expected thrown exception") {
            ts.submitTransaction(
                    mkEvmSubmitTxRequest(4)
            )
        }
        assertThat(exception.message).isEqualTo("Failed to send transaction to all 1 nodes")

        verify(databaseOperations).recordTransactionGas(
                any(),
                eq(0L),
                eq(BigInteger.valueOf(5)),
                eq(BigInteger.valueOf(10))
        )

        testLogAppender.assertError("Failed to send transaction 0 to http://127.0.0.1:9999: Oh dear")
        testLogAppender.assertError("Failed to send transaction to all 1 nodes")
    }

    @Test
    fun `fail send transaction for all 2 nodes`() {

        val web3jRequestHandler = mockWeb3jRequestHandler(200, 10)
        val transactionManagers = mapOf(
                createTransactionManager("http://evm-node-1:9999", "0xfrom", exception = "Oh dear"),
                createTransactionManager("http://evm-node-2:9999", "0xfrom", hasErrorMsg = "Not found"),
        )

        val ts = createTransactionSubmitter(
                web3jRequestHandler,
                transactionManagers,
                mockFeeEstimatorFactory(10, 5)
        )

        mockTakenBy()

        val exception = assertThrows<RuntimeException>("Expected thrown exception") {
            ts.submitTransaction(
                    mkEvmSubmitTxRequest(4)
            )
        }
        assertThat(exception.message).isEqualTo("Failed to send transaction to all 2 nodes")

        verify(databaseOperations).recordTransactionGas(
                any(),
                eq(0L),
                eq(BigInteger.valueOf(5)),
                eq(BigInteger.valueOf(10))
        )

        testLogAppender.assertError("Failed to send transaction 0 to http://evm-node-1:9999: Oh dear")
        testLogAppender.assertError("Failed to send transaction 0 to http://evm-node-2:9999: Web3j request failed with error code: 404 and message: Not found")
        testLogAppender.assertError("Failed to send transaction to all 2 nodes")
    }
}
