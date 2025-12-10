package net.postchain.eif

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import mu.KotlinLogging
import net.postchain.api.rest.model.TxRid
import net.postchain.common.hexStringToByteArray
import net.postchain.concurrent.util.get
import net.postchain.crypto.KeyPair
import net.postchain.eif.contracts.TestToken
import net.postchain.eif.contracts.TokenBridgeWithSnapshotWithdraw
import net.postchain.eif.contracts.Validator
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import net.postchain.gtx.GtxBuilder
import net.postchain.rell.base.runtime.utils.toGtv
import org.awaitility.Awaitility.await
import org.awaitility.Duration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junitpioneer.jupiter.DisableIfTestFails
import org.testcontainers.junit.jupiter.Testcontainers
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.tx.Contract
import org.web3j.utils.Convert
import java.math.BigDecimal
import java.math.BigInteger

@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@DisableIfTestFails
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HBridgeAccountCreationIT : HBridgeBaseIntegrationTest() {
    private val logger = KotlinLogging.logger("test_logger")

    private val initialMint = 1_000_000_000.chr
    private val aliceDeposit = 1000.chr
    private val bobDeposit = 2000.chr
    private val charlieDeposit = 3000.chr
    private val daveDeposit = 4000.chr
    private val ether = Convert.toWei(BigDecimal.ONE, Convert.Unit.ETHER).toBigInteger()

    // Contracts
    private lateinit var bridge: TokenBridgeWithSnapshotWithdraw
    private lateinit var bobBridge: TokenBridgeWithSnapshotWithdraw
    private lateinit var charlieBridge: TokenBridgeWithSnapshotWithdraw
    private lateinit var daveBridge: TokenBridgeWithSnapshotWithdraw
    private lateinit var bridgeAddress: ByteArray
    private lateinit var bridgeDeployHeight: BigInteger
    private lateinit var testToken: TestToken

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
        bobBridge = TokenBridgeWithSnapshotWithdraw.load(bridge.contractAddress, web3j, createTransactionManager(bobCredentials.evmCredentials), gasProvider)
        charlieBridge = TokenBridgeWithSnapshotWithdraw.load(bridge.contractAddress, web3j, createTransactionManager(charlieCredentials.evmCredentials), gasProvider)
        daveBridge = TokenBridgeWithSnapshotWithdraw.load(bridge.contractAddress, web3j, createTransactionManager(daveCredentials.evmCredentials), gasProvider)
        logger.info { "Token bridge deployed as ${bridge.contractAddress} at height $bridgeDeployHeight" }

        // Deploy a test token that we mint and then approve transfer of coins to chrL2 contract
        testToken = Contract.deployRemoteCall(TestToken::class.java, web3j, transactionManager, gasProvider, testTokenBinary, "").send()
        testToken.mint(Address(transactionManager.fromAddress), Uint256(initialMint)).send() // Alice controls the entire initial supply
        testToken.approve(Address(bridge.contractAddress), Uint256(initialMint)).send() // Bridge can spend the entire initial supply
        testTokenAddress = testToken.contractAddress.substring(2).hexStringToByteArray()

        // Allow token on bridge
        bridge.allowToken(Address(testToken.contractAddress)).send()

        // Send gas token to Bob and Charlie
        transactionManager.sendTransaction(gasProvider.gasPrice, gasProvider.gasLimit, bobCredentials.evmCredentials.address, "", ether)
        transactionManager.sendTransaction(gasProvider.gasPrice, gasProvider.gasLimit, charlieCredentials.evmCredentials.address, "", ether)
        transactionManager.sendTransaction(gasProvider.gasPrice, gasProvider.gasLimit, daveCredentials.evmCredentials.address, "", ether)
        buildEvmBlocks()

        // Approve bridge for Bob's tokens
        val bobTestToken = TestToken.load(testToken.contractAddress, web3j, createTransactionManager(bobCredentials.evmCredentials), gasProvider)
        bobTestToken.approve(Address(bridge.contractAddress), Uint256(bobDeposit)).send()

        // Approve bridge for Charlie's tokens
        val charlieTestToken = TestToken.load(testToken.contractAddress, web3j, createTransactionManager(charlieCredentials.evmCredentials), gasProvider)
        charlieTestToken.approve(Address(bridge.contractAddress), Uint256(charlieDeposit)).send()

        // Approve bridge for Charlie's tokens
        val daveTestToken = TestToken.load(testToken.contractAddress, web3j, createTransactionManager(daveCredentials.evmCredentials), gasProvider)
        daveTestToken.approve(Address(bridge.contractAddress), Uint256(daveDeposit)).send()

        // Alice transfers tokens to Bob, Charlie, and Dave
        testToken.transfer(bobCredentials.evmAddress, Uint256(bobDeposit)).send()
        testToken.transfer(charlieCredentials.evmAddress, Uint256(charlieDeposit)).send()
        testToken.transfer(daveCredentials.evmAddress, Uint256(daveDeposit)).send()

        // Verify initial balances
        assertEvmBalances(
                initialMint - bobDeposit - charlieDeposit - daveDeposit,
                bobDeposit, charlieDeposit, daveDeposit
        )
    }

    @Test
    @Order(2)
    fun `start nodes`() {
        logger.info { "start nodes" }

        // c0
        startManagedSystem(1, 0, restApi = true)

        // c1
        val chainGtvConfig = loadEifBlockchainConfig(0L)
        chainId = startNewBlockchain(
                setOf(0), setOf(), rawBlockchainConfiguration = GtvEncoder.encodeGtv(chainGtvConfig)
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
    fun `register erc20 asset`() {
        logger.info { "register erc20 asset" }

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
        sealBlock()
    }

    @Test
    @Order(4)
    fun `register account on deposit using static ras transfer rules`() {
        logger.info { "register account using static ras transfer rules" }

        aliceAccount = depositAndVerifyAccountCreation(
                aliceCredentials,
                bridge,
                aliceDeposit,
                initialMint - bobDeposit - charlieDeposit - daveDeposit - aliceDeposit
        )
    }

    @Test
    @Order(5)
    fun `register account on deposit using static and dynamic ras transfer rules`() {
        logger.info { "register account using static and dynamic ras transfer rules" }

        // Enable account creation on deposit
        assertAccountCreationOnDeposit(false)
        toggleAccountCreationOnDeposit(networkId, testTokenAddress, adminKeyPair, true)
        await().pollInterval(Duration.ONE_SECOND).atMost(Duration.ONE_MINUTE).untilAsserted {
            sealBlock()
            assertAccountCreationOnDeposit(true)
        }

        bobAccount = depositAndVerifyAccountCreation(
                bobCredentials,
                bobBridge,
                bobDeposit,
                0L.toBigInteger()
        )
    }

    @Test
    @Order(6)
    fun `register account on deposit using dynamic ras transfer rules`() {
        logger.info { "register account using dynamic ras transfer rules" }

        val config = loadEifBlockchainConfig(1L)
        val newConfig = removeRasTransferRule(config)
        addDappBlockchainConfiguration(chainId, GtvEncoder.encodeGtv(newConfig), currentBlockHeight + 2)
        sealBlock() // build block and reloading config
        sealBlock() // build block with the new config

        // Assert that the config has been updated
        val cfg = node.getBlockchainInstance(chainId).blockchainEngine.getConfiguration()
        assertThat(cfg.rawConfig["nonce"]).isEqualTo(1L.toGtv())

        // Cache the block queries after reconfiguration
        blockQuery = node.getBlockchainInstance().blockchainEngine.getBlockQueries()

        charlieAccount = depositAndVerifyAccountCreation(
                charlieCredentials,
                charlieBridge,
                charlieDeposit,
                0L.toBigInteger()
        )
    }

    @Test
    @Order(8)
    fun `cannot register account on deposit with no ras transfer rules`() {
        logger.info { "cannot register account on deposit with no ras transfer rules" }

        // Disable account creation on deposit
        assertAccountCreationOnDeposit(true)
        toggleAccountCreationOnDeposit(networkId, testTokenAddress, adminKeyPair, false)
        await().pollInterval(Duration.ONE_SECOND).atMost(Duration.ONE_MINUTE).untilAsserted {
            sealBlock()
            assertAccountCreationOnDeposit(false)
        }

        // Dave deposits tokens, assert that the deposit was bounced
        depositTokensAndVerify(
                daveCredentials, daveBridge, daveDeposit, 0L.toBigInteger(), true
        )

        // Verify the initial balance is null because the account has not been created yet
        assertAccountBalance(gtvHash(daveCredentials.evmAddressBA), null)

        // Trying to register an account
        daveAccount = registerAccountByDeposit(daveCredentials, bcRid)
        sealBlock()

        // Assert that transaction was rejected
        assertThat(
                node.getRestApiModel(bcRid)
                        ?.getStatus(TxRid(daveAccount.registrationTxRid!!))
                        ?.rejectReason!!
        ).contains("No pending transfer to account id")
    }

    fun depositAndVerifyAccountCreation(
            credentials: UserCredentials,
            bridge: TokenBridgeWithSnapshotWithdraw,
            amount: BigInteger,
            expectedFinalEvmBalance: BigInteger
    ): FtAccount {

        // User deposits tokens
        depositTokensAndVerify(credentials, bridge, amount, expectedFinalEvmBalance)

        // Verify the initial balance is null because the account has not been created yet
        assertAccountBalance(gtvHash(credentials.evmAddressBA), null)

        // Register account
        val account = registerAccountByDeposit(credentials, bcRid)

        // Check the account balance by EVM address
        await().pollInterval(Duration.ONE_SECOND).pollInterval(Duration.ONE_SECOND).atMost(Duration.ONE_MINUTE).untilAsserted {
            sealBlock()

            val expectedAccountId = blockQuery.query(
                    "eif.hbridge.get_account_for_eoa_address",
                    gtv("evm_address" to gtv(credentials.evmAddressBA))
            ).get()
            assertFalse(expectedAccountId.isNull())
            assertThat(expectedAccountId.asByteArray()).isEqualTo(account.accountId)
            assertAccountBalance(account.accountId, amount)
        }

        return account
    }

    fun depositTokensAndVerify(credentials: UserCredentials, bridge: TokenBridgeWithSnapshotWithdraw, amount: BigInteger, expectedFinalEvmBalance: BigInteger, expectBounce: Boolean = false) {
        logger.info { "user 0x${credentials.evmAddressStr} deposits $amount CHR on evm" }

        // Deposit tokens
        bridge.deposit(Address(testToken.contractAddress), Uint256(amount)).send()
        buildEvmBlocks()

        // Verify balances on EVM
        assertThat(testToken.balanceOf(credentials.evmAddress).send().value)
                .isEqualTo(expectedFinalEvmBalance)

        // Assert that the deposit was either created or bounced
        if (expectBounce) {
            await().pollInterval(Duration.ONE_SECOND).atMost(Duration.ONE_MINUTE).untilAsserted {
                sealBlock()

                val withdrawalGtv = blockQuery.query(
                        name = "eif.hbridge.get_erc20_withdrawals",
                        args = gtv("filter" to gtv("beneficiary" to gtv(daveCredentials.evmAddressBA)))
                ).get()

                val withdrawal = withdrawalGtv["data"]?.asArray()?.firstOrNull()
                assertNotNull(withdrawal)
                assertThat(withdrawal!!.asDict()["amount"]?.asBigInteger()).isEqualTo(daveDeposit)
            }
        } else {
            await().pollInterval(Duration.ONE_SECOND).atMost(Duration.ONE_MINUTE).untilAsserted {
                sealBlock()

                val deposit = blockQuery.query(
                        "eif.hbridge.get_erc20_deposits",
                        gtv("filter" to gtv("recipient_id" to gtv(gtvHash(credentials.evmAddressBA))))
                ).get()["data"]?.asArray()?.firstOrNull()
                assertNotNull(deposit)
                assertThat(deposit!!.asDict()["state"]?.asString()).isEqualTo(DepositStatus.pending.name)
            }
        }
    }

    fun assertAccountCreationOnDeposit(expected: Boolean) = assertThat(
            blockQuery.query("eif.hbridge.is_account_creation_on_deposit_enabled",
                    gtv("network_id" to gtv(networkId), "token_address" to gtv(testTokenAddress))
            ).get().asBoolean()
    ).isEqualTo(expected)

    fun assertAccountBalance(accountId: ByteArray, expectedBalance: BigInteger?) = assertThat(
            getAssetBalance(accountId)
    ).isEqualTo(expectedBalance)

    fun assertEvmBalances(aliceBalance: BigInteger, bobBalance: BigInteger, charlieBalance: BigInteger, daveBalance: BigInteger) {
        assertThat(testToken.balanceOf(aliceCredentials.evmAddress).send().value).isEqualTo(aliceBalance)
        assertThat(testToken.balanceOf(bobCredentials.evmAddress).send().value).isEqualTo(bobBalance)
        assertThat(testToken.balanceOf(charlieCredentials.evmAddress).send().value).isEqualTo(charlieBalance)
        assertThat(testToken.balanceOf(daveCredentials.evmAddress).send().value).isEqualTo(daveBalance)
    }

    fun toggleAccountCreationOnDeposit(networkId: Long, tokenAddress: ByteArray, keyPair: KeyPair, enable: Boolean) {
        GtxBuilder(bcRid, listOf(keyPair.pubKey.data), myCS, merkleHashCalculator)
                .addOperation(
                        if (enable) "eif.hbridge.enable_account_creation_on_deposit"
                        else "eif.hbridge.disable_account_creation_on_deposit",
                        gtv(networkId), gtv(tokenAddress)
                )
                .finish()
                .sign(cryptoSystem.buildSigMaker(keyPair))
                .buildGtx()
                .encode().also { enqueueTx(it) }
    }

    fun removeRasTransferRule(gtv: Gtv): Gtv = mapGtvDictValue(gtv, "gtx") { (_, gtx) ->
        mapGtvDictValue(gtx, "rell") { (_, rell) ->
            mapGtvDictValue(rell, "moduleArgs") { (_, moduleArgs) ->
                mapGtvDictValue(moduleArgs, "lib.ft4.core.accounts.strategies.transfer") { null }
            }
        }
    }
}
