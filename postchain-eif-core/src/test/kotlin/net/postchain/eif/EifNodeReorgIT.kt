package net.postchain.eif

import mu.KotlinLogging
import net.postchain.common.hexStringToByteArray
import net.postchain.concurrent.util.get
import net.postchain.devtools.PostchainTestNode
import net.postchain.eif.contracts.TestToken
import net.postchain.eif.contracts.TokenBridgeWithSnapshotWithdraw
import net.postchain.eif.contracts.Validator
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import net.postchain.gtx.GTXModuleAware
import org.awaitility.Awaitility.await
import org.awaitility.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.methods.request.EthFilter
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.core.methods.response.EthSendTransaction
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.Response
import org.web3j.protocol.http.HttpService
import org.web3j.tx.Contract
import java.math.BigInteger

abstract class EifNodeTestBase : HBridgeBaseIntegrationTest() {
    protected abstract val evmReadOffset: Long
    protected abstract val readOffset: Long

    private val logger = KotlinLogging.logger {}
    protected lateinit var bridge: TokenBridgeWithSnapshotWithdraw
    protected lateinit var token: TestToken
    protected lateinit var bridgeAddress: ByteArray
    protected lateinit var reorgService: HttpService

    protected fun initializeNodeTest() {
        deployBridgeAndToken()
        startPostchain()
        configureHbridge()
    }

    protected fun runReorgTest(expectOrphanedCredit: Boolean) {
        initializeNodeTest()

        val processor = getEventProcessor(node, networkId)
        val depositReceipt = bridge.deposit(Address(token.contractAddress), Uint256(BigInteger.valueOf(100))).send()
        val depositTransaction = web3j.ethGetTransactionByHash(depositReceipt.transactionHash).send().transaction.get()
        val depositHeight = depositReceipt.blockNumber
        val originalBlock = web3j.ethGetBlockByNumber(DefaultBlockParameter.valueOf(depositHeight), false).send().block
        val originalHash = originalBlock.hash

        if (evmReadOffset == 0L && readOffset == 0L) {
            await().atMost(Duration.ONE_MINUTE).untilAsserted {
                assertTrue(processor.getEventData().any { it.evmBlockHeight == depositHeight })
            }
        } else {
            buildEvmBlocks(3)
            assertTrue(processor.getEventData().isEmpty())
        }

        reorgDepositBlock(
                depositHeight = depositHeight,
                originalHash = originalHash,
                transactionNonce = depositTransaction.nonce,
                transactionGasPrice = depositTransaction.gasPrice,
        )

        sealBlock()

        val deposit = blockQuery.query(
                "eif.hbridge.get_erc20_deposit_by_evm_tx_hash",
                gtv("evm_tx_hash" to gtv(depositReceipt.transactionHash.removePrefix("0x").hexStringToByteArray())),
        ).get()
        val lastEvmBlock = blockQuery.query(
                "get_last_evm_block",
                gtv("network_id" to gtv(networkId)),
        ).get()
        val balance = getAssetBalance(aliceAccount)
        logger.info {
            "E2 offsets evmReadOffset=$evmReadOffset readOffset=$readOffset " +
                    "depositHeight=$depositHeight originalHash=$originalHash " +
                    "deposit=$deposit lastEvmBlock=$lastEvmBlock balance=$balance"
        }

        if (expectOrphanedCredit) {
            assertEquals(BigInteger.valueOf(100), balance, "zero confirmation offsets should commit the orphaned deposit")
            assertFalse(lastEvmBlock.isNull(), "orphaned EVM block was not committed to eif_event_receiver")
            assertEquals(depositHeight.toLong(), lastEvmBlock["evm_block_height"]!!.asInteger())
            assertTrue(
                    lastEvmBlock["evm_block_hash"]!!.asByteArray().contentEquals(
                            originalHash.removePrefix("0x").hexStringToByteArray()
                    ),
                    "eif_event_receiver did not retain the orphaned EVM block hash",
            )
        } else {
            assertTrue(deposit.isNull(), "orphaned deposit is present in hbridge state: $deposit")
            assertTrue(balance == null || balance == BigInteger.ZERO, "orphaned deposit was credited: $balance")
            assertTrue(lastEvmBlock.isNull(), "orphaned EVM block is present in eif_event_receiver state: $lastEvmBlock")
        }
    }

