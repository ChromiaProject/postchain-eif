package net.postchain.eif

import assertk.assertThat
import assertk.assertions.contains
import mu.KotlinLogging
import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.concurrent.util.get
import net.postchain.core.BlockRid
import net.postchain.crypto.devtools.KeyPairHelper
import net.postchain.eif.contracts.ChromiaTestToken
import net.postchain.eif.contracts.ChromiaTokenBridge
import net.postchain.eif.contracts.TokenMinterTest
import net.postchain.eif.contracts.Validator
import net.postchain.eif.transaction.TransactionSubmitter
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import net.postchain.gtv.mapper.toObject
import org.awaitility.Awaitility.await
import org.awaitility.Duration
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
import org.web3j.abi.datatypes.Uint
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.crypto.Credentials
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.exceptions.TransactionException
import org.web3j.tx.Contract
import org.web3j.tx.FastRawTransactionManager
import org.web3j.tx.Transfer
import org.web3j.tx.response.PollingTransactionReceiptProcessor
import org.web3j.utils.Convert
import java.math.BigDecimal
import java.math.BigInteger

@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@DisableIfTestFails
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HBridgeNativeModeIT : HBridgeBaseIntegrationTest() {
    private val logger = KotlinLogging.logger("test_logger")

    private val accountNum = 15
    private val accountBalance = 1L

    private val initialMint = 1000_000_000.chr
    private val withdrawAmount = 1000.chr
    private val multiWithdrawAmount = 1.chr
    private val depositAmount = 100.chr

    // Contracts
    private lateinit var bridge: ChromiaTokenBridge
    private lateinit var bridgeAddress: ByteArray
    private lateinit var minter: TokenMinterTest
    private lateinit var chromiaTestToken: ChromiaTestToken

    // Multiple withdraws in same block to get multi-level page tree
    private val multiWithdrawTimes = 30

    @Test
    @Order(1)
    fun `deploy contracts`() {
        logger.info { "deploy contracts" }

        // Deploy validator contract
        validator = Contract.deployRemoteCall(Validator::class.java, web3j, transactionManager, gasProvider, validatorBinary,
                FunctionEncoder.encodeConstructor(listOf(DynamicArray(Address::class.java, node0EvmAddress)))
        ).send()

        // Deploy token bridge contract
        bridge = Contract.deployRemoteCall(ChromiaTokenBridge::class.java, web3j, transactionManager, gasProvider, chromiaTokenBridgeBinary, "").send().apply {
            initialize(Address(validator.contractAddress), Uint256(2)).send()
        }
        bridgeAddress = bridge.contractAddress.substring(2).hexStringToByteArray()

        // Deploy a test token that we mint and then approve transfer of coins to chrL2 contract
        chromiaTestToken = Contract.deployRemoteCall(ChromiaTestToken::class.java, web3j, transactionManager, gasProvider, chromiaTestTokenBinary, "").send()
        chromiaTestToken.approve(Address(bridge.contractAddress), Uint256(initialMint)).send() // Bridge can spend the entire initial supply
        testTokenAddress = chromiaTestToken.contractAddress.substring(2).hexStringToByteArray()
        // Allow token
        bridge.allowToken(Address(chromiaTestToken.contractAddress)).send()

        // Deploy minter contract
        minter = Contract.deployRemoteCall(TokenMinterTest::class.java, web3j, transactionManager, gasProvider, tokenMinterBinary,
                FunctionEncoder.encodeConstructor(listOf(Uint(initialMint), Address(chromiaTestToken.contractAddress), Address(bridge.contractAddress), aliceCredentials.evmAddress))
        ).send()
        bridge.setTokenMinter(Address(minter.contractAddress)).send()
        chromiaTestToken.setTokenMinter(Address(minter.contractAddress)).send()

        aliceBalance = chromiaTestToken.balanceOf(aliceCredentials.evmAddress).send()
        assertEquals(BigInteger.ZERO, aliceBalance.value)
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

        enqueueTx(configureEventReceiverContract(bridgeAddress, 0, bcRid, adminKeyPair))
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

        enqueueTx(registerAsset(tokenName, tokenSymbol, tokenDecimal, tokenIconUrl, bcRid, adminKeyPair))
        sealBlock()

        val value = node.getBlockchainInstance().blockchainEngine.getBlockQueries()
                .query("ft4.get_assets_by_name", gtv(
                        "name" to gtv(tokenName),
                        "page_size" to gtv(1L),
                        "page_cursor" to GtvNull
                )).get()
        assetId = value["data"]?.get(0)?.get("id")!!.asByteArray()

        enqueueTx(registerERC20Asset(testTokenAddress, assetId, bcRid, adminKeyPair, BridgeMode.native))
        enqueueTx(configureBridgeWithErc20Assets(bridgeAddress, bcRid, adminKeyPair))

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

        enqueueTx(mintChromiaAsset(aliceAccount, assetId, initialMint, adminKeyPair))
        sealBlock()
        assertEquals(initialMint, getAssetBalance(aliceAccount))
    }

    @Test
    @Order(4)
    fun `withdraw token to evm`() {
        logger.info { "withdraw token to evm" }

        val txRid = withdrawOnPostchainV2(aliceCredentials, aliceAccount, assetId, withdrawAmount, bridgeAddress, bcRid)
        sealBlock()
        snapshotHeights.add(currentBlockHeight)
        assertEquals(initialMint - withdrawAmount, getAssetBalance(aliceAccount))

        // Get and verify the withdrawal data
        val withdrawInfo = getLastWithdrawal(aliceCredentials)
        assertEquals(withdrawInfo["amount"]!!.asBigInteger(), withdrawAmount)
        val serial = withdrawInfo["serial"]!!.asInteger()

        // Query to get the event proof to withdraw fund on evm
        val eventData = gtv(
                gtv(serial),
                networkContractDiscriminator(networkId, bridgeAddress),
                gtv(to32Bytes(chromiaTestToken.contractAddress.substring(2))),
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
                    eventProof.chromiaWeb3EventProof(),
                    eventProof.web3BlockHeader(),
                    eventProof.web3Signatures(),
                    eventProof.web3Signers(),
                    eventProof.chromiaWeb3ExtraProofData()
            ).send()
        }
        assertThat(exception.message!!).contains("TokenBridge: blockchain rid is not set")
        bridge.setBlockchainRid(Bytes32(bcRid.data)).send()

        // Updating validators
        logger.info { "\tcan't withdraw using the confirmation proof built before the validator list was changed" }
        updateValidatorsInPostchain()
        updateValidatorsInValidatorContract()
        val exception2 = assertThrows<TransactionException> {
            bridge.withdrawRequest(
                    eventProof.web3EventData(),
                    eventProof.chromiaWeb3EventProof(),
                    eventProof.web3BlockHeader(),
                    eventProof.web3Signatures(),
                    eventProof.web3Signers(),
                    eventProof.chromiaWeb3ExtraProofData()
            ).send()
        }
        assertThat(exception2.message!!).contains("TokenBridge: block signature is invalid")

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
                eventProof2.chromiaWeb3EventProof(),
                eventProof2.web3BlockHeader(),
                eventProof2.web3Signatures(),
                eventProof2.web3Signers(),
                eventProof2.chromiaWeb3ExtraProofData()
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
        aliceBalance = chromiaTestToken.balanceOf(aliceCredentials.evmAddress).send()
        assertEquals(withdrawAmount, aliceBalance.value)

        // Verifying that the withdrawal is completed
        verifyWithdrawalStatusOnPostchain(eventHash, WithdrawalStatus.withdrawn)
    }

    @Test
    @Order(5)
    fun `multiple withdraws per block`() {
        logger.info { "withdraw 1 CHR $multiWithdrawTimes times in one block" }
        val lastTxRid = (0 until multiWithdrawTimes).map {
            Thread.sleep(1) // To get unique nop
            withdrawOnPostchainV2(aliceCredentials, aliceAccount, assetId, multiWithdrawAmount, bridgeAddress, bcRid)
        }.last()
        sealBlock()

        assertEquals(
                initialMint - withdrawAmount - multiWithdrawTimes.toBigInteger() * multiWithdrawAmount,
                getAssetBalance(aliceAccount)
        )

        // Just completing the last one on EVM side
        val lastMultiEventHash = getWithdrawalEventHashByTxRid(lastTxRid)
        val lastMultiEventProof = blockQuery.query("get_event_merkle_proof",
                gtv("eventHash" to gtv(lastMultiEventHash.toHex()))).get().toObject<EventMerkleProof>()
        logger.info { "\trequesting withdrawal of the last withdraw made" }
        bridge.withdrawRequest(
                lastMultiEventProof.web3EventData(),
                lastMultiEventProof.chromiaWeb3EventProof(),
                lastMultiEventProof.web3BlockHeader(),
                lastMultiEventProof.web3Signatures(),
                lastMultiEventProof.web3Signers(),
                lastMultiEventProof.chromiaWeb3ExtraProofData()
        ).send()
        buildEvmBlocks(2)

        bridge.withdraw(Bytes32(lastMultiEventHash), aliceCredentials.evmAddress).send()
        buildEvmBlocks()

        aliceBalance = chromiaTestToken.balanceOf(aliceCredentials.evmAddress).send()
        assertEquals(withdrawAmount + multiWithdrawAmount, aliceBalance.value)

        logger.info { "\tmaking a new withdrawal in next block" }
        val nextBlockWithdraw = withdrawOnPostchainV2(aliceCredentials, aliceAccount, assetId, multiWithdrawAmount, bridgeAddress, bcRid)
        sealBlock()

        assertEquals(
                initialMint - withdrawAmount - (multiWithdrawTimes + 1).toBigInteger() * multiWithdrawAmount,
                getAssetBalance(aliceAccount)
        )

        val nextBlockEventHash = getWithdrawalEventHashByTxRid(nextBlockWithdraw)
        val nextBlockEventProof = blockQuery.query("get_event_merkle_proof",
                gtv("eventHash" to gtv(nextBlockEventHash.toHex()))
        ).get().toObject<EventMerkleProof>()
        logger.info { "\trequesting withdrawal again" }
        bridge.withdrawRequest(
                nextBlockEventProof.web3EventData(),
                nextBlockEventProof.chromiaWeb3EventProof(),
                nextBlockEventProof.web3BlockHeader(),
                nextBlockEventProof.web3Signatures(),
                nextBlockEventProof.web3Signers(),
                nextBlockEventProof.chromiaWeb3ExtraProofData()
        ).send()
        buildEvmBlocks(2)

        bridge.withdraw(Bytes32(nextBlockEventHash), aliceCredentials.evmAddress).send()
        buildEvmBlocks()
        aliceBalance = chromiaTestToken.balanceOf(aliceCredentials.evmAddress).send()
        assertEquals(withdrawAmount + multiWithdrawAmount * BigInteger.TWO, aliceBalance.value)
    }

    @Test
    @Order(6)
    fun `deposit token on evm`() {
        logger.info { "deposit $depositAmount on evm" }

        // Alice deposits `depositAmount` of tokens on the bridge to transfer funds to chromia
        bridge.deposit(Address(chromiaTestToken.contractAddress), Uint256(depositAmount)).send()

        aliceBalance = chromiaTestToken.balanceOf(aliceCredentials.evmAddress).send()
        assertEquals(withdrawAmount + multiWithdrawAmount * BigInteger.TWO - depositAmount, aliceBalance.value)

        // Check the asset balance
        await().atMost(Duration.ONE_MINUTE).untilAsserted {
            sealBlock() // keep postchain build new blocks to ensure that all evm deposits are recorded
            assertEquals(
                    initialMint - withdrawAmount - (multiWithdrawTimes + 1).toBigInteger() * multiWithdrawAmount + depositAmount,
                    getAssetBalance(aliceAccount))
        }
        snapshotHeights.add(currentBlockHeight)

        // Getting accountNum
        aliceAccount.accountNum = blockQuery.query(
                "eif.hbridge.get_state_slot_ids_for_address",
                gtv(
                        "recipient_address" to gtv(aliceCredentials.evmAddressStr),
                        "network_id" to gtv(networkId)
                )
        ).get()[0].asInteger()
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
                bridgeAddress.toHex(),
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
                bridgeAddress.toHex(),
                unpauseFunctionData,
                BigInteger.ZERO
        )
        await().atMost(Duration.ONE_MINUTE).untilAsserted {
            sealBlock()
            assertFalse(bridge.paused().send().value)
        }
    }
}
