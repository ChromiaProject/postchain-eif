package net.postchain.eif

import net.postchain.base.BaseBlockEContext
import net.postchain.base.SpecialTransactionPosition
import net.postchain.base.TxEventSink
import net.postchain.common.hexStringToByteArray
import net.postchain.common.exception.UserMistake
import net.postchain.common.toHex
import net.postchain.common.wrap
import net.postchain.concurrent.util.get
import net.postchain.core.TxEContext
import net.postchain.devtools.PostchainTestNode
import net.postchain.eif.contracts.TokenBridgeWithSnapshotWithdraw
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtx.GTXModuleAware
import org.apache.logging.log4j.Level
import org.awaitility.Awaitility.await
import org.awaitility.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.tx.Contract
import java.math.BigInteger

abstract class EifNodeAttackTestBase : EifNodeTestBase() {
    override val evmReadOffset = 10L
    override val readOffset = 0L

    protected fun configuredDeposit(amount: Long = 100): Pair<EvmBlockOp, String> {
        val receipt = bridge.deposit(Address(token.contractAddress), Uint256(BigInteger.valueOf(amount))).send()
        waitForReadableEvmHead()

        val processor = getEventProcessor(node, networkId)
        await().atMost(Duration.ONE_MINUTE).untilAsserted {
            assertTrue(processor.getEventData().any { it.evmBlockHeight == receipt.blockNumber })
        }

        return processor.getEventData().first { it.evmBlockHeight == receipt.blockNumber } to receipt.transactionHash
    }

    protected fun waitForReadableEvmHead() {
        val targetBlockNumber = web3j.ethBlockNumber().send().blockNumber +
                BigInteger.valueOf(evmReadOffset + readOffset + 3)
        await().atMost(Duration.ONE_MINUTE).until {
            web3j.ethBlockNumber().send().blockNumber >= targetBlockNumber
        }
    }

    protected fun eventProcessor(testNode: PostchainTestNode): EvmEventProcessor = getEventProcessor(testNode, networkId)

    protected fun eventExtension(testNode: PostchainTestNode): EifSpecialTxExtension =
            (testNode.getBlockchainInstance(chainId).blockchainEngine.getConfiguration() as GTXModuleAware)
                    .module.getSpecialTxExtensions()
                    .filterIsInstance<EifSpecialTxExtension>().first()

    protected fun assertSignerConfiguration(signerNodes: List<PostchainTestNode>) {
        val expectedSigners = signerNodes
                .map { it.pubKey.hexStringToByteArray().toHex() }
                .sorted()
        val expectedSignerKeys = signerNodes.map { it.pubKey.uppercase() }.toSet()
        nodes.forEach { testNode ->
            val process = testNode.getBlockchainInstance(chainId)
            assertEquals(
                    testNode.pubKey.uppercase() in expectedSignerKeys,
                    process.isSigner(),
                    "unexpected validator role for node ${testNode.pubKey}",
            )
            assertEquals(
                    expectedSigners,
                    process.blockchainEngine.getConfiguration().signers.map { it.toHex() }.sorted(),
                    "unexpected chain signer list on node ${testNode.pubKey}",
            )
        }
    }

    protected fun fabricatedDeposit(expected: EvmBlockOp, transactionHash: ByteArray): EvmBlockOp {
        val eventFields = expected.events.single().asArray().toMutableList()
        val nonIndexedValues = eventFields[6].asArray().toMutableList()
        eventFields[0] = gtv(transactionHash)
        nonIndexedValues[0] = gtv(BigInteger.valueOf(999))
        eventFields[6] = gtv(nonIndexedValues)
        return expected.copy(events = listOf(gtv(eventFields)))
    }

    protected fun getAssetBalanceAt(testNode: PostchainTestNode): BigInteger? {
        val balance = testNode.getBlockchainInstance(chainId).blockchainEngine.getBlockQueries().query(
                "ft4.get_asset_balance",
                gtv("account_id" to gtv(aliceAccount.accountId), "asset_id" to gtv(assetId)),
        ).get()
        return if (balance.isNull()) null else balance["amount"]!!.asBigInteger()
    }