    protected fun deployBridgeAndToken() {
        val encodedConstructor = FunctionEncoder.encodeConstructor(
                listOf(DynamicArray(Address::class.java, node0EvmAddress))
        )
        validator = Contract.deployRemoteCall(
                Validator::class.java,
                web3j,
                transactionManager,
                gasProvider,
                validatorBinary,
                encodedConstructor,
        ).send()
        bridge = Contract.deployRemoteCall(
                TokenBridgeWithSnapshotWithdraw::class.java,
                web3j,
                transactionManager,
                gasProvider,
                tokenBridgeWithSnapshotWithdrawBinary,
                "",
        ).send().apply {
            initialize(Address(validator.contractAddress), Uint256(2)).send()
        }
        bridgeAddress = bridge.contractAddress.removePrefix("0x").hexStringToByteArray()
        val bridgeDeployHeight = web3j.ethGetTransactionByHash(bridge.transactionReceipt.get().transactionHash)
                .send().result.blockNumber

        token = Contract.deployRemoteCall(
                TestToken::class.java,
                web3j,
                transactionManager,
                gasProvider,
                testTokenBinary,
                "",
        ).send()
        token.mint(Address(transactionManager.fromAddress), Uint256(BigInteger.valueOf(1_000))).send()
        token.approve(Address(bridge.contractAddress), Uint256(BigInteger.valueOf(1_000))).send()
        bridge.allowToken(Address(token.contractAddress)).send()
        testTokenAddress = token.contractAddress.removePrefix("0x").hexStringToByteArray()

        reorgService = HttpService("http://${evmContainer.getServiceHost("geth", 8545)}:${evmContainer.getServicePort("geth", 8545)}")
        this.bridgeDeployHeight = bridgeDeployHeight
    }

    protected lateinit var bridgeDeployHeight: BigInteger

    protected fun startPostchain() {
        configOverrides.setProperty("messaging.port", 0)
        startManagedSystem(1, 0, restApi = false)
        chainId = startNewBlockchain(
                setOf(0),
                setOf(),
                rawBlockchainConfiguration = GtvEncoder.encodeGtv(loadConfigWithOffsets()),
        )
        buildBlock(chainId)
        node = nodes[0]
        bcRid = node.getBlockchainInstance(chainId).blockchainEngine.blockchainRid
        blockQuery = node.getBlockchainInstance().blockchainEngine.getBlockQueries()
        enqueueTx(configureEventReceiverContract(bridgeAddress, bridgeDeployHeight.toLong(), bcRid, adminKeyPair))
        sealBlock()
    }

    protected fun configureHbridge() {
        val tokenName = "Reorg Test Token"
        enqueueTx(registerAsset(tokenName, "RTT", decimals.toLong(), "https://chromaway.com/reorg-test", bcRid, adminKeyPair))
        sealBlock()

        val asset = blockQuery.query(
                "ft4.get_assets_by_name",
                gtv(
                        "name" to gtv(tokenName),
                        "page_size" to gtv(1L),
                        "page_cursor" to GtvNull,
                ),
        ).get()
        assetId = asset["data"]?.get(0)?.get("id")!!.asByteArray()

        enqueueTx(registerERC20Asset(testTokenAddress, assetId, bcRid, adminKeyPair, BridgeMode.foreign))
        enqueueTx(configureBridgeWithErc20Assets(bridgeAddress, bcRid, adminKeyPair))
        aliceAccount = registerAccount(aliceCredentials.keyPair.pubKey, bcRid, adminKeyPair)
        linkAccount(aliceCredentials, aliceAccount, bcRid)
        sealBlock()
    }

