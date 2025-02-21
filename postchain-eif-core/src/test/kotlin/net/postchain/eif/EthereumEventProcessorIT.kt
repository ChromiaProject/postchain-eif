package net.postchain.eif

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.common.wrap
import net.postchain.core.BlockchainEngine
import net.postchain.core.block.BlockQueries
import net.postchain.eif.contracts.TestToken
import net.postchain.eif.contracts.TokenBridge
import net.postchain.eif.web3j.Web3jRequestHandler
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import org.awaitility.Awaitility
import org.awaitility.Duration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.testcontainers.junit.jupiter.Testcontainers
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.protocol.Web3j
import org.web3j.tx.Contract.deployRemoteCall
import java.math.BigInteger
import java.util.concurrent.CompletableFuture

@Testcontainers(disabledWithoutDocker = true)
class EthereumEventProcessorIT : EifBaseIntegrationTest(
        prependUrls = listOf("http://127.0.0.1:8888", "http://127.0.0.1:9999")
) {

    private val validatorContract = Address("0x0000000000000000000000000000000000000001")
    private var url = "http://localhost:8545"
    private var web3jServices = mutableListOf<Web3j>()

    @BeforeEach
    override fun setup() {

        super.setup()

        val gethHost = evmContainer.getServiceHost("geth", 8545)
        val gethPort = evmContainer.getServicePort("geth", 8545)
        url = "http://$gethHost:$gethPort"
        web3jServices.add(web3j)
    }

    @Test
    fun `Deposit events on ethereum should be parsed and private validated`() {
        val initialMint = 50L
        // Deploy token bridge contract
        val bridge = deployRemoteCall(TokenBridge::class.java, web3jServices[0], transactionManager, gasProvider, tokenBridgeBinary, "").send().apply {
            initialize(validatorContract, Uint256(2)).send()
        }

        // Mock query for last evm block in this test
        val blockQueriesMock: BlockQueries = mock {
            on { query(eq("get_last_evm_block"), any()) } doReturn CompletableFuture.completedFuture(GtvNull)
            on { query(eq("get_last_evm_event_height"), any()) } doReturn CompletableFuture.completedFuture(GtvNull)
        }
        val engineMock: BlockchainEngine = mock {
            on { getBlockQueries() } doReturn blockQueriesMock
        }

        val contractDeployTransactionHash = bridge.transactionReceipt.get().transactionHash
        val contractDeployBlockNumber = web3jServices[0].ethGetTransactionByHash(contractDeployTransactionHash)
                .send().result.blockNumber
        val eventsToRead = listOf(TokenBridge.DEPOSITEDERC20_EVENT)

        val evmEventProcessor = EvmEventProcessor(
                BigInteger.ONE,
                100L,
        )
        val evmEventFetcher = EvmEventFetcher(
                1L,
                listOf(parseEvmAddress(bridge.contractAddress) to 0),
                hasLegacyDynamicContracts = false,
                hasDynamicContacts = false,
                eventsToRead,
                hasDynamicEvents = false,
                BigInteger.ZERO,
                200L,
                networkSkipToHeight = contractDeployBlockNumber.toLong(),
                engineMock,
                Web3jRequestHandler(500, 60_000, 2, mutableListOf(url), web3jServices),
                500,
                hasLastEvmEventHeightQuery = true,
                evmEventProcessor
        )

        // Deploy a test token that we mint and then approve transfer of coins to chrL2 contract
        val testToken = deployRemoteCall(TestToken::class.java, web3jServices[0], transactionManager, gasProvider, testTokenBinary, "").send().apply {
            mint(Address(transactionManager.fromAddress), Uint256(BigInteger.valueOf(initialMint))).send()
            approve(Address(bridge.contractAddress), Uint256(BigInteger.valueOf(initialMint))).send()
        }

        // Allow token
        bridge.allowToken(Address(testToken.contractAddress)).send()
        // Deposit to postchain
        (1..5).forEach { i ->
            bridge.deposit(Address(testToken.contractAddress), Uint256(BigInteger.TEN)).send()
        }

        Awaitility.await()
                .atMost(Duration.ONE_MINUTE)
                .untilAsserted {
                    val eventBlocks = evmEventProcessor.getEventData()
                    val events = eventBlocks.flatMap { it.events }
                    assertThat(events.size).isEqualTo(5)
                }

        // validate events
        val eventData = evmEventProcessor.getEventData()
        assertTrue(evmEventProcessor.isValidEventData(eventData).valid)
        // Test if NoOp version can also validate
        assertTrue(NoOpEventProcessor().isValidEventData(eventData).valid)

        // Verify that we can't skip any events by removing a block
        assertFalse(evmEventProcessor.isValidEventData(eventData.subList(1, eventData.size)).valid)

        // Verify that we can't skip any events by removing them from the first block in the list
        val eventBlocksWithoutEvents = eventData.mapIndexed { i, eventBlock ->
            if (i == 0) {
                eventBlock.copy(events = emptyList())
            } else {
                eventBlock
            }
        }
        assertFalse(evmEventProcessor.isValidEventData(eventBlocksWithoutEvents).valid)

        // Mock that the block was validated and committed to DB
        evmEventProcessor.markAsProcessed(eventData)

        // Assert events before last committed block are not included now
        assertTrue(evmEventProcessor.getEventData().isEmpty())

        // One more final transaction
        // Maxing out this transaction
        val max = BigInteger.TWO.pow(256) - BigInteger.valueOf(initialMint + 1)
        with(testToken) {
            mint(Address(transactionManager.fromAddress), Uint256(max)).send()
            approve(Address(bridge.contractAddress), Uint256(max)).send()
        }
        bridge.deposit(Address(testToken.contractAddress), Uint256(max)).send()

        Awaitility.await()
                .atMost(Duration.ONE_MINUTE)
                .untilAsserted {
                    val eventBlocks = evmEventProcessor.getEventData()
                    val events = eventBlocks.flatMap { it.events }
                    assertThat(events.size).isEqualTo(1)
                }

        val lastEventBlock = evmEventProcessor.getEventData().first()
        val lastEvent = lastEventBlock.events.first()
        val indexedValues = lastEvent[EncodedEvent.INDEXED_VALUES.index].asArray()
        val nonIndexedValues = lastEvent[EncodedEvent.NON_INDEXED_VALUES.index].asArray()

        // Check that data in the event matches what we sent
        assertThat(transactionManager.fromAddress).isEqualTo("0x${indexedValues[0].asByteArray().toHex().lowercase()}") // owner
        assertThat(testToken.contractAddress).isEqualTo("0x${indexedValues[1].asByteArray().toHex().lowercase()}") // token
        assertThat(nonIndexedValues[0].asBigInteger()).isEqualTo(max) // value

        evmEventFetcher.shutdown()
    }

    @Test
    fun `Multiple events can be received from multiple contracts which are statically and dynamically configured`() {
        val networkId = 1L
        val initialMint = 30L
        // Deploy two token bridge contracts
        val bridgeFirst = deployRemoteCall(TokenBridge::class.java, web3jServices[0], transactionManager, gasProvider, tokenBridgeBinary, "").send().apply {
            initialize(validatorContract, Uint256(2)).send()
        }
        val firstContractDeployBlockNumber = web3jServices[0].ethGetTransactionByHash(bridgeFirst.transactionReceipt.get().transactionHash)
                .send().result.blockNumber
        val bridgeSecond = deployRemoteCall(TokenBridge::class.java, web3jServices[0], transactionManager, gasProvider, tokenBridgeBinary, "").send().apply {
            initialize(validatorContract, Uint256(2)).send()
        }
        val secondContractDeployBlockNumber = web3jServices[0].ethGetTransactionByHash(bridgeSecond.transactionReceipt.get().transactionHash)
                .send().result.blockNumber
        val bridgeThird = deployRemoteCall(TokenBridge::class.java, web3jServices[0], transactionManager, gasProvider, tokenBridgeBinary, "").send().apply {
            initialize(validatorContract, Uint256(2)).send()
        }
        val thirdContractDeployBlockNumber = web3jServices[0].ethGetTransactionByHash(bridgeThird.transactionReceipt.get().transactionHash)
                .send().result.blockNumber

        // Mock queries in this test
        val blockQueriesMock: BlockQueries = mock {
            on { query(eq("get_last_evm_block"), any()) } doReturn CompletableFuture.completedFuture(GtvNull)
            on { query(eq("get_last_evm_event_height"), any()) } doReturn CompletableFuture.completedFuture(GtvNull)

            on { query(eq(EIF_CONFIG_CONTRACTS_TO_FETCH_QUERY), eq(gtv(mapOf("network_id" to gtv(networkId))))) } doReturn
                    CompletableFuture.completedFuture(gtv(listOf(gtv(
                            mapOf(
                                    "address" to gtv(bridgeSecond.contractAddress.substring(2).hexStringToByteArray()),
                                    "skip_to_height" to gtv(secondContractDeployBlockNumber.toLong()),
                            )))))

            on { query(eq(EIF_CONFIG_EVENTS_QUERY), eq(gtv(mapOf("network_id" to gtv(networkId))))) } doReturn
                    CompletableFuture.completedFuture(gtv(listOf(gtv(mapOf(
                            "name" to gtv("DepositedERC20"),
                            "inputs" to gtv(listOf(
                                    gtv(mapOf("type" to gtv("address"), "indexed" to gtv(true))),
                                    gtv(mapOf("type" to gtv("address"), "indexed" to gtv(true))),
                                    gtv(mapOf("type" to gtv("uint256"), "indexed" to gtv(false))),
                                    gtv(mapOf("type" to gtv("bytes32"), "indexed" to gtv(false))),
                            )))))))
        }
        val engineMock: BlockchainEngine = mock {
            on { getBlockQueries() } doReturn blockQueriesMock
        }

        val evmEventProcessor = EvmEventProcessor(
                BigInteger.ONE,
                100L,
        )
        val evmEventFetcher = EvmEventFetcher(
                networkId,
                listOf(parseEvmAddress(bridgeFirst.contractAddress) to firstContractDeployBlockNumber.toLong()),
                hasLegacyDynamicContracts = false,
                hasDynamicContacts = true,
                listOf(TokenBridge.ALLOWTOKEN_EVENT),
                hasDynamicEvents = true,
                BigInteger.ZERO,
                200L,
                networkSkipToHeight = 0L,
                engineMock,
                Web3jRequestHandler(500, 60_000, 2, mutableListOf(url), web3jServices), 500,
                hasLastEvmEventHeightQuery = true,
                evmEventProcessor,
        )

        // Deploy a test token that we mint and then approve transfer of coins to chrL2 contracts
        val testToken = deployRemoteCall(TestToken::class.java, web3jServices[0], transactionManager, gasProvider, testTokenBinary, "").send().apply {
            mint(Address(transactionManager.fromAddress), Uint256(BigInteger.valueOf(initialMint))).send()
            approve(Address(bridgeFirst.contractAddress), Uint256(BigInteger.TEN)).send()
            approve(Address(bridgeSecond.contractAddress), Uint256(BigInteger.TEN)).send()
            approve(Address(bridgeThird.contractAddress), Uint256(BigInteger.TEN)).send()
        }

        // Allow token
        bridgeFirst.allowToken(Address(testToken.contractAddress)).send()
        bridgeSecond.allowToken(Address(testToken.contractAddress)).send()
        bridgeThird.allowToken(Address(testToken.contractAddress)).send()

        // Deposit to postchain
        bridgeFirst.deposit(Address(testToken.contractAddress), Uint256(BigInteger.TEN)).send()
        bridgeSecond.deposit(Address(testToken.contractAddress), Uint256(BigInteger.TEN)).send()
        bridgeThird.deposit(Address(testToken.contractAddress), Uint256(BigInteger.TEN)).send()

        // Verify we got both events from the different contracts
        Awaitility.await()
                .atMost(Duration.ONE_MINUTE)
                .untilAsserted {
                    val eventBlocks = evmEventProcessor.getEventData()
                    val events = eventBlocks.flatMap { it.events }
                    assertThat(events.size).isEqualTo(4)
                    val eventContractAddresses = events.map {
                        "0x${it[EncodedEvent.CONTRACT.index].asByteArray().toHex()}".lowercase() to it[EncodedEvent.NAME.index].asString()
                    }
                    assertThat(eventContractAddresses).containsExactly(
                            bridgeFirst.contractAddress.lowercase() to TokenBridge.ALLOWTOKEN_EVENT.name,
                            bridgeSecond.contractAddress.lowercase() to TokenBridge.ALLOWTOKEN_EVENT.name,
                            bridgeFirst.contractAddress.lowercase() to TokenBridge.DEPOSITEDERC20_EVENT.name,
                            bridgeSecond.contractAddress.lowercase() to TokenBridge.DEPOSITEDERC20_EVENT.name,
                    )
                }

        whenever(blockQueriesMock.query(eq(EIF_CONFIG_CONTRACTS_TO_FETCH_QUERY), eq(gtv(mapOf("network_id" to gtv(networkId)))))) doReturn
                CompletableFuture.completedFuture(gtv(listOf(
                        gtv(mapOf(
                                "address" to gtv(bridgeSecond.contractAddress.substring(2).hexStringToByteArray()),
                                "skip_to_height" to gtv(secondContractDeployBlockNumber.toLong()),
                        )),
                        gtv(mapOf(
                                "address" to gtv(bridgeThird.contractAddress.substring(2).hexStringToByteArray()),
                                "skip_to_height" to gtv(thirdContractDeployBlockNumber.toLong()),
                        )))))

        // Verify we got the event from third contract also
        Awaitility.await()
                .atMost(Duration.ONE_MINUTE)
                .untilAsserted {
                    val eventBlocks = evmEventProcessor.getEventData()
                    val events = eventBlocks.flatMap { it.events }
                    assertThat(events.size).isEqualTo(6)
                    val eventContractAddresses = events.map {
                        "0x${it[EncodedEvent.CONTRACT.index].asByteArray().toHex()}".lowercase() to it[EncodedEvent.NAME.index].asString()
                    }
                    assertThat(eventContractAddresses).containsExactly(
                            bridgeFirst.contractAddress.lowercase() to TokenBridge.ALLOWTOKEN_EVENT.name,
                            bridgeSecond.contractAddress.lowercase() to TokenBridge.ALLOWTOKEN_EVENT.name,
                            bridgeThird.contractAddress.lowercase() to TokenBridge.ALLOWTOKEN_EVENT.name,
                            bridgeFirst.contractAddress.lowercase() to TokenBridge.DEPOSITEDERC20_EVENT.name,
                            bridgeSecond.contractAddress.lowercase() to TokenBridge.DEPOSITEDERC20_EVENT.name,
                            bridgeThird.contractAddress.lowercase() to TokenBridge.DEPOSITEDERC20_EVENT.name,
                    )
                }

        evmEventFetcher.shutdown()
    }
}
