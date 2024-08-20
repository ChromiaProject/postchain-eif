package net.postchain.eif.transaction

import assertk.assertThat
import assertk.assertions.isEqualTo
import net.postchain.eif.web3j.Web3jRawTransactionHandler
import net.postchain.eif.web3j.Web3jRequestHandler
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.eq
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.web3j.crypto.RawTransaction
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.methods.response.EthSendTransaction
import org.web3j.protocol.core.methods.response.EthTransaction
import org.web3j.protocol.core.methods.response.Transaction
import org.web3j.tx.RawTransactionManager
import java.util.Optional

class TransactionSubmitterCancelTest : MockedTestBaseTransactionSubmitter() {

    /**
     * Verify that cancelling a transaction submits a new simplified transaction with same nonce. A transaction can't
     * be cancelled, but we can override it with a cheaper transaction.
     */
    @Test
    fun `cancel pending transaction`() {

        val nonceValue = 123.toBigInteger()
        val gasLimitValue = 5000.toBigInteger()
        val fromAddressValue = "AA"

        // Mock pending evm transaction
        val ethTransaction = mock<EthTransaction>() {
            on { transaction } doAnswer {
                Optional.of(mock<Transaction> {
                    on { nonce } doReturn nonceValue
                    on { maxPriorityFeePerGas } doReturn 1_000.toBigInteger()
                    on { maxFeePerGas } doReturn 1_000_000.toBigInteger()
                    on { gas } doReturn gasLimitValue
                })
            }
        }
        val web3jRequestHandler = mock<Web3jRequestHandler> {
            on { sendWeb3jRequest(requestFactory = any<(Web3j) -> Request<*, EthTransaction>>()) } doReturn ethTransaction
        }

        // Mock transaction handler to capture the replacement transaction
        val web3jRawTransactionHandler = mock<Web3jRawTransactionHandler> {
            on { fromAddress } doReturn fromAddressValue
            on { sendWeb3jTransaction(any(), any()) } doAnswer {
                mock< EthSendTransaction>()
            }
        }

        // Cancel transaction in TXS
        val ts = createTransactionSubmitter(
                web3jRequestHandler,
                web3jRawTransactionHandler,
                mockFeeEstimatorFactory()
        )

        val txPending = mkEvmPendingDbTx()
        ts.cancelTransaction(txPending)

        val argumentCaptor = argumentCaptor<(RawTransactionManager) -> EthSendTransaction>()
        val transactionCaptor = argumentCaptor<RawTransaction>()

        verify(web3jRawTransactionHandler, times(1)).sendWeb3jTransaction(eq(txPending.rowId), argumentCaptor.capture())

        val transactionFactory = argumentCaptor.firstValue
        val transactionManagerMock = mock<RawTransactionManager> {
            on { signAndSend(any()) } doReturn mock<EthSendTransaction>()
        }
        transactionFactory(transactionManagerMock)
        verify(transactionManagerMock).signAndSend(transactionCaptor.capture())
        val rawTransaction = transactionCaptor.firstValue

        // Verify our replacement transaction
        assertThat(rawTransaction.transaction.nonce).isEqualTo(nonceValue)
        assertThat(rawTransaction.transaction.data).isEqualTo("")
        assertThat(rawTransaction.transaction.to).isEqualTo(fromAddressValue)
    }
}
