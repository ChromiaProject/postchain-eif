package net.postchain.eif

import assertk.assertThat
import assertk.assertions.containsExactly
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.core.BlockchainEngine
import net.postchain.core.block.BlockQueries
import net.postchain.eif.contracts.TestToken
import net.postchain.eif.contracts.TokenBridge
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import net.postchain.gtx.data.OpData
import org.awaitility.Awaitility
import org.awaitility.Duration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Testcontainers
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.crypto.Credentials
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import org.web3j.tx.Contract.deployRemoteCall
import org.web3j.tx.FastRawTransactionManager
import org.web3j.tx.TransactionManager
import org.web3j.tx.gas.DefaultGasProvider
import org.web3j.tx.response.PollingTransactionReceiptProcessor
import java.math.BigInteger
import java.util.concurrent.CompletableFuture

@Testcontainers(disabledWithoutDocker = true)
class EthereumEventProcessorIT {

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
    private val accountId = Bytes32("fc91c4abaff09f4c67a0ab84d4e9afd37c929978bea3fa1790403ab6ee85bf33"
            .hexStringToByteArray())
    private val validatorContract = Address("0x0000000000000000000000000000000000000000")
    private var url = "http://localhost:8545"
    private var web3jServices = mutableListOf<Web3j>()
    private lateinit var transactionManager: TransactionManager

    private val tokenBridgeBinary = getBinaryFromArtifactResource("/artifacts/contracts/TokenBridge.sol/TokenBridge.json")
    private val testTokenBinary = getBinaryFromArtifactResource("/artifacts/contracts/token/TestToken.sol/TestToken.json")

    @BeforeEach
    fun setup() {
        gethContainer.start()

        val gethHost = gethContainer.getServiceHost("geth", 8545)
        val gethPort = gethContainer.getServicePort("geth", 8545)
        url = "http://$gethHost:$gethPort"
        web3jServices.add(Web3j.build(HttpService(url)))

        transactionManager = FastRawTransactionManager(
                web3jServices[0],
                credentials,
                PollingTransactionReceiptProcessor(
                        web3jServices[0],
                        1000,
                        30
                )
        )
    }

    @AfterEach
    fun tearDown() {
        web3jServices.forEach { it.shutdown() }
        gethContainer.stop()
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
        }
        val engineMock: BlockchainEngine = mock {
            on { getBlockQueries() } doReturn blockQueriesMock
        }

        val contractDeployTransactionHash = bridge.transactionReceipt.get().transactionHash
        val contractDeployBlockNumber = web3jServices[0].ethGetTransactionByHash(contractDeployTransactionHash)
                .send().result.blockNumber
        val eventsToRead = listOf(TokenBridge.DEPOSITEDERC20_EVENT)
        val evmEventProcessor =
                EvmEventProcessor(1L, web3jServices, listOf(bridge.contractAddress), eventsToRead,
                        BigInteger.ZERO, BigInteger.ONE, 200L, 100L,
                        contractDeployBlockNumber, BigInteger.ZERO, engineMock, Web3jRequestHandler(500, 60_000, 2, mutableListOf(url)), 500).apply {
                }

        // Deploy a test token that we mint and then approve transfer of coins to chrL2 contract
        val testToken = deployRemoteCall(TestToken::class.java, web3jServices[0], transactionManager, gasProvider, testTokenBinary, "").send().apply {
            mint(Address(transactionManager.fromAddress), Uint256(BigInteger.valueOf(initialMint))).send()
            approve(Address(bridge.contractAddress), Uint256(BigInteger.valueOf(initialMint))).send()
        }

        // Allow token
        bridge.allowToken(Address(testToken.contractAddress)).send()
        // Deposit to postchain
        for (i in 1..5) {
            bridge.deposit(Address(testToken.contractAddress), Uint256(BigInteger.TEN), accountId).send()
        }

        Awaitility.await()
                .atMost(Duration.ONE_MINUTE)
                .untilAsserted {
                    val eventBlocks = evmEventProcessor.getEventData()
                    val events = eventBlocks.flatMap { it[EncodedBlock.EVENTS.index].asArray().asList() }
                    assertEquals(events.size, 5)
                }

        // validate events
        val eventData = evmEventProcessor.getEventData()
        val eventBlocksToValidate = eventData
                .map { OpData(OP_EVM_BLOCK, it) }
        assertTrue(evmEventProcessor.isValidEventData(eventBlocksToValidate))
        // Test if NoOp version can also validate
        assertTrue(NoOpEventProcessor().isValidEventData(eventBlocksToValidate))

        // Verify that we can't skip any events by removing a block
        assertFalse(evmEventProcessor.isValidEventData(eventBlocksToValidate.subList(1, eventBlocksToValidate.size)))

        // Verify that we can't skip any events by removing them from the first block in the list
        val eventBlocksWithoutEvents = eventBlocksToValidate.mapIndexed { i, eventBlock ->
            if (i == 0) {
                OpData(OP_EVM_BLOCK, arrayOf(
                        eventBlock.args[EncodedBlock.NUMBER.index],
                        eventBlock.args[EncodedBlock.HASH.index],
                        gtv(emptyList())
                ))
            } else {
                eventBlock
            }
        }
        assertFalse(evmEventProcessor.isValidEventData(eventBlocksWithoutEvents))

        // Mock that the block was validated and committed to DB
        evmEventProcessor.markAsProcessed(eventBlocksToValidate)

