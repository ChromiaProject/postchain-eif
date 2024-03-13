package net.postchain.eif

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import mu.KotlinLogging
import net.postchain.base.BaseBlockWitness
import net.postchain.base.configuration.KEY_SIGNERS
import net.postchain.base.snapshot.SimpleDigestSystem
import net.postchain.common.BlockchainRid
import net.postchain.common.data.Hash
import net.postchain.common.data.KECCAK256
import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.concurrent.util.get
import net.postchain.core.BlockRid
import net.postchain.core.block.BlockQueries
import net.postchain.crypto.KeyPair
import net.postchain.crypto.SigMaker
import net.postchain.crypto.Signature
import net.postchain.crypto.devtools.KeyPairHelper
import net.postchain.devtools.ManagedModeTest
import net.postchain.devtools.PostchainTestNode
import net.postchain.devtools.PostchainTestNode.Companion.DEFAULT_CHAIN_IID
import net.postchain.eif.contracts.TestToken
import net.postchain.eif.contracts.TokenBridge
import net.postchain.eif.contracts.Validator
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvArray
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvInteger
import net.postchain.gtv.GtvNull
import net.postchain.gtv.gtvml.GtvMLParser
import net.postchain.gtv.mapper.toObject
import net.postchain.gtv.merkle.GtvMerkleHashCalculator
import net.postchain.gtv.merkleHash
import net.postchain.gtx.GtxBuilder
import org.awaitility.Awaitility
import org.awaitility.Duration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertThrows
import org.junitpioneer.jupiter.DisableIfTestFails
import org.testcontainers.containers.DockerComposeContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.crypto.Credentials
import org.web3j.crypto.Sign
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.exceptions.TransactionException
import org.web3j.protocol.http.HttpService
import org.web3j.tx.Contract
import org.web3j.tx.FastRawTransactionManager
import org.web3j.tx.TransactionManager
import org.web3j.tx.gas.DefaultGasProvider
import org.web3j.tx.response.PollingTransactionReceiptProcessor
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class EvmType {
    GETH, BSC
}

data class AccountRegister(
        var accountId: ByteArray = ByteArray(32),
        val privKey: ByteArray,
        val pubkey: ByteArray,
        val evmAddress: ByteArray,
        val balance: Long
)

