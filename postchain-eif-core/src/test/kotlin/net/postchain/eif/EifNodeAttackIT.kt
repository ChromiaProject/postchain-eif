package net.postchain.eif

import net.postchain.base.BaseBlockEContext
import net.postchain.base.SpecialTransactionPosition
import net.postchain.base.TxEventSink
import net.postchain.common.hexStringToByteArray
import net.postchain.common.exception.UserMistake
import net.postchain.common.wrap
import net.postchain.concurrent.util.get
import net.postchain.core.TxEContext
import net.postchain.devtools.PostchainTestNode
import net.postchain.eif.contracts.TokenBridgeWithSnapshotWithdraw
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtx.GTXModuleAware
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

    protected fun assertRejectedByActualValidator(proposal: EvmBlockOp, expectedMessage: String) {
        val engine = node.getBlockchainInstance(chainId).blockchainEngine
        val nextBlockHeight = node.blockQueries().getLastBlockHeight().get()!! + 1
        val executionContext = engine.blockBuilderStorage.openReadConnection(chainId)
        val blockContext = BaseBlockEContext(
                executionContext,
                nextBlockHeight,
                nextBlockHeight,
                System.currentTimeMillis(),
                emptyMap(),
                object : TxEventSink {
                    override fun processEmittedEvent(ctx: TxEContext, topic: String, data: Gtv) = Unit
                },
        )
        val failure = try {
            assertThrows(UserMistake::class.java) {
                eventExtension(node).validateSpecialOperations(
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
