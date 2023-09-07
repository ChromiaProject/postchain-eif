package net.postchain.eif

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import net.postchain.base.snapshot.SimpleDigestSystem
import net.postchain.common.BlockchainRid
import net.postchain.common.data.KECCAK256
import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.concurrent.util.get
import net.postchain.core.Transaction
import net.postchain.crypto.KeyPair
import net.postchain.crypto.SigMaker
import net.postchain.crypto.devtools.KeyPairHelper
import net.postchain.devtools.IntegrationTestSetup
import net.postchain.devtools.testinfra.BaseTestInfrastructureFactory
import net.postchain.eif.contracts.TestToken
import net.postchain.eif.contracts.TokenBridge
import net.postchain.eif.contracts.Validator
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvArray
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvInteger
import net.postchain.gtv.GtvNull
import net.postchain.gtx.GtxBuilder
import org.awaitility.Awaitility
import org.awaitility.Duration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.wait.strategy.Wait
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.crypto.Credentials
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
import java.security.MessageDigest

enum class EvmType {
    GETH, BSC
}

abstract class EifIntegrationTest(evmType: EvmType) : IntegrationTestSetup() {

    private val networkId = 1337L
    private val gasProvider = DefaultGasProvider()
    private val evmContainer = when (evmType) {
        EvmType.GETH -> {
            GethContainer().withExposedService(
                    "geth", 8545,
                    Wait.forLogMessage(".*HTTP server started.*\\s", 1))
        }
        EvmType.BSC -> {
            BscContainer().withExposedService(
                    "geth", 8545,
                    Wait.forLogMessage(".*HTTP server started.*\\s", 1))
        }
    }
    private val credentials = Credentials
            .create("0x53914554952e5473a54b211a31303078abde83b8128995785901eed28df3f610")

    private val tokenBridgeBinary = getBinaryFromArtifactResource("/artifacts/contracts/TokenBridge.sol/TokenBridge.json")
    private val testTokenBinary = getBinaryFromArtifactResource("/artifacts/contracts/token/TestToken.sol/TestToken.json")
    private val validatorBinary = getBinaryFromArtifactResource("/artifacts/contracts/Validator.sol/Validator.json")

    private lateinit var web3j: Web3j
    private lateinit var transactionManager: TransactionManager

