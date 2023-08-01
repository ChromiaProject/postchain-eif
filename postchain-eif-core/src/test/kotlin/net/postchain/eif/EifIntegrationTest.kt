package net.postchain.eif

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import net.postchain.base.snapshot.SimpleDigestSystem
import net.postchain.common.data.KECCAK256
import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.concurrent.util.get
import net.postchain.core.Transaction
import net.postchain.crypto.KeyPair
import net.postchain.crypto.devtools.KeyPairHelper
import net.postchain.devtools.IntegrationTestSetup
import net.postchain.devtools.testinfra.BaseTestInfrastructureFactory
import net.postchain.eif.contracts.TestToken
import net.postchain.eif.contracts.TokenBridge
import net.postchain.eif.contracts.Validator
import net.postchain.gtv.GtvArray
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import net.postchain.gtv.merkle.GtvMerkleHashCalculator
import net.postchain.gtv.merkleHash
import net.postchain.gtx.GtxBuilder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Testcontainers
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.DynamicBytes
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
import java.security.MessageDigest

@Testcontainers(disabledWithoutDocker = true)
class EifIntegrationTest : IntegrationTestSetup() {

    private val networkId = 1L
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
    private lateinit var web3j: Web3j
    private lateinit var transactionManager: TransactionManager

    private val tokenBridgeBinary = getBinaryFromArtifactResource("/artifacts/contracts/TokenBridge.sol/TokenBridge.json")
    private val testTokenBinary = getBinaryFromArtifactResource("/artifacts/contracts/token/TestToken.sol/TestToken.json")
    private val validatorBinary = getBinaryFromArtifactResource("/artifacts/contracts/Validator.sol/Validator.json")

    private enum class AuthType {
        S, M, ES, EM
    }
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
        val initialMint = BigInteger("FF".repeat(32), 16)

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
            mint(Address(transactionManager.fromAddress), Uint256(initialMint)).send()
            approve(Address(bridge.contractAddress), Uint256(initialMint)).send()
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
        val tokenName = "Chromia"
        val tokenSymbol = "CHR"
        val tokenDecimal = 18L
        val tokenIconUrl = "https://chromaway.com/chr"
        fun registerAsset(): ByteArray {
            val b = GtxBuilder(bcRid, listOf(KeyPairHelper.pubKey(0)), myCS)
            b.addOperation("ft4.admin.register_asset",
                    gtv(tokenName), gtv(tokenSymbol), gtv(tokenDecimal), gtv(tokenIconUrl))
            return b.finish()
                    .sign(sigMaker)
                    .buildGtx()
                    .encode()
        }
        enqueueTx(registerAsset())
        sealBlock()

        val value = node.getBlockchainInstance().blockchainEngine.getBlockQueries()
                .query("ft4.get_asset_by_name", gtv("name" to gtv(tokenName))).get()
        val assetId = value[0]["id"]!!
        fun addNewEvmErc20(): ByteArray {
            val b = GtxBuilder(bcRid, listOf(KeyPairHelper.pubKey(0)), myCS)
            b.addOperation("add_new_evm_erc20",
                    gtv(networkId), gtv(testTokenAddress), gtv(tokenName), gtv(tokenSymbol), gtv(tokenDecimal))
            return b.finish()
                    .sign(sigMaker)
                    .buildGtx()
                    .encode()
        }

        fun addTokenMapping(): ByteArray {
            val b = GtxBuilder(bcRid, listOf(KeyPairHelper.pubKey(0)), myCS)
            b.addOperation("add_new_token_mapping", gtv(networkId), gtv(testTokenAddress), assetId)
            return b.finish()
                    .sign(sigMaker)
                    .buildGtx()
                    .encode()
        }

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

