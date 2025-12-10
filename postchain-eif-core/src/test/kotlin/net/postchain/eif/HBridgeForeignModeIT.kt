package net.postchain.eif

import assertk.assertThat
import assertk.assertions.contains
import mu.KotlinLogging
import net.postchain.base.BaseBlockHeader
import net.postchain.base.BaseBlockWitness
import net.postchain.common.data.Hash
import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.concurrent.util.get
import net.postchain.core.BlockRid
import net.postchain.crypto.Secp256K1CryptoSystem
import net.postchain.crypto.devtools.KeyPairHelper
import net.postchain.eif.contracts.TestToken
import net.postchain.eif.contracts.TokenBridgeWithSnapshotWithdraw
import net.postchain.eif.contracts.Validator
import net.postchain.eif.transaction.TransactionSubmitter
import net.postchain.eif.web3j.web3BlockHeader
import net.postchain.eif.web3j.web3EventData
import net.postchain.eif.web3j.web3EventProof
import net.postchain.eif.web3j.web3ExtraProofData
import net.postchain.eif.web3j.web3Signatures
import net.postchain.eif.web3j.web3Signers
import net.postchain.eif.web3j.web3StateData
import net.postchain.eif.web3j.web3StateProof
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import net.postchain.gtv.mapper.toObject
import net.postchain.gtv.merkle.GtvMerkleHashCalculatorV2
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
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.abi.datatypes.generated.Uint64
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
class HBridgeForeignModeIT : HBridgeBaseIntegrationTest() {
    private val logger = KotlinLogging.logger("test_logger")

    private val accountNum = 15
    private val accountBalance = 1L

    private val initialMint = 1_000_000_000.chr
    private val depositAmount = 1000.chr
    private val withdrawAmount = 100.chr
    private val multiWithdrawAmount = 1.chr

    // Contracts
    private lateinit var bridge: TokenBridgeWithSnapshotWithdraw
    private lateinit var bridgeAddress: ByteArray
    private lateinit var bridgeDeployHeight: BigInteger
    private lateinit var testToken: TestToken

    private var lastSnapshotBlockHeight = -1L

    private lateinit var wdTxHashInitiatedBeforeMassExit: Hash
    private lateinit var wdTxHashRequestedBeforeMassExit: Hash
    private lateinit var wdTxHashInitiatedAfterMassExit: Hash

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
        bridgeAddress = bridge.contractAddress.substring(2).hexStringToByteArray()
        bridgeDeployHeight = web3j.ethGetTransactionByHash(bridge.transactionReceipt.get().transactionHash).send().result.blockNumber
        logger.info { "Token bridge deployed as ${bridge.contractAddress} at height $bridgeDeployHeight" }

        // Deploy a test token that we mint and then approve transfer of coins to chrL2 contract
        testToken = Contract.deployRemoteCall(TestToken::class.java, web3j, transactionManager, gasProvider, testTokenBinary, "").send()
        testToken.mint(Address(transactionManager.fromAddress), Uint256(initialMint)).send() // Alice controls the entire initial supply
        testToken.approve(Address(bridge.contractAddress), Uint256(initialMint)).send() // Bridge can spend the entire initial supply
        testTokenAddress = testToken.contractAddress.substring(2).hexStringToByteArray()
        // Allow token
        bridge.allowToken(Address(testToken.contractAddress)).send()

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

        enqueueTx(configureEventReceiverContract(bridgeAddress, bridgeDeployHeight.toLong(), bcRid, adminKeyPair))
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