    protected fun loadConfigWithOffsets(): Gtv {
        val config = loadEifBlockchainConfig().asDict().toMutableMap()
        val eif = config["eif"]!!.asDict().toMutableMap()
        val chains = eif["chains"]!!.asDict().toMutableMap()
        val ethereum = chains["ethereum"]!!.asDict().toMutableMap()
        ethereum["evm_read_offset"] = gtv(evmReadOffset)
        ethereum["read_offset"] = gtv(readOffset)
        chains["ethereum"] = gtv(ethereum)
        eif["chains"] = gtv(chains)
        config["eif"] = gtv(eif)
        return gtv(config)
    }

    private fun reorgDepositBlock(
            depositHeight: BigInteger,
            originalHash: String,
            transactionNonce: BigInteger,
            transactionGasPrice: BigInteger,
    ) {
        rpc("miner_stop")
        rpc("debug_setHead", listOf("0x${depositHeight.subtract(BigInteger.ONE).toString(16)}"))

        val cancellation = Transaction.createEtherTransaction(
                transactionManager.fromAddress,
                transactionNonce,
                transactionGasPrice.multiply(BigInteger.TEN),
                BigInteger.valueOf(21_000),
                transactionManager.fromAddress,
                BigInteger.ZERO,
        )
        val cancellationResult: EthSendTransaction = web3j.ethSendTransaction(cancellation).send()
        assertFalse(cancellationResult.hasError(), cancellationResult.error?.message ?: "replacement transaction failed")
        rpc("miner_start")

        await().atMost(Duration.ONE_MINUTE).untilAsserted {
            val replacementBlock = web3j.ethGetBlockByNumber(
                    DefaultBlockParameter.valueOf(depositHeight),
                    false,
            ).send().block
            assertNotNull(replacementBlock)
            assertNotEquals(originalHash, replacementBlock.hash)
            val filter = EthFilter(
                    DefaultBlockParameter.valueOf(depositHeight),
                    DefaultBlockParameter.valueOf(depositHeight),
                    bridge.contractAddress,
            )
            assertTrue(web3j.ethGetLogs(filter).send().logs.isEmpty())
        }
        buildEvmBlocks(3)
        rpc("miner_stop")
    }

    @Suppress("UNCHECKED_CAST")
    protected fun rpc(method: String, params: List<Any> = emptyList()): Response<Any> {
        val responseType = Response::class.java as Class<Response<Any>>
        val response = Request<Any, Response<Any>>(method, params, reorgService, responseType).send()
        assertFalse(response.hasError(), response.error?.message ?: "$method failed")
        return response
    }

    protected fun getEventProcessor(testNode: PostchainTestNode, evmNetworkId: Long): EvmEventProcessor =
            (testNode.getBlockchainInstance(chainId).blockchainEngine.getConfiguration() as GTXModuleAware)
                    .module.getSpecialTxExtensions()
                    .filterIsInstance<EifSpecialTxExtension>().first()
                    .pipelines[evmNetworkId]?.processor as EvmEventProcessor
}

abstract class EifNodeReorgTestBase : EifNodeTestBase() {
    protected fun runDefaultDepthReorgTest() = runReorgTest(expectOrphanedCredit = false)

    protected fun runZeroDepthReorgTest() = runReorgTest(expectOrphanedCredit = true)
}

@Testcontainers(disabledWithoutDocker = true)
class EifNodeReorgDefaultDepthIT : EifNodeReorgTestBase() {
    override val evmReadOffset = 100L
    override val readOffset = 2L

    @Test
    fun `E2 orphaned deposit is not committed after EVM reorg at default offsets`() = runDefaultDepthReorgTest()
}

@Testcontainers(disabledWithoutDocker = true)
class EifNodeReorgZeroDepthIT : EifNodeReorgTestBase() {
    override val evmReadOffset = 0L
    override val readOffset = 0L

    @Test
    fun `E2 documented risk zero confirmation offsets credit orphaned deposit`() {
        // Zero confirmation offsets are unsafe; production must configure a positive evm_read_offset.
        runZeroDepthReorgTest()
    }
}
