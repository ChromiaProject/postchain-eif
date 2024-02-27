package net.postchain.eif.transaction

import assertk.assertThat
import assertk.assertions.isEqualTo
import net.postchain.eif.Web3jRequestHandler
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.mockingDetails
import org.mockito.kotlin.verify
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.methods.response.EthGetBalance
import org.web3j.tx.TransactionManager
import org.web3j.tx.gas.ContractGasProvider
import java.math.BigInteger
import java.util.concurrent.LinkedBlockingQueue

class TransactionSubmitterFailuresTest : MockedBaseTransactionSubmitterTest() {

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
            mock<ContractGasProvider>()
        )

        val exception = assertThrows<RuntimeException>("Expected thrown exception") {
            ts.submitTransaction(
                EvmSubmitTransactionRequest(
                    0L,
                    "",
                    "function_name",
                    listOf(),
                    listOf(),
                    0L,
                    "".toByteArray(),
                    RellTransactionStatus.TAKEN
                )
            )
        }
        assertThat(exception.message).isEqualTo("Failed to get balance for request id 0: Oh dear")

        assertThat(mockingDetails(databaseOperations).invocations.size).isEqualTo(2)
        verify(databaseOperations).failTransaction(any(), eq(0L))
        verify(databaseOperations).recordTransactionFailure(
            any(),
            eq(0L),
            eq(null),
            eq("Failed to get balance for request id 0: Oh dear"),
            anyString()
        )
    }

    @Test
    fun `fail getting estimated gas`() {

        val web3jRequestHandler = mockBalanceAndEstimateGas(200, null)
        val gasProvider = mockGasProvider(5, 10)
        val transactionManagers =
            mapOf(createTransactionManager("http://127.0.0.1:9999", "0xfrom", exception = "Oh dear"))

        val ts = createTransactionSubmitter(
            web3jRequestHandler,
            transactionManagers,
            gasProvider
        )

        val exception = assertThrows<RuntimeException>("Expected thrown exception") {
            ts.submitTransaction(
                EvmSubmitTransactionRequest(
                    0L,
                    "",
                    "function_name",
                    listOf(),
                    listOf(),
                    0L,
                    "".toByteArray(),
                    RellTransactionStatus.TAKEN
                )
            )
        }
        assertThat(exception.message).isEqualTo("Failed to get estimated gas usage for request id 0: Failed to get gas estimate")

        assertThat(mockingDetails(databaseOperations).invocations.size).isEqualTo(3)
        verify(databaseOperations).failTransaction(any(), eq(0L))
        verify(databaseOperations).recordTransactionGas(any(), eq(0L), eq(BigInteger.valueOf(5)), eq(BigInteger.valueOf(10)))
        verify(databaseOperations).recordTransactionFailure(
            any(),
            eq(0L),
            eq(null),
            eq("Failed to get estimated gas usage for request id 0: Failed to get gas estimate"),
            anyString()
        )
    }

    @Test
    fun `fail send transaction for all 1 nodes`() {

        val web3jRequestHandler = mockBalanceAndEstimateGas(200, 10)
        val gasProvider = mockGasProvider(5, 10)
        val transactionManagers =
            mapOf(createTransactionManager("http://127.0.0.1:9999", "0xfrom", exception = "Oh dear"))

        val ts = createTransactionSubmitter(
            web3jRequestHandler,
            transactionManagers,
            gasProvider
        )

        val exception = assertThrows<RuntimeException>("Expected thrown exception") {
            ts.submitTransaction(
                EvmSubmitTransactionRequest(
                    0L,
                    "",
                    "function_name",
                    listOf(),
                    listOf(),
                    0L,
                    "".toByteArray(),
                    RellTransactionStatus.TAKEN
                )
            )
        }
        assertThat(exception.message).isEqualTo("Failed to send transaction to all 1 nodes")

        assertThat(mockingDetails(databaseOperations).invocations.size).isEqualTo(4)
        verify(databaseOperations).failTransaction(any(), eq(0L))
        verify(databaseOperations).recordTransactionGas(any(), eq(0L), eq(BigInteger.valueOf(5)), eq(BigInteger.valueOf(10)))
        verify(databaseOperations).recordTransactionFailure(
            any(),
            eq(0L),
            eq("http://127.0.0.1:9999"),
            eq("Failed to send transaction 0: Oh dear"),
            anyString()
        )
        verify(databaseOperations).recordTransactionFailure(
            any(),
            eq(0L),
            eq(null),
            eq("Failed to send transaction to all 1 nodes"),
            anyString()
        )
    }

    @Test
    fun `fail send transaction for all 2 nodes`() {

        val web3jRequestHandler = mockBalanceAndEstimateGas(200, 10)
        val gasProvider = mockGasProvider(5, 10)
        val transactionManagers = mapOf(
            createTransactionManager("http://evm-node-1:9999", "0xfrom", exception = "Oh dear"),
            createTransactionManager("http://evm-node-2:9999", "0xfrom", hasErrorMsg = "Not found"),
        )

        val ts = createTransactionSubmitter(
            web3jRequestHandler,
            transactionManagers,
            gasProvider
        )

        val exception = assertThrows<RuntimeException>("Expected thrown exception") {
            ts.submitTransaction(
                EvmSubmitTransactionRequest(
                    0L,
                    "",
                    "function_name",
                    listOf(),
                    listOf(),
                    0L,
                    "".toByteArray(),
                    RellTransactionStatus.TAKEN
                )
            )
        }
        assertThat(exception.message).isEqualTo("Failed to send transaction to all 2 nodes")

        assertThat(mockingDetails(databaseOperations).invocations.size).isEqualTo(5)
        verify(databaseOperations).failTransaction(any(), eq(0L))
        verify(databaseOperations).recordTransactionGas(any(), eq(0L), eq(BigInteger.valueOf(5)), eq(BigInteger.valueOf(10)))
        verify(databaseOperations).recordTransactionFailure(
            any(),
            eq(0L),
            eq("http://evm-node-1:9999"),
            eq("Failed to send transaction 0: Oh dear"),
            anyString()
        )
        verify(databaseOperations).recordTransactionFailure(
            any(),
            eq(0L),
            eq("http://evm-node-2:9999"),
            eq("Failed to send transaction 0: Web3j request failed with error code: 404 and message: Not found"),
            anyString()
        )
        verify(databaseOperations).recordTransactionFailure(
            any(),
            eq(0L),
            eq(null),
            eq("Failed to send transaction to all 2 nodes"),
            anyString()
        )
    }

    private fun createTransactionSubmitter(
        web3jRequestHandler: Web3jRequestHandler,
        transactionManagers: Map<String, TransactionManager>,
        gasProvider: ContractGasProvider
    ): TransactionSubmitter {
        return TransactionSubmitter(
            web3jRequestHandler,
            transactionManagers,
            gasProvider,
            databaseOperations,
            storage,
            0,
            0,
            Long.MAX_VALUE,
            LinkedBlockingQueue(),
            mutableMapOf(),
            mutableMapOf(),
            BigInteger.valueOf(10),
            Long.MAX_VALUE
        )
    }
}
