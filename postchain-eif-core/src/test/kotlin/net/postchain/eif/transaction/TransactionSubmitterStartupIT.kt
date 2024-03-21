package net.postchain.eif.transaction

import assertk.assertThat
import assertk.assertions.isEqualTo
import net.postchain.devtools.getModules
import net.postchain.eif.EifBaseIntegrationTest
import net.postchain.eif.EvmType
import net.postchain.eif.contracts.Validator
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_ERRORS_COLUMN_MESSAGE
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
class TransactionSubmitterStartupIT : EifBaseIntegrationTest(
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
    fun `verify continue taken txs on startup`() {

        val node = createNodes(1, "/net/postchain/eif/transaction/blockchain_config_queue.xml")[0]

        val txSubmitterTestModule = node.getModules().filterIsInstance<TransactionSubmitterTestGTXModule>().first()

        txSubmitterTestModule.addGetTransactionStatus(0, RellTransactionStatus.QUEUED)

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertTrue(txSubmitterTestModule.conf.queue.isEmpty())
            assertStatusOperation(txSubmitterTestModule, 0, RellTransactionStatus.PENDING)
        }
    }

    @Test
    fun `verify continue pending txs on startup`() {

        val node = createNodes(1, "/net/postchain/eif/transaction/blockchain_config_pending.xml")[0]

        val txSubmitterTestModule = node.getModules().filterIsInstance<TransactionSubmitterTestGTXModule>().first()

        txSubmitterTestModule.addGetTransactionStatus(0, RellTransactionStatus.PENDING)
        txSubmitterTestModule.addGetPendingTransactions(mkEvmPendingRellTx(
            "tx-hash",
            contractAddress,
        ))

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)

            // It will fail since the transaction is not actually submitted
            withDbErrors(node, 0) {
                assertThat(it.size).isEqualTo(1)
                assertThat(it[0].get(EVM_TX_ERRORS_COLUMN_MESSAGE)).isEqualTo("Failed to poll for receipt for request id 0")
            }
        }
    }
}