    protected fun assertRejectedByActualValidator(proposal: EvmBlockOp, expectedMessage: String) {
        assertRejectedByActualValidator(node, proposal, expectedMessage)
    }

    protected fun assertRejectedByActualValidator(
            testNode: PostchainTestNode,
            proposal: EvmBlockOp,
            expectedMessage: String,
    ) {
        val engine = testNode.getBlockchainInstance(chainId).blockchainEngine
        val nextBlockHeight = engine.getBlockQueries().getLastBlockHeight().get() + 1
        val executionContext = engine.blockBuilderStorage.openReadConnection(chainId)
        val blockContext = BaseBlockEContext(
                executionContext,
                nextBlockHeight,
                nextBlockHeight,
                System.currentTimeMillis(),
                emptyMap(),
                object : TxEventSink {
                    override fun processEmittedEvent(ctxt: TxEContext, type: String, data: Gtv) = Unit
                },
        )
        val failure = try {
            assertThrows(UserMistake::class.java) {
                eventExtension(testNode).validateSpecialOperations(
                        SpecialTransactionPosition.Begin,
                        blockContext,
                        listOf(proposal.toOpData()),
                )
            }
        } finally {
            engine.blockBuilderStorage.closeReadConnection(executionContext)
        }
        assertEquals(expectedMessage, failure.message)
    }

    protected fun mismatchMessage(proposal: EvmBlockOp, expected: EvmBlockOp): String =
            "Received unexpected block ${proposal.evmBlockHeight} with hash ${proposal.evmBlockHash} in network ${proposal.networkId}." +
                    " Expected block ${expected.evmBlockHeight} with hash ${expected.evmBlockHash} in network ${expected.networkId}"
}

@Testcontainers(disabledWithoutDocker = true)
class EifNodeWrongContractIT : EifNodeAttackTestBase() {