        enqueueTx(registerERC20Asset(testTokenAddress, assetId, bcRid, adminKeyPair, BridgeMode.foreign))
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
    }

    @Test
    @Order(4)
    fun `deposit token on evm`() {
        logger.info { "deposit $depositAmount on evm" }

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

        // Getting account state slot id
        aliceAccount.accountStateSlotId = blockQuery.query(
                "eif.hbridge.get_state_slot_ids_for_address",
                gtv(
                        "beneficiary" to gtv(aliceCredentials.evmAddressStr),
                        "network_id" to gtv(networkId)
                )
        ).get()[0].asInteger()
    }

    @Test
    @Order(5)
    fun `withdraw token to evm`() {
        logger.info { "withdraw token to evm" }

        val txRid = withdrawOnPostchainV2(aliceCredentials, aliceAccount, assetId, withdrawAmount, bridgeAddress, bcRid)
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
                networkContractDiscriminator(networkId, bridgeAddress),
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
        assertThat(exception.message!!).contains("TokenBridge: blockchain rid is not set")
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
            withdrawOnPostchainV2(aliceCredentials, aliceAccount, assetId, multiWithdrawAmount, bridgeAddress, bcRid)
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
        bridge.withdrawRequest(
                lastMultiEventProof.web3EventData(),
                lastMultiEventProof.web3EventProof(),
                lastMultiEventProof.web3BlockHeader(),
                lastMultiEventProof.web3Signatures(),
                lastMultiEventProof.web3Signers(),
                lastMultiEventProof.web3ExtraProofData()
        ).send()
        buildEvmBlocks(2)

        bridge.withdraw(Bytes32(lastMultiEventHash), aliceCredentials.evmAddress).send()
        buildEvmBlocks()

        aliceBalance = testToken.balanceOf(aliceCredentials.evmAddress).send()
        assertEquals(initialMint - depositAmount + withdrawAmount + multiWithdrawAmount, aliceBalance.value)

        logger.info { "\tmaking a new withdrawal in next block" }
        val nextBlockWithdraw = withdrawOnPostchainV2(aliceCredentials, aliceAccount, assetId, multiWithdrawAmount, bridgeAddress, bcRid)
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
        bridge.withdrawRequest(
                nextBlockEventProof.web3EventData(),
                nextBlockEventProof.web3EventProof(),
                nextBlockEventProof.web3BlockHeader(),
                nextBlockEventProof.web3Signatures(),
                nextBlockEventProof.web3Signers(),
                nextBlockEventProof.web3ExtraProofData()
        ).send()
        buildEvmBlocks(2)

        bridge.withdraw(Bytes32(nextBlockEventHash), aliceCredentials.evmAddress).send()
        buildEvmBlocks()
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
        wdTxHashInitiatedBeforeMassExit = withdrawOnPostchainV2(aliceCredentials, aliceAccount, assetId, withdrawAmount, bridgeAddress, bcRid)
        sealBlock()
        logger.info { "\tinitiating withdrawal before mass exit - Tx Rid: ${wdTxHashInitiatedBeforeMassExit.toHex()}" }
        snapshotHeights.add(currentBlockHeight)
        wdTxHashRequestedBeforeMassExit = withdrawOnPostchainV2(aliceCredentials, aliceAccount, assetId, withdrawAmount, bridgeAddress, bcRid)
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

        // Build two new blocks to make account states mature enough
        sealBlock()
        sealBlock()

        val merkleHashCalculator = GtvMerkleHashCalculatorV2(Secp256K1CryptoSystem())
        repeat(1) { // the 2nd iteration reassigns the mass exit block
            // Get the last snapshot block height as mass-exit block
            lastSnapshotBlockHeight = currentBlockHeight
            val blockRid = blockQuery.getBlockRid(lastSnapshotBlockHeight).get()!!
            val blockDetail = blockQuery.getBlock(blockRid, true).get()!!

            val evmBlockHeader = encodeBlockHeaderDataForEVM(
                    blockRid,
                    BaseBlockHeader(blockDetail.header, merkleHashCalculator).blockHeaderRec,
            )

            // TODO: we use state proof only for extraProofData, we don't use the proof itself.
            // otherwise it's rather hard to construct extra proof data...
            val stateProof = blockQuery.query(
                    "get_account_state_merkle_proof",
                    gtv(
                            "blockHeight" to gtv(lastSnapshotBlockHeight),
                            "accountNumber" to gtv(aliceAccount.accountStateSlotId)
                    )
            ).get().toObject<AccountStateMerkleProof>()

            val evmSignatures = BaseBlockWitness.fromBytes(blockDetail.witness).getSignatures().map {
                EifSignature(
                        sig = encodeSignatureWithV(blockRid, it),
                        pubkey = getEthereumAddress(it.subjectID)
                )
            }.sortedBy { Address(it.pubkey.toHex()).toUint().value }

            // trigger mass exit
            bridge.triggerMassExit(
                    evmBlockHeader.web3BlockHeader(),
                    evmSignatures.web3Signatures(),
                    evmSignatures.web3Signers(),
                    stateProof.web3ExtraProofData()
            ).send()
            buildEvmBlocks()
            sealBlock()
        }

    }

    private fun indexOfSubsequence(data: ByteArray, subsequence: ByteArray): Int {
        if (subsequence.isEmpty() || data.size < subsequence.size) return -1

        for (i in 0..data.size - subsequence.size) {
            if (data.sliceArray(i until i + subsequence.size).contentEquals(subsequence)) {
                return i
            }
        }
        return -1
    }

    @Test
    @Order(9)
    fun `request and complete withdrawal initiated before mass exit`() {
        logger.info { "request and complete withdrawal on evm initiated before mass exit" }

        completeWithdrawalBySnapshot(wdTxHashInitiatedBeforeMassExit)

        aliceBalance = testToken.balanceOf(aliceCredentials.evmAddress).send()
        assertEquals(initialMint - depositAmount + withdrawAmount * 2.toBigInteger() + multiWithdrawAmount * 2.toBigInteger(), aliceBalance.value)
    }

    private fun completeWithdrawalBySnapshot(wdTxHash: Hash) {
        // Request withdrawal proof
        val eventHash = getWithdrawalEventHashByTxRid(wdTxHash)
        val eventProof = blockQuery.query(
                "get_event_merkle_proof",
                gtv("eventHash" to gtv(eventHash.toHex()))
        ).get().toObject<EventMerkleProof>()

        // Request account state proof
        val wStateSlotID = blockQuery.query(
                "eif.hbridge.get_withdrawal_state_slot_ids_for_address",
                gtv(
                        "beneficiary" to gtv(aliceCredentials.evmAddressStr),
                        "network_id" to gtv(networkId),
                )
        ).get().asArray()[0].asInteger()
        val stateProof = blockQuery.query(
                "get_account_state_merkle_proof",
                gtv(
                        "blockHeight" to gtv(lastSnapshotBlockHeight),
                        "accountNumber" to gtv(wStateSlotID)
                )
        ).get().toObject<AccountStateMerkleProof>()

        // Find the index of the withdrawal event hash in the account state data
        val offset = indexOfSubsequence(stateProof.stateData, eventHash)
        assertTrue(offset > 0)
        assertTrue(offset % 32 == 0)
        val n = offset / 32 - 3
        assertTrue(n >= 0)

        logger.info { "\trequesting withdrawal initiated before mass exit - Tx Rid: ${wdTxHash.toHex()}" }
        bridge.completeWithdrawalBySnapshot(
                stateProof.web3StateData(),
                Uint64(n.toLong()),
                eventProof.web3EventData(),
                stateProof.web3StateProof()
        ).send()
        buildEvmBlocks(2)
    }

    @Test
    @Order(10)
    fun `complete withdrawal requested before mass exit`() {
        logger.info { "complete withdrawal on evm requested before mass exit" }

        completeWithdrawalBySnapshot(wdTxHashRequestedBeforeMassExit)

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
                        "accountNumber" to gtv(aliceAccount.accountStateSlotId)
                )
        ).get().toObject<AccountStateMerkleProof>()

        // Withdrawing by snapshot
        bridge.withdrawBySnapshot(
                stateProof.web3StateData(),
                stateProof.web3StateProof()
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
        wdTxHashInitiatedAfterMassExit = withdrawOnPostchainV2(aliceCredentials, aliceAccount, assetId, 1.chr, bridgeAddress, bcRid)
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
        assertThat(exception.message!!).contains("TokenBridge: action is not allowed during mass exit")

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
        val gasLimitPauseFunction = estimateGas(bridgeAddress.toHex(), pauseFunctionData)
        val gasPricePauseFunction = gasProvider.gasPrice

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
        val gasLimitUnpauseFunction = estimateGas(bridgeAddress.toHex(), pauseFunctionData)
        val gasPriceUnpauseFunction = gasProvider.gasPrice

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
