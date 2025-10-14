package net.postchain.eif.transaction

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import net.postchain.devtools.getModules
import net.postchain.eif.EifBaseIntegrationTest
import net.postchain.eif.contracts.Validator
import org.awaitility.Awaitility
import org.awaitility.Duration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.testcontainers.junit.jupiter.Testcontainers
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.tx.Contract
import java.math.BigInteger
import java.util.concurrent.TimeUnit

@Timeout(value = 5, unit = TimeUnit.MINUTES)
@Testcontainers(disabledWithoutDocker = true)
class TransactionSubmitterHealthCheckIT : EifBaseIntegrationTest() {

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
        val contract = Contract.deployRemoteCall(Validator::class.java, web3j, transactionManager, gasProvider, validatorBinary, encodedConstructor).send()
        contractAddress = contract.contractAddress.substring(2)
    }

    @Test
    fun `Assert that tx submitter becomes unhealthy when RPC nodes are unreachable`() {
        with(configOverrides) {
            setProperty("evm.healthCheckInterval", 1000)
            setProperty("ethereum.urls", listOf("http://localhost:9000"))
        }

        val nodes = createNodes(1, "/net/postchain/eif/transaction/blockchain_config.xml")
        val node = nodes[0]

        val txSubmitterTestModule = node.getModules().filterIsInstance<TransactionSubmitterTestGTXModule>().first()
        val txExtension = txSubmitterTestModule.getSpecialTxExtensions().filterIsInstance<TransactionSubmitterSpecialTxExtension>().first()

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            val txSubmitter = txExtension.getTransactionSubmitter(1337)
            assertThat(txSubmitter).isNotNull()
            assertThat(txSubmitter!!.isHealthy()).isFalse()
        }
    }

    @Test
    fun `Assert that tx submitter becomes unhealthy when wallet balance is too low`() {
        with(configOverrides) {
            // Random key with no balance
            setProperty("ethereum.privateKey", "0x53914554952e5473a54b211a31303078abde83b8128995785901eed28df3f611")
            setProperty("evm.healthCheckInterval", 1000)
        }

        val nodes = createNodes(1, "/net/postchain/eif/transaction/blockchain_config.xml")
        val node = nodes[0]

        val txSubmitterTestModule = node.getModules().filterIsInstance<TransactionSubmitterTestGTXModule>().first()
        val txExtension = txSubmitterTestModule.getSpecialTxExtensions().filterIsInstance<TransactionSubmitterSpecialTxExtension>().first()

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            val txSubmitter = txExtension.getTransactionSubmitter(1337)
            assertThat(txSubmitter).isNotNull()
            assertThat(txSubmitter!!.isHealthy()).isFalse()
        }
    }
}
