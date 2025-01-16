package net.postchain.eif

import assertk.assertThat
import assertk.assertions.isEqualTo
import net.postchain.base.BaseBlockWitness
import net.postchain.base.configuration.KEY_SIGNERS
import net.postchain.base.snapshot.SimpleDigestSystem
import net.postchain.common.BlockchainRid
import net.postchain.common.data.Hash
import net.postchain.common.data.KECCAK256
import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.concurrent.util.get
import net.postchain.core.block.BlockQueries
import net.postchain.crypto.KeyPair
import net.postchain.crypto.PubKey
import net.postchain.crypto.devtools.KeyPairHelper
import net.postchain.devtools.PostchainTestNode
import net.postchain.devtools.PostchainTestNode.Companion.DEFAULT_CHAIN_IID
import net.postchain.eif.contracts.Validator
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvArray
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvInteger
import net.postchain.gtv.GtvNull
import net.postchain.gtv.gtvml.GtvMLParser
import net.postchain.gtv.merkleHash
import net.postchain.gtx.GtxBuilder
import org.awaitility.Awaitility.await
import org.awaitility.Duration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.crypto.Credentials
import org.web3j.crypto.Sign
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class HBridgeBaseIntegrationTest : EifBaseIntegrationTest() {

    lateinit var ds: SimpleDigestSystem

    val decimals = 18
    inline val Int.chr: BigInteger get() = BigInteger(this.toString() + "0".repeat(decimals), 10)

    val node0EvmAddress = Address("659e4a3726275edFD125F52338ECe0d54d15BD99")
    val node1EvmAddress = Address("2c3fA9C9FC3C5CB2f9C09aF6f7214f64382eA086")

    // Contracts
    lateinit var validator: Validator
    lateinit var testTokenAddress: ByteArray

    var chainId = -1L
    lateinit var node: PostchainTestNode
    lateinit var blockQuery: BlockQueries

    lateinit var assetId: ByteArray

    lateinit var bcRid: BlockchainRid

    var currentBlockHeight = 0L

    // * Users
    // Admin
    val adminKeyPair = KeyPairHelper.keyPair(0)

    // Alice
    val aliceCredentials = UserCredentials(
            keyPair = KeyPair(
                    "038f888dec563b5bc253e87abc90afd26c3287021d10236ea19d248043dc39e0b8".hexStringToByteArray(),
                    "71b5b7f8de0661af934a5e4612f3d0ba183e639bdf4e7452fb6457ed3cfbc825".hexStringToByteArray()),
            // Alice's EVM address is not computed based on Alice's Chromia key pair
            evmCredentials = evmCredentials
    )
    lateinit var aliceAccount: FtAccount
    lateinit var aliceBalance: Uint256

    // Bob
    val bobCredentials = UserCredentials(
            keyPair = KeyPair(
                    "02C568851773991374293504BCB593A88C3D1B799C48AB3B250138B1CEE7D08CE3".hexStringToByteArray(),
                    "346B362B66A4F3CE3FEB41043E522C625B6310DEBEBA0E69DF2011748FB38325".hexStringToByteArray()),
            evmCredentials = Credentials.create("346B362B66A4F3CE3FEB41043E522C625B6310DEBEBA0E69DF2011748FB38325")
    )
    lateinit var bobAccount: FtAccount

    @BeforeAll
    fun setupBeforeAll() {
        logger.info("setupBeforeAll")
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
        logger.info("tearDownAfterAll")
        super.tearDown() // Calling @AfterEach IntegrationTestSetup.tearDown()
        evmContainer.stop()
    }

    @AfterEach
    override fun tearDown() {
        // This method blocks @AfterEach EifBaseIntegrationTest.tearDown()
    }

    // Register asset on postchain
    fun registerAsset(
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

    enum class BridgeMode {
        native,
        foreign
    }

    fun mintChromiaAsset(
            account: FtAccount,
            assetId: ByteArray,
            amount: BigInteger,
            keyPair: KeyPair,
    ): ByteArray = GtxBuilder(bcRid, listOf(keyPair.pubKey.data), myCS)
            .addOperation(
                    "ft4.admin.mint",
                    gtv(account.accountId),
                    gtv(assetId),
                    gtv(amount),
            )
            .finish()
            .sign(cryptoSystem.buildSigMaker(keyPair))
            .buildGtx()
            .encode()

    fun registerERC20Asset(
            tokenAddress: ByteArray,
            assetId: ByteArray,
            bcRid: BlockchainRid,
            keyPair: KeyPair,
            mode: BridgeMode,
    ): ByteArray = GtxBuilder(bcRid, listOf(keyPair.pubKey.data), myCS)
            .addOperation(
                    "eif.hbridge.register_erc20_asset",
                    gtv(networkId),
                    gtv(tokenAddress),
                    gtv(assetId),
                    gtv(mode.ordinal.toLong()),
                    gtv(true) // enable snapshots
            )
            .finish()
            .sign(cryptoSystem.buildSigMaker(keyPair))
            .buildGtx()
            .encode()

    fun configureEventReceiverContract(
            contractAddress: ByteArray,
            bcRid: BlockchainRid,
            keyPair: KeyPair
    ): ByteArray = GtxBuilder(bcRid, listOf(keyPair.pubKey.data), myCS)
            .addOperation(
                    "eif.hbridge.add_contract_config",
                    gtv(networkId),
                    gtv(contractAddress),
            )
            .finish()
            .sign(cryptoSystem.buildSigMaker(keyPair))
            .buildGtx()
            .encode()

    fun configureBridgeWithErc20Assets(
            bridgeAddress: ByteArray,
            bcRid: BlockchainRid,
            keyPair: KeyPair
    ): ByteArray = GtxBuilder(bcRid, listOf(keyPair.pubKey.data), myCS)
            .addOperation(
                    "eif.hbridge.register_bridge_with_erc20_assets",
                    gtv(networkId),
                    gtv(bridgeAddress)
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
    fun registerAccount(
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

    fun linkAccount(
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
                gtv(mapOf("op_name" to opName, "op_args" to opArgs))
        ).get().asString()
                .replace("{blockchain_rid}", bcRid.toHex().uppercase())
                .replace("{nonce}", nonce.toHex().uppercase())
                .replace("{account_id}", userAccount.accountId.toHex().uppercase())
                .replace("{auth_descriptor_id}", userAccount.authDescriptorId.toHex().uppercase())
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

    // Withdraw ft token on postchain
    fun withdrawOnPostchainV1(
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

    // Withdraw ft token on postchain
    fun withdrawOnPostchainV2(
            userCredentials: UserCredentials,
            userAccount: FtAccount,
            assetId: ByteArray,
            withdrawAmount: BigInteger,
            bridgeAddress: ByteArray,
            bcRid: BlockchainRid
    ): Hash {

        val b = GtxBuilder(bcRid, listOf(userCredentials.keyPair.pubKey.data), myCS)

        b.addOperation("ft4.ft_auth", gtv(userAccount.accountId), gtv(userAccount.authDescriptorId))
        b.addOperation(
                "eif.hbridge.bridge_ft_asset_to_evm",
                gtv(assetId),
                gtv(withdrawAmount),
                gtv(networkId),
                gtv(userCredentials.evmAddressBA),
                gtv(bridgeAddress)
        )
        b.addOperation("nop", GtvInteger(System.currentTimeMillis()))

        val signer = cryptoSystem.buildSigMaker(userCredentials.keyPair)
        val tx = b.finish().sign(signer).buildGtx()
        val txRid = tx.calculateTxRid(hashCalculator)
        enqueueTx(tx.encode())

        return txRid
    }

    // Transfer ft4 token to another account
    fun transfer(
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

    fun updateValidatorsInPostchain() {
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
    fun updateValidatorsInValidatorContract() {
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

    fun verifyWithdrawalStatusOnPostchain(eventHash: ByteArray, expectedStatus: WithdrawalStatus) {
        await().pollInterval(Duration.TWO_SECONDS).atMost(Duration.ONE_MINUTE).untilAsserted {
            sealBlock()

            val withdrawal = blockQuery.query("eif.hbridge.get_erc20_withdrawal_by_event_hash", gtv("event_hash" to gtv(eventHash))).get()
            assertThat(withdrawal.asDict()["status"]?.asString()).isEqualTo(expectedStatus.name)
        }
    }

    fun getContractValidatorList(): List<Address> {
        val count = validator.validatorCount.send().value.toLong()
        val validators = mutableListOf<Address>()
        (0 until count).forEach {
            validators.add(validator.validators(Uint256(it)).send())
        }
        return validators
    }

    fun getLastWithdrawal(userCredentials: UserCredentials): Map<String, Gtv> {
        val all = blockQuery.query("eif.hbridge.get_erc20_withdrawal", gtv(
                "network_id" to gtv(networkId),
                "token_address" to gtv(testTokenAddress),
                "beneficiary" to gtv(userCredentials.evmAddressBA)
        )).get().asArray()

        return all.map { it.asDict() }.maxByOrNull { it["serial"]!!.asInteger() }!!
    }

    fun loadEifBlockchainConfig(): Gtv = GtvMLParser.parseGtvML(
            javaClass.getResource("/net/postchain/eif/eif.xml")!!.readText()
    )

    fun getWithdrawalEventHashByTxRid(txRid: ByteArray): ByteArray = blockQuery.query(
            "eif.hbridge.get_erc20_withdrawal_by_tx",
            gtv("tx_rid" to gtv(txRid), "op_index" to gtv(1))
    ).get().asDict()["event_hash"]!!.asByteArray()

    fun getAssetBalance(userAccount: FtAccount): BigInteger? {
        val balanceGtv = blockQuery.query("ft4.get_asset_balance",
                gtv("account_id" to gtv(userAccount.accountId), "asset_id" to gtv(assetId))
        ).get()

        return if (balanceGtv.isNull()) null else balanceGtv["amount"]!!.asBigInteger()
    }

    fun buildEvmBlocks(nrOfBlocks: Int = 1) {
        val targetBlockNumber = web3j.ethBlockNumber().send().blockNumber + nrOfBlocks.toBigInteger()
        await().atMost(Duration.TEN_SECONDS).until {
            web3j.ethBlockNumber().send().blockNumber >= targetBlockNumber
        }
    }

    fun networkContractDiscriminator(networkId: Long, contractAddress: ByteArray) =
            gtv(BigInteger.valueOf(networkId).shiftLeft(160) + BigInteger(contractAddress))

    @Suppress("EnumEntryName")
    enum class WithdrawalStatus {
        created,
        requested,
        pending,
        withdrawn,
        withdrawn_to_chromia,
    }
}
