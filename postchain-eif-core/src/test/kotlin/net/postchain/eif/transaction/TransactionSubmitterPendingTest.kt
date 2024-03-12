package net.postchain.eif.transaction

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import net.postchain.eif.Web3jRequestHandler
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.methods.response.EthGetBalance
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt
import org.web3j.protocol.core.methods.response.EthTransaction
import org.web3j.tx.gas.ContractGasProvider

class TransactionSubmitterPendingTest : MockedTestBaseTransactionSubmitter() {

    @Test
    fun `fail getting receipt`() {

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

        // Fails on getting receipt
        ts.pollPendingTransaction(
            "tx-hash",
            mkEvmPendingDbTx()
        )

        verify(databaseOperations).recordTransactionError(
            any(),
            eq(0L),
            eq(null),
            eq("Failed to poll for receipt for request id 0"),
            anyString()
        )
    }

    @Test
    fun `fail verifying transaction`() {

        val web3jRequestHandler = mock<Web3jRequestHandler>()

        val ts = createTransactionSubmitter(
            web3jRequestHandler,
            mapOf(createTransactionManager("http://127.0.0.1:9999", "0xfrom", exception = "Oh dear")),
            mock<ContractGasProvider>()
        )

        val txPending = mkEvmPendingDbTx(5)

        mockWeb3jRequest(web3jRequestHandler, EthGetTransactionReceipt::class, mockTransactionReceiptResponse(10, true))
        mockWeb3jRequest(web3jRequestHandler, EthTransaction::class, mockEthTransactionResponse("contract-address-no-match", "0x4c6240000"))
        ts.pollPendingTransaction("tx-hash", txPending)

        assertThat(txPending.status).isEqualTo(PendingTxStatus.REVERTED)

        verify(databaseOperations).recordTransactionError(
            any(),
            eq(txPending.rowId),
            eq(null),
            eq("Transaction does not match original"),
            eq(null)
        )
    }

    @Test
    fun `reverted transaction`() {

        val web3jRequestHandler = mock<Web3jRequestHandler>()

        val ts = createTransactionSubmitter(
            web3jRequestHandler,
            mapOf(createTransactionManager("http://127.0.0.1:9999", "0xfrom", exception = "Oh dear")),
            mock<ContractGasProvider>()
        )

        val txPending = mkEvmPendingDbTx(5)

        mockWeb3jRequest(web3jRequestHandler, EthGetTransactionReceipt::class, mockTransactionReceiptResponse(10, false))
        mockWeb3jRequest(web3jRequestHandler, EthTransaction::class, mockEthTransactionResponse(
            "contractAddress",
            "0xe71731e4000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000000010000000000000000000000000101010101010101010101010101010101010101"
        ))
        ts.pollPendingTransaction("tx-hash", txPending)

        assertThat(txPending.status).isEqualTo(PendingTxStatus.REVERTED)

        verify(databaseOperations).recordTransactionError(
            any(),
            eq(txPending.rowId),
            eq(null),
            eq("Transaction was reverted"),
            eq(null)
        )
    }

    @Test
    fun `successful verification with block number weight`() {

        val web3jRequestHandler = mock<Web3jRequestHandler>()

        val ts = createTransactionSubmitter(
            web3jRequestHandler,
            mapOf(createTransactionManager("http://127.0.0.1:9999", "0xfrom", exception = "Oh dear")),
            mock<ContractGasProvider>()
        )

        val txPending = mkEvmPendingDbTx()

        // First poll - get receipt and store block number
        mockWeb3jRequest(web3jRequestHandler, EthGetTransactionReceipt::class, mockTransactionReceiptResponse(5, true))
        ts.pollPendingTransaction("tx-hash", txPending)

        assertThat(txPending.blockNumber!!.toLong()).isEqualTo(5)
        assertThat(txPending.status).isEqualTo(PendingTxStatus.VERIFYING)

        // Second poll with block number 6 - nothing has changed since we wait for 5 blocks
        mockWeb3jRequest(web3jRequestHandler, EthGetTransactionReceipt::class, mockTransactionReceiptResponse(6, true))
        ts.pollPendingTransaction("tx-hash", txPending)

        assertThat(txPending.blockNumber!!.toLong()).isEqualTo(5)
        assertThat(txPending.status).isEqualTo(PendingTxStatus.VERIFYING)

        // Third poll with block number 10 - evm has built 5 blocks - lets verify everything
        mockWeb3jRequest(web3jRequestHandler, EthGetTransactionReceipt::class, mockTransactionReceiptResponse(10, true))
        mockWeb3jRequest(web3jRequestHandler, EthTransaction::class, mockEthTransactionResponse(
            "contractAddress",
            "0xe71731e4000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000000010000000000000000000000000101010101010101010101010101010101010101"
        ))
        ts.pollPendingTransaction("tx-hash", txPending)

        assertThat(txPending.blockNumber!!.toLong()).isEqualTo(10)
        assertThat(txPending.status).isEqualTo(PendingTxStatus.SUCCESS)
        assertThat(txPending.blockHash).isNotNull()
        assertThat(txPending.effectiveGasPrice).isNotNull()
        assertThat(txPending.gasUsed).isNotNull()
    }
}
