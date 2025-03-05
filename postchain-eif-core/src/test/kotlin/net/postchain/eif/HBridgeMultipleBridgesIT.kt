package net.postchain.eif

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import mu.KotlinLogging
import net.postchain.common.data.Hash
import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.concurrent.util.get
import net.postchain.eif.contracts.TestToken
import net.postchain.eif.contracts.TokenBridgeWithSnapshotWithdraw
import net.postchain.eif.contracts.Validator
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import net.postchain.gtv.mapper.toObject
import org.awaitility.Awaitility.await
import org.awaitility.Duration
import org.junit.jupiter.api.Assertions.assertEquals
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
import org.web3j.protocol.exceptions.TransactionException
import org.web3j.tx.Contract
import java.math.BigInteger

@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@DisableIfTestFails
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HBridgeMultipleBridgesIT : HBridgeBaseIntegrationTest() {
    private val logger = KotlinLogging.logger("test_logger")

    private val initialMint = 1000_000_000.chr
    private val depositAmount1 = 1000.chr
    private val depositAmount2 = 2000.chr
    private val withdrawAmount1 = 100.chr
    private val withdrawAmount2 = 200.chr

    // Contracts
    private lateinit var bridge1: TokenBridgeWithSnapshotWithdraw
    private lateinit var bridge1Address: ByteArray
    private lateinit var bridge2: TokenBridgeWithSnapshotWithdraw
    private lateinit var bridge2Address: ByteArray
    private lateinit var testToken: TestToken
    private lateinit var withdrawalTxRid1: Hash

    @Test
    @Order(1)
    fun `deploy contracts`() {
        logger.info { "deploy contracts" }

        // Deploy validator contract
        val encodedConstructor = FunctionEncoder.encodeConstructor(listOf(DynamicArray(Address::class.java, node0EvmAddress)))
        validator = Contract.deployRemoteCall(Validator::class.java, web3j, transactionManager, gasProvider, validatorBinary, encodedConstructor).send()

        // Deploy token bridge contracts
        bridge1 = Contract.deployRemoteCall(TokenBridgeWithSnapshotWithdraw::class.java, web3j, transactionManager, gasProvider, tokenBridgeWithSnapshotWithdrawBinary, "").send().apply {
            initialize(Address(validator.contractAddress), Uint256(2)).send()
        }
        bridge1Address = bridge1.contractAddress.substring(2).hexStringToByteArray()
        bridge2 = Contract.deployRemoteCall(TokenBridgeWithSnapshotWithdraw::class.java, web3j, transactionManager, gasProvider, tokenBridgeWithSnapshotWithdrawBinary, "").send().apply {
            initialize(Address(validator.contractAddress), Uint256(2)).send()
        }
        bridge2Address = bridge2.contractAddress.substring(2).hexStringToByteArray()

        // Deploy a test token
        testToken = Contract.deployRemoteCall(TestToken::class.java, web3j, transactionManager, gasProvider, testTokenBinary, "").send()
        testToken.mint(Address(transactionManager.fromAddress), Uint256(initialMint)).send() // Alice controls the entire initial supply

        // Approve bridges to transfer testToken
        testToken.approve(Address(bridge1.contractAddress), Uint256(initialMint)).send()
        testToken.approve(Address(bridge2.contractAddress), Uint256(initialMint)).send()
        testTokenAddress = testToken.contractAddress.substring(2).hexStringToByteArray()

        // Allow testToken on bridges
        bridge1.allowToken(Address(testToken.contractAddress)).send()
        bridge2.allowToken(Address(testToken.contractAddress)).send()

        // Verify initial balance
        aliceBalance = testToken.balanceOf(aliceCredentials.evmAddress).send()
        assertEquals(initialMint, aliceBalance.value)
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

        // Set blockchain RID
        bridge1.setBlockchainRid(Bytes32(bcRid.data)).send()
        bridge2.setBlockchainRid(Bytes32(bcRid.data)).send()

        // Configure dynamic bridges
        enqueueTx(configureEventReceiverContract(bridge1Address, 0, bcRid, adminKeyPair))
        enqueueTx(configureEventReceiverContract(bridge2Address, 0, bcRid, adminKeyPair))
        sealBlock()
    }

    @Test
    @Order(3)
    fun `register ft accounts`() {
        logger.info { "register ft accounts" }

        val tokenName = "Chromia"
        val tokenSymbol = "CHR"
        val tokenDecimal = decimals.toLong()
        val tokenIconUrl = "https://chromaway.com/chr"

        // Register FT4 asset
        enqueueTx(registerAsset(tokenName, tokenSymbol, tokenDecimal, tokenIconUrl, bcRid, adminKeyPair))
        sealBlock()

        val value = node.getBlockchainInstance().blockchainEngine.getBlockQueries()
                .query("ft4.get_assets_by_name", gtv(
                        "name" to gtv(tokenName),
                        "page_size" to GtvNull,
                        "page_cursor" to GtvNull
                )).get()
        assetId = value["data"]?.get(0)?.get("id")!!.asByteArray()

        // Register ERC-20 asset
        enqueueTx(registerERC20Asset(testTokenAddress, assetId, bcRid, adminKeyPair, BridgeMode.foreign))
        enqueueTx(configureBridgeWithErc20Assets(bridge1Address, bcRid, adminKeyPair))
        enqueueTx(configureBridgeWithErc20Assets(bridge2Address, bcRid, adminKeyPair))

        // Register FT4 Alice account
        aliceAccount = registerAccount(aliceCredentials.keyPair.pubKey, bcRid, adminKeyPair)
        linkAccount(aliceCredentials, aliceAccount, bcRid)

        // Register FT4 Bob account
        bobAccount = registerAccount(bobCredentials.keyPair.pubKey, bcRid, adminKeyPair)

        sealBlock()
    }

    @Test
    @Order(4)
    fun `deposit token on evm`() {
        logger.info { "deposit token on evm" }

        // To transfer funds to chromia, Alice deposits
        //  - `depositAmount1` of tokens on the bridge1,
        //  - `depositAmount2` of tokens on the bridge2,
        bridge1.deposit(Address(testToken.contractAddress), Uint256(depositAmount1)).send()
        bridge2.deposit(Address(testToken.contractAddress), Uint256(depositAmount2)).send()
        buildEvmBlocks()

        aliceBalance = testToken.balanceOf(aliceCredentials.evmAddress).send()
        assertEquals(initialMint - depositAmount1 - depositAmount2, aliceBalance.value)

        // Check the asset balance on Chromia
        await().atMost(Duration.ONE_MINUTE).untilAsserted {
            sealBlock() // keep postchain build new blocks to ensure that all evm deposits are recorded
            assertEquals(depositAmount1 + depositAmount2, getAssetBalance(aliceAccount))
        }
        snapshotHeights.add(currentBlockHeight)

        // Getting accountNum
        aliceAccount.accountNum = blockQuery.query("eif.hbridge.get_state_slot_ids_for_address",
                gtv("recipient_address" to gtv(aliceCredentials.evmAddressStr))).get()[0].asInteger()
    }

    @Test
    @Order(5)
    fun `withdraw token to bridge1`() {
        logger.info { "withdraw ${prettyAmount(withdrawAmount1)} tokens to bridge1" }
        withdrawalTxRid1 = withdrawTokenToEvm(
                bridge1,
                bridge1Address,
                withdrawAmount1,
                initialBalanceOnChromia = depositAmount1 + depositAmount2,
                initialBalanceOnEvm = initialMint - (depositAmount1 + depositAmount2)
        )
    }

    @Test
    @Order(6)
    fun `withdraw token to bridge2`() {
        logger.info { "withdraw ${prettyAmount(withdrawAmount2)} tokens to bridge2" }
        withdrawTokenToEvm(
                bridge2,
                bridge2Address,
                withdrawAmount2,
                initialBalanceOnChromia = depositAmount1 + depositAmount2 - withdrawAmount1,
                initialBalanceOnEvm = initialMint - (depositAmount1 + depositAmount2) + withdrawAmount1
        )
    }

    @Test
    @Order(7)
    fun `double spending prevented - cannot withdraw token on bridge2 after withdrawal on bridge1`() {
        logger.info { "withdraw ${prettyAmount(withdrawAmount1)} tokens to bridge2 after withdrawal on bridge1" }

        // Get withdrawal1 even hash and proof
        val eventHash1 = getWithdrawalEventHashByTxRid(withdrawalTxRid1)
        val eventProof1 = blockQuery.query(
                "get_event_merkle_proof", gtv("eventHash" to gtv(eventHash1.toHex()))
        ).get().toObject<EventMerkleProof>()

        // Requesting withdrawal1 on a bridge2
        val exception = assertThrows<TransactionException> {
            bridge2.withdrawRequest(
                    eventProof1.web3EventData(),
                    eventProof1.web3EventProof(),
                    eventProof1.web3BlockHeader(),
                    eventProof1.web3Signatures(),
                    eventProof1.web3Signers(),
                    eventProof1.web3ExtraProofData()
            ).send()
        }
        assertThat(exception.message!!).contains("Postchain: Invalid discriminator. Please verify the network ID and bridge contract.")
        buildEvmBlocks()

        // Verifying that the withdrawal is still completed
        verifyWithdrawalStatusOnPostchain(eventHash1, WithdrawalStatus.withdrawn)

        // Alice's balance has not changed
        aliceBalance = testToken.balanceOf(aliceCredentials.evmAddress).send()
        assertThat(aliceBalance.value).isEqualTo(
                initialMint - (depositAmount1 + depositAmount2) + withdrawAmount1 + withdrawAmount2
        )
    }

    private fun withdrawTokenToEvm(
            bridge: TokenBridgeWithSnapshotWithdraw,
            bridgeAddress: ByteArray,
            withdrawAmount: BigInteger,
            initialBalanceOnChromia: BigInteger,
            initialBalanceOnEvm: BigInteger,
    ): Hash {
        // Alice withdraws `withdrawAmount1` amount of tokens to the bridge
        val txRid = withdrawOnPostchainV2(aliceCredentials, aliceAccount, assetId, withdrawAmount, bridgeAddress, bcRid)
        sealBlock()
        snapshotHeights.add(currentBlockHeight)
        assertEquals(initialBalanceOnChromia - withdrawAmount, getAssetBalance(aliceAccount))

        // Get withdrawal evenHash and proof
        val eventHash = getWithdrawalEventHashByTxRid(txRid)
        val eventProof = blockQuery.query(
                "get_event_merkle_proof", gtv("eventHash" to gtv(eventHash.toHex()))
        ).get().toObject<EventMerkleProof>()

        // Requesting withdrawal on a bridge
        bridge.withdrawRequest(
                eventProof.web3EventData(),
                eventProof.web3EventProof(),
                eventProof.web3BlockHeader(),
                eventProof.web3Signatures(),
                eventProof.web3Signers(),
                eventProof.web3ExtraProofData()
        ).send()
        buildEvmBlocks()

        // Verify withdrawal status
        verifyWithdrawalStatusOnPostchain(eventHash, WithdrawalStatus.requested)

        // Complete the withdrawal
        bridge.withdraw(Bytes32(eventHash), aliceCredentials.evmAddress).send()
        buildEvmBlocks()
        aliceBalance = testToken.balanceOf(aliceCredentials.evmAddress).send()
        assertThat(aliceBalance.value).isEqualTo(
                initialBalanceOnEvm + withdrawAmount
        )

        // Verifying that the withdrawal is completed
        verifyWithdrawalStatusOnPostchain(eventHash, WithdrawalStatus.withdrawn)

        return txRid
    }

    private fun prettyAmount(amount: BigInteger) = amount.divide(1.chr)
}
