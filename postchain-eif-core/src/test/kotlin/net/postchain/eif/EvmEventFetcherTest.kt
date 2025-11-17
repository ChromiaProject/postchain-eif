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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class EvmEventFetcherTest {

    private val networkId = 1L
    private val address1 = "1234000000000000000000000000000000000000".hexStringToByteArray()
    private val address2 = "5678000000000000000000000000000000000000".hexStringToByteArray()

    @Test
    fun `should decrease from-height when same contract with no processed events re-added with lower skip-to-height after removal`() {
        // Test data
        val networkSkipToHeight = 100L
        val wrongSkipToHeight = 200L
        val correctSkipToHeight: Long = networkSkipToHeight
        val wrongHeightContract = gtv(mapOf(
                "address" to gtv(address1),
                "skip_to_height" to gtv(wrongSkipToHeight),
        ))
        val correctHeightContract = gtv(mapOf(
                "address" to gtv(address1),
                "skip_to_height" to gtv(correctSkipToHeight),
        ))
        val contractsToFetch = mutableListOf(wrongHeightContract)

        // Mocks and Test subject
        val mocks = createMockEvmEventSystem(networkSkipToHeight, wrongSkipToHeight, contractsToFetch)

        // Wait 3 sec and verify that the `from-height` is greater `wrongSkipToHeight`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(wrongSkipToHeight)
        }

        // Remove the contract
        contractsToFetch.clear()

        // Verify that the `from-height` is greater `wrongSkipToHeight`
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(wrongSkipToHeight)
        }

        // Add the same contract with a lower skip_to_height and verify that the `from-height` is updated
        contractsToFetch.add(correctHeightContract)
        mocks.networkBlockHeight.set(correctSkipToHeight)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(correctSkipToHeight)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(wrongSkipToHeight)
        }
    }

    @Test
    fun `shouldn't decrease from-height when same contract with processed events re-added with lower skip-to-height after removal`() {
        // Test data
        val networkSkipToHeight = 100L
        val wrongSkipToHeight = 200L
        val correctSkipToHeight: Long = networkSkipToHeight
        val wrongHeightContract = gtv(mapOf(
                "address" to gtv(address1),
                "skip_to_height" to gtv(wrongSkipToHeight),
        ))
        val correctHeightContract = gtv(mapOf(
                "address" to gtv(address1),
                "skip_to_height" to gtv(correctSkipToHeight),
        ))
        val contractsToFetch = mutableListOf(wrongHeightContract)

        // Mocks and Test subject
        val mocks = createMockEvmEventSystem(networkSkipToHeight, wrongSkipToHeight, contractsToFetch)

        // Wait 3 sec and verify that the `from-height` is greater `wrongSkipToHeight`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(wrongSkipToHeight)
        }

        // Remove the contract
        contractsToFetch.clear()

        // Verify that the `from-height` is greater `wrongSkipToHeight`
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(wrongSkipToHeight)
        }

        // Add the same contract with a lower skip_to_height and verify that the `from-height` has not been updated
        // since there were processed events
        contractsToFetch.add(correctHeightContract)
        val blockQueries = mockBlockQueries(contractsToFetch, listOf(address1 to wrongSkipToHeight))
        mocks.blockQueriesHolder.set(blockQueries)
        await.withPollDelay(delay).atMost(Duration.TEN_SECONDS).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(wrongSkipToHeight)
        }
    }

    @Test
    fun `shouldn't increase from-height when same contract with no processed events re-added with higher skip-to-height after removal`() {
        // Test data
        val networkSkipToHeight = 100L
        val wrongSkipToHeight: Long = networkSkipToHeight
        val correctSkipToHeight = 200L
        val wrongHeightContract = gtv(mapOf(
                "address" to gtv(address1),
                "skip_to_height" to gtv(wrongSkipToHeight),
        ))
        val correctHeightContract = gtv(mapOf(
                "address" to gtv(address1),
                "skip_to_height" to gtv(correctSkipToHeight),
        ))
        val contractsToFetch = mutableListOf(wrongHeightContract)

        // Mocks and Test subject
        val mocks = createMockEvmEventSystem(networkSkipToHeight, networkSkipToHeight, contractsToFetch)

        // Wait 3 sec and verify that the `from-height` is `wrongSkipToHeight`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(wrongSkipToHeight)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(correctSkipToHeight)
        }

        // Remove the contract
        contractsToFetch.clear()

        // Verify that the `from-height` is still `wrongSkipToHeight`
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(wrongSkipToHeight)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(correctSkipToHeight)
        }

        // Add the same contract with a higher skip-to-height and verify that the `from-height` has not been updated
        contractsToFetch.add(correctHeightContract)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(wrongSkipToHeight)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(correctSkipToHeight)
        }
    }

    @Test
    fun `shouldn't increase from-height when same contract with processed events re-added with higher skip-to-height after removal`() {
        // Test data
        val networkSkipToHeight = 100L
        val wrongSkipToHeight: Long = networkSkipToHeight
        val correctSkipToHeight = 200L
        val wrongHeightContract = gtv(mapOf(
                "address" to gtv(address1),
                "skip_to_height" to gtv(wrongSkipToHeight),
        ))
        val correctHeightContract = gtv(mapOf(
                "address" to gtv(address1),
                "skip_to_height" to gtv(correctSkipToHeight),
        ))
        val contractsToFetch = mutableListOf(wrongHeightContract)

        // Mocks and Test subject
        val mocks = createMockEvmEventSystem(networkSkipToHeight, networkSkipToHeight, contractsToFetch)

        // Wait 3 sec and verify that the `from-height` is `wrongSkipToHeight`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(wrongSkipToHeight)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(correctSkipToHeight)
        }

        // Remove the contract
        contractsToFetch.clear()

        // Verify that the `from-height` is still `wrongSkipToHeight`
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(wrongSkipToHeight)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(correctSkipToHeight)
        }

        // Add the same contract with a higher skip-to-height and verify that the `from-height` has not been updated
        contractsToFetch.add(correctHeightContract)
        val blockQueries = mockBlockQueries(contractsToFetch, listOf(address1 to wrongSkipToHeight))
        mocks.blockQueriesHolder.set(blockQueries)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(wrongSkipToHeight)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(correctSkipToHeight)
        }
    }

    @Test
    fun `should decrease from-height when contract with no processed events removed and new contract added with lower skip-to-height`() {
        // Test data
        val networkSkipToHeight = 100L
        val skipToHeight1 = 200L
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
        val mocks = createMockEvmEventSystem(networkSkipToHeight, networkSkipToHeight, contractsToFetch)

        // Wait 3 sec and verify that the `from-height` is `skipToHeight1`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isEqualTo(skipToHeight1)
        }

        // Remove the contract
        contractsToFetch.clear()

        // Verify that the `from-height` is still `skipToHeight1`
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isEqualTo(skipToHeight1)
        }

        // Add another contract with a lower skip-to-height and verify that the `from-height` is updated
        contractsToFetch.add(contract2)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeight2)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeight1)
        }
    }

    @Test
    fun `should decrease from-height when contract with processed events removed and new contract added with lower skip-to-height`() {
        // Test data
        val networkSkipToHeight = 100L
        val skipToHeight1 = 200L
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
        val mocks = createMockEvmEventSystem(networkSkipToHeight, networkSkipToHeight, contractsToFetch)

        // Wait 3 sec and verify that the `from-height` is `skipToHeight1`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isEqualTo(skipToHeight1)
        }

        // Remove the contract
        contractsToFetch.clear()

        // Verify that the `from-height` is still `skipToHeight1`
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isEqualTo(skipToHeight1)
        }

        // Add another contract with a lower skip-to-height and verify that the `from-height` is updated
        contractsToFetch.add(contract2)
        val blockQueries = mockBlockQueries(contractsToFetch, listOf(address1 to skipToHeight1, address2 to skipToHeight2))
        mocks.blockQueriesHolder.set(blockQueries)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeight2)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeight1)
        }
    }

    @Test
    fun `shouldn't increase from-height when contract with no processed events removed and new contract added with higher skip-to-height`() {
        // Test data
        val networkSkipToHeight = 100L
        val skipToHeight1 = 100L
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
        val mocks = createMockEvmEventSystem(networkSkipToHeight, networkSkipToHeight, contractsToFetch)

        // Wait 3 sec and verify that the `from-height` is `wrongSkipToHeight`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeight1)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeight2)
        }

        // Remove the contract
        contractsToFetch.clear()

        // Verify that the `from-height` is still `wrongSkipToHeight`
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeight1)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeight2)
        }

        // Add another contract with a higher skip-to-height and verify that the `from-height` has not been updated
        contractsToFetch.add(contract2)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeight1)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeight2)
        }
    }

    @Test
    fun `shouldn't increase from-height when contract with processed events removed and new contract added with higher skip-to-height`() {
        // Test data
        val networkSkipToHeight = 100L
        val skipToHeight1 = 100L
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
        val mocks = createMockEvmEventSystem(networkSkipToHeight, networkSkipToHeight, contractsToFetch)

        // Wait 3 sec and verify that the `from-height` is `wrongSkipToHeight`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeight1)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeight2)
        }

        // Remove the contract
        contractsToFetch.clear()

        // Verify that the `from-height` is still `wrongSkipToHeight`
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeight1)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeight2)
        }

        // Add another contract with a higher skip-to-height and verify that the `from-height` has not been updated
        contractsToFetch.add(contract2)
        val blockQueries = mockBlockQueries(contractsToFetch, listOf(address1 to skipToHeight1, address2 to skipToHeight2))
        mocks.blockQueriesHolder.set(blockQueries)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeight1)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeight2)
        }
    }

    @Test
    fun `should decrease from-height when first contract has no processed events and second contract added with lower skip-to-height`() {
        // Test data
        val networkSkipToHeight = 100L
        val skipToHeight1 = 200L
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
        val mocks = createMockEvmEventSystem(networkSkipToHeight, networkSkipToHeight, contractsToFetch)

        // Wait 3 sec and verify that the `from-height` is `skipToHeight1`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isEqualTo(skipToHeight1)
        }

        // Add the second contract with a lower skip-to-height and verify that the `from-height` is updated
        contractsToFetch.add(contract2)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeight2)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeight1)
        }
    }

    @Test
    fun `should decrease from-height when first contract has processed events and second contract added with lower skip-to-height`() {
        // Test data
        val networkSkipToHeight = 100L
        val skipToHeight1 = 200L
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
        val mocks = createMockEvmEventSystem(networkSkipToHeight, networkSkipToHeight, contractsToFetch)

        // Wait 3 sec and verify that the `from-height` is `skipToHeight1`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isEqualTo(skipToHeight1)
        }

        // Add the second contract with a lower skip-to-height and verify that the `from-height` is updated
        contractsToFetch.add(contract2)
        val blockQueries = mockBlockQueries(contractsToFetch, listOf(address1 to skipToHeight1, address2 to skipToHeight2))
        mocks.blockQueriesHolder.set(blockQueries)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeight2)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeight1)
        }
    }

    @Test
    fun `shouldn't decrease from-height when first contract has no processed events and second contract added with higher skip-to-height`() {
        // Test data
        val networkSkipToHeight = 100L
        val skipToHeight1 = 100L
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
        val mocks = createMockEvmEventSystem(networkSkipToHeight, networkSkipToHeight, contractsToFetch)

        // Wait 3 sec and verify that the `from-height` is `skipToHeight1`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeight1)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeight2)
        }

        // Add the second contract with a higher skip-to-height and verify that the `from-height` has not been updated
        contractsToFetch.add(contract2)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeight1)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeight2)
        }
    }

    @Test
    fun `shouldn't decrease from-height when first contract has processed events and second contract added with higher skip-to-height`() {
        // Test data
        val networkSkipToHeight = 100L
        val skipToHeight1 = 100L
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
        val mocks = createMockEvmEventSystem(networkSkipToHeight, networkSkipToHeight, contractsToFetch)

        // Wait 3 sec and verify that the `from-height` is `skipToHeight1`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeight1)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeight2)
        }

        // Add the second contract with a higher skip-to-height and verify that the `from-height` has not been updated
        contractsToFetch.add(contract2)
        val blockQueries = mockBlockQueries(contractsToFetch, listOf(address1 to skipToHeight1, address2 to skipToHeight2))
        mocks.blockQueriesHolder.set(blockQueries)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeight1)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeight2)
        }
    }

    @Suppress("UNCHECKED_CAST", "SameParameterValue")
    private fun createMockEvmEventSystem(networkSkipToHeight: Long, currentNetworkBlock: Long, contractsToFetch: List<Gtv>): Mocks {
        // Mocks
        val blockQueries = mockBlockQueries(contractsToFetch)
        val blockQueriesHolder = AtomicReference(blockQueries)
        val blockchainEngine = mock<BlockchainEngine> {
            on { getBlockQueries() } doAnswer { blockQueriesHolder.get() }
        }

        // Mock web3j
        val currentBlock = AtomicLong(currentNetworkBlock)
        val blockNumber = mock<EthBlockNumber> {
            on { blockNumber }.thenAnswer {
                currentBlock.incrementAndGet().toBigInteger()
            }
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
                networkId = networkId,
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

        return Mocks(eventFetcher, eventProcessor, blockQueriesHolder, currentBlock)
    }

    private fun mockBlockQueries(contractsToFetch: List<Gtv>, contractLastEventHeights: List<Pair<ByteArray, Long>> = emptyList()) = mock<BlockQueries> {
        on { query(eq(EIF_CONFIG_CONTRACTS_TO_FETCH_QUERY), any()) } doAnswer {
            CompletableFuture.completedStage(gtv(contractsToFetch))
        }

        if (contractLastEventHeights.isEmpty()) {
            on { query(eq(EIF_LAST_EVM_EVENT_HEIGHT_QUERY), any()) } doReturn
                    CompletableFuture.completedStage(GtvNull)
        } else {
            contractLastEventHeights.forEach { (address, height) ->
                on {
                    query(
                            eq(EIF_LAST_EVM_EVENT_HEIGHT_QUERY),
                            eq(gtv("network_id" to gtv(networkId), "contract_address" to gtv(address)))
                    )
                } doReturn CompletableFuture.completedStage(gtv(height.toBigInteger()))
            }
        }

        on { query(eq(EIF_CONFIG_EVENTS_QUERY), any()) } doReturn
                CompletableFuture.completedStage(gtv(listOf(
                        gtv(mapOf(
                                "name" to gtv("Deposit"),
                                "inputs" to gtv(listOf())
                        )),
                )))
    }

    class Mocks(
            val eventFetcher: EvmEventFetcher,
            val eventProcessor: EvmEventProcessor,
            val blockQueriesHolder: AtomicReference<BlockQueries>,
            val networkBlockHeight: AtomicLong,
    )
}