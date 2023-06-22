package net.postchain.eif

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import net.postchain.common.hexStringToByteArray
import net.postchain.devtools.IntegrationTestSetup
import net.postchain.eif.contracts.TokenBridge
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Testcontainers
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.crypto.Credentials
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import org.web3j.tx.Contract
import org.web3j.tx.FastRawTransactionManager
import org.web3j.tx.TransactionManager
import org.web3j.tx.gas.DefaultGasProvider
import org.web3j.tx.response.PollingTransactionReceiptProcessor

@Testcontainers(disabledWithoutDocker = true)
class EifIntegrationTest : IntegrationTestSetup() {

    private val gethContainer = GethContainer()
            .withExposedService(
                    "geth", 8545,
                    Wait.forLogMessage(".*HTTP server started.*\\s", 1)
            )
    private val gasProvider = DefaultGasProvider()

    // This could be any private key but value must match in /geth-compose/geth/key.txt
    // and the address created must be added to /geth-compose/geth/test.json
    private val credentials = Credentials
            .create("0x0000000000000000000000000000000001000000000000000000000000000000")
    private val accountId = Bytes32("fc91c4abaff09f4c67a0ab84d4e9afd37c929978bea3fa1790403ab6ee85bf33"
            .hexStringToByteArray())
    private val validatorContract = Address("0x0000000000000000000000000000000000000000")
    private lateinit var web3j: Web3j
    private lateinit var transactionManager: TransactionManager

    private val tokenBridgeBinary = getBinaryFromArtifactResource("/artifacts/contracts/TokenBridge.sol/TokenBridge.json")
    private val testTokenBinary = getBinaryFromArtifactResource("/artifacts/contracts/token/TestToken.sol/TestToken.json")

    @BeforeEach
    fun setup() {
        gethContainer.start()

        val gethHost = gethContainer.getServiceHost("geth", 8545)
        val gethPort = gethContainer.getServicePort("geth", 8545)
        web3j = Web3j.build(
                HttpService(
                        "http://$gethHost:$gethPort"
                )
        )

        transactionManager = FastRawTransactionManager(
                web3j,
                credentials,
                PollingTransactionReceiptProcessor(
                        web3j,
                        1000,
                        30
                )
        )

        with(configOverrides) {
            setProperty("infrastructure", "base/test")
            setProperty("ethereum.url", "http://$gethHost:$gethPort")
            setProperty("ethereum.maxReadAhead", 200)
            setProperty("ethereum.maxQueueSize", 100)
        }
    }

    @AfterEach
    override fun tearDown() {
        super.tearDown()
        gethContainer.stop()
    }

    @Test
    fun `deposit`() {
        // Deploy token bridge contract
        val bridge = Contract.deployRemoteCall(TokenBridge::class.java, web3j, transactionManager, gasProvider, tokenBridgeBinary, "").send().apply {
            initialize(validatorContract).send()
        }

        val nodes = createNodes(1, "/net/postchain/eif/blockchain_config_it.xml")
        val node = nodes[0]
    }

    private fun getBinaryFromArtifactResource(resourcePath: String): String {
        val artifactFile = javaClass.getResource(resourcePath)?.readText()
        val artifactJson = GsonBuilder().create().fromJson(artifactFile, JsonObject::class.java)
        return artifactJson.get("bytecode").asString
    }
}