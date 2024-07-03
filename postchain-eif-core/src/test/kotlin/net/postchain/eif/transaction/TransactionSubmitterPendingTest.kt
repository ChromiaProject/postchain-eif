package net.postchain.eif.transaction

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import net.postchain.eif.Web3jRequestHandler
import org.apache.logging.log4j.Level
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.methods.response.EthBlockNumber
import org.web3j.protocol.core.methods.response.EthGetBalance
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt
import org.web3j.protocol.core.methods.response.EthTransaction
import java.lang.reflect.AccessibleObject
import java.math.BigInteger

class TransactionSubmitterPendingTest : MockedTestBaseTransactionSubmitter() {

    @Test
    fun `fail getting transaction details for verification`() {

        val blockNumber = mock<EthBlockNumber> {
            on { blockNumber } doReturn BigInteger.valueOf(10)
        }

        val web3jRequestHandler = mock<Web3jRequestHandler> {
            on { sendWeb3jRequest(requestFactory = any<(Web3j) -> Request<*, EthGetBalance>>()) } doThrow RuntimeException(
                    "Oh dear"
            )
        }
        mockWeb3jRequest(web3jRequestHandler, EthGetTransactionReceipt::class, mockTransactionReceiptResponse(5, true))

        mockWeb3jRequest(web3jRequestHandler, EthBlockNumber::class, blockNumber)

        val ts = createTransactionSubmitter(
                web3jRequestHandler,
                mapOf(createTransactionManager("http://127.0.0.1:9999", "0xfrom", exception = "Oh dear")),
        )

        ts.addPendingTransaction(mkEvmPendingDbTx(5))

        // Fails on getting transaction details
        ts.pollPendingTransactions()

        testLogAppender.assertEvent(Level.ERROR, "Failed to process pending transaction 0") {
            it.toString() == "java.lang.RuntimeException: Oh dear"
        }
    }

    @Test
    fun `fail verifying transaction`() {

        val web3jRequestHandler = mock<Web3jRequestHandler>()

        val ts = createTransactionSubmitter(
                web3jRequestHandler,
                mapOf(createTransactionManager("http://127.0.0.1:9999", "0xfrom", exception = "Oh dear")),
        )

        val txPending = mkEvmPendingDbTx(10)
        txPending.blockNumber = BigInteger.TEN

        mockWeb3jRequest(web3jRequestHandler, EthBlockNumber::class, mockBlockNumber(BigInteger.valueOf(20)))
        mockWeb3jRequest(web3jRequestHandler, EthTransaction::class, mockEthTransactionResponse("contract-address-no-match", "0x4c6240000"))
        mockWeb3jRequest(web3jRequestHandler, EthGetTransactionReceipt::class, mockTransactionReceiptResponse(5, true))

        ts.pollPendingTransaction(txPending, BigInteger.valueOf(15))

        assertThat(txPending.status).isEqualTo(PendingTxStatus.REVERTED)

        testLogAppender.assertError("Transaction 0 does not match original")
    }

    @Test
    fun `reverted transaction`() {

        val web3jRequestHandler = mock<Web3jRequestHandler>()

        val ts = createTransactionSubmitter(
                web3jRequestHandler,
                mapOf(createTransactionManager("http://127.0.0.1:9999", "0xfrom", exception = "Oh dear")),
        )

        val txPending = mkEvmPendingDbTx(5)

        mockWeb3jRequest(web3jRequestHandler, EthGetTransactionReceipt::class, mockTransactionReceiptResponse(5, false))
        mockWeb3jRequest(web3jRequestHandler, EthBlockNumber::class, mockBlockNumber(BigInteger.TEN))
        mockWeb3jRequest(web3jRequestHandler, EthTransaction::class, mockEthTransactionResponse(
                "contractAddress",
                "0x9329efad000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000000010000000000000000000000000101010101010101010101010101010101010101"
        ))
        ts.pollPendingTransaction(txPending, BigInteger.valueOf(ts.nodeTxVerificationEvmBlocks + 15))

        assertThat(txPending.status).isEqualTo(PendingTxStatus.REVERTED)

        testLogAppender.assertWarn("Transaction 0 got reverted")
    }