@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@DisableIfTestFails
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class EifIntegrationTest : ManagedModeTest() {

    val logger = KotlinLogging.logger("test_logger")
    val node1Logger = KotlinLogging.logger("eif_node1_logger")

    private val networkId = 1337L
    private val gasProvider = DefaultGasProvider()
    private lateinit var ds: SimpleDigestSystem

    lateinit var evmContainer: DockerComposeContainer<*>
    protected lateinit var evmServiceUrl: String
    private val credentials = Credentials
            .create("0x53914554952e5473a54b211a31303078abde83b8128995785901eed28df3f610")
    private val tokenBridgeBinary = getBinaryFromArtifactResource("/artifacts/contracts/TokenBridge.sol/TokenBridge.json")
    private val testTokenBinary = getBinaryFromArtifactResource("/artifacts/contracts/token/TestToken.sol/TestToken.json")
    private val validatorBinary = getBinaryFromArtifactResource("/artifacts/contracts/Validator.sol/Validator.json")

    private enum class AuthType {
        S, M
    }

    protected lateinit var web3j: Web3j
    protected lateinit var transactionManager: TransactionManager

    private val accountNum = 15
    private val accountBalance = 1L
    private val registerAccounts = mutableListOf<AccountRegister>()
    private val snapshotHeights = mutableListOf<Long>()

    // user
    private val evmAddress = "e105ba42b66d08ac7ca7fc48c583599044a6dab3"
    private val userEvmAddress = evmAddress.hexStringToByteArray()
    private val userPubkey = "038f888dec563b5bc253e87abc90afd26c3287021d10236ea19d248043dc39e0b8".hexStringToByteArray()
    private val userPriKey = "71b5b7f8de0661af934a5e4612f3d0ba183e639bdf4e7452fb6457ed3cfbc825".hexStringToByteArray()

    // TODO: use getEvmAddress
    private val node0EvmAddress = Address("659e4a3726275edFD125F52338ECe0d54d15BD99")
    private val node1EvmAddress = Address("2c3fA9C9FC3C5CB2f9C09aF6f7214f64382eA086")

    // other
    val otherEvmAddressString = "661683e5d36E83B38B1a20247ba6F5c410dC165d"
    val otherEvmAddress = otherEvmAddressString.hexStringToByteArray()

    private val initialMint = BigInteger("FF".repeat(32), 16)
    private val depositNum = 5
    private val depositAmount = BigInteger("AA".repeat(16), 16)
    private val totalDepositedAmount = depositNum.toBigInteger() * depositAmount
    private val totalTransferAmount = BigInteger("1234567890ABCDEF", 16)
    private lateinit var toRemainingAccount: BigInteger
    private lateinit var validator: Validator
    private lateinit var bridge: TokenBridge
    private lateinit var testToken: TestToken
    private lateinit var testTokenAddress: ByteArray
    private lateinit var userBalance: Uint256
    private lateinit var withdrawAmount: BigInteger
    private lateinit var accountId: Gtv
    private lateinit var accountNumber: Gtv
    private lateinit var authDescriptorId: Hash
    private lateinit var authId: Gtv
    private lateinit var otherAccountId: Gtv
    private lateinit var assetId: Gtv
    private lateinit var node: PostchainTestNode
    private lateinit var blockQuery: BlockQueries
    private var chainId = -1L
    private lateinit var bcRid: BlockchainRid
    private var currentBlockHeight = 0L
    private var lastSnapshotBlockHeight = -1L

    // get smart contract binary from resource
    private fun getBinaryFromArtifactResource(resourcePath: String): String {
        val artifactFile = EifIntegrationTest::class.java.getResource(resourcePath)?.readText()
        val artifactJson = GsonBuilder().create().fromJson(artifactFile, JsonObject::class.java)
        return artifactJson.get("bytecode").asString
    }

    open fun setup() {
        assert(::evmContainer.isInitialized) { "evmContainer is not initialized" }

        ds = SimpleDigestSystem(MessageDigest.getInstance(KECCAK256))

        val evmHost = evmContainer.getServiceHost("geth", 8545)
        val evmPort = evmContainer.getServicePort("geth", 8545)
        evmServiceUrl = "http://$evmHost:$evmPort"

        web3j = Web3j.build(HttpService(evmServiceUrl))

        transactionManager = FastRawTransactionManager(
                web3j,
                credentials,
                PollingTransactionReceiptProcessor(web3j, 1000, 30)
        )

        with(configOverrides) {
            setProperty("infrastructure", net.postchain.devtools.testinfra.BaseTestInfrastructureFactory::class.qualifiedName)
            setProperty("ethereum.urls", listOf(
                    "http://127.0.0.1:8888",
                    "http://127.0.0.1:9999",
                    evmServiceUrl
            ).joinToString())
            setProperty("ethereum.maxReadAhead", 200)
            setProperty("ethereum.maxQueueSize", 100)
            setProperty("evm.maxTryErrors", 1)
        }
    }

    @AfterAll
    fun tearDownAfterAll() {
        super.tearDown() // Calling @AfterEach IntegrationTestSetup.tearDown()
        if (::web3j.isInitialized) web3j.shutdown()
        if (::evmContainer.isInitialized) evmContainer.stop()
    }

    @AfterEach
    override fun tearDown() {
        // This method blocks @AfterEach IntegrationTestSetup.tearDown()
    }

    @Test
    @Order(1)
    fun `deploy contracts`() {
        logger.info { "deploy contracts" }

        // Deploy validator contract
        val encodedConstructor = FunctionEncoder.encodeConstructor(listOf(DynamicArray(Address::class.java, node0EvmAddress)))
        validator = Contract.deployRemoteCall(Validator::class.java, web3j, transactionManager, gasProvider, validatorBinary, encodedConstructor).send()

        // Deploy token bridge contract
        bridge = Contract.deployRemoteCall(TokenBridge::class.java, web3j, transactionManager, gasProvider, tokenBridgeBinary, "").send().apply {
            initialize(Address(validator.contractAddress), Uint256(2)).send()
        }

        // Deploy a test token that we mint and then approve transfer of coins to chrL2 contract
        testToken = Contract.deployRemoteCall(TestToken::class.java, web3j, transactionManager, gasProvider, testTokenBinary, "").send().apply {
            mint(Address(transactionManager.fromAddress), Uint256(initialMint)).send()
            approve(Address(bridge.contractAddress), Uint256(initialMint)).send()
        }
        testTokenAddress = testToken.contractAddress.substring(2).hexStringToByteArray()
        // Allow token
        bridge.allowToken(Address(testToken.contractAddress)).send()
    }

    @Test
    @Order(2)
    fun `start nodes`() {
        logger.info { "start nodes" }

        // c0
        startManagedSystem(1, 1, restApi = true)

        // c1
        val chainGtvConfig = GtvMLParser.parseGtvML(
                javaClass.getResource("/net/postchain/eif/blockchain_config_it.xml")!!.readText()
        )
        chainId = startNewBlockchain(
                setOf(0), setOf(1), rawBlockchainConfiguration = GtvEncoder.encodeGtv(chainGtvConfig)
        )
        buildBlock(chainId)
        node = nodes[0]
        bcRid = node.getBlockchainInstance(chainId).blockchainEngine.blockchainRid
        logger.info { "Chain deployed: chainId: $chainId, blockchainRid: $bcRid" }
    }

    @Test
    @Order(3)
    fun `register ft accounts`() {
        logger.info { "register ft accounts" }

        val sigMaker = cryptoSystem.buildSigMaker(KeyPair(KeyPairHelper.pubKey(0), KeyPairHelper.privKey(0)))
        val tokenName = "Chromia"
        val tokenSymbol = "CHR"
        val tokenDecimal = 18L
        val tokenIconUrl = "https://chromaway.com/chr"

        enqueueTx(registerAsset(tokenName, tokenSymbol, tokenDecimal, tokenIconUrl, bcRid, sigMaker))
        sealBlock()

        val value = node.getBlockchainInstance().blockchainEngine.getBlockQueries()
                .query("ft4.get_assets_by_name", gtv(
                        "name" to gtv(tokenName),
                        "page_size" to gtv(1L),
                        "page_cursor" to GtvNull
                )).get()
        assetId = value["data"]?.get(0)?.get("id")!!

        // Register evm account
        val otherPubkey = "02E0A8A3C79C9F18B7CEAD2493435AC926B4A527EF670B873F5F1410084EFF9C80".hexStringToByteArray()
        val otherPrivkey = "B31AB878C62B0E940B345C659A456D3573CF25960823C34C7BEEB5D1F813BEFD".hexStringToByteArray()
        val sig = gtv(
                gtv("39b0c8c44a10d0fd70c0ed0e833cf6d93818ae1b10777857eb868516932796dc".hexStringToByteArray()),
                gtv("44de8f297cce55c3da8401dd77269d0baf978f60e97ebc5717d4c8eeaed3bea9".hexStringToByteArray()),
                gtv(28L))

        val otherSig = gtv(
                gtv("8fa4216cd5979efdeb109e10f87225ea9579fd21289fac7f1410278554e79aff".hexStringToByteArray()),
                gtv("442017757e4e627a98d40c89cdbdde4612251cc86acabba27cf1683cd1d7cb4c".hexStringToByteArray()),
                gtv(28L))

        enqueueTx(addNewEvmErc20(testTokenAddress, tokenName, tokenSymbol, tokenDecimal, bcRid, sigMaker))
        enqueueTx(addTokenMapping(testTokenAddress, assetId, bcRid, sigMaker))

        // Register accounts
        enqueueTx(registerAccount(userPubkey, userPriKey, userEvmAddress, sig, bcRid))
        enqueueTx(registerAccount(otherPubkey, otherPrivkey, otherEvmAddress, otherSig, bcRid))

        for (i in 1..accountNum) {
            val acc = AccountRegister(
                    ByteArray(32),
                    KeyPairHelper.privKey(i),
                    KeyPairHelper.pubKey(i),
                    getEthereumAddress(KeyPairHelper.pubKey(i)),
                    accountBalance
            )
            registerAccounts.add(acc)
            val registerMessage = getRegisterMessage(acc.evmAddress.toHex().lowercase(), acc.pubkey.toHex().lowercase())
            val evmSig = Sign.signPrefixedMessage(
                    registerMessage.toByteArray(StandardCharsets.UTF_8),
                    Credentials.create(acc.privKey.toHex()).ecKeyPair
            )
            val gtvEvmSig = gtv(
                    gtv(evmSig.r),
                    gtv(evmSig.s),
                    gtv(BigInteger(evmSig.v).longValueExact())
            )
            enqueueTx(registerAccount(acc.pubkey, acc.privKey, acc.evmAddress, gtvEvmSig, bcRid))
        }
        sealBlock()

        // query ft account id by evm address
        blockQuery = node.getBlockchainInstance().blockchainEngine.getBlockQueries()
        accountId = blockQuery.query("eif.evm.get_account_id_by_evm_address",
                gtv("acc" to gtv(userEvmAddress))).get()
        otherAccountId = blockQuery.query("eif.evm.get_account_id_by_evm_address",
                gtv("acc" to gtv(otherEvmAddress))).get()
        registerAccounts.forEach {
            it.accountId = blockQuery.query("eif.evm.get_account_id_by_evm_address", gtv("acc" to gtv(it.evmAddress))).get().asByteArray()
        }
    }


    @Test
    @Order(4)
    fun `deposit token on evm`() {
        logger.info { "deposit token on evm" }

        // Deposit token on EVM smart contract to bridge it to postchain
        for (i in 1..depositNum) {
            bridge.deposit(Address(testToken.contractAddress), Uint256(depositAmount)).send()
        }

        userBalance = testToken.balanceOf(Address(evmAddress)).send()
        assertEquals(userBalance.value, initialMint - totalDepositedAmount)

        // Check the asset balance
        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            sealBlock() // keep postchain mine new blocks to ensure that all evm deposits are recorded
            val balance = blockQuery.query("ft4.get_asset_balance", gtv("account_id" to accountId, "asset_id" to assetId)).get()["amount"]!!.asBigInteger()
            assertEquals(totalDepositedAmount, balance)
        }
        snapshotHeights.add(currentBlockHeight)

        // Check eif state for account as well
        val expectedState = SimpleGtvEncoder.encodeGtv(gtv(
                gtv(to32Bytes(evmAddress)), // encode gtv array with assumption that the data contains only byte32 and uint256
                gtv(1 * 2 * 32), // 2 * 32 bytes per entry
                gtv(to32Bytes(testToken.contractAddress.substring(2))), // encode gtv array with assumption that the data contains only byte32 and uint256
                gtv(totalDepositedAmount)
        ))
        val accounts = blockQuery.query("eif.data.get_network_accounts",
                gtv("network_id" to gtv(networkId))).get()
        accountNumber = accounts[0].asDict()["state_n"]!!

        val args = gtv(
                "blockHeight" to gtv(currentBlockHeight),
                "accountNumber" to gtv(accountNumber.asInteger())
        )
        val accountState = blockQuery.query("get_account_state_merkle_proof", args).get().asDict()

        val stateData = accountState["stateData"]!!
        assertEquals(expectedState.toHex(), stateData.asByteArray().toHex())
    }

    @Test
    @Order(5)
    fun `withdraw token to evm`() {
        logger.info { "withdraw token to evm" }

        // Bridge some ft token to evm
        val gtvAuthDescriptorId = blockQuery.query(
                "ft4.get_account_auth_descriptors",
                gtv("id" to accountId)
        ).get()[0]["id"]!!

        val auth = gtv(
                gtv(AuthType.S.ordinal.toLong()),
                gtv(GtvArray(arrayOf(gtv("A"), gtv("T"))), gtv(userPubkey)),
                GtvNull
        )

        authDescriptorId = auth.merkleHash(GtvMerkleHashCalculator(myCS))
        assertEquals(gtv(authDescriptorId), gtvAuthDescriptorId)
        authId = gtv(accountId, gtvAuthDescriptorId)

        withdrawAmount = BigInteger("1234567890", 16)
        enqueueTx(withdrawOnPostchain(userPubkey, userPriKey, authId, testTokenAddress, userEvmAddress, withdrawAmount, bcRid))
        sealBlock()
        snapshotHeights.add(currentBlockHeight)

        // Check eif state for account after withdraw as well
        val expectedState1 = SimpleGtvEncoder.encodeGtv(gtv(
                gtv(to32Bytes(evmAddress)), // encode gtv array with assumption that the data contains only byte32 and uint256
                gtv(1 * 2 * 32), // 2 * 32 bytes per entry
                gtv(to32Bytes(testToken.contractAddress.substring(2))), // encode gtv array with assumption that the data contains only byte32 and uint256
                gtv(totalDepositedAmount - withdrawAmount)
        ))
        val arg1 = gtv(
                "blockHeight" to gtv(currentBlockHeight),
                "accountNumber" to gtv(accountNumber.asInteger())
        )
        val accountState1 = blockQuery.query("get_account_state_merkle_proof", arg1).get().asDict()

        val stateData1 = accountState1["stateData"]!!
        assertEquals(expectedState1.toHex(), stateData1.asByteArray().toHex())

        val balance = blockQuery.query("ft4.get_asset_balance",
                gtv("account_id" to accountId, "asset_id" to assetId)).get()["amount"]!!.asBigInteger()
        assertEquals(totalDepositedAmount - withdrawAmount, balance)

        // Get and verify the withdrawal data
        val withdrawInfo = getLastWithdrawal(userEvmAddress)
        assertEquals(withdrawInfo["amount"]!!.asBigInteger(), withdrawAmount)
        val serial = withdrawInfo["serial"]!!.asInteger()

        // Query to get the event proof to withdraw fund on evm
        val eventData = gtv(
                gtv(serial),
                gtv(networkId),
                gtv(to32Bytes(testToken.contractAddress.substring(2))),
                gtv(to32Bytes(evmAddress)),
                gtv(withdrawAmount)
        )
        val encodedEventData = SimpleGtvEncoder.encodeGtv(eventData)
        val eventHash = ds.digest(encodedEventData)
        val eventProof = blockQuery.query("get_event_merkle_proof",
                gtv("eventHash" to gtv(eventHash.toHex()))
        ).get().toObject<EventMerkleProof>()
        assertArrayEquals(encodedEventData, eventProof.eventData)

        // Trying to send withdrawRequest without setting blockchain RID
        logger.info { "\tcan't withdraw without setting blockchain RID" }
        val exception = assertThrows<TransactionException> {
            bridge.withdrawRequest(
                    eventProof.web3EventData(),
                    eventProof.web3EventProof(),
                    eventProof.web3BlockHeader(),
                    eventProof.web3Signatures(),
                    eventProof.web3Signers(),
                    eventProof.web3ExtraProofData()
            ).send()
        }
        assertEquals(exception.message!!.contains("TokenBridge: blockchain rid is not set"), true)
        bridge.setBlockchainRid(Bytes32(bcRid.data)).send()

        // Updating validators
        logger.info { "\tcan't withdraw using the confirmation proof built before the validator list was changed" }
        updateValidatorsInPostchain()
        updateValidatorsInValidatorContract()
        val exception2 = assertThrows<TransactionException> {
            bridge.withdrawRequest(
                    eventProof.web3EventData(),
                    eventProof.web3EventProof(),
                    eventProof.web3BlockHeader(),
                    eventProof.web3Signatures(),
                    eventProof.web3Signers(),
                    eventProof.web3ExtraProofData()
            ).send()
        }
        assertEquals(exception2.message!!.contains("TokenBridge: block signature is invalid"), true)

        // Building a new withdrawal confirmation proof
        logger.info { "\tbuilding a new withdrawal confirmation proof using the new validator list" }
        val eventBlockHeight = blockQuery.query("get_event_block_height",
                gtv("eventHash" to gtv(eventHash.toHex()))
        ).get().asInteger()

        val blockRid = nodes[0].getRestApiModel(bcRid)?.getBlock(eventBlockHeight, true)!!.rid
        val signature0 = nodes[0].getRestApiModel(bcRid)?.confirmBlock(BlockRid(blockRid))!!
        val signature1 = nodes[1].getRestApiModel(bcRid)?.confirmBlock(BlockRid(blockRid))!!
        val signatures = listOf(
                EifSignature(
                        encodeSignatureWithV(blockRid, Signature(signature0.subjectID, signature0.data)),
                        getEthereumAddress(signature0.subjectID)),
                EifSignature(
                        encodeSignatureWithV(blockRid, Signature(signature1.subjectID, signature1.data)),
                        getEthereumAddress(signature1.subjectID))
        ).sortedBy { it.pubkey.toHex() }
        val eventProof2 = eventProof.copy(blockWitness = signatures)

        logger.info { "\trequesting withdrawal using the new confirmation proof" }
        val receipt = bridge.withdrawRequest(
                eventProof2.web3EventData(),
                eventProof2.web3EventProof(),
                eventProof2.web3BlockHeader(),
                eventProof2.web3Signatures(),
                eventProof2.web3Signers(),
                eventProof2.web3ExtraProofData()
        ).send()
        // wait some seconds to allow evm node to mine some new blocks
        // that mature enough to withdraw requesting fund
        Awaitility.await().atMost(Duration.TEN_SECONDS).until {
            val block = web3j.ethGetBlockByNumber(DefaultBlockParameter.valueOf(receipt.blockNumber.add(BigInteger.TWO)), false).send()
            block.block != null
        }
        bridge.withdraw(Bytes32(eventHash), Address(evmAddress)).send()
        userBalance = testToken.balanceOf(Address(evmAddress)).send()
        assertEquals(userBalance.value, initialMint - totalDepositedAmount + withdrawAmount)
    }

    @Test
    @Order(6)
    fun `transfer ft token to another account`() {
        logger.info { "transfer ft token to another account" }

        // Transfer ft token to another account
        val toOtherAccounts = accountNum.toBigInteger() * accountBalance.toBigInteger()
        toRemainingAccount = totalTransferAmount - toOtherAccounts

        enqueueTx(transfer(userPubkey, userPriKey, accountId, authDescriptorId, otherAccountId, assetId, toRemainingAccount, bcRid))
        sealBlock()
        snapshotHeights.add(currentBlockHeight)
        registerAccounts.forEach {
            enqueueTx(transfer(userPubkey, userPriKey, accountId, authDescriptorId, gtv(it.accountId), assetId, it.balance.toBigInteger(), bcRid))
        }
        sealBlock()
        snapshotHeights.add(currentBlockHeight)

        enqueueTx(withdrawOnPostchain(userPubkey, userPriKey, authId, testTokenAddress, userEvmAddress, withdrawAmount, bcRid))
        sealBlock()
        snapshotHeights.add(currentBlockHeight)
    }

    @Test
    @Order(7)
    fun `trigger mass exit`() {
        logger.info { "trigger mass exit" }

        // Get the last snapshot block height as mass-exit block
        lastSnapshotBlockHeight = currentBlockHeight
        var lastBlockRID: ByteArray? = null
        while (lastSnapshotBlockHeight >= 0) {
            val block = blockQuery.getBlockAtHeight(lastSnapshotBlockHeight, false).get()
            val header = block!!.header.rawData.toHex()
            if (header.takeLast(64) != "0".repeat(64)) {
                lastBlockRID = block.header.blockRID
                break
            }
            lastSnapshotBlockHeight--
        }

        assertNotNull(lastBlockRID, "There should be valid block for mass-exit")
        bridge.triggerMassExit(Uint256(lastSnapshotBlockHeight), Bytes32(lastBlockRID)).send()
    }

    @Test
    @Order(8)
    fun `withdraw after mass exit`() {
        logger.info { "withdraw token to evm after mass exit" }

        // Withdraw request on evm for the last postchain withdraw
        val withdrawInfo2 = getLastWithdrawal(userEvmAddress)
        assertEquals(withdrawInfo2["amount"]!!.asBigInteger(), withdrawAmount)
        val serial2 = withdrawInfo2["serial"]!!.asInteger()

        // Query to get the event proof to withdraw fund on evm
        val eventData2 = gtv(
                gtv(serial2),
                gtv(networkId),
                gtv(to32Bytes(testToken.contractAddress.substring(2))),
                gtv(to32Bytes(evmAddress)),
                gtv(withdrawAmount)
        )
        val encodedEventData2 = SimpleGtvEncoder.encodeGtv(eventData2)
        val eventHash2 = ds.digest(encodedEventData2)
        val eventProof2 = blockQuery.query("get_event_merkle_proof",
                gtv("eventHash" to gtv(eventHash2.toHex()))
        ).get().toObject<EventMerkleProof>()
        assertArrayEquals(encodedEventData2, eventProof2.eventData)

        val receipt = bridge.withdrawRequest(
                eventProof2.web3EventData(),
                eventProof2.web3EventProof(),
                eventProof2.web3BlockHeader(),
                eventProof2.web3Signatures(),
                eventProof2.web3Signers(),
                eventProof2.web3ExtraProofData()
        ).send()

        // wait some seconds to allow evm node to mine some new blocks
        // that mature enough to withdraw requesting fund
        Awaitility.await().atMost(Duration.TEN_SECONDS).until {
            val block = web3j.ethGetBlockByNumber(DefaultBlockParameter.valueOf(receipt.blockNumber.add(BigInteger.TWO)), false).send()
            block.block != null
        }
        bridge.withdraw(Bytes32(eventHash2), Address(evmAddress)).send()
        userBalance = testToken.balanceOf(Address(evmAddress)).send()
        assertEquals(userBalance.value, initialMint - totalDepositedAmount + (withdrawAmount * BigInteger.TWO))
    }

    @Test
    @Order(9)
    fun `withdraw token to evm after mass exit using snapshot`() {
        logger.info { "withdraw token to evm after mass exit using snapshot" }

        // Withdraw remaining token of the account by using snapshot state with mass-exit
        val stateProof = blockQuery.query(
                "get_account_state_merkle_proof",
                gtv(
                        "blockHeight" to gtv(lastSnapshotBlockHeight),
                        "accountNumber" to accountNumber
                )
        ).get().toObject<AccountStateMerkleProof>()

        bridge.withdrawBySnapshot(
                stateProof.web3StateData(),
                stateProof.web3StateProof(),
                stateProof.web3BlockHeader(),
                stateProof.web3Signatures(),
                stateProof.web3Signers(),
                stateProof.web3ExtraProofData()
        ).send()

        // Withdraw the remaining token balance of other account as well
        val otherAccountNumber = accountNumber.asInteger() + 1
        val otherState = blockQuery.query(
                "get_account_state_merkle_proof",
                gtv(
                        "blockHeight" to gtv(lastSnapshotBlockHeight),
                        "accountNumber" to gtv(otherAccountNumber)
                )
        ).get().toObject<AccountStateMerkleProof>()

        bridge.withdrawBySnapshot(
                otherState.web3StateData(),
                otherState.web3StateProof(),
                otherState.web3BlockHeader(),
                otherState.web3Signatures(),
                otherState.web3Signers(),
                otherState.web3ExtraProofData()
        ).send()

        enqueueTx(withdrawOnPostchain(userPubkey, userPriKey, authId, testTokenAddress, userEvmAddress, withdrawAmount, bcRid))
        sealBlock()
        snapshotHeights.add(currentBlockHeight)
    }

    @Test
    @Order(10)
    fun `user can't withdraw token to evm after mass exit block height`() {
        logger.info { "user can't withdraw token to evm after mass exit block height" }

        val withdrawInfo3 = getLastWithdrawal(userEvmAddress)
        assertThat(withdrawInfo3["amount"]!!.asBigInteger()).isEqualTo(withdrawAmount)
        val serial3 = withdrawInfo3["serial"]!!.asInteger()

        // Query to get the event proof to withdraw fund on evm
        val eventData3 = gtv(
                gtv(serial3),
                gtv(networkId),
                gtv(to32Bytes(testToken.contractAddress.substring(2))),
                gtv(to32Bytes(evmAddress)),
                gtv(withdrawAmount)
        )
        val encodedEventData3 = SimpleGtvEncoder.encodeGtv(eventData3)
        val eventHash3 = ds.digest(encodedEventData3)
        val eventProof3 = blockQuery.query("get_event_merkle_proof",
                gtv("eventHash" to gtv(eventHash3.toHex()))
        ).get().toObject<EventMerkleProof>()
        assertArrayEquals(encodedEventData3, eventProof3.eventData)

        // User cannot send withdraw request after the mass-exit block height
        val exception = assertThrows<TransactionException> {
            bridge.withdrawRequest(
                    eventProof3.web3EventData(),
                    eventProof3.web3EventProof(),
                    eventProof3.web3BlockHeader(),
                    eventProof3.web3Signatures(),
                    eventProof3.web3Signers(),
                    eventProof3.web3ExtraProofData()
            ).send()
        }
        assertThat(exception.message!!).contains("TokenBridge: cannot withdraw request after the mass exit block height")

        userBalance = testToken.balanceOf(Address(evmAddress)).send()
        assertEquals(userBalance.value, initialMint - totalTransferAmount)
        userBalance = testToken.balanceOf(Address(otherEvmAddressString)).send()
        assertEquals(userBalance.value, toRemainingAccount)

        assertEquals(6, snapshotHeights.size)
        // Because the snapshots to keep is 2 then the snapshot older height will not available
        // for the first account
        val oldSnapshotHeight = snapshotHeights[snapshotHeights.size - 3]
        val oldState = blockQuery.query("get_account_state_merkle_proof",
                gtv(
                        "blockHeight" to gtv(oldSnapshotHeight),
                        "accountNumber" to accountNumber
                )).get()
        assertEquals(oldState, GtvNull)

        // account state is still available on the latest snapshot
        val latestSnapshotHeight = snapshotHeights[snapshotHeights.size - 1]
        val latestState = blockQuery.query("get_account_state_merkle_proof",
                gtv(
                        "blockHeight" to gtv(latestSnapshotHeight),
                        "accountNumber" to accountNumber
                )).get()
        assertNotNull(latestState)
    }

    /**
     * convert evm address to 32 bytes to compliance with EIF simple gtv encoder
     * @see SimpleGtvEncoder.encodeGtv
     */
    private fun to32Bytes(address: String) = "000000000000000000000000$address".hexStringToByteArray()

    // Register asset on postchain
    private fun registerAsset(tokenName: String, tokenSymbol: String, tokenDecimal: Long, tokenIconUrl: String, bcRid: BlockchainRid, sigMaker: SigMaker): ByteArray {
        val b = GtxBuilder(bcRid, listOf(KeyPairHelper.pubKey(0)), myCS)
        b.addOperation("ft4.admin.register_asset",
                gtv(tokenName), gtv(tokenSymbol), gtv(tokenDecimal), gtv(tokenIconUrl))
        return b.finish()
                .sign(sigMaker)
                .buildGtx()
                .encode()
    }

    // Add new evm erc20 token
    private fun addNewEvmErc20(tokenAddress: ByteArray, name: String, symbol: String, decimal: Long, bcRid: BlockchainRid, sigMaker: SigMaker): ByteArray {
        val b = GtxBuilder(bcRid, listOf(KeyPairHelper.pubKey(0)), myCS)
        b.addOperation("eif.ft4.add_new_evm_erc20", gtv(networkId), gtv(tokenAddress), gtv(name), gtv(symbol), gtv(decimal))
        return b.finish()
                .sign(sigMaker)
                .buildGtx()
                .encode()
    }

    // Add new token mapping
    private fun addTokenMapping(tokenAddress: ByteArray, assetId: Gtv, bcRid: BlockchainRid, sigMaker: SigMaker): ByteArray {
        val b = GtxBuilder(bcRid, listOf(KeyPairHelper.pubKey(0)), myCS)
        b.addOperation("eif.ft4.add_new_token_mapping", gtv(networkId), gtv(tokenAddress), assetId)
        return b.finish()
                .sign(sigMaker)
                .buildGtx()
                .encode()
    }

    // Register account on postchain
    private fun registerAccount(userPubkey: ByteArray, userPriKey: ByteArray, userEVMAddress: ByteArray, sig: GtvArray, bcRid: BlockchainRid): ByteArray {
        val auth = gtv(
                gtv(AuthType.S.ordinal.toLong()),
                gtv(GtvArray(arrayOf(gtv("A"), gtv("T"))), gtv(userPubkey)),
                GtvNull
        )

        val b = GtxBuilder(bcRid, listOf(userPubkey), myCS)
        b.addOperation("eif.evm.register_account", gtv(userEVMAddress), auth, sig)

        val signer = cryptoSystem.buildSigMaker(KeyPair(userPubkey, userPriKey))
        return b.finish()
                .sign(signer)
                .buildGtx()
                .encode()
    }

    // Withdraw ft4 token on postchain
    private fun withdrawOnPostchain(userPubkey: ByteArray, userPriKey: ByteArray,
                                    authId: Gtv, tokenAddress: ByteArray,
                                    userEvmAddress: ByteArray, withdrawAmount: BigInteger, bcRid: BlockchainRid): ByteArray {
        val b = GtxBuilder(bcRid, listOf(userPubkey), myCS)
        b.addOperation("eif.ft4.bridge_ft_token_to_evm", authId, gtv(networkId), gtv(tokenAddress), gtv(userEvmAddress), gtv(withdrawAmount))
        b.addOperation("nop", GtvInteger(System.currentTimeMillis()))
        val signer = cryptoSystem.buildSigMaker(KeyPair(userPubkey, userPriKey))
        return b.finish()
                .sign(signer)
                .buildGtx()
                .encode()
    }

    // Transfer ft4 token to another account
    private fun transfer(userPubkey: ByteArray, userPriKey: ByteArray,
                         accountId: Gtv, authDescriptorId: Hash, otherAccountId: Gtv,
                         assetId: Gtv, transferAmount: BigInteger, bcRid: BlockchainRid): ByteArray {
        val b = GtxBuilder(bcRid, listOf(userPubkey), myCS)
        b.addOperation("ft4.ft_auth", accountId, gtv(authDescriptorId))
        b.addOperation("ft4.transfer", otherAccountId, assetId, gtv(transferAmount))

        val signer = cryptoSystem.buildSigMaker(KeyPair(userPubkey, userPriKey))
        return b.finish()
                .sign(signer)
                .buildGtx()
                .encode()
    }

    fun sealBlock() {
        currentBlockHeight += 1
        buildBlock(DEFAULT_CHAIN_IID)
        assertEquals(currentBlockHeight, getLastHeight(node))
    }

    fun enqueueTx(data: ByteArray) {
        try {
            // In a multi-node environment, we need to add tx to each node's txQueue
            // to ensure that the tx will be included in the next block.
            nodes.forEach {
                val engine = it.getBlockchainInstance(DEFAULT_CHAIN_IID).blockchainEngine
                val tx = engine.getConfiguration().getTransactionFactory().decodeTransaction(data)
                engine.getTransactionQueue().enqueue(tx)
            }
        } catch (e: Exception) {
            logger.error(e) { "Can't enqueue tx" }
        }
    }

    private fun getRegisterMessage(evmAddress: String, disposableKey: String) = "Create account for EVM wallet:\n${evmAddress}\n\nDisposable key:\n${disposableKey}"

    protected fun updateValidatorsInPostchain() {
        val lastBlockHeight = getLastHeight(node)
        // replica node[1] becomes a validator
        val newSigners = listOf(0, 1).associateWith { nodes[it].pubKey.hexStringToByteArray() }
        val newConfig = GtvMLParser.parseGtvML(
                javaClass.getResource("/net/postchain/eif/blockchain_config_it.xml")!!.readText()
        ).asDict().toMutableMap()
        newConfig[KEY_SIGNERS] = gtv(newSigners.values.map { gtv(it) })

        // adding a new config at height (last + 2)
        // (last + 1) will not work because (last + 1) config already loaded by afterCommit handler
        val newConfigHeight = lastBlockHeight + 2
        val newRawConfig = GtvEncoder.encodeGtv(gtv(newConfig))
        addDappBlockchainConfiguration(chainId, newRawConfig, newConfigHeight)

        // building at least two blocks to build a block with new signers
        sealBlock()
        sealBlock()

        // asserting that new config is loaded
        val witness = node.blockQueries().getBlockAtHeight(newConfigHeight).get()!!.witness as BaseBlockWitness
        val signers = witness.getSignatures().map { it.subjectID.toHex() }.sorted().toTypedArray()
        assertArrayEquals(
                listOf(nodes[0].pubKey, nodes[1].pubKey).sorted().toTypedArray(),
                witness.getSignatures().map { it.subjectID.toHex() }.sorted().toTypedArray()
        )
        blockQuery = node.getBlockchainInstance().blockchainEngine.getBlockQueries()
    }

    // This function emulates validator list updating by TransactionSubmitter
    protected fun updateValidatorsInValidatorContract() {
        // getting the current validator list
        val currentValidators = getContractValidatorList()

        // asserting that node0 is the only validator
        assertArrayEquals(arrayOf(node0EvmAddress), currentValidators.toTypedArray())

        // setting the new validator list: [node0, node1]
        val newValidators = listOf(node0EvmAddress, node1EvmAddress).sortedBy { it.value }.toTypedArray()
        val validatorsArg = DynamicArray(Address::class.java, *newValidators)
        validator.updateValidators(validatorsArg).send()

        // asserting that new validator list is set
        assertArrayEquals(newValidators, getContractValidatorList().toTypedArray())
    }

    protected fun getContractValidatorList(): List<Address> {
        val count = validator.validatorCount.send().value.toLong()
        val validators = mutableListOf<Address>()
        (0 until count).forEach {
            validators.add(validator.validators(Uint256(it)).send())
        }
        return validators
    }

    protected fun getLastWithdrawal(beneficiary: ByteArray): Map<String, Gtv> {
        val all = blockQuery.query("eif.ft4.get_erc20_withdrawal", gtv(
                "network_id" to gtv(networkId),
                "token_address" to gtv(testTokenAddress),
                "beneficiary" to gtv(beneficiary)
        )).get().asArray()

        return all.map { it.asDict() }.maxByOrNull { it["serial"]!!.asInteger() }!!
    }
}