    @Test
    fun `E1 deposit from an unconfigured same-bytecode bridge is ignored`() {
        deployBridgeAndToken()
        val unconfiguredBridge = Contract.deployRemoteCall(
                TokenBridgeWithSnapshotWithdraw::class.java,
                web3j,
                transactionManager,
                gasProvider,
                tokenBridgeWithSnapshotWithdrawBinary,
                "",
        ).send().apply {
            initialize(Address(validator.contractAddress), Uint256(2)).send()
        }
        unconfiguredBridge.allowToken(Address(token.contractAddress)).send()
        token.approve(Address(unconfiguredBridge.contractAddress), Uint256(BigInteger.valueOf(1_000))).send()

        startPostchain()
        configureHbridge()

        rpc("miner_stop")
        val nonce = web3j.ethGetTransactionCount(
                transactionManager.fromAddress,
                DefaultBlockParameterName.PENDING,
        ).send().transactionCount
        val gasPrice = web3j.ethGasPrice().send().gasPrice
        val unconfiguredTxHash = sendDepositWithoutMining(
                unconfiguredBridge.contractAddress,
                nonce,
                gasPrice,
                BigInteger.valueOf(200),
        )
        val configuredTxHash = sendDepositWithoutMining(
                bridge.contractAddress,
                nonce + BigInteger.ONE,
                gasPrice,
                BigInteger.valueOf(100),
        )
        rpc("miner_start")

        val unconfiguredReceipt = awaitReceipt(unconfiguredTxHash)
        val configuredReceipt = awaitReceipt(configuredTxHash)
        assertEquals(configuredReceipt.blockNumber, unconfiguredReceipt.blockNumber)

        waitForReadableEvmHead()
        val processor = eventProcessor(node)
        await().atMost(Duration.ONE_MINUTE).untilAsserted {
            assertTrue(processor.getEventData().any { it.evmBlockHeight == configuredReceipt.blockNumber })
        }
        val eventsFromDepositBlock = processor.getEventData()
                .single { it.evmBlockHeight == configuredReceipt.blockNumber }
                .events
        assertEquals(1, eventsFromDepositBlock.size)
        val configuredEvent = eventsFromDepositBlock.single().asArray()
        val includedTxHash = configuredEvent[0].asByteArray()
        assertTrue(includedTxHash.contentEquals(configuredTxHash.removePrefix("0x").hexStringToByteArray()))
        val configuredLogIndex = configuredReceipt.logs.single {
            it.address.equals(bridge.contractAddress, ignoreCase = true)
        }.logIndex
        val unconfiguredLogIndex = unconfiguredReceipt.logs.single {
            it.address.equals(unconfiguredBridge.contractAddress, ignoreCase = true)
        }.logIndex

        sealBlock()

        val configuredEventHeight = blockQuery.query(
                "get_last_evm_event_height",
                gtv(
                        "network_id" to gtv(networkId),
                        "contract_address" to gtv(bridgeAddress),
                ),
        ).get()
        val unconfiguredEventHeight = blockQuery.query(
                "get_last_evm_event_height",
                gtv(
                        "network_id" to gtv(networkId),
                        "contract_address" to gtv(unconfiguredBridge.contractAddress.removePrefix("0x").hexStringToByteArray()),
                ),
        ).get()
        val unconfiguredDeposit = blockQuery.query(
                "eif.hbridge.get_erc20_deposit_by_evm_tx_hash",
                gtv(
                        "evm_tx_hash" to gtv(unconfiguredTxHash.removePrefix("0x").hexStringToByteArray()),
                        "evm_log_index" to gtv(unconfiguredLogIndex),
                ),
        ).get()
        val configuredDeposit = blockQuery.query(
                "eif.hbridge.get_erc20_deposit_by_evm_tx_hash",
                gtv(
                        "evm_tx_hash" to gtv(configuredTxHash.removePrefix("0x").hexStringToByteArray()),
                        "evm_log_index" to gtv(configuredLogIndex),
                ),
        ).get()
        assertEquals(configuredReceipt.blockNumber.toLong(), configuredEventHeight.asInteger())
        assertTrue(unconfiguredEventHeight.isNull(), "unconfigured bridge event reached eif_event_receiver: $unconfiguredEventHeight")
        assertTrue(unconfiguredDeposit.isNull(), "unconfigured bridge deposit reached hbridge: $unconfiguredDeposit")
        assertFalse(configuredDeposit.isNull(), "configured bridge deposit was not recorded")
        assertEquals(BigInteger.valueOf(100), getAssetBalance(aliceAccount))
    }

    private fun sendDepositWithoutMining(
            bridgeAddress: String,
            nonce: BigInteger,
            gasPrice: BigInteger,
            amount: BigInteger,
    ): String {
        val function = Function(
                "deposit",
                listOf(Address(token.contractAddress), Uint256(amount)),
                emptyList(),
        )
        val transaction = Transaction.createFunctionCallTransaction(
                transactionManager.fromAddress,
                nonce,
                gasPrice,
                BigInteger.valueOf(250_000),
                bridgeAddress,
                BigInteger.ZERO,
                FunctionEncoder.encode(function),
        )
        val response = web3j.ethSendTransaction(transaction).send()
        assertFalse(response.hasError(), response.error?.message ?: "deposit transaction was rejected")
        return response.transactionHash
    }

    private fun awaitReceipt(transactionHash: String) = run {
        await().atMost(Duration.ONE_MINUTE).untilAsserted {
            assertTrue(web3j.ethGetTransactionReceipt(transactionHash).send().transactionReceipt.isPresent)
        }
        web3j.ethGetTransactionReceipt(transactionHash).send().transactionReceipt.get()
    }
}

@Testcontainers(disabledWithoutDocker = true)
class EifNodeSpecialTxValidationIT : EifNodeAttackTestBase() {

