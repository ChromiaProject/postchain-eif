package net.postchain.eif.transaction

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.matches
import net.postchain.devtools.getModules
import net.postchain.eif.EifBaseIntegrationTest
import net.postchain.eif.EvmType
import net.postchain.eif.contracts.Validator
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_ERRORS_COLUMN_MESSAGE
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_ERRORS_COLUMN_REQUEST_ID
import org.awaitility.Awaitility
import org.awaitility.Duration
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.tx.Contract
import java.math.BigInteger

@Testcontainers(disabledWithoutDocker = true)
class TransactionSubmitterTimeoutIT : EifBaseIntegrationTest(
        EvmType.GETH
) {

    private lateinit var contractAddress: String

    @BeforeEach
    override fun setup() {

        super.setup()

        with(configOverrides) {
            setProperty("ethereum.privateKey", "0x53914554952e5473a54b211a31303078abde83b8128995785901eed28df3f610")
            setProperty("evm.txPollInterval", 1000)
        }

        // Deploy validator contract
        val encodedConstructor = FunctionEncoder.encodeConstructor(listOf(DynamicArray(Address::class.java, Address(BigInteger.ONE))))
        val contract = Contract.deployRemoteCall(Validator::class.java, web3j, transactionManager, gasProvider, validatorBinary, encodedConstructor).send()
        contractAddress = contract.contractAddress.substring(2)
    }

    @Test
    fun `timeout queued transaction`() {
        val nodes = createNodes(1, "/net/postchain/eif/transaction/blockchain_config_0_verification_timeout.xml")
        val node = nodes[0]

        val txSubmitterTestModule = node.getModules().filterIsInstance<TransactionSubmitterTestGTXModule>().first()

        val evmSubmitTxRellRequest = mkEvmSubmitTxRellRequest(0, contractAddress, created = System.currentTimeMillis() - 25 * 60 * 60000)
        txSubmitterTestModule.addTransactionsAvailableToTake(evmSubmitTxRellRequest)
        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertTrue(txSubmitterTestModule.conf.queuedTxs.contains(0))
        }
        assertStatusOperation(txSubmitterTestModule, evmSubmitTxRellRequest.rowId, RellTransactionStatus.QUEUED)
        withDbErrors(node, evmSubmitTxRellRequest.rowId) {
            assertThat(it.size).isEqualTo(1)
            assertThat(it[0].get(EVM_TX_ERRORS_COLUMN_MESSAGE)).matches("Transaction 0 with timestamp \\d+ was not processed within \\d+ ms and timed out".toRegex())
        }
    }

    @Test
    fun `timeout pending transaction`() {

        val nodes = createNodes(1, "/net/postchain/eif/transaction/blockchain_config_0_verification_timeout_and_pending.xml")
        val node = nodes[0]

        val txSubmitterTestModule = node.getModules().filterIsInstance<TransactionSubmitterTestGTXModule>().first()

        val sendResult =
            sendTransaction(contractAddress)

        val evmSubmitTransactionRequest = mkEvmSubmitTxRellRequest(0, contractAddress, txHash = sendResult!!.transactionHash, functionName =  "updateValidators-incorrect", status = RellTransactionStatus.PENDING)

        txSubmitterTestModule.addTransaction(evmSubmitTransactionRequest)

        // Make sure it is added
        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertThat(withTxSubmitter(txSubmitterTestModule, 0) { _, _ -> true })
                .isNotNull()
                .isEqualTo(true)
        }

        // Wait for timeout
        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)

            // There should be a timeout error message
            withDbErrors(node, evmSubmitTransactionRequest.rowId)  {
                assertTrue(
                    it.any { it.get(EVM_TX_ERRORS_COLUMN_REQUEST_ID) == evmSubmitTransactionRequest.rowId &&
                            it.get(EVM_TX_ERRORS_COLUMN_MESSAGE).matches("Transaction 0 with timestamp \\d+ was not processed within 0 ms and timed out".toRegex())
                    }
                )
            }
        }
    }
}