    @BeforeEach
    fun setup() {
        evmContainer.start()

        val evmHost = evmContainer.getServiceHost("geth", 8545)
        val evmPort = evmContainer.getServicePort("geth", 8545)
        web3j = Web3j.build(
                HttpService(
                        "http://$evmHost:$evmPort"
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
            setProperty("ethereum.urls", "http://127.0.0.1:8888, http://127.0.0.1:9999, http://$evmHost:$evmPort")
            setProperty("ethereum.maxReadAhead", 200)
            setProperty("ethereum.maxQueueSize", 100)
            setProperty("evm.maxTryErrors", 1)
        }
    }

    @AfterEach
    override fun tearDown() {
        super.tearDown()
        web3j.shutdown()
        evmContainer.stop()
    }

    @Test
    fun deposit() {
        val initialMint = 100L

        // Deploy validator contract
        val postchainValidator = "659e4a3726275edFD125F52338ECe0d54d15BD99"
        val encodedConstructor = FunctionEncoder.encodeConstructor(listOf(DynamicArray(Address::class.java, Address(postchainValidator))))
        val validator = Contract.deployRemoteCall(Validator::class.java, web3j, transactionManager, gasProvider, validatorBinary, encodedConstructor).send()

        // Deploy token bridge contract
        val bridge = Contract.deployRemoteCall(TokenBridge::class.java, web3j, transactionManager, gasProvider, tokenBridgeBinary, "").send().apply {
            initialize(Address(validator.contractAddress)).send()
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

        enqueueTx(registerAsset("Chromia", bcRid, sigMaker))
        sealBlock()

        val value = node.getBlockchainInstance().blockchainEngine.getBlockQueries()
                .query("ft3.get_asset_by_name", gtv("name" to gtv("Chromia"))).get()
        val assetId = value[0]["id"]!!

        // Register evm account
        val evmAddress = "e105ba42b66d08ac7ca7fc48c583599044a6dab3"
        val userEvmAddress = evmAddress.hexStringToByteArray()
        val userPubkey = "038f888dec563b5bc253e87abc90afd26c3287021d10236ea19d248043dc39e0b8".hexStringToByteArray()
        val userPriKey = "71b5b7f8de0661af934a5e4612f3d0ba183e639bdf4e7452fb6457ed3cfbc825".hexStringToByteArray()

        val otherEvmAddressString = "661683e5d36E83B38B1a20247ba6F5c410dC165d"
        val otherEvmAddess = otherEvmAddressString.hexStringToByteArray()
        val otherPubkey = "02E0A8A3C79C9F18B7CEAD2493435AC926B4A527EF670B873F5F1410084EFF9C80".hexStringToByteArray()
        val otherPrikey = "B31AB878C62B0E940B345C659A456D3573CF25960823C34C7BEEB5D1F813BEFD".hexStringToByteArray()
        val sig = gtv(
                gtv("39b0c8c44a10d0fd70c0ed0e833cf6d93818ae1b10777857eb868516932796dc".hexStringToByteArray()),
                gtv("44de8f297cce55c3da8401dd77269d0baf978f60e97ebc5717d4c8eeaed3bea9".hexStringToByteArray()),
                gtv(28L))

        val otherSig = gtv(
                gtv("8fa4216cd5979efdeb109e10f87225ea9579fd21289fac7f1410278554e79aff".hexStringToByteArray()),
                gtv("442017757e4e627a98d40c89cdbdde4612251cc86acabba27cf1683cd1d7cb4c".hexStringToByteArray()),
                gtv(28L))

        enqueueTx(addNewEvmErc20(testTokenAddress, "Chromia", "CHR", 6, bcRid, sigMaker))
        enqueueTx(addTokenMapping(testTokenAddress, assetId, bcRid, sigMaker))
        enqueueTx(registerAccount(userPubkey, userPriKey, userEvmAddress, sig, bcRid))
        enqueueTx(registerAccount(otherPubkey, otherPrikey, otherEvmAddess, otherSig, bcRid))
        sealBlock()

        // query ft3 account by evm address
        val blockQuery = node.getBlockchainInstance().blockchainEngine.getBlockQueries()
        val accountId = blockQuery.query("ft3.evm.get_account_by_evm_address", gtv("acc" to gtv(userEvmAddress))).get()
        val otherAccountId = blockQuery.query("ft3.evm.get_account_by_evm_address", gtv("acc" to gtv(otherEvmAddess))).get()

        // Deposit to postchain
        for (i in 1..5) {
            bridge.deposit(Address(testToken.contractAddress), Uint256(BigInteger.TEN), Bytes32(accountId.asByteArray())).send()
        }

        val depositedAmount = 50L
        var userBalance = testToken.balanceOf(Address(evmAddress)).send()
        assertEquals(userBalance.value, BigInteger.valueOf(initialMint - depositedAmount))

        // Check the ft3 balance
        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            sealBlock() // keep postchain mine new blocks to ensure that all evm deposits are recorded
            val balance = blockQuery.query("ft3.get_asset_balance", gtv("account_id" to accountId, "asset_id" to assetId)).get()["amount"]!!.asInteger()
            assertEquals(depositedAmount, balance)
        }

        // Check eif state for account as well
        val expectedState = SimpleGtvEncoder.encodeGtv(gtv(
                gtv(to32Bytes(evmAddress)), // encode gtv array with assumption that the data contains only byte32 and uint256
                gtv(1*2*32), // 2 * 32 bytes per entry
                gtv(to32Bytes(testToken.contractAddress.substring(2))), // encode gtv array with assumption that the data contains only byte32 and uint256
                gtv(depositedAmount)
        ))
        val accounts = blockQuery.query("get_network_accounts", gtv("network_id" to gtv(networkId))).get()
        val accountNumber = accounts[0].asDict()["state_n"]!!

        val args = gtv(
                "blockHeight" to gtv(currentBlockHeight),
                "accountNumber" to gtv(accountNumber.asInteger())
        )
        val accountState = blockQuery.query("get_account_state_merkle_proof", args).get().asDict()

        val stateData = accountState["stateData"]!!
        assertEquals(stateData.asByteArray().contentEquals(expectedState), true)

        // Bridge some ft3 token to evm
        val auth = blockQuery.query(
                "ft3.get_account_auth_descriptors",
                gtv("id" to accountId)
        ).get()[0].asDict()["id"]!!
        val authId = gtv(accountId, auth)

        val withdrawAmount = 5L
        enqueueTx(withdrawOnPostchain(userPubkey, userPriKey, authId, testTokenAddress, userEvmAddress, withdrawAmount, bcRid))
        sealBlock()

        // Check eif state for account after withdraw as well
        val expectedState1 = SimpleGtvEncoder.encodeGtv(gtv(
                gtv(to32Bytes(evmAddress)), // encode gtv array with assumption that the data contains only byte32 and uint256
                gtv(1*2*32), // 2 * 32 bytes per entry
                gtv(to32Bytes(testToken.contractAddress.substring(2))), // encode gtv array with assumption that the data contains only byte32 and uint256
                gtv(depositedAmount-withdrawAmount)
        ))
        val arg1 = gtv(
                "blockHeight" to gtv(currentBlockHeight),
                "accountNumber" to gtv(accountNumber.asInteger())
        )
        val accountState1 = blockQuery.query("get_account_state_merkle_proof", arg1).get().asDict()

        val stateData1 = accountState1["stateData"]!!
        assertEquals(stateData1.asByteArray().contentEquals(expectedState1), true)

        val balance = blockQuery.query("ft3.get_asset_balance",
                gtv("account_id" to accountId, "asset_id" to assetId)).get()["amount"]!!.asInteger()
        assertEquals(depositedAmount - withdrawAmount, balance)

        // Get and verify the withdrawal data
        val withdrawInfo = blockQuery.query("get_erc20_withdrawal", gtv(
                "network_id" to gtv(networkId),
                "token_address" to gtv(testTokenAddress),
                "beneficiary" to gtv(userEvmAddress)
        )).get()[0].asDict()
        assertEquals(withdrawInfo["amount"]!!.asInteger(), withdrawAmount)
        val serial = withdrawInfo["serial"]!!.asInteger()

        // Query to get the event proof to withdraw fund on evm
        val eventData = gtv(
                gtv(serial),
                gtv(networkId),
                gtv(to32Bytes(testToken.contractAddress.substring(2))),
                gtv(to32Bytes(evmAddress)),
                gtv(withdrawAmount)
        )
        val ds = SimpleDigestSystem(MessageDigest.getInstance(KECCAK256))
        val eventHash = ds.digest(SimpleGtvEncoder.encodeGtv(eventData))
        val eventProof = blockQuery.query("get_event_merkle_proof",
                gtv("eventHash" to gtv(eventHash.toHex()))).get().asDict()

        val actualEventData = eventProof["eventData"]!!.asByteArray()
        assertEquals(
                SimpleGtvEncoder.encodeGtv(eventData).contentEquals(actualEventData),
                true
        )

        val blockHeader = eventProof["blockHeader"]!!.asByteArray()

        val p = eventProof["eventProof"]!!.asDict()
        val leaf = Bytes32(p["leaf"]!!.asByteArray())
        val position = Uint256(p["position"]!!.asInteger())
        val merkleProofs = p["merkleProofs"]!!.asArray().map { Bytes32(it.asByteArray()) }
        val proof = TokenBridge.Proof(leaf, position, DynamicArray(Bytes32::class.java, merkleProofs))
        val blockWitness = eventProof["blockWitness"]!!.asArray()
        val signatures = blockWitness.map { DynamicBytes(it.asDict()["sig"]!!.asByteArray()) }
        val signers = blockWitness.map { Address(it.asDict()["pubkey"]!!.asByteArray().toHex()) }
        val extraMerkleProof = eventProof["extraMerkleProof"]!!.asDict()
        val extraProofs = extraMerkleProof["extraMerkleProofs"]!!.asArray().map { Bytes32(it.asByteArray()) }
        val extraProofData = TokenBridge.ExtraProofData(
                DynamicBytes(extraMerkleProof["leaf"]!!.asByteArray()),
                Bytes32(extraMerkleProof["hashedLeaf"]!!.asByteArray()),
                Uint256(extraMerkleProof["position"]!!.asInteger()),
                Bytes32(extraMerkleProof["extraRoot"]!!.asByteArray()),
                DynamicArray(Bytes32::class.java, extraProofs)
        )

        var receipt = bridge.withdrawRequest(
                DynamicBytes(actualEventData),
                proof,
                DynamicBytes(blockHeader),
                DynamicArray(DynamicBytes::class.java, signatures),
                DynamicArray(Address::class.java, signers),
                extraProofData
        ).send()
        // wait some seconds to allow evm node to mine some new blocks
        // that mature enough to withdraw requesting fund
        Awaitility.await().atMost(Duration.TEN_SECONDS).until {
            val block = web3j.ethGetBlockByNumber(DefaultBlockParameter.valueOf(receipt.blockNumber.add(BigInteger.TWO)), false).send()
            block.block != null
        }
        bridge.withdraw(Bytes32(eventHash), Address(evmAddress)).send()
        userBalance = testToken.balanceOf(Address(evmAddress)).send()
        assertEquals(userBalance.value, BigInteger.valueOf(initialMint - depositedAmount + withdrawAmount))

        // Transfer ft3 token to another account
        val transferAmount = 20L
        val inputs = GtvArray(arrayOf(gtv(
                accountId,
                assetId,
                auth,
                gtv(transferAmount),
                gtv(mapOf())
        )))

        val outputs = GtvArray(arrayOf(gtv(
                otherAccountId,
                assetId,
                gtv(transferAmount),
                gtv(mapOf())
        )))

        enqueueTx(transfer(userPubkey, userPriKey, inputs, outputs, bcRid))
        sealBlock()

        enqueueTx(withdrawOnPostchain(userPubkey, userPriKey, authId, testTokenAddress, userEvmAddress, withdrawAmount, bcRid))
        sealBlock()

        // Get the last snapshot block height as mass-exit block
        var lastBlockHeight = currentBlockHeight
        var lastBlockRID: ByteArray? = null
        while (lastBlockHeight >= 0) {
            val block = blockQuery.getBlockAtHeight(lastBlockHeight, false).get()
            val header = block!!.header.rawData.toHex()
            if (header.takeLast(64) != "0".repeat(64)) {
                lastBlockRID = block.header.blockRID
                break
            }
            lastBlockHeight--
        }

        assertNotNull(lastBlockRID, "There should be valid block for mass-exit")
        if (lastBlockRID != null) {
            bridge.triggerMassExit(Uint256(lastBlockHeight), Bytes32(lastBlockRID)).send()

            // Withdraw request on evm for the last postchain withdraw
            val withdrawInfo2 = blockQuery.query("get_erc20_withdrawal", gtv(
                    "network_id" to gtv(networkId),
                    "token_address" to gtv(testTokenAddress),
                    "beneficiary" to gtv(userEvmAddress)
            )).get()[0].asDict()
            assertEquals(withdrawInfo2["amount"]!!.asInteger(), withdrawAmount)
            val serial2 = withdrawInfo2["serial"]!!.asInteger()

            // Query to get the event proof to withdraw fund on evm
            val eventData2 = gtv(
                    gtv(serial2),
                    gtv(networkId),
                    gtv(to32Bytes(testToken.contractAddress.substring(2))),
                    gtv(to32Bytes(evmAddress)),
                    gtv(withdrawAmount)
            )
            val eventHash2 = ds.digest(SimpleGtvEncoder.encodeGtv(eventData2))
            val eventProof2 = blockQuery.query("get_event_merkle_proof",
                    gtv("eventHash" to gtv(eventHash2.toHex()))).get().asDict()

            val actualEventData2 = eventProof2["eventData"]!!.asByteArray()
            assertEquals(
                    SimpleGtvEncoder.encodeGtv(eventData2).contentEquals(actualEventData2),
                    true
            )

            val blockHeader2 = eventProof2["blockHeader"]!!.asByteArray()

            val p2 = eventProof2["eventProof"]!!.asDict()
            val leaf2 = Bytes32(p2["leaf"]!!.asByteArray())
            val position2 = Uint256(p2["position"]!!.asInteger())
            val merkleProofs2 = p2["merkleProofs"]!!.asArray().map { Bytes32(it.asByteArray()) }
            val proof2 = TokenBridge.Proof(leaf2, position2, DynamicArray(Bytes32::class.java, merkleProofs2))

            val blockWitness2 = eventProof2["blockWitness"]!!.asArray()
            val signatures2 = blockWitness2.map { DynamicBytes(it.asDict()["sig"]!!.asByteArray()) }
            val signers2 = blockWitness2.map { Address(it.asDict()["pubkey"]!!.asByteArray().toHex()) }

            val extraMerkleProof2 = eventProof2["extraMerkleProof"]!!.asDict()
            val extraProofs2 = extraMerkleProof2["extraMerkleProofs"]!!.asArray().map { Bytes32(it.asByteArray()) }
            val extraProofData2 = TokenBridge.ExtraProofData(
                    DynamicBytes(extraMerkleProof2["leaf"]!!.asByteArray()),
                    Bytes32(extraMerkleProof2["hashedLeaf"]!!.asByteArray()),
                    Uint256(extraMerkleProof2["position"]!!.asInteger()),
                    Bytes32(extraMerkleProof2["extraRoot"]!!.asByteArray()),
                    DynamicArray(Bytes32::class.java, extraProofs2)
            )

            receipt = bridge.withdrawRequest(
                    DynamicBytes(actualEventData2),
                    proof2,
                    DynamicBytes(blockHeader2),
                    DynamicArray(DynamicBytes::class.java, signatures2),
                    DynamicArray(Address::class.java, signers2),
                    extraProofData2
            ).send()

            // wait some seconds to allow evm node to mine some new blocks
            // that mature enough to withdraw requesting fund
            Awaitility.await().atMost(Duration.TEN_SECONDS).until {
                val block = web3j.ethGetBlockByNumber(DefaultBlockParameter.valueOf(receipt.blockNumber.add(BigInteger.TWO)), false).send()
                block.block != null
            }
            bridge.withdraw(Bytes32(eventHash2), Address(evmAddress)).send()
            userBalance = testToken.balanceOf(Address(evmAddress)).send()
            assertEquals(userBalance.value, BigInteger.valueOf(initialMint - depositedAmount + 2*withdrawAmount))

            // Withdraw remaining token of the account by using snapshot state with mass-exit
            val state = blockQuery.query("get_account_state_merkle_proof",
                    gtv(
                            "blockHeight" to gtv(lastBlockHeight),
                            "accountNumber" to accountNumber
                    )).get().asDict()

            val stateStateData = state["stateData"]!!.asByteArray()
            val stateProof = state["stateProof"]!!.asDict()
            val stateLeaf = Bytes32(stateProof["leaf"]!!.asByteArray())
            val statePosition = Uint256(stateProof["position"]!!.asInteger())
            val stateMerkleProofs = stateProof["merkleProofs"]!!.asArray().map { Bytes32(it.asByteArray()) }
            val stateProof2 = TokenBridge.Proof(stateLeaf, statePosition, DynamicArray(Bytes32::class.java, stateMerkleProofs))
            val stateBlockHeader = state["blockHeader"]!!.asByteArray()
            val stateBlockWitness = state["blockWitness"]!!.asArray()
            val stateSignatures = stateBlockWitness.map { DynamicBytes(it.asDict()["sig"]!!.asByteArray()) }
            val stateSigners = stateBlockWitness.map { Address(it.asDict()["pubkey"]!!.asByteArray().toHex()) }
            val stateExtraMerkleProof = state["extraMerkleProof"]!!.asDict()
            val stateExtraProofs = stateExtraMerkleProof["extraMerkleProofs"]!!.asArray().map { Bytes32(it.asByteArray()) }
            val stateExtraProofData = TokenBridge.ExtraProofData(
                    DynamicBytes(stateExtraMerkleProof["leaf"]!!.asByteArray()),
                    Bytes32(stateExtraMerkleProof["hashedLeaf"]!!.asByteArray()),
                    Uint256(stateExtraMerkleProof["position"]!!.asInteger()),
                    Bytes32(stateExtraMerkleProof["extraRoot"]!!.asByteArray()),
                    DynamicArray(Bytes32::class.java, stateExtraProofs)
            )
            bridge.withdrawBySnapshot(
                    DynamicBytes(stateStateData),
                    stateProof2,
                    DynamicBytes(stateBlockHeader),
                    DynamicArray(DynamicBytes::class.java, stateSignatures),
                    DynamicArray(Address::class.java, stateSigners),
                    stateExtraProofData
            ).send()

            // Withdraw the remaining token balance of other account as well
            val otherAccountNumber = accountNumber.asInteger()+1
            val otherState = blockQuery.query("get_account_state_merkle_proof",
                    gtv(
                            "blockHeight" to gtv(lastBlockHeight),
                            "accountNumber" to gtv(otherAccountNumber)
                    )).get().asDict()

            val otherStateData = otherState["stateData"]!!.asByteArray()
            val otherProof = otherState["stateProof"]!!.asDict()
            val otherLeaf = Bytes32(otherProof["leaf"]!!.asByteArray())
            val otherPosition = Uint256(otherProof["position"]!!.asInteger())
            val otherMerkleProofs = otherProof["merkleProofs"]!!.asArray().map { Bytes32(it.asByteArray()) }
            val otherStateProof = TokenBridge.Proof(otherLeaf, otherPosition, DynamicArray(Bytes32::class.java, otherMerkleProofs))
            val otherExtraMerkleProof = otherState["extraMerkleProof"]!!.asDict()
            val otherExtraProofs = otherExtraMerkleProof["extraMerkleProofs"]!!.asArray().map { Bytes32(it.asByteArray()) }
            val otherExtraProofData = TokenBridge.ExtraProofData(
                    DynamicBytes(otherExtraMerkleProof["leaf"]!!.asByteArray()),
                    Bytes32(otherExtraMerkleProof["hashedLeaf"]!!.asByteArray()),
                    Uint256(otherExtraMerkleProof["position"]!!.asInteger()),
                    Bytes32(otherExtraMerkleProof["extraRoot"]!!.asByteArray()),
                    DynamicArray(Bytes32::class.java, otherExtraProofs)
            )
            bridge.withdrawBySnapshot(
                    DynamicBytes(otherStateData),
                    otherStateProof,
                    DynamicBytes(stateBlockHeader),
                    DynamicArray(DynamicBytes::class.java, stateSignatures),
                    DynamicArray(Address::class.java, stateSigners),
                    otherExtraProofData
            ).send()

            enqueueTx(withdrawOnPostchain(userPubkey, userPriKey, authId, testTokenAddress, userEvmAddress, withdrawAmount, bcRid))
            sealBlock()

            val withdrawInfo3 = blockQuery.query("get_erc20_withdrawal", gtv(
                    "network_id" to gtv(networkId),
                    "token_address" to gtv(testTokenAddress),
                    "beneficiary" to gtv(userEvmAddress)
            )).get()[0].asDict()
            assertEquals(withdrawInfo3["amount"]!!.asInteger(), withdrawAmount)
            val serial3 = withdrawInfo3["serial"]!!.asInteger()

            // Query to get the event proof to withdraw fund on evm
            val eventData3 = gtv(
                    gtv(serial3),
                    gtv(networkId),
                    gtv(to32Bytes(testToken.contractAddress.substring(2))),
                    gtv(to32Bytes(evmAddress)),
                    gtv(withdrawAmount)
            )
            val eventHash3 = ds.digest(SimpleGtvEncoder.encodeGtv(eventData3))
            val eventProof3 = blockQuery.query("get_event_merkle_proof",
                    gtv("eventHash" to gtv(eventHash3.toHex()))).get().asDict()

            val actualEventData3 = eventProof3["eventData"]!!.asByteArray()
            assertEquals(
                    SimpleGtvEncoder.encodeGtv(eventData3).contentEquals(actualEventData3),
                    true
            )

            val blockHeader3 = eventProof3["blockHeader"]!!.asByteArray()

            val p3 = eventProof3["eventProof"]!!.asDict()
            val leaf3 = Bytes32(p3["leaf"]!!.asByteArray())
            val position3 = Uint256(p3["position"]!!.asInteger())
            val merkleProofs3 = p3["merkleProofs"]!!.asArray().map { Bytes32(it.asByteArray()) }
            val proof3 = TokenBridge.Proof(leaf3, position3, DynamicArray(Bytes32::class.java, merkleProofs3))

            val blockWitness3 = eventProof3["blockWitness"]!!.asArray()
            val signatures3 = blockWitness2.map { DynamicBytes(it.asDict()["sig"]!!.asByteArray()) }
            val signers3 = blockWitness3.map { Address(it.asDict()["pubkey"]!!.asByteArray().toHex()) }

            val extraMerkleProof3 = eventProof3["extraMerkleProof"]!!.asDict()
            val extraProofs3 = extraMerkleProof3["extraMerkleProofs"]!!.asArray().map { Bytes32(it.asByteArray()) }
            val extraProofData3 = TokenBridge.ExtraProofData(
                    DynamicBytes(extraMerkleProof3["leaf"]!!.asByteArray()),
                    Bytes32(extraMerkleProof3["hashedLeaf"]!!.asByteArray()),
                    Uint256(extraMerkleProof3["position"]!!.asInteger()),
                    Bytes32(extraMerkleProof3["extraRoot"]!!.asByteArray()),
                    DynamicArray(Bytes32::class.java, extraProofs3)
            )

            // User cannot send withdraw request after the mass-exit block height
            val exception = assertThrows<TransactionException> {
                bridge.withdrawRequest(
                        DynamicBytes(actualEventData3),
                        proof3,
                        DynamicBytes(blockHeader3),
                        DynamicArray(DynamicBytes::class.java, signatures3),
                        DynamicArray(Address::class.java, signers3),
                        extraProofData3
                ).send()
            }
            assertEquals(exception.message!!.contains("TokenBridge: cannot withdraw request after the mass exit block height"), true)

            userBalance = testToken.balanceOf(Address(evmAddress)).send()
            assertEquals(userBalance.value, BigInteger.valueOf(initialMint-transferAmount))
            userBalance = testToken.balanceOf(Address(otherEvmAddressString)).send()
            assertEquals(userBalance.value, BigInteger.valueOf(transferAmount))
        }
    }

    // get smart contract binary from resource
    private fun getBinaryFromArtifactResource(resourcePath: String): String {
        val artifactFile = javaClass.getResource(resourcePath)?.readText()
        val artifactJson = GsonBuilder().create().fromJson(artifactFile, JsonObject::class.java)
        return artifactJson.get("bytecode").asString
    }

    /**
     * convert evm address to 32 bytes to compliance with EIF simple gtv encoder
     * @see SimpleGtvEncoder.encodeGtv
     */
    private fun to32Bytes(address: String) = "000000000000000000000000$address".hexStringToByteArray()

    // Register asset on postchain
    private fun registerAsset(name: String, bcRid: BlockchainRid, sigMaker: SigMaker): ByteArray {
        val b = GtxBuilder(bcRid, listOf(KeyPairHelper.pubKey(0)), myCS)
        b.addOperation("ft3.dev_register_asset", gtv(name), gtv(bcRid.data))
        return b.finish()
                .sign(sigMaker)
                .buildGtx()
                .encode()
    }

    // Add new evm erc20 token
    private fun addNewEvmErc20(tokenAddress: ByteArray, name: String, symbol: String, decimal: Long, bcRid: BlockchainRid, sigMaker: SigMaker): ByteArray {
        val b = GtxBuilder(bcRid, listOf(KeyPairHelper.pubKey(0)), myCS)
        b.addOperation("add_new_evm_erc20", gtv(networkId), gtv(tokenAddress), gtv(name), gtv(symbol), gtv(decimal))
        return b.finish()
                .sign(sigMaker)
                .buildGtx()
                .encode()
    }

    // Add new token mapping
    private fun addTokenMapping(tokenAddress: ByteArray, assetId: Gtv, bcRid: BlockchainRid, sigMaker: SigMaker): ByteArray {
        val b = GtxBuilder(bcRid, listOf(KeyPairHelper.pubKey(0)), myCS)
        b.addOperation("add_new_token_mapping", gtv(networkId), gtv(tokenAddress), assetId)
        return b.finish()
                .sign(sigMaker)
                .buildGtx()
                .encode()
    }

    // Register account on postchain
    private fun registerAccount(userPubkey: ByteArray, userPriKey: ByteArray, userEVMAddress: ByteArray, sig: GtvArray, bcRid: BlockchainRid): ByteArray {
        val auth = gtv(
                gtv("S"),
                GtvArray(arrayOf(gtv(userPubkey))),
                gtv(GtvArray(arrayOf(gtv("T"))), gtv(userPubkey)),
                GtvNull
        )

        val b = GtxBuilder(bcRid, listOf(userPubkey), myCS)
        b.addOperation("ft3.evm.register_account", gtv(userEVMAddress), auth, sig)

        val signer = cryptoSystem.buildSigMaker(KeyPair(userPubkey, userPriKey))
        return b.finish()
                .sign(signer)
                .buildGtx()
                .encode()
    }

    // Withdraw ft3 token on postchain
    private fun withdrawOnPostchain(userPubkey: ByteArray, userPriKey: ByteArray,
                                    authId: Gtv, tokenAddress: ByteArray,
                                    userEvmAddress: ByteArray, withdrawAmount: Long, bcRid: BlockchainRid): ByteArray {
        val b = GtxBuilder(bcRid, listOf(userPubkey), myCS)
        b.addOperation("bridge_ft3_token_to_evm", authId, gtv(networkId), gtv(tokenAddress), gtv(userEvmAddress), gtv(withdrawAmount))
        b.addOperation("nop", GtvInteger(System.currentTimeMillis()))
        val signer = cryptoSystem.buildSigMaker(KeyPair(userPubkey, userPriKey))
        return b.finish()
                .sign(signer)
                .buildGtx()
                .encode()
    }

    // Transfer ft3 token to another account
    private fun transfer(userPubkey: ByteArray, userPriKey: ByteArray, inputs: Gtv, outputs: Gtv, bcRid: BlockchainRid): ByteArray {
        val b = GtxBuilder(bcRid, listOf(userPubkey), myCS)
        b.addOperation("ft3.transfer", inputs, outputs)

        val signer = cryptoSystem.buildSigMaker(KeyPair(userPubkey, userPriKey))
        return b.finish()
                .sign(signer)
                .buildGtx()
                .encode()
    }
}