        fun registerAccount(userPubkey: ByteArray, userPriKey: ByteArray, userEVMAddress: ByteArray, sig: GtvArray): ByteArray {
            val auth = gtv(
                    gtv(AuthType.S.ordinal.toLong()),
                    gtv(GtvArray(arrayOf(gtv("T"))), gtv(userPubkey)),
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

        enqueueTx(addNewEvmErc20())
        enqueueTx(addTokenMapping())
        enqueueTx(registerAccount(userPubkey, userPriKey, userEvmAddress, sig))
        enqueueTx(registerAccount(otherPubkey, otherPrikey, otherEvmAddess, otherSig))
        sealBlock()

        // query ft account id by evm address
        val blockQuery = node.getBlockchainInstance().blockchainEngine.getBlockQueries()
        val accountId = blockQuery.query("eif.evm.get_account_id_by_evm_address",
                gtv("acc" to gtv(userEvmAddress))).get()
        val otherAccountId = blockQuery.query("eif.evm.get_account_id_by_evm_address",
                gtv("acc" to gtv(otherEvmAddess))).get()

        // Deposit to postchain
        val depositedAmount = BigInteger("AA".repeat(16), 16)
        for (i in 1..10) {
            bridge.deposit(Address(testToken.contractAddress), Uint256(depositedAmount), Bytes32(accountId.asByteArray())).send()
        }
        Thread.sleep(3000) // wait some seconds for deposit txs was confirmed
        repeat(20) { sealBlock() } // keep postchain mine new blocks to ensure that all evm deposits are recorded

        val totalDepositedAmount = depositedAmount * BigInteger.TEN
        var userBalance = testToken.balanceOf(Address(evmAddress)).send()
        assertEquals(userBalance.value, initialMint - totalDepositedAmount)

        // Check the asset balance
        var balance = blockQuery.query("ft4.get_asset_balance",
                gtv("account_id" to accountId, "asset_id" to assetId)).get()["amount"]!!.asBigInteger()
        assertEquals(totalDepositedAmount, balance)

        // Check eif state for account as well
        val expectedState = SimpleGtvEncoder.encodeGtv(gtv(
                gtv(to32Bytes(evmAddress)), // encode gtv array with assumption that the data contains only byte32 and uint256
                gtv(1*2*32), // 2 * 32 bytes per entry
                gtv(to32Bytes(testToken.contractAddress.substring(2))), // encode gtv array with assumption that the data contains only byte32 and uint256
                gtv(totalDepositedAmount)
        ))
        val accounts = blockQuery.query("get_network_accounts",
                gtv("network_id" to gtv(networkId))).get()
        val accountNumber = accounts[0].asDict()["state_n"]!!

        val args = gtv(
                "blockHeight" to gtv(currentBlockHeight),
                "accountNumber" to gtv(accountNumber.asInteger())
        )
        val accountState = blockQuery.query("get_account_state_merkle_proof", args).get().asDict()

        val stateData = accountState["stateData"]!!
        assertEquals(stateData.asByteArray().contentEquals(expectedState), true)

        // Bridge some ft token to evm
        val gtvAuthDescriptorId = blockQuery.query(
                "ft4.get_account_auth_descriptors",
                gtv("id" to accountId, "page_size" to gtv(1L), "page_cursor" to GtvNull)
        ).get()["data"]!![0]["id"]!!

        val auth = gtv(
                gtv(AuthType.S.ordinal.toLong()),
                gtv(GtvArray(arrayOf(gtv("T"))), gtv(userPubkey)),
                GtvNull
        )

        val authDescriptorId = auth.merkleHash(GtvMerkleHashCalculator(myCS))
        assertEquals(gtv(authDescriptorId), gtvAuthDescriptorId)
        val authId = gtv(accountId, gtvAuthDescriptorId)

        val withdrawAmount = BigInteger("1234567890", 16)
        fun withdrawOnPostchain(): ByteArray {
            val b = GtxBuilder(bcRid, listOf(userPubkey), myCS)
            b.addOperation("bridge_ft_token_to_evm", authId, gtv(networkId), gtv(testTokenAddress), gtv(userEvmAddress), gtv(withdrawAmount))
            val signer = cryptoSystem.buildSigMaker(KeyPair(userPubkey, userPriKey))
            return b.finish()
                    .sign(signer)
                    .buildGtx()
                    .encode()
        }

        enqueueTx(withdrawOnPostchain())
        sealBlock()

        // Check eif state for account after withdraw as well
        val expectedState1 = SimpleGtvEncoder.encodeGtv(gtv(
                gtv(to32Bytes(evmAddress)), // encode gtv array with assumption that the data contains only byte32 and uint256
                gtv(1*2*32), // 2 * 32 bytes per entry
                gtv(to32Bytes(testToken.contractAddress.substring(2))), // encode gtv array with assumption that the data contains only byte32 and uint256
                gtv(totalDepositedAmount-withdrawAmount)
        ))
        val arg1 = gtv(
                "blockHeight" to gtv(currentBlockHeight),
                "accountNumber" to gtv(accountNumber.asInteger())
        )
        val accountState1 = blockQuery.query("get_account_state_merkle_proof", arg1).get().asDict()

        val stateData1 = accountState1["stateData"]!!
        assertEquals(stateData1.asByteArray().contentEquals(expectedState1), true)

        balance = blockQuery.query("ft4.get_asset_balance",
                gtv("account_id" to accountId, "asset_id" to assetId)).get()["amount"]!!.asBigInteger()
        assertEquals(totalDepositedAmount - withdrawAmount, balance)

        // Get and verify the withdrawal data
        val withdrawInfo = blockQuery.query("get_erc20_withdrawal", gtv(
                "network_id" to gtv(networkId),
                "token_address" to gtv(testTokenAddress),
                "beneficiary" to gtv(userEvmAddress)
        )).get()[0].asDict()
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

        bridge.withdrawRequest(
                DynamicBytes(actualEventData),
                proof,
                DynamicBytes(blockHeader),
                DynamicArray(DynamicBytes::class.java, signatures),
                DynamicArray(Address::class.java, signers),
                extraProofData
        ).send()

        // wait some seconds to allow evm node to mine some new blocks
        // that mature enough to withdraw requesting fund
        Thread.sleep(5000)
        bridge.withdraw(Bytes32(eventHash), Address(evmAddress)).send()
        userBalance = testToken.balanceOf(Address(evmAddress)).send()
        assertEquals(userBalance.value, initialMint - totalDepositedAmount + withdrawAmount)

        // Transfer ft token to another account
        val transferAmount = BigInteger("1234567890ABCDEF", 16)
        fun transfer(): ByteArray {
            val b = GtxBuilder(bcRid, listOf(userPubkey), myCS)
            b.addOperation("ft4.ft_auth", accountId, gtv(authDescriptorId))
            b.addOperation("ft4.transfer", otherAccountId, assetId, gtv(transferAmount))

            val signer = cryptoSystem.buildSigMaker(KeyPair(userPubkey, userPriKey))
            return b.finish()
                    .sign(signer)
                    .buildGtx()
                    .encode()
        }

        enqueueTx(transfer())
        sealBlock()

        // Get the last snapshot block height as mass-exit block
        var lastBlockHeight = currentBlockHeight
        var lastBlockRID: ByteArray? = null
        while (lastBlockHeight >= 0) {
            val block = blockQuery.getBlockAtHeight(lastBlockHeight, false).get()
            val header = block!!.header.rawData.toHex()
            if (header.takeLast(64) != "0000000000000000000000000000000000000000000000000000000000000000") {
                lastBlockRID = block.header.blockRID
                break
            }
            lastBlockHeight--
        }

        assertNotNull(lastBlockRID, "There should be valid block for mass-exit")
        if (lastBlockRID != null) {
            bridge.triggerMassExit(Uint256(lastBlockHeight), Bytes32(lastBlockRID)).send()

            // Withdraw remaining token of the account by using snapshot state with mass-exit
            val state = blockQuery.query("get_account_state_merkle_proof",
                    gtv(
                            "blockHeight" to gtv(lastBlockHeight),
                            "accountNumber" to accountNumber
                    )).get().asDict()

            val stateData = state["stateData"]!!.asByteArray()
            val proof = state["stateProof"]!!.asDict()
            val leaf = Bytes32(proof["leaf"]!!.asByteArray())
            val position = Uint256(proof["position"]!!.asInteger())
            val merkleProofs = proof["merkleProofs"]!!.asArray().map { Bytes32(it.asByteArray()) }
            val stateProof = TokenBridge.Proof(leaf, position, DynamicArray(Bytes32::class.java, merkleProofs))
            val blockHeader = state["blockHeader"]!!.asByteArray()
            val blockWitness = state["blockWitness"]!!.asArray()
            val signatures = blockWitness.map { DynamicBytes(it.asDict()["sig"]!!.asByteArray()) }
            val signers = blockWitness.map { Address(it.asDict()["pubkey"]!!.asByteArray().toHex()) }
            val extraMerkleProof = state["extraMerkleProof"]!!.asDict()
            val extraProofs = extraMerkleProof["extraMerkleProofs"]!!.asArray().map { Bytes32(it.asByteArray()) }
            val extraProofData = TokenBridge.ExtraProofData(
                    DynamicBytes(extraMerkleProof["leaf"]!!.asByteArray()),
                    Bytes32(extraMerkleProof["hashedLeaf"]!!.asByteArray()),
                    Uint256(extraMerkleProof["position"]!!.asInteger()),
                    Bytes32(extraMerkleProof["extraRoot"]!!.asByteArray()),
                    DynamicArray(Bytes32::class.java, extraProofs)
            )
            bridge.withdrawBySnapshot(
                    DynamicBytes(stateData),
                    stateProof,
                    DynamicBytes(blockHeader),
                    DynamicArray(DynamicBytes::class.java, signatures),
                    DynamicArray(Address::class.java, signers),
                    extraProofData
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
                    DynamicBytes(blockHeader),
                    DynamicArray(DynamicBytes::class.java, signatures),
                    DynamicArray(Address::class.java, signers),
                    otherExtraProofData
            ).send()

            userBalance = testToken.balanceOf(Address(evmAddress)).send()
            assertEquals(userBalance.value, initialMint - transferAmount)
            userBalance = testToken.balanceOf(Address(otherEvmAddressString)).send()
            assertEquals(userBalance.value, transferAmount)
        }
    }

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
}