    @Test
    fun `E3 rejects wrong network and a block hash absent from the configured EVM chain`() {
        initializeNodeTest()
        val (expected, _) = configuredDeposit()
        val processor = eventProcessor(node)
        assertTrue(processor.isValidEventData(listOf(expected)).valid)

        val wrongNetwork = expected.copy(networkId = networkId + 1)
        assertRejectedByActualValidator(wrongNetwork, mismatchMessage(wrongNetwork, expected))

        val wrongHash = expected.copy(evmBlockHash = ByteArray(32) { 0x7f }.wrap())
        assertRejectedByActualValidator(wrongHash, mismatchMessage(wrongHash, expected))

        val lastEvmBlock = blockQuery.query("get_last_evm_block", gtv("network_id" to gtv(networkId))).get()
        assertTrue(lastEvmBlock.isNull(), "rejected EVM block proposal changed eif_event_receiver state: $lastEvmBlock")
        assertTrue(getAssetBalance(aliceAccount) == null || getAssetBalance(aliceAccount) == BigInteger.ZERO)

        sealBlock()
        assertEquals(BigInteger.valueOf(100), getAssetBalance(aliceAccount))
    }
}

@Testcontainers(disabledWithoutDocker = true)
class EifNodeFabricatedEventIT : EifNodeAttackTestBase() {

    @Test
    fun `E4 single honest validator rejects a fabricated deposit event and continues the chain`() {
        initializeNodeTest()
        val (expected, _) = configuredDeposit()
        val eventFields = expected.events.single().asArray().toMutableList()
        val nonIndexedValues = eventFields[6].asArray().toMutableList()
        nonIndexedValues[0] = gtv(BigInteger.valueOf(999))
        eventFields[6] = gtv(nonIndexedValues)
        val fabricatedDeposit = expected.copy(events = listOf(gtv(eventFields)))

        assertRejectedByActualValidator(
                fabricatedDeposit,
                "Events in received block ${expected.evmBlockHeight} do not match expected events",
        )
        assertTrue(getAssetBalance(aliceAccount) == null || getAssetBalance(aliceAccount) == BigInteger.ZERO)
        val lastEvmBlockBeforeCommit =
                blockQuery.query("get_last_evm_block", gtv("network_id" to gtv(networkId))).get()
        assertTrue(lastEvmBlockBeforeCommit.isNull(), "fabricated proposal changed eif_event_receiver state")

        sealBlock()

        assertEquals(BigInteger.valueOf(100), getAssetBalance(aliceAccount))
        val lastEvmBlock = blockQuery.query("get_last_evm_block", gtv("network_id" to gtv(networkId))).get()
        assertEquals(expected.evmBlockHeight.toLong(), lastEvmBlock["evm_block_height"]!!.asInteger())
    }
}

@Testcontainers(disabledWithoutDocker = true)
class EifNodeByzantineMultiNodeIT : EifNodeAttackTestBase() {

