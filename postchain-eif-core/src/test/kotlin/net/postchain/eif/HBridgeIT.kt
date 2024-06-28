package net.postchain.eif

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
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
import net.postchain.crypto.PrivKey
import net.postchain.crypto.PubKey
import net.postchain.crypto.devtools.KeyPairHelper
import net.postchain.devtools.PostchainTestNode
import net.postchain.devtools.PostchainTestNode.Companion.DEFAULT_CHAIN_IID
import net.postchain.eif.contracts.TestToken
import net.postchain.eif.contracts.TokenBridgeWithSnapshotWithdraw
import net.postchain.eif.contracts.Validator
import net.postchain.eif.transaction.TransactionSubmitter
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertThrows
import org.junitpioneer.jupiter.DisableIfTestFails
import org.testcontainers.junit.jupiter.Testcontainers
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.crypto.Credentials
import org.web3j.crypto.Sign
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.exceptions.TransactionException
import org.web3j.tx.Contract
import org.web3j.tx.FastRawTransactionManager
import org.web3j.tx.Transfer
import org.web3j.tx.response.PollingTransactionReceiptProcessor
import org.web3j.utils.Convert
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@DisableIfTestFails
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HBridgeIT : EifBaseIntegrationTest() {
    private val logger = KotlinLogging.logger("test_logger")

    private lateinit var ds: SimpleDigestSystem
    private val hashCalculator = GtvMerkleHashCalculator(myCS)

    private val accountNum = 15
    private val accountBalance = 1L

    private val adminKeyPair = KeyPair(KeyPairHelper.pubKey(0), KeyPairHelper.privKey(0))

    // user
    private val userEvmAddress = "e105ba42b66d08ac7ca7fc48c583599044a6dab3"
    private val userKeyPair = KeyPair(PubKey("038f888dec563b5bc253e87abc90afd26c3287021d10236ea19d248043dc39e0b8".hexStringToByteArray()),
            PrivKey("71b5b7f8de0661af934a5e4612f3d0ba183e639bdf4e7452fb6457ed3cfbc825".hexStringToByteArray()))

    // TODO: use getEvmAddress
    private val node0EvmAddress = Address("659e4a3726275edFD125F52338ECe0d54d15BD99")
    private val node1EvmAddress = Address("2c3fA9C9FC3C5CB2f9C09aF6f7214f64382eA086")

    // other
    private val otherPubkey = "02E0A8A3C79C9F18B7CEAD2493435AC926B4A527EF670B873F5F1410084EFF9C80".hexStringToByteArray()
    private val otherEvmAddress = "661683e5d36E83B38B1a20247ba6F5c410dC165d"

    private val initialMint = BigInteger("FF".repeat(32), 16)
    private val depositAmount = BigInteger("AA".repeat(16), 16)
    private val totalTransferAmount = BigInteger("1234567890ABCDEF", 16)
    private lateinit var toRemainingAccount: BigInteger
    private lateinit var validator: Validator
    private lateinit var bridge: TokenBridgeWithSnapshotWithdraw
    private lateinit var testToken: TestToken
    private lateinit var testTokenAddress: ByteArray
    private lateinit var userBalance: Uint256
    private lateinit var withdrawAmount: BigInteger
    private lateinit var accountId: ByteArray
    private var accountNumber: Long = -1
    private lateinit var authDescriptorId: Hash
    private lateinit var otherAccountId: ByteArray
    private lateinit var assetId: ByteArray
    private lateinit var node: PostchainTestNode
    private lateinit var blockQuery: BlockQueries
    private var chainId = -1L
    private lateinit var bcRid: BlockchainRid
    private var currentBlockHeight = 0L
    private var lastSnapshotBlockHeight = -1L

    @BeforeAll
    fun setupBeforeAll() {
        super.setup()

        ds = SimpleDigestSystem(MessageDigest.getInstance(KECCAK256))

        with(configOverrides) {
            setProperty("infrastructure", net.postchain.devtools.testinfra.BaseTestInfrastructureFactory::class.qualifiedName)
            setProperty("ethereum.maxReadAhead", 200)
            setProperty("ethereum.maxQueueSize", 100)
            setProperty("evm.maxTryErrors", 1)
        }
    }

    @BeforeEach
    override fun setup() {
        // This method blocks @BeforeEach in EifBaseIntegrationTest.setup()
    }

    @AfterAll
    fun tearDownAfterAll() {
        super.tearDown() // Calling @AfterEach IntegrationTestSetup.tearDown()
        web3j.shutdown()
        evmContainer.stop()
    }

    @AfterEach
    override fun tearDown() {
        // This method blocks @AfterEach EifBaseIntegrationTest.tearDown()
    }

    @Test
    @Order(1)
    fun `deploy contracts`() {
        logger.info { "deploy contracts" }

        // Deploy validator contract
        val encodedConstructor = FunctionEncoder.encodeConstructor(listOf(DynamicArray(Address::class.java, node0EvmAddress)))
        validator = Contract.deployRemoteCall(Validator::class.java, web3j, transactionManager, gasProvider, validatorBinary, encodedConstructor).send()

        // Deploy token bridge contract
        bridge = Contract.deployRemoteCall(TokenBridgeWithSnapshotWithdraw::class.java, web3j, transactionManager, gasProvider, tokenBridgeWithSnapshotWithdrawBinary, "").send().apply {
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
        val chainGtvConfig = loadEifBlockchainConfig()
        chainId = startNewBlockchain(
                setOf(0), setOf(1), rawBlockchainConfiguration = GtvEncoder.encodeGtv(chainGtvConfig)
        )
        buildBlock(chainId)
        node = nodes[0]
        bcRid = node.getBlockchainInstance(chainId).blockchainEngine.blockchainRid
        logger.info { "Chain deployed: chainId: $chainId, blockchainRid: $bcRid" }
        blockQuery = node.getBlockchainInstance().blockchainEngine.getBlockQueries()

        val apiVersion = node.getBlockchainInstance().blockchainEngine.getBlockQueries()
                .query("eif.api_version", gtv(emptyMap())).get().asInteger()
        logger.info { "EIF API version: $apiVersion" }
    }

    @Test
    @Order(3)
    fun `register ft accounts`() {
        logger.info { "register ft accounts" }

        val tokenName = "Chromia"
        val tokenSymbol = "CHR"
        val tokenDecimal = 18L
        val tokenIconUrl = "https://chromaway.com/chr"

        enqueueTx(registerAsset(tokenName, tokenSymbol, tokenDecimal, tokenIconUrl, bcRid, adminKeyPair))
        sealBlock()

        val value = node.getBlockchainInstance().blockchainEngine.getBlockQueries()
                .query("ft4.get_assets_by_name", gtv(
                        "name" to gtv(tokenName),
                        "page_size" to gtv(1L),
                        "page_cursor" to GtvNull
                )).get()
        assetId = value["data"]?.get(0)?.get("id")!!.asByteArray()

        enqueueTx(registerERC20Asset(testTokenAddress, assetId, bcRid, adminKeyPair))

        val (accountId1, authDescriptorId1) = registerAccount(userKeyPair.pubKey.data, bcRid, adminKeyPair)
        accountId = accountId1
        linkAccount(userKeyPair, accountId1, authDescriptorId1, userEvmAddress.hexStringToByteArray(), credentials, bcRid)

        otherAccountId = registerAccount(otherPubkey, bcRid, adminKeyPair).first

        for (i in 1..accountNum) {
            val acc = AccountRegister(
                    ByteArray(32),
                    KeyPairHelper.privKey(i),
                    KeyPairHelper.pubKey(i),
                    getEthereumAddress(KeyPairHelper.pubKey(i)),
                    accountBalance
            )
            registerAccounts.add(acc)
            acc.accountId = registerAccount(acc.pubkey, bcRid, adminKeyPair).first
        }
        sealBlock()
    }

    @Test
    @Order(4)
    fun `deposit token on evm`() {
        logger.info { "deposit token on evm" }

        // Deposit token on EVM smart contract to bridge it to postchain
        bridge.deposit(Address(testToken.contractAddress), Uint256(depositAmount)).send()

        userBalance = testToken.balanceOf(Address(userEvmAddress)).send()
        assertEquals(userBalance.value, initialMint - depositAmount)

        // Check the asset balance
        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            sealBlock() // keep postchain build new blocks to ensure that all evm deposits are recorded
            val gtvBalance = blockQuery.query("ft4.get_asset_balance", gtv("account_id" to gtv(accountId), "asset_id" to gtv(assetId)))
                    .get()
            assertThat(gtvBalance).isNotEqualTo(GtvNull)
            val balance = gtvBalance["amount"]!!.asBigInteger()
            assertEquals(depositAmount, balance)
        }
        snapshotHeights.add(currentBlockHeight)

        /*
        // Check eif state for account as well
        val expectedState = SimpleGtvEncoder.encodeGtv(gtv(
                gtv(to32Bytes(evmAddress)), // encode gtv array with assumption that the data contains only byte32 and uint256
                gtv(1 * 2 * 32), // 2 * 32 bytes per entry
                gtv(to32Bytes(testToken.contractAddress.substring(2))), // encode gtv array with assumption that the data contains only byte32 and uint256
                gtv(totalDepositedAmount)
        ))
        val accounts = blockQuery.query("eif.data.get_network_accounts", // we don't have this any longer
                gtv("network_id" to gtv(networkId))).get()
        accountNumber = accounts[0].asDict()["state_n"]!!

        val args = gtv(
                "blockHeight" to gtv(currentBlockHeight),
                "accountNumber" to gtv(accountNumber.asInteger())
        )
        val accountState = blockQuery.query("get_account_state_merkle_proof", args).get().asDict()

        val stateData = accountState["stateData"]!!
        assertEquals(expectedState.toHex(), stateData.asByteArray().toHex())
         */
    }

    @Test
    @Order(5)
    fun `withdraw token to evm`() {
        logger.info { "withdraw token to evm" }

        // Bridge some ft token to evm
        val gtvAuthDescriptorId = blockQuery.query(
                "ft4.get_account_auth_descriptors",
                gtv("id" to gtv(accountId))
        ).get()[0]["id"]!!

        val auth = gtv(
                gtv(AuthType.S.ordinal.toLong()),
                gtv(GtvArray(arrayOf(gtv("A"), gtv("T"))), gtv(userKeyPair.pubKey.data)),
                GtvNull
        )

        authDescriptorId = auth.merkleHash(hashCalculator)
        assertEquals(gtv(authDescriptorId), gtvAuthDescriptorId)

        withdrawAmount = BigInteger("1234567890", 16)
        val txRid = withdrawOnPostchain(userKeyPair, accountId, authDescriptorId, assetId, withdrawAmount, userEvmAddress.hexStringToByteArray(), bcRid)
        sealBlock()
        snapshotHeights.add(currentBlockHeight)

        /*
        // Check eif state for account after withdraw as well
        val expectedState1 = SimpleGtvEncoder.encodeGtv(gtv(
                gtv(to32Bytes(userEvmAddress)), // encode gtv array with assumption that the data contains only byte32 and uint256
                gtv(1 * 2 * 32), // 2 * 32 bytes per entry
                gtv(to32Bytes(testToken.contractAddress.substring(2))), // encode gtv array with assumption that the data contains only byte32 and uint256
                gtv(depositAmount - withdrawAmount)
        ))
        val arg1 = gtv(
                "blockHeight" to gtv(currentBlockHeight),
                "accountNumber" to gtv(accountNumber)
        )
        val accountState1 = blockQuery.query("get_account_state_merkle_proof", arg1).get().asDict()
        val stateData1 = accountState1["stateData"]!!
        assertEquals(expectedState1.toHex(), stateData1.asByteArray().toHex())
        */

        val balance = blockQuery.query("ft4.get_asset_balance",
                gtv("account_id" to gtv(accountId), "asset_id" to gtv(assetId))).get()["amount"]!!.asBigInteger()
        assertEquals(depositAmount - withdrawAmount, balance)

        // Get and verify the withdrawal data
        val withdrawInfo = getLastWithdrawal(userEvmAddress.hexStringToByteArray())
        assertEquals(withdrawInfo["amount"]!!.asBigInteger(), withdrawAmount)
        val serial = withdrawInfo["serial"]!!.asInteger()

        // Query to get the event proof to withdraw fund on evm
        val eventData = gtv(
                gtv(serial),
                gtv(networkId),
                gtv(to32Bytes(testToken.contractAddress.substring(2))),
                gtv(to32Bytes(userEvmAddress)),
                gtv(withdrawAmount)
        )
        val encodedEventData = SimpleGtvEncoder.encodeGtv(eventData)
        val eventHash = ds.digest(encodedEventData)

        // or get evenHash by txRid:
        val eventHash2 = getWithdrawalEventHashByTxRid(txRid)
        assertEquals(eventHash.toHex(), eventHash2.toHex())

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
        val newBlockWitness = listOf(
                nodes[0].getRestApiModel(bcRid)?.confirmBlock(BlockRid(blockRid))!!,
                nodes[1].getRestApiModel(bcRid)?.confirmBlock(BlockRid(blockRid))!!
        )

        val eventProof2 = blockQuery.query("get_event_merkle_proof", gtv(
                "eventHash" to gtv(eventHash.toHex()),
                "signers" to gtv(newBlockWitness.map { gtv(it.subjectID) }),
                "signatures" to gtv(newBlockWitness.map { gtv(it.data) }),
        )).get().toObject<EventMerkleProof>()

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
        bridge.withdraw(Bytes32(eventHash), Address(userEvmAddress)).send()
        userBalance = testToken.balanceOf(Address(userEvmAddress)).send()
        assertEquals(userBalance.value, initialMint - depositAmount + withdrawAmount)
    }

    @Test
    @Order(6)
    fun `multiple withdraws per block`() {
        // Multiple withdraws in same block to get multi-level page tree
        val multiWithdrawAmount = BigInteger.ONE
        val multiWithdrawTimes = 30
        logger.info { "withdraw token $multiWithdrawTimes times in one block" }
        repeat(multiWithdrawTimes - 1) {
            withdrawOnPostchain(userKeyPair, accountId, authDescriptorId, assetId, multiWithdrawAmount, userEvmAddress.hexStringToByteArray(), bcRid)
            Thread.sleep(1) // To get unique nop
        }
        val lastMultiTxRid = withdrawOnPostchain(userKeyPair, accountId, authDescriptorId, assetId, multiWithdrawAmount, userEvmAddress.hexStringToByteArray(), bcRid)
        sealBlock()
        val balanceAfterMultiWithdraw = blockQuery.query("ft4.get_asset_balance",
                gtv("account_id" to gtv(accountId), "asset_id" to gtv(assetId))).get()["amount"]!!.asBigInteger()
        assertEquals(depositAmount - withdrawAmount - BigInteger.valueOf(multiWithdrawTimes.toLong()) * multiWithdrawAmount, balanceAfterMultiWithdraw)

        // Just completing the last one on EVM side
        val lastMultiEventHash = getWithdrawalEventHashByTxRid(lastMultiTxRid)

        val lastMultiEventProof = blockQuery.query("get_event_merkle_proof",
                gtv("eventHash" to gtv(lastMultiEventHash.toHex()))
        ).get().toObject<EventMerkleProof>()
        logger.info { "\trequesting withdrawal of the last withdraw made" }
        val lastMultiReceipt = bridge.withdrawRequest(
                lastMultiEventProof.web3EventData(),
                lastMultiEventProof.web3EventProof(),
                lastMultiEventProof.web3BlockHeader(),
                lastMultiEventProof.web3Signatures(),
                lastMultiEventProof.web3Signers(),
                lastMultiEventProof.web3ExtraProofData()
        ).send()
        // wait some seconds to allow evm node to mine some new blocks
        // that mature enough to withdraw requesting fund
        Awaitility.await().atMost(Duration.TEN_SECONDS).until {
            val block = web3j.ethGetBlockByNumber(DefaultBlockParameter.valueOf(lastMultiReceipt.blockNumber.add(BigInteger.TWO)), false).send()
            block.block != null
        }
        bridge.withdraw(Bytes32(lastMultiEventHash), Address(userEvmAddress)).send()
        userBalance = testToken.balanceOf(Address(userEvmAddress)).send()
        assertEquals(userBalance.value, initialMint - depositAmount + withdrawAmount + multiWithdrawAmount)

        logger.info { "\tmaking a new withdrawal in next block" }
        val nextBlockWithdraw = withdrawOnPostchain(userKeyPair, accountId, authDescriptorId, assetId, multiWithdrawAmount, userEvmAddress.hexStringToByteArray(), bcRid)
        sealBlock()

        val balanceAfterNextBlockWithdraw = blockQuery.query("ft4.get_asset_balance",
                gtv("account_id" to gtv(accountId), "asset_id" to gtv(assetId))).get()["amount"]!!.asBigInteger()
        assertEquals(depositAmount - withdrawAmount - BigInteger.valueOf(multiWithdrawTimes.toLong() + 1L) * multiWithdrawAmount, balanceAfterNextBlockWithdraw)

        val nextBlockEventHash = getWithdrawalEventHashByTxRid(nextBlockWithdraw)

        val nextBlockEventProof = blockQuery.query("get_event_merkle_proof",
                gtv("eventHash" to gtv(nextBlockEventHash.toHex()))
        ).get().toObject<EventMerkleProof>()
        logger.info { "\trequesting withdrawal again" }
        val nextBlockReceipt = bridge.withdrawRequest(
                nextBlockEventProof.web3EventData(),
                nextBlockEventProof.web3EventProof(),
                nextBlockEventProof.web3BlockHeader(),
                nextBlockEventProof.web3Signatures(),
                nextBlockEventProof.web3Signers(),
                nextBlockEventProof.web3ExtraProofData()
        ).send()

        Awaitility.await().atMost(Duration.TEN_SECONDS).until {
            val block = web3j.ethGetBlockByNumber(DefaultBlockParameter.valueOf(nextBlockReceipt.blockNumber.add(BigInteger.TWO)), false).send()
            block.block != null
        }
        bridge.withdraw(Bytes32(nextBlockEventHash), Address(userEvmAddress)).send()
        userBalance = testToken.balanceOf(Address(userEvmAddress)).send()
        assertEquals(userBalance.value, initialMint - depositAmount + withdrawAmount + multiWithdrawAmount * BigInteger.TWO)
    }

    @Test
    @Order(7)
    fun `transfer ft token to another account`() {
        logger.info { "transfer ft token to another account" }

        // Transfer ft token to another account
        val toOtherAccounts = accountNum.toBigInteger() * accountBalance.toBigInteger()
        toRemainingAccount = totalTransferAmount - toOtherAccounts

        enqueueTx(transfer(userKeyPair, accountId, authDescriptorId, otherAccountId, assetId, toRemainingAccount, bcRid))
        sealBlock()
        snapshotHeights.add(currentBlockHeight)
        registerAccounts.forEach {
            enqueueTx(transfer(userKeyPair, accountId, authDescriptorId, it.accountId, assetId, it.balance.toBigInteger(), bcRid))
        }
        sealBlock()
        snapshotHeights.add(currentBlockHeight)

        withdrawOnPostchain(userKeyPair, accountId, authDescriptorId, assetId, withdrawAmount, userEvmAddress.hexStringToByteArray(), bcRid)
        sealBlock()
        snapshotHeights.add(currentBlockHeight)
    }

    @Test
    @Disabled
    @Order(8)
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
    @Disabled
    @Order(9)
    fun `withdraw after mass exit`() {
        logger.info { "withdraw token to evm after mass exit" }

        // Withdraw request on evm for the last postchain withdraw
        val withdrawInfo2 = getLastWithdrawal(userEvmAddress.hexStringToByteArray())
        assertEquals(withdrawInfo2["amount"]!!.asBigInteger(), withdrawAmount)

        // Get the withdrawal event hash
        val eventHash2 = getWithdrawalEventHashByTxRid(withdrawInfo2["event_hash"]!!.asByteArray())

        // Query to get the event proof to withdraw fund on evm
        val eventProof2 = blockQuery.query("get_event_merkle_proof",
                gtv("eventHash" to gtv(eventHash2.toHex()))
        ).get().toObject<EventMerkleProof>()

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
        bridge.withdraw(Bytes32(eventHash2), Address(userEvmAddress)).send()
        userBalance = testToken.balanceOf(Address(userEvmAddress)).send()
        assertEquals(userBalance.value, initialMint - depositAmount + (withdrawAmount * BigInteger.TWO))
    }

    @Test
    @Disabled
    @Order(10)
    fun `withdraw token to evm after mass exit using snapshot`() {
        logger.info { "withdraw token to evm after mass exit using snapshot" }

        // Withdraw remaining token of the account by using snapshot state with mass-exit
        val stateProof = blockQuery.query(
                "get_account_state_merkle_proof",
                gtv(
                        "blockHeight" to gtv(lastSnapshotBlockHeight),
                        "accountNumber" to gtv(accountNumber)
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
        val otherAccountNumber = accountNumber + 1
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

        withdrawOnPostchain(userKeyPair, accountId, authDescriptorId, assetId, withdrawAmount, userEvmAddress.hexStringToByteArray(), bcRid)
        sealBlock()
        snapshotHeights.add(currentBlockHeight)
    }

    @Test
    @Disabled
    @Order(11)
    fun `user can't withdraw token to evm after mass exit block height`() {
        logger.info { "user can't withdraw token to evm after mass exit block height" }

        val withdrawInfo3 = getLastWithdrawal(userEvmAddress.hexStringToByteArray())
        assertThat(withdrawInfo3["amount"]!!.asBigInteger()).isEqualTo(withdrawAmount)

        // Get the withdrawal event hash
        val eventHash3 = getWithdrawalEventHashByTxRid(withdrawInfo3["event_hash"]!!.asByteArray())

        // Query to get the event proof to withdraw fund on evm
        val eventProof3 = blockQuery.query("get_event_merkle_proof",
                gtv("eventHash" to gtv(eventHash3.toHex()))
        ).get().toObject<EventMerkleProof>()

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

        userBalance = testToken.balanceOf(Address(userEvmAddress)).send()
        assertEquals(userBalance.value, initialMint - totalTransferAmount)
        userBalance = testToken.balanceOf(Address(otherEvmAddress)).send()
        assertEquals(userBalance.value, toRemainingAccount)

        assertEquals(6, snapshotHeights.size)
        // Because the snapshots to keep is 2 then the snapshot older height will not available
        // for the first account
        val oldSnapshotHeight = snapshotHeights[snapshotHeights.size - 3]
        val oldState = blockQuery.query("get_account_state_merkle_proof",
                gtv(
                        "blockHeight" to gtv(oldSnapshotHeight),
                        "accountNumber" to gtv(accountNumber)
                )).get()
        assertEquals(oldState, GtvNull)

        // account state is still available on the latest snapshot
        val latestSnapshotHeight = snapshotHeights[snapshotHeights.size - 1]
        val latestState = blockQuery.query("get_account_state_merkle_proof",
                gtv(
                        "blockHeight" to gtv(latestSnapshotHeight),
                        "accountNumber" to gtv(accountNumber)
                )).get()
        assertNotNull(latestState)
    }

    @Order(12)
    @Test
    fun `pause token bridge contract`() {
        Transfer(web3j, transactionManager).sendFunds(
                node0EvmAddress.value,
                BigDecimal.valueOf(400), Convert.Unit.ETHER).send()

        val nodeTransactionManager = FastRawTransactionManager(
                web3j,
                Credentials.create(node.appConfig.privKey),
                PollingTransactionReceiptProcessor(
                        web3j,
                        1000,
                        30
                )
        )

        val pauseFunctionData = TransactionSubmitter.encodeFunction("pause", listOf(), listOf())
        val gasLimitPauseFunction = gasProvider.getGasLimit(pauseFunctionData)
        val gasPricePauseFunction = gasProvider.getGasPrice(pauseFunctionData)

        nodeTransactionManager.sendTransaction(
                gasPricePauseFunction,
                gasLimitPauseFunction,
                bridge.contractAddress,
                pauseFunctionData,
                BigInteger.ZERO
        )

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            sealBlock()
            assertTrue(bridge.paused().send().value)
        }

        val unpauseFunctionData = TransactionSubmitter.encodeFunction("unpause", listOf(), listOf())
        val gasLimitUnpauseFunction = gasProvider.getGasLimit(unpauseFunctionData)
        val gasPriceUnpauseFunction = gasProvider.getGasPrice(unpauseFunctionData)

        transactionManager.sendTransaction(
                gasPriceUnpauseFunction,
                gasLimitUnpauseFunction,
                bridge.contractAddress,
                unpauseFunctionData,
                BigInteger.ZERO
        )
        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            sealBlock()
            assertFalse(bridge.paused().send().value)
        }
    }

    // Register asset on postchain
    private fun registerAsset(
            tokenName: String,
            tokenSymbol: String,
            tokenDecimal: Long,
            tokenIconUrl: String,
            bcRid: BlockchainRid,
            keyPair: KeyPair
    ): ByteArray {
        val b = GtxBuilder(bcRid, listOf(keyPair.pubKey.data), myCS)
        b.addOperation(
                "ft4.admin.register_asset",
                gtv(tokenName), gtv(tokenSymbol), gtv(tokenDecimal), gtv(tokenIconUrl)
        )
        return b.finish()
                .sign(cryptoSystem.buildSigMaker(keyPair))
                .buildGtx()
                .encode()
    }

    private fun registerERC20Asset(
            tokenAddress: ByteArray,
            assetId: ByteArray,
            bcRid: BlockchainRid,
            keyPair: KeyPair
    ): ByteArray {
        val b = GtxBuilder(bcRid, listOf(keyPair.pubKey.data), myCS)
        b.addOperation(
                "eif.hbridge.register_erc20_asset",
                gtv(networkId),
                gtv(tokenAddress),
                gtv(assetId),
        )
        return b.finish()
                .sign(cryptoSystem.buildSigMaker(keyPair))
                .buildGtx()
                .encode()
    }

    /**
     * Register account on postchain.
     *
     * @return accountId, authDescriptorId
     */
    private fun registerAccount(
            userPubkey: ByteArray,
            bcRid: BlockchainRid,
            keyPair: KeyPair
    ): Pair<ByteArray, ByteArray> {

        val auth = gtv(
                gtv(AuthType.S.ordinal.toLong()),
                gtv(GtvArray(arrayOf(gtv("A"), gtv("T"))), gtv(userPubkey)),
                GtvNull
        )

        val authDescriptorId = auth.merkleHash(hashCalculator)

        val b = GtxBuilder(bcRid, listOf(keyPair.pubKey.data), myCS)
        b.addOperation("ft4.admin.register_account", auth)

        enqueueTx(b.finish()
                .sign(cryptoSystem.buildSigMaker(keyPair))
                .buildGtx()
                .encode())

        val accountId = gtv(userPubkey).merkleHash(hashCalculator)

        return accountId to authDescriptorId
    }

    private fun linkAccount(
            userKeyPair: KeyPair,
            accountId: ByteArray, authDescriptorId: ByteArray,
            userEvmAddress: ByteArray,
            credentials: Credentials,
            bcRid: BlockchainRid,
    ) {
        val opName = gtv("eif.hbridge.link_evm_eoa_account")
        val opArgs = gtv(listOf(gtv(userEvmAddress)))

        val nonce = gtv(listOf(
                gtv(bcRid.data),
                opName,
                opArgs,
                gtv(0),
        )).merkleHash(hashCalculator)

        val message = blockQuery.query("ft4.get_auth_message_template",
                gtv(mapOf("op_name" to opName, "op_args" to opArgs))).get().asString()
                .replace("{blockchain_rid}", bcRid.toHex().uppercase())
                .replace("{nonce}", nonce.toHex().uppercase())
        val evmSig = Sign.signPrefixedMessage(
                message.toByteArray(StandardCharsets.UTF_8),
                credentials.ecKeyPair
        )
        val signature = gtv(
                gtv(evmSig.r),
                gtv(evmSig.s),
                gtv(BigInteger(evmSig.v).longValueExact())
        )

        val b = GtxBuilder(bcRid, listOf(userKeyPair.pubKey.data), myCS)
        b.addOperation("ft4.evm_signatures", gtv(listOf(gtv(userEvmAddress))), gtv(listOf(signature)))
        b.addOperation("ft4.ft_auth", gtv(accountId), gtv(authDescriptorId))
        b.addOperation("eif.hbridge.link_evm_eoa_account", gtv(userEvmAddress))
        enqueueTx(b.finish()
                .sign(cryptoSystem.buildSigMaker(userKeyPair))
                .buildGtx()
                .encode())
    }

    // Withdraw ft3 token on postchain
    private fun withdrawOnPostchain(
            userKeyPair: KeyPair,
            accountId: ByteArray, authDescriptorId: ByteArray,
            assetId: ByteArray, withdrawAmount: BigInteger, userEvmAddress: ByteArray,
            bcRid: BlockchainRid
    ): Hash {

        val b = GtxBuilder(bcRid, listOf(userKeyPair.pubKey.data), myCS)

        b.addOperation("ft4.ft_auth", gtv(accountId), gtv(authDescriptorId))
        b.addOperation(
                "eif.hbridge.bridge_ft4_token_to_evm",
                gtv(networkId),
                gtv(assetId),
                gtv(withdrawAmount),
                gtv(userEvmAddress)
        )
        b.addOperation("nop", GtvInteger(System.currentTimeMillis()))

        val signer = cryptoSystem.buildSigMaker(userKeyPair)
        val tx = b.finish().sign(signer).buildGtx()
        val txRid = tx.calculateTxRid(hashCalculator)
        enqueueTx(tx.encode())

        return txRid
    }

    // Transfer ft3 token to another account
    private fun transfer(
            userKeyPair: KeyPair,
            accountId: ByteArray, authDescriptorId: Hash, otherAccountId: ByteArray,
            assetId: ByteArray, transferAmount: BigInteger, bcRid: BlockchainRid
    ): ByteArray {
        val b = GtxBuilder(bcRid, listOf(userKeyPair.pubKey.data), myCS)
        b.addOperation("ft4.ft_auth", gtv(accountId), gtv(authDescriptorId))
        b.addOperation("ft4.transfer", gtv(otherAccountId), gtv(assetId), gtv(transferAmount))

        val signer = cryptoSystem.buildSigMaker(userKeyPair)
        return b.finish()
                .sign(signer)
                .buildGtx()
                .encode()
    }

    private fun getRegisterMessage(evmAddress: String, disposableKey: String) =
            "Create account for EVM wallet:\n${evmAddress}\n\nDisposable key:\n${disposableKey}"

    private fun sealBlock() {
        currentBlockHeight += 1
        buildBlock(DEFAULT_CHAIN_IID)
        assertEquals(currentBlockHeight, getLastHeight(node))
    }

    private fun enqueueTx(data: ByteArray) {
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

    private fun updateValidatorsInPostchain() {
        val lastBlockHeight = getLastHeight(node)
        // replica node[1] becomes a validator
        val newSigners = listOf(0, 1).associateWith { nodes[it].pubKey.hexStringToByteArray() }
        val newConfig = loadEifBlockchainConfig().asDict().toMutableMap()
        newConfig[KEY_SIGNERS] = gtv(newSigners.values.map { gtv(it) })

        // adding a new config at height (last + 2)
        // (last + 1) will not work because (last + 1) config already loaded by afterCommit handler
        val newConfigHeight = lastBlockHeight + 2
        val newConfigGtv = gtv(newConfig)
        val newRawConfig = GtvEncoder.encodeGtv(newConfigGtv)
        addDappBlockchainConfiguration(chainId, newRawConfig, newConfigHeight)

        // building at least two blocks to build a block with new signers
        sealBlock()
        awaitChainRestarted(DEFAULT_CHAIN_IID, currentBlockHeight, newConfigGtv.merkleHash(hashCalculator))
        sealBlock()

        // asserting that new config is loaded
        val witness = node.blockQueries().getBlockAtHeight(newConfigHeight).get()!!.witness as BaseBlockWitness
        assertArrayEquals(
                listOf(nodes[0].pubKey, nodes[1].pubKey).sorted().toTypedArray(),
                witness.getSignatures().map { it.subjectID.toHex() }.sorted().toTypedArray()
        )
        blockQuery = node.getBlockchainInstance().blockchainEngine.getBlockQueries()
    }

    // This function emulates validator list updating by TransactionSubmitter
    private fun updateValidatorsInValidatorContract() {
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

    private fun getContractValidatorList(): List<Address> {
        val count = validator.validatorCount.send().value.toLong()
        val validators = mutableListOf<Address>()
        (0 until count).forEach {
            validators.add(validator.validators(Uint256(it)).send())
        }
        return validators
    }

    private fun getLastWithdrawal(beneficiary: ByteArray): Map<String, Gtv> {
        val all = blockQuery.query("eif.hbridge.get_erc20_withdrawal", gtv(
                "network_id" to gtv(networkId),
                "token_address" to gtv(testTokenAddress),
                "beneficiary" to gtv(beneficiary)
        )).get().asArray()

        return all.map { it.asDict() }.maxByOrNull { it["serial"]!!.asInteger() }!!
    }

    private fun loadEifBlockchainConfig(): Gtv = GtvMLParser.parseGtvML(
            javaClass.getResource("/net/postchain/eif/eif.xml")!!.readText()
    )

    private fun getWithdrawalEventHashByTxRid(txRid: ByteArray): ByteArray = blockQuery.query(
            "eif.hbridge.get_erc20_withdrawal_by_tx",
            gtv("tx_rid" to gtv(txRid), "op_index" to gtv(1))
    ).get().asDict()["event_hash"]!!.asByteArray()
}
