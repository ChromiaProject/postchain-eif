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
import net.postchain.gtv.GtvArray
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
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
            .create("0x53914554952e5473a54b211a31303078abde83b8128995785901eed28df3f610")
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
    fun deposit() {
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
        val testTokenAddress = testToken.contractAddress.substring(2).hexStringToByteArray()
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
        val assetId = value[0]["id"]!!
        fun addNewEvmErc20(): ByteArray {
            val b = GtxBuilder(bcRid, listOf(KeyPairHelper.pubKey(0)), myCS)
            b.addOperation("add_new_evm_erc20", gtv(1), gtv(testTokenAddress), gtv("Chromia"), gtv("CHR"), gtv(6))
            return b.finish()
                    .sign(sigMaker)
                    .buildGtx()
                    .encode()
        }

        fun addTokenMapping(): ByteArray {
            val b = GtxBuilder(bcRid, listOf(KeyPairHelper.pubKey(0)), myCS)
            b.addOperation("add_new_token_mapping", gtv(1), gtv(testTokenAddress), assetId)
            return b.finish()
                    .sign(sigMaker)
                    .buildGtx()
                    .encode()
        }

        // Register evm account
        val userEVMAddress = "e105ba42b66d08ac7ca7fc48c583599044a6dab3".hexStringToByteArray()
        fun registerAccount(): ByteArray {
            val userPubkey = "038f888dec563b5bc253e87abc90afd26c3287021d10236ea19d248043dc39e0b8".hexStringToByteArray()
            val userPriKey = "71b5b7f8de0661af934a5e4612f3d0ba183e639bdf4e7452fb6457ed3cfbc825".hexStringToByteArray()
            val auth = gtv(
                    gtv("S"),
                    GtvArray(arrayOf(gtv(userPubkey))),
                    gtv(GtvArray(arrayOf(gtv("T"))), gtv(userPubkey)),
                    GtvNull
            )

            val sig = gtv(
                    gtv("39b0c8c44a10d0fd70c0ed0e833cf6d93818ae1b10777857eb868516932796dc".hexStringToByteArray()),
                    gtv("44de8f297cce55c3da8401dd77269d0baf978f60e97ebc5717d4c8eeaed3bea9".hexStringToByteArray()),
                    gtv(28L))

            val b = GtxBuilder(bcRid, listOf(userPubkey), myCS)
            b.addOperation("ft3.evm.register_account", gtv(userEVMAddress), auth, sig)

            val signer = cryptoSystem.buildSigMaker(KeyPair(userPubkey, userPriKey))
            return b.finish()
                    .sign(signer)
                    .buildGtx()
                    .encode()
        }

        enqueueTx(addNewEvmErc20())
        enqueueTx(addTokenMapping())
        enqueueTx(registerAccount())
        sealBlock()

        // query ft3 account by evm address
        val blockQuery = node.getBlockchainInstance().blockchainEngine.getBlockQueries()
        val accountId = blockQuery.query("ft3.evm.get_account_by_evm_address", gtv(mapOf("acc" to gtv(userEVMAddress)))).get()

        // Deposit to postchain
        for (i in 1..5) {
            bridge.deposit(Address(testToken.contractAddress), Uint256(BigInteger.TEN), Bytes32(accountId.asByteArray())).send()
        }

        repeat(10) { sealBlock() }

        // Check the ft3 balance
        val balance = blockQuery.query("ft3.get_asset_balance", gtv(mapOf("account_id" to accountId, "asset_id" to assetId))).get()
        assertEquals(50L, balance["amount"]!!.asInteger())
    }

    private fun getBinaryFromArtifactResource(resourcePath: String): String {
        val artifactFile = javaClass.getResource(resourcePath)?.readText()
        val artifactJson = GsonBuilder().create().fromJson(artifactFile, JsonObject::class.java)
        return artifactJson.get("bytecode").asString
    }
}