    @Test
    fun `successful verification with block number weight`() {

        val web3jRequestHandler = mock<Web3jRequestHandler>()

        val ts = createTransactionSubmitter(
                web3jRequestHandler,
                mapOf(createTransactionManager("http://127.0.0.1:9999", "0xfrom", exception = "Oh dear")),
        )

        val txPending = mkEvmPendingDbTx()

        // First poll - get receipt and store block number
        mockWeb3jRequest(web3jRequestHandler, EthGetTransactionReceipt::class, mockTransactionReceiptResponse(5, true))
        ts.pollPendingTransaction(txPending, BigInteger.valueOf(5))

        assertThat(txPending.blockNumber!!.toLong()).isEqualTo(5)
        assertThat(txPending.status).isEqualTo(PendingTxStatus.VERIFYING)

        // Second poll with block number 6 - nothing has changed since we wait for 5 blocks
        mockWeb3jRequest(web3jRequestHandler, EthBlockNumber::class, mockBlockNumber(BigInteger.valueOf(6)))
        ts.pollPendingTransaction(txPending, BigInteger.valueOf(6))

        assertThat(txPending.blockNumber!!.toLong()).isEqualTo(5)
        assertThat(txPending.status).isEqualTo(PendingTxStatus.VERIFYING)

        // Third poll with block number 10 - evm has built 5 blocks - lets verify everything
        mockWeb3jRequest(web3jRequestHandler, EthTransaction::class, mockEthTransactionResponse(
                "contractAddress",
                "0x9329efad000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000000010000000000000000000000000101010101010101010101010101010101010101"
        ))
        ts.pollPendingTransaction(txPending, BigInteger.valueOf(10))

        assertThat(txPending.blockNumber!!.toLong()).isEqualTo(5)
        assertThat(txPending.status).isEqualTo(PendingTxStatus.SUCCESS)
        assertThat(txPending.blockHash).isNotNull()
        assertThat(txPending.effectiveGasPrice).isNotNull()
        assertThat(txPending.gasUsed).isNotNull()
    }

    @Test
    fun `get verified transactions - validation timeout not expired`() {

        val web3jRequestHandler = mock<Web3jRequestHandler>()

        val ts = createTransactionSubmitter(
                web3jRequestHandler,
                mapOf(createTransactionManager("http://127.0.0.1:9999", "0xfrom", exception = "Oh dear")),
        )

        val txPending = mkEvmPendingDbTx(networkId = 0L)
        txPending.status = PendingTxStatus.SUCCESS
        val completedTimeField = txPending.javaClass.getDeclaredField("completedTime")
        AccessibleObject.setAccessible(listOf(completedTimeField).toTypedArray(), true)
        completedTimeField.set(txPending, Long.MAX_VALUE)
        ts.addPendingTransaction(txPending)

        assertThat(ts.getVerifiedTransactions().isEmpty()).isTrue()
    }

    @Test
    fun `get verified transactions - validation timeout expired`() {

        val web3jRequestHandler = mock<Web3jRequestHandler>()

        val ts = createTransactionSubmitter(
                web3jRequestHandler,
                mapOf(createTransactionManager("http://127.0.0.1:9999", "0xfrom", exception = "Oh dear")),
        )

        val txPending = mkEvmPendingDbTx(networkId = 0L)
        txPending.status = PendingTxStatus.SUCCESS
        val completedTimeField = txPending.javaClass.getDeclaredField("completedTime")
        AccessibleObject.setAccessible(listOf(completedTimeField).toTypedArray(), true)
        completedTimeField.set(txPending, 0L)
        ts.addPendingTransaction(txPending)

        assertThat(ts.getVerifiedTransactions().isNotEmpty()).isTrue()
    }
}