        // Assert events before last committed block are not included now
        assertTrue(evmEventProcessor.getEventData().isEmpty())

        // One more final transaction
        // Maxing out this transaction
        val max = BigInteger.TWO.pow(256) - BigInteger.valueOf(initialMint + 1)
        with(testToken) {
            mint(Address(transactionManager.fromAddress), Uint256(max)).send()
            approve(Address(bridge.contractAddress), Uint256(max)).send()
        }
        bridge.deposit(Address(testToken.contractAddress), Uint256(max), accountId).send()

        Awaitility.await()
                .atMost(Duration.ONE_MINUTE)
                .untilAsserted {
                    val eventBlocks = evmEventProcessor.getEventData()
                    val events = eventBlocks.flatMap { it[EncodedBlock.EVENTS.index].asArray().asList() }
                    assertEquals(events.size, 1)
                }

        val lastEventBlock = evmEventProcessor.getEventData().first()
        val lastEvent = lastEventBlock[EncodedBlock.EVENTS.index].asArray().first()
        val indexedValues = lastEvent[EncodedEvent.INDEXED_VALUES.index].asArray()
        val nonIndexedValues = lastEvent[EncodedEvent.NON_INDEXED_VALUES.index].asArray()

        // Check that data in the event matches what we sent
        assertEquals("0x${indexedValues[0].asByteArray().toHex().lowercase()}", transactionManager.fromAddress) // owner
        assertEquals("0x${indexedValues[1].asByteArray().toHex().lowercase()}", testToken.contractAddress) // token
        assertEquals(nonIndexedValues[1].asBigInteger(), max) // value

        evmEventProcessor.shutdown()
    }

    @Test
    fun `Events can be received from multiple contracts`() {
        val initialMint = 20L
        // Deploy two token bridge contracts
        val bridgeFirst = deployRemoteCall(TokenBridge::class.java, web3jServices[0], transactionManager, gasProvider, tokenBridgeBinary, "").send().apply {
            initialize(validatorContract, Uint256(2)).send()
        }
        val bridgeSecond = deployRemoteCall(TokenBridge::class.java, web3jServices[0], transactionManager, gasProvider, tokenBridgeBinary, "").send().apply {
            initialize(validatorContract, Uint256(2)).send()
        }

        // Mock query for last evm block in this test
        val blockQueriesMock: BlockQueries = mock {
            on { query(eq("get_last_evm_block"), any()) } doReturn CompletableFuture.completedFuture(GtvNull)
        }
        val engineMock: BlockchainEngine = mock {
            on { getBlockQueries() } doReturn blockQueriesMock
        }

        val contractDeployTransactionHash = bridgeFirst.transactionReceipt.get().transactionHash
        val contractDeployBlockNumber = web3jServices[0].ethGetTransactionByHash(contractDeployTransactionHash)
                .send().result.blockNumber
        val contractAddresses = listOf(bridgeFirst.contractAddress, bridgeSecond.contractAddress)
        val eventsToRead = listOf(TokenBridge.DEPOSITEDERC20_EVENT)
        val evmEventProcessor =
                EvmEventProcessor(1L, web3jServices, contractAddresses, eventsToRead,
                        BigInteger.ZERO, BigInteger.ONE, 200L, 100L,
                        contractDeployBlockNumber, BigInteger.ZERO, engineMock, Web3jRequestHandler(500, 60_000, 2, mutableListOf(url)), 500).apply {
                }

        // Deploy a test token that we mint and then approve transfer of coins to chrL2 contracts
        val testToken = deployRemoteCall(TestToken::class.java, web3jServices[0], transactionManager, gasProvider, testTokenBinary, "").send().apply {
            mint(Address(transactionManager.fromAddress), Uint256(BigInteger.valueOf(initialMint))).send()
            approve(Address(bridgeFirst.contractAddress), Uint256(BigInteger.TEN)).send()
            approve(Address(bridgeSecond.contractAddress), Uint256(BigInteger.TEN)).send()
        }

        // Allow token
        bridgeFirst.allowToken(Address(testToken.contractAddress)).send()
        bridgeSecond.allowToken(Address(testToken.contractAddress)).send()

        // Deposit to postchain
        bridgeFirst.deposit(Address(testToken.contractAddress), Uint256(BigInteger.TEN), accountId).send()
        bridgeSecond.deposit(Address(testToken.contractAddress), Uint256(BigInteger.TEN), accountId).send()

        // Verify we got both events from the different contracts
        Awaitility.await()
                .atMost(Duration.ONE_MINUTE)
                .untilAsserted {
                    val eventBlocks = evmEventProcessor.getEventData()
                    val events = eventBlocks.flatMap { it[EncodedBlock.EVENTS.index].asArray().asList() }
                    assertEquals(events.size, 2)
                    val eventContractAddresses = events.map { "0x${it[EncodedEvent.CONTRACT.index].asByteArray().toHex()}".lowercase() }
                    assertThat(eventContractAddresses).containsExactly(*contractAddresses.map(String::lowercase).toTypedArray())
                }

        evmEventProcessor.shutdown()
    }

    private fun getBinaryFromArtifactResource(resourcePath: String): String {
        val artifactFile = javaClass.getResource(resourcePath)?.readText()
        val artifactJson = GsonBuilder().create().fromJson(artifactFile, JsonObject::class.java)
        return artifactJson.get("bytecode").asString
    }
}
