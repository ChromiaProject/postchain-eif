package net.postchain.eif

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import net.postchain.common.hexStringToByteArray
import net.postchain.concurrent.util.get
import net.postchain.core.Transaction
import net.postchain.crypto.KeyPair
import net.postchain.crypto.devtools.KeyPairHelper
import net.postchain.devtools.IntegrationTestSetup
import net.postchain.devtools.testinfra.BaseTestInfrastructureFactory
import net.postchain.eif.contracts.TestToken
import net.postchain.eif.contracts.TokenBridge
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtx.GtxBuilder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Testcontainers
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.crypto.Credentials
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import org.web3j.tx.Contract
import org.web3j.tx.FastRawTransactionManager
import org.web3j.tx.TransactionManager
import org.web3j.tx.gas.DefaultGasProvider
import org.web3j.tx.response.PollingTransactionReceiptProcessor
import java.math.BigInteger

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
            setProperty("infrastructure", BaseTestInfrastructureFactory::class.qualifiedName)
            setProperty("ethereum.url", "http://$gethHost:$gethPort")
            setProperty("ethereum.maxReadAhead", 200)
            setProperty("ethereum.maxQueueSize", 100)
        }
    }

    @AfterEach
    override fun tearDown() {
        super.tearDown()
        web3j.shutdown()
        gethContainer.stop()
    }

    @Test
    fun `deposit`() {
        val initialMint = 100L
        // Deploy token bridge contract
        val bridge = Contract.deployRemoteCall(TokenBridge::class.java, web3j, transactionManager, gasProvider, tokenBridgeBinary, "").send().apply {
            initialize(validatorContract).send()
        }

        // Deploy a test token that we mint and then approve transfer of coins to chrL2 contract
        val testToken = Contract.deployRemoteCall(TestToken::class.java, web3j, transactionManager, gasProvider, testTokenBinary, "").send().apply {
            mint(Address(transactionManager.fromAddress), Uint256(BigInteger.valueOf(initialMint))).send()
            approve(Address(bridge.contractAddress), Uint256(BigInteger.valueOf(initialMint))).send()
        }
        // Allow token
        bridge.allowToken(Address(testToken.contractAddress)).send()

        val nodes = createNodes(1, "/net/postchain/eif/blockchain_config_it.xml")
        val node = nodes[0]
        val bcRid = systemSetup.blockchainMap[1]!!.rid // Just assume we have chain 1

        var currentBlockHeight = -1L

        fun sealBlock() {
            currentBlockHeight += 1
            buildBlockAndCommit(node.getBlockchainInstance().blockchainEngine)
            assertEquals(currentBlockHeight, getLastHeight(node))
        }

        fun enqueueTx(data: ByteArray): Transaction? {
            try {
                val tx = node.getBlockchainInstance().blockchainEngine.getConfiguration().getTransactionFactory()
                        .decodeTransaction(data)
                node.getBlockchainInstance().blockchainEngine.getTransactionQueue().enqueue(tx)
                return tx
            } catch (e: Exception) {
                logger.error(e) { "Can't enqueue tx" }
            }
            return null
        }

        val sigMaker = cryptoSystem.buildSigMaker(KeyPair(KeyPairHelper.pubKey(0), KeyPairHelper.privKey(0)))

        fun registerAsset(): ByteArray {
            val b = GtxBuilder(bcRid, listOf(KeyPairHelper.pubKey(0)), myCS)
            b.addOperation("ft3.dev_register_asset", gtv("Chromia"), gtv(bcRid.data))
            return b.finish()
                    .sign(sigMaker)
                    .buildGtx()
                    .encode()
        }
        enqueueTx(registerAsset())
        sealBlock()

        val value = node.getBlockchainInstance().blockchainEngine.getBlockQueries()
                .query("ft3.get_asset_by_name", gtv(mapOf("name" to gtv("Chromia")))).get()
        val assetId = value.get(0).get("id")!!
        fun addNewEvmErc20(): ByteArray {
            val b = GtxBuilder(bcRid, listOf(KeyPairHelper.pubKey(0)), myCS)
            b.addOperation("add_new_evm_erc20", gtv(1), gtv(testToken.contractAddress), gtv("Chromia"), gtv("CHR"), gtv(6))
            return b.finish()
                    .sign(sigMaker)
                    .buildGtx()
                    .encode()
        }

        fun addTokenMapping(): ByteArray {
            val b = GtxBuilder(bcRid, listOf(KeyPairHelper.pubKey(0)), myCS)
            b.addOperation("add_new_token_mapping", gtv(1), gtv(testToken.contractAddress), assetId)
            return b.finish()
                    .sign(sigMaker)
                    .buildGtx()
                    .encode()
        }

        enqueueTx(addNewEvmErc20())
        enqueueTx(addTokenMapping())
        sealBlock()

        // Deposit to postchain
        for (i in 1..5) {
            bridge.deposit(Address(testToken.contractAddress), Uint256(BigInteger.TEN), accountId).send()
        }

        repeat(10) { sealBlock() }
    }

    private fun getBinaryFromArtifactResource(resourcePath: String): String {
        val artifactFile = javaClass.getResource(resourcePath)?.readText()
        val artifactJson = GsonBuilder().create().fromJson(artifactFile, JsonObject::class.java)
        return artifactJson.get("bytecode").asString
    }
}