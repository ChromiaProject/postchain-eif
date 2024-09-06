package net.postchain.eif

import assertk.assertThat
import assertk.assertions.isEqualTo
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
import org.awaitility.Awaitility.await
import org.awaitility.Duration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
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
import org.web3j.protocol.core.methods.response.TransactionReceipt
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

    // * Users
    // Admin
    private val adminKeyPair = KeyPairHelper.keyPair(0)

    // Alice
    private val aliceCredentials = UserCredentials(
            keyPair = KeyPair(
                    "038f888dec563b5bc253e87abc90afd26c3287021d10236ea19d248043dc39e0b8".hexStringToByteArray(),
                    "71b5b7f8de0661af934a5e4612f3d0ba183e639bdf4e7452fb6457ed3cfbc825".hexStringToByteArray()),
            // Alice's EVM address is not computed based on Alice's Chromia key pair
            evmCredentials = evmCredentials
    )
    private lateinit var aliceAccount: FtAccount
    private lateinit var aliceBalance: Uint256

    // Bob
    private val bobCredentials = UserCredentials(
            keyPair = KeyPair(
                    "02C568851773991374293504BCB593A88C3D1B799C48AB3B250138B1CEE7D08CE3".hexStringToByteArray(),
                    "346B362B66A4F3CE3FEB41043E522C625B6310DEBEBA0E69DF2011748FB38325".hexStringToByteArray()),
            evmCredentials = Credentials.create("346B362B66A4F3CE3FEB41043E522C625B6310DEBEBA0E69DF2011748FB38325")
    )
    private lateinit var bobAccount: FtAccount

    private val node0EvmAddress = Address("659e4a3726275edFD125F52338ECe0d54d15BD99")
    private val node1EvmAddress = Address("2c3fA9C9FC3C5CB2f9C09aF6f7214f64382eA086")

    private val decimals = 18
    private inline val Int.chr: BigInteger get() = BigInteger(this.toString() + "0".repeat(decimals), 10)
    private val initialMint = 1000_000_000.chr
    private val depositAmount = 1000.chr
    private val withdrawAmount = 100.chr
    private val multiWithdrawAmount = 1.chr

    // Contracts
    private lateinit var validator: Validator
    private lateinit var bridge: TokenBridgeWithSnapshotWithdraw
    private lateinit var testToken: TestToken
    private lateinit var testTokenAddress: ByteArray

    private lateinit var assetId: ByteArray
    private lateinit var node: PostchainTestNode
    private lateinit var blockQuery: BlockQueries
    private var chainId = -1L
    private lateinit var bcRid: BlockchainRid
    private var currentBlockHeight = 0L
    private var lastSnapshotBlockHeight = -1L

    private lateinit var wdTxHashInitiatedBeforeMassExit: Hash
    private lateinit var wdTxHashRequestedBeforeMassExit: Hash
    private lateinit var wdTxHashInitiatedAfterMassExit: Hash
    private lateinit var lastTxReceipt: TransactionReceipt

    @BeforeAll
    fun setupBeforeAll() {
        super.setup()

        ds = SimpleDigestSystem(MessageDigest.getInstance(KECCAK256))

        with(configOverrides) {
            setProperty("infrastructure", net.postchain.devtools.testinfra.BaseTestInfrastructureFactory::class.qualifiedName)
            setProperty("ethereum.maxReadAhead", 200)
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
        testToken = Contract.deployRemoteCall(TestToken::class.java, web3j, transactionManager, gasProvider, testTokenBinary, "").send()
        testToken.mint(Address(transactionManager.fromAddress), Uint256(initialMint)).send() // Alice controls the entire initial supply
        testToken.approve(Address(bridge.contractAddress), Uint256(initialMint)).send() // Bridge can spend the entire initial supply
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
        val tokenDecimal = decimals.toLong()
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

        aliceAccount = registerAccount(aliceCredentials.keyPair.pubKey, bcRid, adminKeyPair)
        linkAccount(aliceCredentials, aliceAccount, bcRid)

        bobAccount = registerAccount(bobCredentials.keyPair.pubKey, bcRid, adminKeyPair)

        for (i in 1..accountNum) {
            val key = KeyPairHelper.keyPair(i)
            val account = registerAccount(key.pubKey, bcRid, adminKeyPair)
            val acc = AccountRegister(
                    account.accountId,
                    key.privKey.data,
                    key.pubKey.data,
                    getEthereumAddress(KeyPairHelper.pubKey(i)),
                    accountBalance
            )
            registerAccounts.add(acc)
        }
        sealBlock()
    }

    @Test
    @Order(4)
    fun `deposit token on evm`() {
        logger.info { "deposit token on evm" }

        // Alice deposits `depositAmount` of tokens on the bridge to transfer funds to chromia
        bridge.deposit(Address(testToken.contractAddress), Uint256(depositAmount)).send()

        aliceBalance = testToken.balanceOf(aliceCredentials.evmAddress).send()
        assertEquals(initialMint - depositAmount, aliceBalance.value)

        // Check the asset balance
        await().atMost(Duration.ONE_MINUTE).untilAsserted {
            sealBlock() // keep postchain build new blocks to ensure that all evm deposits are recorded
            assertEquals(depositAmount, getAssetBalance(aliceAccount))
        }
        snapshotHeights.add(currentBlockHeight)

        // Getting accountNum
        aliceAccount.accountNum = blockQuery.query("eif.hbridge.get_state_slot_ids_for_address",
                gtv("recipient_address" to gtv(aliceCredentials.evmAddressStr))).get()[0].asInteger()
    }

    @Test
    @Order(5)
    fun `withdraw token to evm`() {
        logger.info { "withdraw token to evm" }

        val txRid = withdrawOnPostchain(aliceCredentials, aliceAccount, assetId, withdrawAmount, bcRid)
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

        assertEquals(depositAmount - withdrawAmount, getAssetBalance(aliceAccount))

        // Get and verify the withdrawal data
        val withdrawInfo = getLastWithdrawal(aliceCredentials)
        assertEquals(withdrawInfo["amount"]!!.asBigInteger(), withdrawAmount)
        val serial = withdrawInfo["serial"]!!.asInteger()

        // Query to get the event proof to withdraw fund on evm
        val eventData = gtv(
                gtv(serial),
                gtv(networkId),
                gtv(to32Bytes(testToken.contractAddress.substring(2))),
                gtv(to32Bytes(aliceCredentials.evmAddressStr)),
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
        assertTrue(exception.message!!.contains("TokenBridge: blockchain rid is not set"))
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
        assertTrue(exception2.message!!.contains("TokenBridge: block signature is invalid"))

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

        verifyWithdrawalStatusOnPostchain(eventHash, WithdrawalStatus.created)

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
        await().atMost(Duration.TEN_SECONDS).until {
            val block = web3j.ethGetBlockByNumber(DefaultBlockParameter.valueOf(receipt.blockNumber.add(BigInteger.TWO)), false).send()
            block.block != null
        }

        verifyWithdrawalStatusOnPostchain(eventHash, WithdrawalStatus.requested)

        // Making withdrawal pending
        bridge.pendingWithdraw(Bytes32(eventHash)).send()
        verifyWithdrawalStatusOnPostchain(eventHash, WithdrawalStatus.pending)

        // Making withdrawal withdrawable again
        bridge.unpendingWithdraw(Bytes32(eventHash)).send()
        verifyWithdrawalStatusOnPostchain(eventHash, WithdrawalStatus.requested)

        // Complete the withdrawal
        bridge.withdraw(Bytes32(eventHash), aliceCredentials.evmAddress).send()
        aliceBalance = testToken.balanceOf(aliceCredentials.evmAddress).send()
        assertEquals(initialMint - depositAmount + withdrawAmount, aliceBalance.value)

        // Verifying that the withdrawal is completed
        verifyWithdrawalStatusOnPostchain(eventHash, WithdrawalStatus.withdrawn)
    }

    @Test
    @Order(6)
    fun `multiple withdraws per block`() {
        // Multiple withdraws in same block to get multi-level page tree
        val multiWithdrawTimes = 30

        logger.info { "withdraw 1 CHR $multiWithdrawTimes times in one block" }
        val lastTxRid = (0 until multiWithdrawTimes).map {
            Thread.sleep(1) // To get unique nop
            withdrawOnPostchain(aliceCredentials, aliceAccount, assetId, multiWithdrawAmount, bcRid)
        }.last()
        sealBlock()

        assertEquals(
                depositAmount - withdrawAmount - multiWithdrawTimes.toBigInteger() * multiWithdrawAmount,
                getAssetBalance(aliceAccount)
        )

        // Just completing the last one on EVM side
        val lastMultiEventHash = getWithdrawalEventHashByTxRid(lastTxRid)
        val lastMultiEventProof = blockQuery.query("get_event_merkle_proof",
                gtv("eventHash" to gtv(lastMultiEventHash.toHex()))).get().toObject<EventMerkleProof>()
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
        await().atMost(Duration.TEN_SECONDS).until {
            val block = web3j.ethGetBlockByNumber(DefaultBlockParameter.valueOf(lastMultiReceipt.blockNumber.add(BigInteger.TWO)), false).send()
            block.block != null
        }
        bridge.withdraw(Bytes32(lastMultiEventHash), aliceCredentials.evmAddress).send()
        aliceBalance = testToken.balanceOf(aliceCredentials.evmAddress).send()
        assertEquals(initialMint - depositAmount + withdrawAmount + multiWithdrawAmount, aliceBalance.value)

        logger.info { "\tmaking a new withdrawal in next block" }
        val nextBlockWithdraw = withdrawOnPostchain(aliceCredentials, aliceAccount, assetId, multiWithdrawAmount, bcRid)
        sealBlock()

        assertEquals(
                depositAmount - withdrawAmount - (multiWithdrawTimes + 1).toBigInteger() * multiWithdrawAmount,
                getAssetBalance(aliceAccount)
        )

        val nextBlockEventHash = getWithdrawalEventHashByTxRid(nextBlockWithdraw)
        val nextBlockEventProof = blockQuery.query("get_event_merkle_proof",
                gtv("eventHash" to gtv(nextBlockEventHash.toHex()))
        ).get().toObject<EventMerkleProof>()
        logger.info { "\trequesting withdrawal again" }
        lastTxReceipt = bridge.withdrawRequest(
                nextBlockEventProof.web3EventData(),
                nextBlockEventProof.web3EventProof(),
                nextBlockEventProof.web3BlockHeader(),
                nextBlockEventProof.web3Signatures(),
                nextBlockEventProof.web3Signers(),
                nextBlockEventProof.web3ExtraProofData()
        ).send()

        await().atMost(Duration.TEN_SECONDS).until {
            val block = web3j.ethGetBlockByNumber(DefaultBlockParameter.valueOf(lastTxReceipt.blockNumber.add(BigInteger.TWO)), false).send()
            block.block != null
        }
        bridge.withdraw(Bytes32(nextBlockEventHash), aliceCredentials.evmAddress).send()
        aliceBalance = testToken.balanceOf(aliceCredentials.evmAddress).send()
        assertEquals(initialMint - depositAmount + withdrawAmount + multiWithdrawAmount * BigInteger.TWO, aliceBalance.value)
    }

    @Test
    @Order(7)
    fun `transfer ft token to another account`() {
        logger.info { "transfer ft token to another account" }

        // Alice transfers 1 CHR to Bob
        enqueueTx(transfer(aliceCredentials, aliceAccount, bobAccount.accountId, assetId, 1.chr, bcRid))
        sealBlock()
        snapshotHeights.add(currentBlockHeight)

        // Alice transfers 1 CHR to anonymous accounts
        registerAccounts.forEach {
            enqueueTx(transfer(aliceCredentials, aliceAccount, it.accountId, assetId, it.balance.toBigInteger(), bcRid))
        }
        sealBlock()
        snapshotHeights.add(currentBlockHeight)
    }

    @Test
    @Order(8)
    fun `trigger mass exit`() {
        logger.info { "trigger mass exit" }

        // Alice withdraws tokens again before mass exit
        wdTxHashInitiatedBeforeMassExit = withdrawOnPostchain(aliceCredentials, aliceAccount, assetId, withdrawAmount, bcRid)
        sealBlock()
        logger.info { "\tinitiating withdrawal before mass exit - Tx Rid: ${wdTxHashInitiatedBeforeMassExit.toHex()}" }
        snapshotHeights.add(currentBlockHeight)
        wdTxHashRequestedBeforeMassExit = withdrawOnPostchain(aliceCredentials, aliceAccount, assetId, withdrawAmount, bcRid)
        sealBlock()
        logger.info { "\tinitiating withdrawal before mass exit - Tx Rid: ${wdTxHashRequestedBeforeMassExit.toHex()}" }
        snapshotHeights.add(currentBlockHeight)
        val eventHash = getWithdrawalEventHashByTxRid(wdTxHashRequestedBeforeMassExit)
        val eventProof = blockQuery.query("get_event_merkle_proof",
                gtv("eventHash" to gtv(eventHash.toHex()))).get().toObject<EventMerkleProof>()
        logger.info { "\trequesting withdrawal before mass exit - Tx Rid: ${wdTxHashRequestedBeforeMassExit.toHex()}" }
        bridge.withdrawRequest(
                eventProof.web3EventData(),
                eventProof.web3EventProof(),
                eventProof.web3BlockHeader(),
                eventProof.web3Signatures(),
                eventProof.web3Signers(),
                eventProof.web3ExtraProofData()
        ).send()

        // Get the last snapshot block height as mass-exit block
        lastSnapshotBlockHeight = currentBlockHeight

        // we only need block header and signatures, but it's the easiest way to get them
        val stateProof = blockQuery.query(
                "get_account_state_merkle_proof",
                gtv(
                        "blockHeight" to gtv(lastSnapshotBlockHeight),
                        "accountNumber" to gtv(aliceAccount.accountNum)
                )
        ).get().toObject<AccountStateMerkleProof>()

        lastTxReceipt = bridge.triggerMassExit(
                stateProof.web3BlockHeader(),
                stateProof.web3Signatures(),
                stateProof.web3Signers()
        ).send()
    }

    @Test
    @Order(9)
    fun `request and complete withdrawal initiated before mass exit`() {
        logger.info { "request and complete withdrawal on evm initiated before mass exit" }

        // Request withdrawal
        val eventHash = getWithdrawalEventHashByTxRid(wdTxHashInitiatedBeforeMassExit)
        val eventProof = blockQuery.query("get_event_merkle_proof",
                gtv("eventHash" to gtv(eventHash.toHex()))).get().toObject<EventMerkleProof>()
        logger.info { "\trequesting withdrawal initiated before mass exit - Tx Rid: ${wdTxHashInitiatedBeforeMassExit.toHex()}" }
        lastTxReceipt = bridge.withdrawRequest(
                eventProof.web3EventData(),
                eventProof.web3EventProof(),
                eventProof.web3BlockHeader(),
                eventProof.web3Signatures(),
                eventProof.web3Signers(),
                eventProof.web3ExtraProofData()
        ).send()
        buildEvmBlocks()

        // Complete withdrawal
        logger.info { "\tcompleting withdrawal initiated before mass exit - Tx Rid: ${wdTxHashInitiatedBeforeMassExit.toHex()}" }
        lastTxReceipt = bridge.withdraw(Bytes32(eventHash), aliceCredentials.evmAddress).send()
        buildEvmBlocks()
        aliceBalance = testToken.balanceOf(aliceCredentials.evmAddress).send()
        assertEquals(initialMint - depositAmount + withdrawAmount * 2.toBigInteger() + multiWithdrawAmount * 2.toBigInteger(), aliceBalance.value)
    }

    @Test
    @Order(10)
    fun `complete withdrawal requested before mass exit`() {
        logger.info { "complete withdrawal on evm requested before mass exit" }

        val eventHash = getWithdrawalEventHashByTxRid(wdTxHashRequestedBeforeMassExit)
        bridge.withdraw(Bytes32(eventHash), aliceCredentials.evmAddress).send()
        buildEvmBlocks()
        aliceBalance = testToken.balanceOf(aliceCredentials.evmAddress).send()
        assertEquals(initialMint - depositAmount + withdrawAmount * 3.toBigInteger() + multiWithdrawAmount * 2.toBigInteger(), aliceBalance.value)
    }

    @Test
    @Order(11)
    fun `withdraw token to evm after mass exit using snapshot`() {
        logger.info { "withdraw token to evm after mass exit using snapshot" }

        val remainingBalanceOnPostchain = getAssetBalance(aliceAccount)!!

        // Withdraw remaining token of the account by using snapshot state with mass-exit
        val stateProof = blockQuery.query(
                "get_account_state_merkle_proof",
                gtv(
                        "blockHeight" to gtv(lastSnapshotBlockHeight),
                        "accountNumber" to gtv(aliceAccount.accountNum)
                )
        ).get().toObject<AccountStateMerkleProof>()

        // Withdrawing by snapshot
        bridge.withdrawBySnapshot(
                stateProof.web3StateData(),
                stateProof.web3StateProof(),
                stateProof.web3ExtraProofData()
        ).send()
        buildEvmBlocks()

        aliceBalance = testToken.balanceOf(aliceCredentials.evmAddress).send()
        assertEquals(
                initialMint - depositAmount +
                        withdrawAmount * 3.toBigInteger() + multiWithdrawAmount * 2.toBigInteger() +
                        remainingBalanceOnPostchain,
                aliceBalance.value)

        /*
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
                otherState.web3ExtraProofData()
        ).send()

        withdrawOnPostchain(userKeyPair, accountId, authDescriptorId, assetId, withdrawAmount, userEvmAddress.hexStringToByteArray(), bcRid)
        sealBlock()
        snapshotHeights.add(currentBlockHeight)*/
    }

    @Test
    @Order(12)
    fun `user can't withdraw token to evm after mass exit block height`() {
        logger.info { "user can't withdraw token to evm after mass exit block height" }

        // Bob sends 1 CHR to Alice
        enqueueTx(transfer(bobCredentials, bobAccount, bobAccount.accountId, assetId, 1.chr, bcRid))
        sealBlock()

        // Alice withdraws 1 CHR after mass exit block
        wdTxHashInitiatedAfterMassExit = withdrawOnPostchain(aliceCredentials, aliceAccount, assetId, 1.chr, bcRid)
        sealBlock()
        logger.info { "\tinitiating withdrawal after mass exit - Tx Rid: ${wdTxHashInitiatedAfterMassExit.toHex()}" }

        val eventHash = getWithdrawalEventHashByTxRid(wdTxHashInitiatedAfterMassExit)
        val eventProof = blockQuery.query("get_event_merkle_proof",
                gtv("eventHash" to gtv(eventHash.toHex()))).get().toObject<EventMerkleProof>()
        logger.info { "\trequesting withdrawal after mass exit - Tx Rid: ${wdTxHashInitiatedAfterMassExit.toHex()}" }

        // User cannot send withdraw request after the mass exit block height
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
        assertTrue(exception.message!!.contains("TokenBridge: cannot withdraw request after the mass exit block height"))

        /*
        aliceBalance = testToken.balanceOf(aliceCredentials.evmAddress).send()
        assertEquals(aliceBalance.value, initialMint - depositAmount + withdrawAmount + 2.chr)
        val bobBalance = testToken.balanceOf(bobCredentials.evmAddress).send()
        assertEquals(bobBalance.value, 1.chr)

        assertEquals(6, snapshotHeights.size)
        // Because the snapshots to keep is 2 then the snapshot older height will not available
        // for the first account
        val oldSnapshotHeight = snapshotHeights[snapshotHeights.size - 3]
        val oldState = blockQuery.query("get_account_state_merkle_proof",
                gtv(
                        "blockHeight" to gtv(oldSnapshotHeight),
                        "accountNumber" to gtv(aliceAccount.accountNum)
                )).get()
        assertEquals(oldState, GtvNull)

        // account state is still available on the latest snapshot
        val latestSnapshotHeight = snapshotHeights[snapshotHeights.size - 1]
        val latestState = blockQuery.query("get_account_state_merkle_proof",
                gtv(
                        "blockHeight" to gtv(latestSnapshotHeight),
                        "accountNumber" to gtv(aliceAccount.accountNum)
                )).get()
        assertNotNull(latestState)
         */
    }

    @Test
    @Order(13)
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

        await().atMost(Duration.ONE_MINUTE).untilAsserted {
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
        await().atMost(Duration.ONE_MINUTE).untilAsserted {
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
    ): ByteArray = GtxBuilder(bcRid, listOf(keyPair.pubKey.data), myCS)
            .addOperation(
                    "eif.hbridge.register_erc20_asset",
                    gtv(networkId),
                    gtv(tokenAddress),
                    gtv(assetId),
                    gtv(true) // enable snapshots
            )
            .finish()
            .sign(cryptoSystem.buildSigMaker(keyPair))
            .buildGtx()
            .encode()

    /**
     * Register account on postchain.
     *
     * @return accountId, authDescriptorId
     */
    private fun registerAccount(
            userPubKey: PubKey,
            bcRid: BlockchainRid,
            adminKeyPair: KeyPair
    ): FtAccount {

        val auth = gtv(
                gtv(AuthType.S.ordinal.toLong()),
                gtv(GtvArray(arrayOf(gtv("A"), gtv("T"))), gtv(userPubKey.data)),
                GtvNull
        )

        val authDescriptorId = auth.merkleHash(hashCalculator)

        val b = GtxBuilder(bcRid, listOf(adminKeyPair.pubKey.data), myCS)
        b.addOperation("ft4.admin.register_account", auth)

        enqueueTx(b.finish()
                .sign(cryptoSystem.buildSigMaker(adminKeyPair))
                .buildGtx()
                .encode())

        val accountId = gtv(userPubKey.data).merkleHash(hashCalculator)

        return FtAccount(accountId, authDescriptorId)
    }

    private fun linkAccount(
            userCredentials: UserCredentials,
            userAccount: FtAccount,
            bcRid: BlockchainRid,
    ) {
        val opName = gtv("eif.hbridge.link_evm_eoa_account")
        val opArgs = gtv(listOf(gtv(userCredentials.evmAddressBA)))

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
                userCredentials.evmCredentials.ecKeyPair
        )
        val signature = gtv(
                gtv(evmSig.r),
                gtv(evmSig.s),
                gtv(BigInteger(evmSig.v).longValueExact())
        )

        val b = GtxBuilder(bcRid, listOf(userCredentials.keyPair.pubKey.data), myCS)
        b.addOperation("ft4.evm_signatures", gtv(listOf(gtv(userCredentials.evmAddressBA))), gtv(listOf(signature)))
        b.addOperation("ft4.ft_auth", gtv(userAccount.accountId), gtv(userAccount.authDescriptorId))
        b.addOperation("eif.hbridge.link_evm_eoa_account", gtv(userCredentials.evmAddressBA))
        enqueueTx(b.finish()
                .sign(cryptoSystem.buildSigMaker(userCredentials.keyPair))
                .buildGtx()
                .encode())
    }

    // Withdraw ft3 token on postchain
    private fun withdrawOnPostchain(
            userCredentials: UserCredentials,
            userAccount: FtAccount,
            assetId: ByteArray,
            withdrawAmount: BigInteger,
            bcRid: BlockchainRid
    ): Hash {

        val b = GtxBuilder(bcRid, listOf(userCredentials.keyPair.pubKey.data), myCS)

        b.addOperation("ft4.ft_auth", gtv(userAccount.accountId), gtv(userAccount.authDescriptorId))
        b.addOperation(
                "eif.hbridge.bridge_ft4_token_to_evm",
                gtv(networkId),
                gtv(assetId),
                gtv(withdrawAmount),
                gtv(userCredentials.evmAddressBA)
        )
        b.addOperation("nop", GtvInteger(System.currentTimeMillis()))

        val signer = cryptoSystem.buildSigMaker(userCredentials.keyPair)
        val tx = b.finish().sign(signer).buildGtx()
        val txRid = tx.calculateTxRid(hashCalculator)
        enqueueTx(tx.encode())

        return txRid
    }

    // Transfer ft4 token to another account
    private fun transfer(
            userCredentials: UserCredentials,
            userAccount: FtAccount,
            recipientAccountId: ByteArray,
            assetId: ByteArray,
            transferAmount: BigInteger,
            bcRid: BlockchainRid
    ): ByteArray {
        val b = GtxBuilder(bcRid, listOf(userCredentials.keyPair.pubKey.data), myCS)
        b.addOperation("ft4.ft_auth", gtv(userAccount.accountId), gtv(userAccount.authDescriptorId))
        b.addOperation("ft4.transfer", gtv(recipientAccountId), gtv(assetId), gtv(transferAmount))

        val signer = cryptoSystem.buildSigMaker(userCredentials.keyPair)
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

    private fun verifyWithdrawalStatusOnPostchain(eventHash: ByteArray, expectedStatus: WithdrawalStatus) {
        await().pollInterval(Duration.TWO_SECONDS).atMost(Duration.ONE_MINUTE).untilAsserted {
            sealBlock()

            val withdrawal = blockQuery.query("eif.hbridge.get_erc20_withdrawal_by_event_hash", gtv("event_hash" to gtv(eventHash))).get()
            assertThat(withdrawal.asDict()["status"]?.asString()).isEqualTo(expectedStatus.name)
        }
    }

    private fun getContractValidatorList(): List<Address> {
        val count = validator.validatorCount.send().value.toLong()
        val validators = mutableListOf<Address>()
        (0 until count).forEach {
            validators.add(validator.validators(Uint256(it)).send())
        }
        return validators
    }

    private fun getLastWithdrawal(userCredentials: UserCredentials): Map<String, Gtv> {
        val all = blockQuery.query("eif.hbridge.get_erc20_withdrawal", gtv(
                "network_id" to gtv(networkId),
                "token_address" to gtv(testTokenAddress),
                "beneficiary" to gtv(userCredentials.evmAddressBA)
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

    private fun getAssetBalance(userAccount: FtAccount): BigInteger? {
        val balanceGtv = blockQuery.query("ft4.get_asset_balance",
                gtv("account_id" to gtv(userAccount.accountId), "asset_id" to gtv(assetId))
        ).get()

        return if (balanceGtv.isNull()) null else balanceGtv["amount"]!!.asBigInteger()
    }

    private fun buildEvmBlocks(nrOfBlocks: BigInteger = BigInteger.TWO) {
        await().atMost(Duration.TEN_SECONDS).until {
            web3j.ethBlockNumber().send().blockNumber >= lastTxReceipt.blockNumber + nrOfBlocks
        }
    }

    @Suppress("EnumEntryName")
    private enum class WithdrawalStatus {
        created,
        requested,
        pending,
        withdrawn,
        withdrawn_to_chromia,
    }
}
