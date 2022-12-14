package net.postchain.eif

import assertk.assert
import assertk.assertions.*
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.core.BlockchainEngine
import net.postchain.core.block.BlockQueries
import net.postchain.eif.contracts.TestToken
import net.postchain.eif.contracts.TokenBridge
import net.postchain.gtv.*
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtx.data.OpData
import nl.komponents.kovenant.Promise
import org.awaitility.Awaitility
import org.awaitility.Duration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
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

@Testcontainers(disabledWithoutDocker = true)
class EthereumEventProcessorTest {

    private val gethContainer = GethContainer()
        .withExposedService(
            "geth", 8545,
            Wait.forLogMessage(".*HTTP server started.*\\s", 1)
        )

    private val gasProvider = DefaultGasProvider()

    // This could be any private key but value must match in /geth-compose/geth/key.txt
    // and the address created must be added to /geth-compose/geth/test.json
    private val credentials = Credentials
        .create("0x0000000000000000000000000000000001000000000000000000000000000000")
    private lateinit var web3j: Web3j
    private lateinit var transactionManager: TransactionManager

    private val tokenBridgeBinary = getBinaryFromArtifactResource("/artifacts/contracts/TokenBridge.sol/TokenBridge.json")
    private val testTokenBinary = getBinaryFromArtifactResource("/artifacts/contracts/token/TestToken.sol/TestToken.json")

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
    }

    @AfterEach
    fun tearDown() {
        web3j.shutdown()
        gethContainer.stop()
    }

    @Test
    fun `Deposit events on ethereum should be parsed and private validated`() {
        val initialMint = 50L
        // Deploy token bridge contract
        val bridge = deployRemoteCall(TokenBridge::class.java, web3j, transactionManager, gasProvider, tokenBridgeBinary, "").send()

        // Mock query for last evm block in this test
        val blockQueriesMock: BlockQueries = mock {
            on { query(eq("get_last_evm_block"), any()) } doReturn Promise.ofSuccess<Gtv, Exception>(GtvNull)
        }
        val engineMock: BlockchainEngine = mock {
            on { getBlockQueries() } doReturn blockQueriesMock
        }

        val contractDeployTransactionHash = bridge.transactionReceipt.get().transactionHash
        val contractDeployBlockNumber = web3j.ethGetTransactionByHash(contractDeployTransactionHash)
            .send().result.blockNumber
        val eventsToRead = listOf(TokenBridge.DEPOSITEDERC20_EVENT)
        val evmEventProcessor =
            EvmEventProcessor(1L, web3j, listOf(bridge.contractAddress), eventsToRead, BigInteger.ZERO, BigInteger.ONE, 200L, 100L, contractDeployBlockNumber, engineMock).apply {
                start()
            }

        // Deploy a test token that we mint and then approve transfer of coins to chrL2 contract
        val testToken = deployRemoteCall(TestToken::class.java, web3j, transactionManager, gasProvider, testTokenBinary, "").send().apply {
            mint(Address(transactionManager.fromAddress), Uint256(BigInteger.valueOf(initialMint))).send()
            approve(Address(bridge.contractAddress), Uint256(BigInteger.valueOf(initialMint))).send()
        }

        // Allow token
        bridge.allowToken(Address(testToken.contractAddress))
        // Deposit to postchain
        for (i in 1..5) {
            bridge.deposit(Address(testToken.contractAddress), Uint256(BigInteger.TEN),
                Bytes32("fc91c4abaff09f4c67a0ab84d4e9afd37c929978bea3fa1790403ab6ee85bf33".hexStringToByteArray())).send()
        }

        Awaitility.await()
            .atMost(Duration.ONE_MINUTE)
            .untilAsserted {
                val eventBlocks = evmEventProcessor.getEventData()
                val events = eventBlocks.flatMap { it[EncodedBlock.EVENTS.index].asArray().asList() }
                assert(events.size == 5).isTrue()
            }

        // validate events
        val eventData = evmEventProcessor.getEventData()
        val eventBlocksToValidate = eventData
            .map { OpData(OP_EVM_BLOCK, it) }
        assert(evmEventProcessor.isValidEventData(eventBlocksToValidate)).isTrue()
        // Test if NoOp version can also validate
        assert(NoOpEventProcessor().isValidEventData(eventBlocksToValidate)).isTrue()

        // Verify that we can't skip any events by removing a block
        assert(evmEventProcessor.isValidEventData(eventBlocksToValidate.subList(1, eventBlocksToValidate.size))).isFalse()

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
        assert(evmEventProcessor.isValidEventData(eventBlocksWithoutEvents)).isFalse()

        // Mock that the block was validated and committed to DB
        evmEventProcessor.markAsProcessed(eventBlocksToValidate)

        // Assert events before last committed block are not included now
        assert(evmEventProcessor.getEventData().isEmpty()).isTrue()

        // One more final transaction
        // Maxing out this transaction
        val max = BigInteger.TWO.pow(256) - BigInteger.valueOf(initialMint + 1)
        with (testToken) {
            mint(Address(transactionManager.fromAddress), Uint256(max)).send()
            approve(Address(bridge.contractAddress), Uint256(max)).send()
        }
        bridge.deposit(Address(testToken.contractAddress), Uint256(max),
            Bytes32("fc91c4abaff09f4c67a0ab84d4e9afd37c929978bea3fa1790403ab6ee85bf33".hexStringToByteArray())).send()

        Awaitility.await()
            .atMost(Duration.ONE_MINUTE)
            .untilAsserted {
                val eventBlocks = evmEventProcessor.getEventData()
                val events = eventBlocks.flatMap { it[EncodedBlock.EVENTS.index].asArray().asList() }
                assert(events.size == 1).isTrue()
            }

        val lastEventBlock = evmEventProcessor.getEventData().first()
        val lastEvent = lastEventBlock[EncodedBlock.EVENTS.index].asArray().first()
        val indexedValues = lastEvent[EncodedEvent.INDEXED_VALUES.index].asArray()
        val nonIndexedValues = lastEvent[EncodedEvent.NON_INDEXED_VALUES.index].asArray()

        // Check that data in the event matches what we sent
        assert("0x${indexedValues[0].asByteArray().toHex()}").isEqualTo(transactionManager.fromAddress, true) // owner
        assert("0x${indexedValues[1].asByteArray().toHex()}").isEqualTo(testToken.contractAddress, true) // token
        assert(nonIndexedValues[1].asBigInteger()).isEqualTo(max) // value

        evmEventProcessor.shutdown()
    }

    @Test
    fun `Events can be received from multiple contracts`() {
        val initialMint = 20L
        // Deploy two token bridge contracts
        val bridgeFirst = deployRemoteCall(TokenBridge::class.java, web3j, transactionManager, gasProvider, tokenBridgeBinary, "").send()
        val bridgeSecond = deployRemoteCall(TokenBridge::class.java, web3j, transactionManager, gasProvider, tokenBridgeBinary, "").send()

        // Mock query for last evm block in this test
        val blockQueriesMock: BlockQueries = mock {
            on { query(eq("get_last_evm_block"), any()) } doReturn Promise.ofSuccess<Gtv, Exception>(GtvNull)
        }
        val engineMock: BlockchainEngine = mock {
            on { getBlockQueries() } doReturn blockQueriesMock
        }

        val contractDeployTransactionHash = bridgeFirst.transactionReceipt.get().transactionHash
        val contractDeployBlockNumber = web3j.ethGetTransactionByHash(contractDeployTransactionHash)
                .send().result.blockNumber
        val contractAddresses = listOf(bridgeFirst.contractAddress, bridgeSecond.contractAddress)
        val eventsToRead = listOf(TokenBridge.DEPOSITEDERC20_EVENT)
        val evmEventProcessor =
                EvmEventProcessor(1L, web3j, contractAddresses, eventsToRead, BigInteger.ZERO, BigInteger.ONE, 200L, 100L, contractDeployBlockNumber, engineMock).apply {
                    start()
                }

        // Deploy a test token that we mint and then approve transfer of coins to chrL2 contracts
        val testToken = deployRemoteCall(TestToken::class.java, web3j, transactionManager, gasProvider, testTokenBinary, "").send().apply {
            mint(Address(transactionManager.fromAddress), Uint256(BigInteger.valueOf(initialMint))).send()
            approve(Address(bridgeFirst.contractAddress), Uint256(BigInteger.TEN)).send()
            approve(Address(bridgeSecond.contractAddress), Uint256(BigInteger.TEN)).send()
        }

        // Allow token
        bridgeFirst.allowToken(Address(testToken.contractAddress))
        bridgeSecond.allowToken(Address(testToken.contractAddress))

        // Deposit to postchain
        bridgeFirst.deposit(Address(testToken.contractAddress), Uint256(BigInteger.TEN),
            Bytes32("fc91c4abaff09f4c67a0ab84d4e9afd37c929978bea3fa1790403ab6ee85bf33".hexStringToByteArray())).send()
        bridgeSecond.deposit(Address(testToken.contractAddress), Uint256(BigInteger.TEN),
            Bytes32("fc91c4abaff09f4c67a0ab84d4e9afd37c929978bea3fa1790403ab6ee85bf33".hexStringToByteArray())).send()

        // Verify we got both events from the different contracts
        Awaitility.await()
                .atMost(Duration.ONE_MINUTE)
                .untilAsserted {
                    val eventBlocks = evmEventProcessor.getEventData()
                    val events = eventBlocks.flatMap { it[EncodedBlock.EVENTS.index].asArray().asList() }
                    assert(events.size == 2).isTrue()
                    val eventContractAddresses = events.map { "0x${it[EncodedEvent.CONTRACT.index].asByteArray().toHex()}".lowercase() }
                    assert(eventContractAddresses).containsExactly(*contractAddresses.map(String::lowercase).toTypedArray())
                }

        evmEventProcessor.shutdown()
    }

    private fun getBinaryFromArtifactResource(resourcePath: String): String {
        val artifactFile = javaClass.getResource(resourcePath).readText()
        val artifactJson = GsonBuilder().create().fromJson(artifactFile, JsonObject::class.java)
        return artifactJson.get("bytecode").asString
    }
}
