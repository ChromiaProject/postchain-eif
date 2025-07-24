package net.postchain.eif.transaction

import assertk.assertThat
import assertk.assertions.isEqualTo
import net.postchain.devtools.getModules
import net.postchain.eif.EifBaseIntegrationTest
import net.postchain.eif.contracts.Validator
import net.postchain.eif.sendAsyncAwait
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.UPDATE_EVM_TRANSACTION_STATUS
import org.awaitility.Awaitility
import org.awaitility.Duration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.tx.Contract
import java.math.BigInteger

@Testcontainers(disabledWithoutDocker = true)
class TransactionSubmitterStartupIT : EifBaseIntegrationTest() {

    private lateinit var contractAddress: String

    @BeforeEach
    override fun setup() {
        super.setup()

        with(configOverrides) {
            setProperty("ethereum.privateKey", "0x53914554952e5473a54b211a31303078abde83b8128995785901eed28df3f610")
            setProperty("evm.txPollInterval", 1000)
            setProperty("api.port", -1)
            setProperty("messaging.port", 0)
        }

        // Deploy validator contract
        val encodedConstructor = FunctionEncoder.encodeConstructor(listOf(DynamicArray(Address::class.java, Address(BigInteger.ONE))))
        val contract = Contract.deployRemoteCall(Validator::class.java, web3j, transactionManager, gasProvider, validatorBinary, encodedConstructor).sendAsyncAwait()
        contractAddress = contract.contractAddress.substring(2)
    }

    @Test
    fun `verify continue taken txs on startup`() {

        val node = createNodes(1, "/net/postchain/eif/transaction/blockchain_config_queue.xml")[0]

        val txSubmitterTestModule = node.getModules().filterIsInstance<TransactionSubmitterTestGTXModule>().first()

        txSubmitterTestModule.addTransaction(mkEvmSubmitTxRellRequest(0, contractAddress, RellTransactionStatus.TAKEN))

        Awaitility.await().atMost(Duration.TEN_SECONDS.multiply(2)).untilAsserted {
            buildBlock(1L)
            assertTransactionsByStatus(txSubmitterTestModule, RellTransactionStatus.QUEUED, 1)
            assertStatusOperation(txSubmitterTestModule, 0, RellTransactionStatus.QUEUED)
        }
    }

    @Test
    fun `verify drop txs on startup if no longer taken by node`() {

        val node = createNodes(1, "/net/postchain/eif/transaction/blockchain_config_queue_taken_lost.xml")[0]

        val txSubmitterTestModule = node.getModules().filterIsInstance<TransactionSubmitterTestGTXModule>().first()

        txSubmitterTestModule.addTransaction(mkEvmSubmitTxRellRequest(0, contractAddress, RellTransactionStatus.TAKEN))

        Awaitility.await().atMost(Duration.TEN_SECONDS.multiply(2)).untilAsserted {
            buildBlock(1L)
            // Make sure the tx is no longer in db
            withDbTransactions(node, 0) {
                assertThat(it.size).isEqualTo(0)
            }
            // Make sure no tx status was sent
            withTxOperations(
                    txSubmitterTestModule,
                    UPDATE_EVM_TRANSACTION_STATUS
            ) { operations ->
                assertThat(operations.size).isEqualTo(0)
            }

            testLogAppender.assertInfo("Transaction 0 is no longer processed by this node")
        }
    }

    @Test
    fun `verify continue pending txs on startup`() {

        createNodes(1, "/net/postchain/eif/transaction/blockchain_config_pending.xml")[0]

        Awaitility.await().atMost(Duration.TEN_SECONDS.multiply(2)).untilAsserted {
            buildBlock(1L)

            // It will fail since the transaction is not actually submitted
            testLogAppender.assertInfo("No receipt found for transaction")
        }
    }

    @Test
    fun `drop tx if status set to completed on bc`() {

        with(configOverrides) {
            setProperty("evm.txPollInterval", 1000)
        }

        val node = createNodes(1, "/net/postchain/eif/transaction/blockchain_config_pending.xml")[0]

        val txSubmitterTestModule = node.getModules().filterIsInstance<TransactionSubmitterTestGTXModule>().first()

        buildBlock(1L)

        txSubmitterTestModule.addTransaction(mkEvmSubmitTxRellRequest(0, contractAddress, RellTransactionStatus.SUCCESS))

        Awaitility.await().atMost(Duration.TEN_SECONDS).untilAsserted {
            buildBlock(1L)

            // It will fail since the transaction is not actually submitted
            testLogAppender.assertWarn("Transaction 0 blockchain status is set to SUCCESS. This node will stop processing this transaction.")
        }
    }
}
