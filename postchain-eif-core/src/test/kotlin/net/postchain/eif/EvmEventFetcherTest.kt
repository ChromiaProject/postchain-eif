package net.postchain.eif

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import net.postchain.common.hexStringToByteArray
import net.postchain.core.BlockchainEngine
import net.postchain.core.block.BlockQueries
import net.postchain.eif.web3j.Web3jRequestHandler
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import org.awaitility.Duration
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withPollDelay
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.Response
import org.web3j.protocol.core.methods.response.EthBlockNumber
import org.web3j.protocol.core.methods.response.EthLog
import java.math.BigInteger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class EvmEventFetcherTest {

    @Test
    fun `should decrease from-height when same contract re-added with lower skip-to-height after removal`() {
        // Test data
        val networkSkipToHeight = 100L
        val contractAddress = "1234000000000000000000000000000000000000".hexStringToByteArray()
        val wrongSkipToHeight = 200L
        val correctSkipToHeight: Long = networkSkipToHeight
        val wrongHeightContract = gtv(mapOf(
                "address" to gtv(contractAddress),
                "skip_to_height" to gtv(wrongSkipToHeight),
        ))
        val correctHeightContract = gtv(mapOf(
                "address" to gtv(contractAddress),
                "skip_to_height" to gtv(correctSkipToHeight),
        ))
        val contractsToFetch = mutableListOf(wrongHeightContract)

        // Mocks and Test subject
        val (_, eventProcessor) = createMockEvmEventSystem(networkSkipToHeight, contractsToFetch)

        // Wait 3 sec and verify that the `from-height` is `wrongSkipToHeight`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(eventProcessor.lastReadLogBlockHeight.toLong()).isEqualTo(wrongSkipToHeight)
        }

        // Remove the contract
        contractsToFetch.clear()

        // Verify that the `from-height` is still `wrongSkipToHeight`
        await.withPollDelay(delay).untilAsserted {
            assertThat(eventProcessor.lastReadLogBlockHeight.toLong()).isEqualTo(wrongSkipToHeight)
        }

        // Add the same contract with a lower skip_to_height and verify that the `from-height` is updated
        contractsToFetch.add(correctHeightContract)
        await.withPollDelay(delay).untilAsserted {
            assertThat(eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(correctSkipToHeight)
            assertThat(eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(wrongSkipToHeight)
        }
    }

    @Test
    fun `shouldn't increase from-height when same contract re-added with higher skip-to-height after removal`() {
        // Test data
        val networkSkipToHeight = 100L
        val contractAddress = "1234000000000000000000000000000000000000".hexStringToByteArray()
        val wrongSkipToHeight: Long = networkSkipToHeight
        val correctSkipToHeight = 200L
        val wrongHeightContract = gtv(mapOf(
                "address" to gtv(contractAddress),
                "skip_to_height" to gtv(wrongSkipToHeight),
        ))
        val correctHeightContract = gtv(mapOf(
                "address" to gtv(contractAddress),
                "skip_to_height" to gtv(correctSkipToHeight),
        ))
        val contractsToFetch = mutableListOf(wrongHeightContract)

        // Mocks and Test subject
        val (_, eventProcessor) = createMockEvmEventSystem(networkSkipToHeight, contractsToFetch)

        // Wait 3 sec and verify that the `from-height` is `wrongSkipToHeight`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(wrongSkipToHeight)
            assertThat(eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(correctSkipToHeight)
        }

        // Remove the contract
        contractsToFetch.clear()

        // Verify that the `from-height` is still `wrongSkipToHeight`
        await.withPollDelay(delay).untilAsserted {
            assertThat(eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(wrongSkipToHeight)
            assertThat(eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(correctSkipToHeight)
        }

        // Add the same contract with a higher skip-to-height and verify that the `from-height` has not been updated
        contractsToFetch.add(correctHeightContract)
        await.withPollDelay(delay).untilAsserted {
            assertThat(eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(wrongSkipToHeight)
            assertThat(eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(correctSkipToHeight)
        }
    }

    @Test
    fun `should decrease from-height when contract removed and new contract added with lower skip-to-height`() {
        // Test data
        val networkSkipToHeight = 100L
        val address1 = "1234000000000000000000000000000000000000".hexStringToByteArray()
        val skipToHeight1 = 200L
        val address2 = "5678000000000000000000000000000000000000".hexStringToByteArray()
        val skipToHeight2 = 100L
        val contract1 = gtv(mapOf(
                "address" to gtv(address1),
                "skip_to_height" to gtv(skipToHeight1),
        ))
        val contract2 = gtv(mapOf(
                "address" to gtv(address2),
                "skip_to_height" to gtv(skipToHeight2),
        ))
        val contractsToFetch = mutableListOf(contract1)

        // Mocks and Test subject
        val (_, eventProcessor) = createMockEvmEventSystem(networkSkipToHeight, contractsToFetch)

        // Wait 3 sec and verify that the `from-height` is `skipToHeight1`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(eventProcessor.lastReadLogBlockHeight.toLong()).isEqualTo(skipToHeight1)
        }

        // Remove the contract
        contractsToFetch.clear()

        // Verify that the `from-height` is still `skipToHeight1`
        await.withPollDelay(delay).untilAsserted {
            assertThat(eventProcessor.lastReadLogBlockHeight.toLong()).isEqualTo(skipToHeight1)
        }

        // Add another contract with a lower skip-to-height and verify that the `from-height` is updated
        contractsToFetch.add(contract2)
        await.withPollDelay(delay).untilAsserted {
            assertThat(eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeight2)
            assertThat(eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeight1)
        }
    }

    @Test
    fun `shouldn't increase from-height when contract removed and new contract added with higher skip-to-height`() {
        // Test data
        val networkSkipToHeight = 100L
        val address1 = "1234000000000000000000000000000000000000".hexStringToByteArray()
        val skipToHeight1 = 100L
        val address2 = "5678000000000000000000000000000000000000".hexStringToByteArray()
        val skipToHeight2 = 200L
        val contract1 = gtv(mapOf(
                "address" to gtv(address1),
                "skip_to_height" to gtv(skipToHeight1),
        ))
        val contract2 = gtv(mapOf(
                "address" to gtv(address2),
                "skip_to_height" to gtv(skipToHeight2),
        ))
        val contractsToFetch = mutableListOf(contract1)

        // Mocks and Test subject
        val (_, eventProcessor) = createMockEvmEventSystem(networkSkipToHeight, contractsToFetch)

        // Wait 3 sec and verify that the `from-height` is `wrongSkipToHeight`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeight1)
            assertThat(eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeight2)
        }

        // Remove the contract
        contractsToFetch.clear()

        // Verify that the `from-height` is still `wrongSkipToHeight`
        await.withPollDelay(delay).untilAsserted {
            assertThat(eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeight1)
            assertThat(eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeight2)
        }

        // Add another contract with a higher skip-to-height and verify that the `from-height` has not been updated
        contractsToFetch.add(contract2)
        await.withPollDelay(delay).untilAsserted {
            assertThat(eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeight1)
            assertThat(eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeight2)
        }
    }

    @Test
    fun `should decrease from-height when second contract added with lower skip-to-height`() {
        // Test data
        val networkSkipToHeight = 100L
        val address1 = "1234000000000000000000000000000000000000".hexStringToByteArray()
        val skipToHeight1 = 200L
        val address2 = "5678000000000000000000000000000000000000".hexStringToByteArray()
        val skipToHeight2 = 100L
        val contract1 = gtv(mapOf(
                "address" to gtv(address1),
                "skip_to_height" to gtv(skipToHeight1),
        ))
        val contract2 = gtv(mapOf(
                "address" to gtv(address2),
                "skip_to_height" to gtv(skipToHeight2),
        ))
        val contractsToFetch = mutableListOf(contract1)

        // Mocks and Test subject
        val (_, eventProcessor) = createMockEvmEventSystem(networkSkipToHeight, contractsToFetch)

        // Wait 3 sec and verify that the `from-height` is `skipToHeight1`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(eventProcessor.lastReadLogBlockHeight.toLong()).isEqualTo(skipToHeight1)
        }

        // Add the second contract with a lower skip-to-height and verify that the `from-height` is updated
        contractsToFetch.add(contract2)
        await.withPollDelay(delay).untilAsserted {
            assertThat(eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeight2)
            assertThat(eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeight1)
        }
    }

    @Test
    fun `shouldn't decrease from-height when second contract added with lower skip-to-height`() {
        // Test data
        val networkSkipToHeight = 100L
        val address1 = "1234000000000000000000000000000000000000".hexStringToByteArray()
        val skipToHeight1 = 100L
        val address2 = "5678000000000000000000000000000000000000".hexStringToByteArray()
        val skipToHeight2 = 200L
        val contract1 = gtv(mapOf(
                "address" to gtv(address1),
                "skip_to_height" to gtv(skipToHeight1),
        ))
        val contract2 = gtv(mapOf(
                "address" to gtv(address2),
                "skip_to_height" to gtv(skipToHeight2),
        ))
        val contractsToFetch = mutableListOf(contract1)

        // Mocks and Test subject
        val (_, eventProcessor) = createMockEvmEventSystem(networkSkipToHeight, contractsToFetch)

        // Wait 3 sec and verify that the `from-height` is `wrongSkipToHeight`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeight1)
            assertThat(eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeight2)
        }

        // Add the second contract with a higher skip-to-height and verify that the `from-height` has not been updated
        contractsToFetch.add(contract2)
        await.withPollDelay(delay).untilAsserted {
            assertThat(eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeight1)
            assertThat(eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeight2)
        }
    }

    @Suppress("UNCHECKED_CAST", "SameParameterValue")
    private fun createMockEvmEventSystem(networkSkipToHeight: Long, contractsToFetch: List<Gtv>): Pair<EvmEventFetcher, EvmEventProcessor> {
        // Mocks
        val blockQueries = mock<BlockQueries> {
            on { query(eq(EIF_CONFIG_CONTRACTS_TO_FETCH_QUERY), any()) } doAnswer {
                CompletableFuture.completedStage(gtv(contractsToFetch))
            }

            on { query(eq(EIF_LAST_EVM_EVENT_HEIGHT_QUERY), any()) } doReturn
                    CompletableFuture.completedStage(GtvNull)

            on { query(eq(EIF_CONFIG_EVENTS_QUERY), any()) } doReturn
                    CompletableFuture.completedStage(gtv(listOf(
                            gtv(mapOf(
                                    "name" to gtv("Deposit"),
                                    "inputs" to gtv(listOf())
                            )),
                    )))
        }
        val blockchainEngine = mock<BlockchainEngine> {
            on { getBlockQueries() } doReturn blockQueries
        }

        // Mock web3j
        var currentBlock = networkSkipToHeight
        val blockNumber = mock<EthBlockNumber> {
            on { blockNumber }.thenAnswer { BigInteger.valueOf(currentBlock++) }
        }
        val events = mock<EthLog> {
            on { logs } doReturn emptyList()
        }
        val ethBlockNumberRequest = Request<String, EthBlockNumber>("eth_blockNumber", emptyList(), mock(), EthBlockNumber::class.java)
        val ethGetLogsRequest = Request<String, EthLog>("eth_getLogs", emptyList(), mock(), EthLog::class.java)
        val web3j: Web3j = mock {
            on { ethBlockNumber() } doReturn ethBlockNumberRequest
            on { ethGetLogs(any()) } doReturn ethGetLogsRequest
        }
        val web3jRequestHandler = mock<Web3jRequestHandler> {
            onBlocking { sendWeb3jRequestWithRetry<Response<*>>(any()) } doAnswer { invocation ->
                val requestFunc = invocation.arguments[0] as (Web3j) -> Request<*, *>
                val request = requestFunc.invoke(web3j)
                when (request.method) {
                    "eth_blockNumber" -> blockNumber
                    "eth_getLogs" -> events
                    else -> mock()
                }
            }
        }

        // Test subject
        val eventProcessor = EvmEventProcessor(
                networkId = 1L,
                readOffset = 0.toBigInteger(),
                maxQueueSize = 1000L,
        )

        val eventFetcher = EvmEventFetcher(
                networkId = 1L,
                staticContracts = emptyList(),
                hasLegacyDynamicContracts = false,
                hasDynamicContacts = true,
                staticEvents = emptyList(),
                hasDynamicEvents = true,
                evmReadOffset = 0.toBigInteger(),
                maxReadAhead = 1L,
                networkSkipToHeight = networkSkipToHeight,
                blockchainEngine = blockchainEngine,
                web3jRequestHandler = web3jRequestHandler,
                delayWhenNoNewBlocks = 1000L,
                hasLastEvmEventHeightQuery = true,
                evmEventProcessor = eventProcessor
        )

        return eventFetcher to eventProcessor
    }
}