    @Test
    fun `E4 four validators reject a Byzantine fabricated deposit and continue with the real deposit`() {
        deployBridgeAndToken()
        startPostchain(
                nodeCount = 4,
                maxEventDelay = 300_000,
                numberOfEventsToTriggerBlockBuilding = 100,
        )
        assertEquals(4, nodes.size)
        assertSignerConfiguration(nodes)
        configureHbridge()

        val (expected, transactionHash) = configuredDeposit()
        val honestNodes = nodes.drop(1)
        val honestProcessors = honestNodes.map(::eventProcessor)
        await().atMost(Duration.ONE_MINUTE).untilAsserted {
            honestProcessors.forEach { processor ->
                assertTrue(processor.getEventData().any { it == expected })
            }
        }

        val fabricatedTransactionHash = ByteArray(32) { 0xff.toByte() }
        val fabricatedBlock = fabricatedDeposit(expected, fabricatedTransactionHash)

        val byzantineProcessor = eventProcessor(nodes[0])
        byzantineProcessor.flushEvents(expected.evmBlockHeight - BigInteger.ONE)
        byzantineProcessor.processLogEventsAndUpdateOffsets(
                listOf(fabricatedBlock),
                expected.evmBlockHeight,
        )
        assertEquals(listOf(fabricatedBlock), byzantineProcessor.getEventData())

        val logAppender = TestLogAppender.addAppender(listOf(Level.ERROR, Level.WARN))
        logAppender.clear()
        val attemptedHeight = getLastHeight(honestNodes.first()) + 1
        buildBlockNoWait(listOf(nodes[0]), chainId, attemptedHeight)
        await().atMost(Duration.ONE_MINUTE).untilAsserted {
            assertTrue(
                    listOf(Level.ERROR, Level.WARN).sumOf { level ->
                        logAppender.countEvents(
                                level,
                                "Events in received block ${expected.evmBlockHeight} do not match expected events",
                        )
                    } >= honestNodes.size,
                    "not every honest validator logged rejection of the Byzantine event proposal")
        }

        nodes.forEach { testNode ->
            val queries = testNode.getBlockchainInstance(chainId).blockchainEngine.getBlockQueries()
            val balance = getAssetBalanceAt(testNode)
            assertTrue(
                    balance == null || balance == BigInteger.ZERO,
                    "fabricated deposit was credited before the real proposal on node ${testNode.pubKey}: $balance",
            )
            assertTrue(queries.query("get_last_evm_block", gtv("network_id" to gtv(networkId))).get().isNull())
            assertEquals(attemptedHeight - 1, getLastHeight(testNode), "validator accepted the Byzantine block")
            val fakeDeposit = queries.query(
                    "eif.hbridge.get_erc20_deposit_by_evm_tx_hash",
                    gtv(
                            "evm_tx_hash" to gtv(fabricatedTransactionHash),
                            "evm_log_index" to gtv(expected.events.single().asArray()[1].asBigInteger()),
                    ),
            ).get()
            assertTrue(fakeDeposit.isNull(), "fabricated deposit was recorded on node ${testNode.pubKey}")
        }

        await().atMost(Duration.ONE_MINUTE).untilAsserted {
            honestProcessors.forEach { processor ->
                assertTrue(processor.getEventData().any { it == expected }, "honest validator did not retain the real EVM deposit")
            }
        }
        eventExtension(nodes[0]).pipelines.getValue(networkId).fetcher.flushEvents(
                expected.evmBlockHeight - BigInteger.ONE,
        )
        await().atMost(Duration.ONE_MINUTE).untilAsserted {
            assertEquals(listOf(expected), byzantineProcessor.getEventData())
        }
        buildBlockNoWait(nodes, chainId, attemptedHeight)
        await().atMost(Duration.ONE_MINUTE).untilAsserted {
            nodes.forEach { testNode ->
                assertEquals(attemptedHeight, getLastHeight(testNode))
            }
        }
        honestNodes.forEach { honestNode ->
            assertEquals(BigInteger.valueOf(100), getAssetBalanceAt(honestNode))
            val queries = honestNode.getBlockchainInstance(chainId).blockchainEngine.getBlockQueries()
            val lastEvmBlock = queries.query("get_last_evm_block", gtv("network_id" to gtv(networkId))).get()
            assertEquals(expected.evmBlockHeight.toLong(), lastEvmBlock["evm_block_height"]!!.asInteger())
            val deposit = queries.query(
                    "eif.hbridge.get_erc20_deposit_by_evm_tx_hash",
                    gtv(
                            "evm_tx_hash" to gtv(transactionHash.removePrefix("0x").hexStringToByteArray()),
                            "evm_log_index" to gtv(expected.events.single().asArray()[1].asBigInteger()),
                    ),
            ).get()
            assertFalse(deposit.isNull(), "real EVM deposit was not recorded")
            assertEquals(BigInteger.valueOf(100), deposit["amount"]!!.asBigInteger())
        }

        val continuedHeight = attemptedHeight + 1
        buildBlockNoWait(nodes, chainId, continuedHeight)
        await().atMost(Duration.ONE_MINUTE).untilAsserted {
            nodes.forEach { testNode ->
                assertEquals(continuedHeight, getLastHeight(testNode))
            }
        }

        nodes.forEach { testNode ->
            val balance = getAssetBalanceAt(testNode)
            assertTrue(
                    balance == null || balance == BigInteger.ZERO || balance == BigInteger.valueOf(100),
                    "fabricated deposit was credited on node ${testNode.pubKey}: $balance",
            )
            val queries = testNode.getBlockchainInstance(chainId).blockchainEngine.getBlockQueries()
            val fakeDeposit = queries.query(
                    "eif.hbridge.get_erc20_deposit_by_evm_tx_hash",
                    gtv(
                            "evm_tx_hash" to gtv(fabricatedTransactionHash),
                            "evm_log_index" to gtv(expected.events.single().asArray()[1].asBigInteger()),
                    ),
            ).get()
            assertTrue(fakeDeposit.isNull(), "fabricated deposit was recorded on node ${testNode.pubKey}")
        }
    }

}

@Testcontainers(disabledWithoutDocker = true)
class EifNodeReplicaValidationIT : EifNodeAttackTestBase() {

    @Test
    fun `replicas reject a fabricated event proposed by the sole signer`() {
        deployBridgeAndToken()
        startPostchain(
                nodeCount = 4,
                signerCount = 1,
                maxEventDelay = 300_000,
                numberOfEventsToTriggerBlockBuilding = 100,
        )
        assertSignerConfiguration(listOf(nodes[0]))
        configureHbridge()

        val (expected, _) = configuredDeposit()
        val replicas = nodes.drop(1)
        val replicaProcessors = replicas.map(::eventProcessor)
        await().atMost(Duration.ONE_MINUTE).untilAsserted {
            replicaProcessors.forEach { processor ->
                assertTrue(processor.getEventData().any { it == expected })
            }
        }

        val fabricatedTransactionHash = ByteArray(32) { 0xff.toByte() }
        val fabricatedBlock = fabricatedDeposit(expected, fabricatedTransactionHash)
        val soleSignerProcessor = eventProcessor(nodes[0])
        soleSignerProcessor.processLogEventsAndUpdateOffsets(
                listOf(fabricatedBlock),
                soleSignerProcessor.lastReadLogBlockHeight,
        )

        val logAppender = TestLogAppender.addAppender(listOf(Level.ERROR, Level.WARN))
        logAppender.clear()
        val attemptedHeight = getLastHeight(nodes[0]) + 1
        buildBlockNoWait(listOf(nodes[0]), chainId, attemptedHeight)
        await().atMost(Duration.ONE_MINUTE).untilAsserted {
            assertTrue(
                    listOf(Level.ERROR, Level.WARN).sumOf { level ->
                        logAppender.countEvents(level, "Exception committing block height $attemptedHeight from peer")
                    } >= replicas.size,
                    "not every replica logged rejection of the sole signer's fabricated event",
            )
        }
        await().atMost(Duration.ONE_MINUTE).untilAsserted {
            assertEquals(attemptedHeight, getLastHeight(nodes[0]))
        }

        val signerQueries = nodes[0].getBlockchainInstance(chainId).blockchainEngine.getBlockQueries()
        val signerDeposit = signerQueries.query(
                "eif.hbridge.get_erc20_deposit_by_evm_tx_hash",
                gtv(
                        "evm_tx_hash" to gtv(fabricatedTransactionHash),
                        "evm_log_index" to gtv(expected.events.single().asArray()[1].asBigInteger()),
                ),
        ).get()
        assertFalse(signerDeposit.isNull(), "sole signer did not commit the fabricated deposit")
        assertEquals(BigInteger.valueOf(999), signerDeposit["amount"]!!.asBigInteger())

        replicas.forEach { replica ->
            val queries = replica.getBlockchainInstance(chainId).blockchainEngine.getBlockQueries()
            assertEquals(attemptedHeight - 1, getLastHeight(replica), "replica accepted the sole signer's block")
            val balance = getAssetBalanceAt(replica)
            assertTrue(balance == null || balance == BigInteger.ZERO, "replica credited the fabricated deposit: $balance")
            assertTrue(
                    queries.query("get_last_evm_block", gtv("network_id" to gtv(networkId))).get().isNull(),
                    "replica changed EVM receiver state",
            )
            val fakeDeposit = queries.query(
                    "eif.hbridge.get_erc20_deposit_by_evm_tx_hash",
                    gtv(
                            "evm_tx_hash" to gtv(fabricatedTransactionHash),
                            "evm_log_index" to gtv(expected.events.single().asArray()[1].asBigInteger()),
                    ),
            ).get()
            assertTrue(fakeDeposit.isNull(), "replica recorded the fabricated deposit")
        }
    }

}
