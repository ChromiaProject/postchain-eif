package net.postchain.eif

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import kotlinx.coroutines.delay
import net.postchain.common.hexStringToByteArray
import net.postchain.common.toHex
import net.postchain.common.types.WrappedByteArray
import net.postchain.common.wrap
import net.postchain.core.BlockchainEngine
import net.postchain.core.block.BlockQueries
import net.postchain.eif.web3j.Web3jRequestHandler
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import org.apache.logging.log4j.Level
import org.awaitility.Duration
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withPollDelay
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.web3j.abi.EventEncoder
import org.web3j.abi.datatypes.Event
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterNumber
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.Response
import org.web3j.protocol.core.methods.request.EthFilter
import org.web3j.protocol.core.methods.response.EthBlockNumber
import org.web3j.protocol.core.methods.response.EthLog
import java.math.BigInteger
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class EvmEventFetcherTest {

    private val networkId = 1L
    private val networkSkipToHeight = 100L
    private val address1 = "1234000000000000000000000000000000000000".hexStringToByteArray()
    private val address2 = "5678000000000000000000000000000000000000".hexStringToByteArray()
    private val skipToHeightLow = 100L
    private val skipToHeightHigh = 200L
    private val contract1High = gtv(mapOf("address" to gtv(address1), "skip_to_height" to gtv(skipToHeightHigh)))
    private val contract1Low = gtv(mapOf("address" to gtv(address1), "skip_to_height" to gtv(skipToHeightLow)))
    private val contract2High = gtv(mapOf("address" to gtv(address2), "skip_to_height" to gtv(skipToHeightHigh)))
    private val contract2Low = gtv(mapOf("address" to gtv(address2), "skip_to_height" to gtv(skipToHeightLow)))

    /** Every fetcher created by a test, stopped after the test so that no fetch loop keeps running (and allocating) */
    private val fetchers = CopyOnWriteArrayList<EvmEventFetcher>()

    @AfterEach
    fun shutdownFetchers() {
        fetchers.forEach { runCatching { it.shutdown() } }
        fetchers.clear()
    }

    @Test
    fun `shouldn't decrease current-height when skip-to-height is decreased and no events processed`() {
        // Mocks and Test subject
        val contractsToFetch = mutableListOf(contract1High)
        val mocks = createMockEvmEventSystem(networkSkipToHeight, skipToHeightHigh, contractsToFetch)

        // Wait 3 sec and verify that the `current-height` is greater `wrongSkipToHeight`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightHigh)
        }

        // Update skip_to_height to a lower value and verify that the `current-height` has not been updated
        val blockQueries = mockBlockQueries(listOf(contract1Low))
        mocks.blockQueriesHolder.set(blockQueries)
        await.withPollDelay(delay).atMost(Duration.TEN_SECONDS).untilAsserted {
            verify(blockQueries, atLeast(1)).query(eq(EIF_CONFIG_CONTRACTS_TO_FETCH_QUERY), any())
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightHigh)
        }
    }

    @Test
    fun `shouldn't decrease current-height when skip-to-height is decreased and events processed`() {
        // Mocks and Test subject
        val contractsToFetch = mutableListOf(contract1High)
        val mocks = createMockEvmEventSystem(networkSkipToHeight, skipToHeightHigh, contractsToFetch)

        // Wait 3 sec and verify that the `current-height` is greater `wrongSkipToHeight`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightHigh)
        }

        // Add the same contract with a lower skip_to_height and verify that the `current-height` has not been updated
        val blockQueries = mockBlockQueries(listOf(contract1Low), listOf(address1 to skipToHeightHigh))
        mocks.blockQueriesHolder.set(blockQueries)
        await.withPollDelay(delay).atMost(Duration.TEN_SECONDS).untilAsserted {
            verify(blockQueries, atLeast(1)).query(eq(EIF_CONFIG_CONTRACTS_TO_FETCH_QUERY), any())
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightHigh)
        }
    }

    @Test
    fun `shouldn't increase current-height when skip-to-height is increased and no events processed`() {
        // Mocks and Test subject
        val contractsToFetch = mutableListOf(contract1Low)
        val mocks = createMockEvmEventSystem(networkSkipToHeight, networkSkipToHeight, contractsToFetch)

        // Wait 3 sec and verify that the `current-height` is `wrongSkipToHeight`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightLow)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeightHigh)
        }

        // Add the same contract with a lower skip_to_height and verify that the `current-height` has not been updated
        val blockQueries = mockBlockQueries(listOf(contract1High))
        mocks.blockQueriesHolder.set(blockQueries)
        await.withPollDelay(delay).atMost(Duration.TEN_SECONDS).untilAsserted {
            verify(blockQueries, atLeast(1)).query(eq(EIF_CONFIG_CONTRACTS_TO_FETCH_QUERY), any())
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightLow)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeightHigh)
        }
    }

    @Test
    fun `shouldn't increase current-height when skip-to-height is increased and events processed`() {
        // Mocks and Test subject
        val contractsToFetch = mutableListOf(contract1Low)
        val mocks = createMockEvmEventSystem(networkSkipToHeight, networkSkipToHeight, contractsToFetch)

        // Wait 3 sec and verify that the `current-height` is `wrongSkipToHeight`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightLow)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeightHigh)
        }

        // Add the same contract with a lower skip_to_height and verify that the `current-height` has not been updated
        val blockQueries = mockBlockQueries(listOf(contract1High), listOf(address1 to skipToHeightLow))
        mocks.blockQueriesHolder.set(blockQueries)
        await.withPollDelay(delay).atMost(Duration.TEN_SECONDS).untilAsserted {
            verify(blockQueries, atLeast(1)).query(eq(EIF_CONFIG_CONTRACTS_TO_FETCH_QUERY), any())
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightLow)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeightHigh)
        }
    }

    @Test
    fun `should decrease current-height when same contract with no processed events re-added with lower skip-to-height after removal`() {
        // Mocks and Test subject
        val contractsToFetch = mutableListOf(contract1High)
        val mocks = createMockEvmEventSystem(networkSkipToHeight, skipToHeightHigh, contractsToFetch)

        // Wait 3 sec and verify that the `current-height` is greater `wrongSkipToHeight`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightHigh)
        }

        // Remove the contract
        contractsToFetch.clear()

        // Verify that the `current-height` is greater `wrongSkipToHeight`
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightHigh)
        }

        // Add the same contract with a lower skip_to_height and verify that the `current-height` is updated
        contractsToFetch.add(contract1Low)
        mocks.networkBlockHeight.set(skipToHeightLow)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightLow)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeightHigh)
        }
    }

    @Test
    fun `shouldn't decrease current-height when same contract with processed events re-added with lower skip-to-height after removal`() {
        // Mocks and Test subject
        val contractsToFetch = mutableListOf(contract1High)
        val mocks = createMockEvmEventSystem(networkSkipToHeight, skipToHeightHigh, contractsToFetch)

        // Wait 3 sec and verify that the `current-height` is greater `wrongSkipToHeight`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightHigh)
        }

        // Remove the contract
        contractsToFetch.clear()

        // Verify that the `current-height` is greater `wrongSkipToHeight`
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightHigh)
        }

        // Add the same contract with a lower skip_to_height and verify that the `current-height` has not been updated
        // since there were processed events
        contractsToFetch.add(contract1Low)
        val blockQueries = mockBlockQueries(contractsToFetch, listOf(address1 to skipToHeightHigh))
        mocks.blockQueriesHolder.set(blockQueries)
        await.withPollDelay(delay).atMost(Duration.TEN_SECONDS).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightHigh)
        }
    }

    @Test
    fun `shouldn't increase current-height when same contract with no processed events re-added with higher skip-to-height after removal`() {
        // Mocks and Test subject
        val contractsToFetch = mutableListOf(contract1Low)
        val mocks = createMockEvmEventSystem(networkSkipToHeight, networkSkipToHeight, contractsToFetch)

        // Wait 3 sec and verify that the `current-height` is `wrongSkipToHeight`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightLow)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeightHigh)
        }

        // Remove the contract
        contractsToFetch.clear()

        // Verify that the `current-height` is still `wrongSkipToHeight`
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightLow)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeightHigh)
        }

        // Add the same contract with a higher skip-to-height and verify that the `current-height` has not been updated
        contractsToFetch.add(contract1High)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightLow)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeightHigh)
        }
    }

    @Test
    fun `shouldn't increase current-height when same contract with processed events re-added with higher skip-to-height after removal`() {
        // Mocks and Test subject
        val contractsToFetch = mutableListOf(contract1Low)
        val mocks = createMockEvmEventSystem(networkSkipToHeight, networkSkipToHeight, contractsToFetch)

        // Wait 3 sec and verify that the `current-height` is `wrongSkipToHeight`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightLow)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeightHigh)
        }

        // Remove the contract
        contractsToFetch.clear()

        // Verify that the `current-height` is still `wrongSkipToHeight`
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightLow)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeightHigh)
        }

        // Add the same contract with a higher skip-to-height and verify that the `current-height` has not been updated
        contractsToFetch.add(contract1High)
        val blockQueries = mockBlockQueries(contractsToFetch, listOf(address1 to skipToHeightLow))
        mocks.blockQueriesHolder.set(blockQueries)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightLow)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeightHigh)
        }
    }

    @Test
    fun `should decrease current-height when contract with no processed events removed and new contract added with lower skip-to-height`() {
        // Mocks and Test subject
        val contractsToFetch = mutableListOf(contract1High)
        val mocks = createMockEvmEventSystem(networkSkipToHeight, networkSkipToHeight, contractsToFetch)

        // Wait 3 sec and verify that the `current-height` is `skipToHeight1`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isEqualTo(skipToHeightHigh)
        }

        // Remove the contract
        contractsToFetch.clear()

        // Verify that the `current-height` is still `skipToHeight1`
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isEqualTo(skipToHeightHigh)
        }

        // Add another contract with a lower skip-to-height and verify that the `current-height` is updated
        contractsToFetch.add(contract2Low)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightLow)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeightHigh)
        }
    }

    @Test
    fun `should decrease current-height when contract with processed events removed and new contract added with lower skip-to-height`() {
        // Mocks and Test subject
        val contractsToFetch = mutableListOf(contract1High)
        val mocks = createMockEvmEventSystem(networkSkipToHeight, networkSkipToHeight, contractsToFetch)

        // Wait 3 sec and verify that the `current-height` is `skipToHeight1`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isEqualTo(skipToHeightHigh)
        }

        // Remove the contract
        contractsToFetch.clear()

        // Verify that the `current-height` is still `skipToHeight1`
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isEqualTo(skipToHeightHigh)
        }

        // Add another contract with a lower skip-to-height and verify that the `current-height` is updated
        contractsToFetch.add(contract2Low)
        val blockQueries = mockBlockQueries(contractsToFetch, listOf(address1 to skipToHeightHigh, address2 to skipToHeightLow))
        mocks.blockQueriesHolder.set(blockQueries)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightLow)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeightHigh)
        }
    }

    @Test
    fun `shouldn't increase current-height when contract with no processed events removed and new contract added with higher skip-to-height`() {
        // Mocks and Test subject
        val contractsToFetch = mutableListOf(contract1Low)
        val mocks = createMockEvmEventSystem(networkSkipToHeight, networkSkipToHeight, contractsToFetch)

        // Wait 3 sec and verify that the `current-height` is `wrongSkipToHeight`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightLow)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeightHigh)
        }

        // Remove the contract
        contractsToFetch.clear()

        // Verify that the `current-height` is still `wrongSkipToHeight`
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightLow)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeightHigh)
        }

        // Add another contract with a higher skip-to-height and verify that the `current-height` has not been updated
        contractsToFetch.add(contract2High)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightLow)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeightHigh)
        }
    }

    @Test
    fun `shouldn't increase current-height when contract with processed events removed and new contract added with higher skip-to-height`() {
        // Mocks and Test subject
        val contractsToFetch = mutableListOf(contract1Low)
        val mocks = createMockEvmEventSystem(networkSkipToHeight, networkSkipToHeight, contractsToFetch)

        // Wait 3 sec and verify that the `current-height` is `wrongSkipToHeight`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightLow)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeightHigh)
        }

        // Remove the contract
        contractsToFetch.clear()

        // Verify that the `current-height` is still `wrongSkipToHeight`
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightLow)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeightHigh)
        }

        // Add another contract with a higher skip-to-height and verify that the `current-height` has not been updated
        contractsToFetch.add(contract2High)
        val blockQueries = mockBlockQueries(contractsToFetch, listOf(address1 to skipToHeightLow, address2 to skipToHeightHigh))
        mocks.blockQueriesHolder.set(blockQueries)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightLow)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeightHigh)
        }
    }

    @Test
    fun `should decrease current-height when first contract has no processed events and second contract added with lower skip-to-height`() {
        // Mocks and Test subject
        val contractsToFetch = mutableListOf(contract1High)
        val mocks = createMockEvmEventSystem(networkSkipToHeight, networkSkipToHeight, contractsToFetch)

        // Wait 3 sec and verify that the `current-height` is `skipToHeight1`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isEqualTo(skipToHeightHigh)
        }

        // Add the second contract with a lower skip-to-height and verify that the `current-height` is updated
        contractsToFetch.add(contract2Low)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightLow)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeightHigh)
        }
    }

    @Test
    fun `should decrease current-height when first contract has processed events and second contract added with lower skip-to-height`() {
        // Mocks and Test subject
        val contractsToFetch = mutableListOf(contract1High)
        val mocks = createMockEvmEventSystem(networkSkipToHeight, networkSkipToHeight, contractsToFetch)

        // Wait 3 sec and verify that the `current-height` is `skipToHeight1`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isEqualTo(skipToHeightHigh)
        }

        // Add the second contract with a lower skip-to-height and verify that the `current-height` is updated
        contractsToFetch.add(contract2Low)
        val blockQueries = mockBlockQueries(contractsToFetch, listOf(address1 to skipToHeightHigh, address2 to skipToHeightLow))
        mocks.blockQueriesHolder.set(blockQueries)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightLow)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeightHigh)
        }
    }

    @Test
    fun `shouldn't decrease current-height when first contract has no processed events and second contract added with higher skip-to-height`() {
        // Mocks and Test subject
        val contractsToFetch = mutableListOf(contract1Low)
        val mocks = createMockEvmEventSystem(networkSkipToHeight, networkSkipToHeight, contractsToFetch)

        // Wait 3 sec and verify that the `current-height` is `skipToHeight1`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightLow)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeightHigh)
        }

        // Add the second contract with a higher skip-to-height and verify that the `current-height` has not been updated
        contractsToFetch.add(contract2High)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightLow)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeightHigh)
        }
    }

    @Test
    fun `shouldn't decrease current-height when first contract has processed events and second contract added with higher skip-to-height`() {
        // Mocks and Test subject
        val contractsToFetch = mutableListOf(contract1Low)
        val mocks = createMockEvmEventSystem(networkSkipToHeight, networkSkipToHeight, contractsToFetch)

        // Wait 3 sec and verify that the `current-height` is `skipToHeight1`
        val delay = Duration(3L, TimeUnit.SECONDS)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightLow)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeightHigh)
        }

        // Add the second contract with a higher skip-to-height and verify that the `current-height` has not been updated
        contractsToFetch.add(contract2High)
        val blockQueries = mockBlockQueries(contractsToFetch, listOf(address1 to skipToHeightLow, address2 to skipToHeightHigh))
        mocks.blockQueriesHolder.set(blockQueries)
        await.withPollDelay(delay).untilAsserted {
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(skipToHeightLow)
            assertThat(mocks.eventProcessor.lastReadLogBlockHeight.toLong()).isLessThan(skipToHeightHigh)
        }
    }

    // ---- Restart / resume ------------------------------------------------------------------------------------------

    @Test
    fun `should resume from the persisted read cursor on start`() {
        val rig = createRig(
                contracts = listOf(contract1Low),
                lastEventHeights = mapOf(address1.wrap() to 120L),
                persistedCursor = persistedCursorAt(150L),
                headHeight = 160L,
        )
        try {
            await.atMost(Duration.TEN_SECONDS).untilAsserted {
                assertThat(rig.filters.isNotEmpty()).isTrue()
            }
            // Reading starts right after the persisted height, not at the last committed event (120) or skip-to-height (100)
            assertThat(rig.filters.first()).isEqualTo(151L to 160L)

            await.atMost(Duration.TEN_SECONDS).untilAsserted {
                val cursor = rig.store.load(networkId)
                assertThat(cursor).isNotNull()
                assertThat(cursor!!.height).isEqualTo(160.toBigInteger())
                assertThat(cursor.contracts).isEqualTo(mapOf(address1.wrap() to skipToHeightLow))
            }
            assertThat(rig.filters.all { (from, _) -> from > 150L }).isTrue()
        } finally {
            rig.eventFetcher.shutdown()
        }
    }

    @Test
    fun `should ignore a persisted read cursor below ignoreReadCursorBelow`() {
        val rig = createRig(
                contracts = listOf(contract1Low),
                lastEventHeights = mapOf(address1.wrap() to 120L),
                persistedCursor = persistedCursorAt(150L),
                headHeight = 160L,
                ignoreReadCursorBelow = 155L,
        )
        try {
            await.atMost(Duration.TEN_SECONDS).untilAsserted {
                assertThat(rig.filters.isNotEmpty()).isTrue()
            }
            // The cursor (150) is below the configured height: reading starts after the last committed event (120) instead
            assertThat(rig.filters.first()).isEqualTo(121L to 160L)
            // ... and the cursor is rewritten from there, so it is ignored again until the reader has passed 155
            assertThat(rig.store.savedCursors.first().height).isEqualTo(120.toBigInteger())
            await.atMost(Duration.TEN_SECONDS).untilAsserted {
                assertThat(rig.store.load(networkId)?.height).isEqualTo(160.toBigInteger())
            }
        } finally {
            rig.eventFetcher.shutdown()
        }
    }

    @Test
    fun `should use a persisted read cursor at or above ignoreReadCursorBelow`() {
        val rig = createRig(
                contracts = listOf(contract1Low),
                lastEventHeights = mapOf(address1.wrap() to 120L),
                persistedCursor = persistedCursorAt(150L),
                headHeight = 160L,
                ignoreReadCursorBelow = 150L,
        )
        try {
            await.atMost(Duration.TEN_SECONDS).untilAsserted {
                assertThat(rig.filters.isNotEmpty()).isTrue()
            }
            assertThat(rig.filters.first()).isEqualTo(151L to 160L)
        } finally {
            rig.eventFetcher.shutdown()
        }
    }

    @Test
    fun `shouldn't resume below the on-chain height when the persisted read cursor is behind it`() {
        val rig = createRig(
                contracts = listOf(contract1Low),
                lastEventHeights = mapOf(address1.wrap() to 140L),
                persistedCursor = persistedCursorAt(110L),
                headHeight = 160L,
        )
        try {
            await.atMost(Duration.TEN_SECONDS).untilAsserted {
                assertThat(rig.filters.isNotEmpty()).isTrue()
            }
            assertThat(rig.filters.first()).isEqualTo(141L to 160L)
        } finally {
            rig.eventFetcher.shutdown()
        }
    }

    @Test
    fun `should start from skip-to-height of a contract with no processed events that the persisted read cursor does not cover`() {
        val rig = createRig(
                contracts = listOf(contract1Low, contract2Low),
                lastEventHeights = mapOf(address1.wrap() to 120L),
                persistedCursor = persistedCursorAt(150L),
                headHeight = 160L,
        )
        try {
            await.atMost(Duration.TEN_SECONDS).untilAsserted {
                assertThat(rig.filters.isNotEmpty()).isTrue()
            }
            assertThat(rig.filters.first()).isEqualTo(101L to 160L)
            // Both contracts are covered once the range has been read
            await.atMost(Duration.TEN_SECONDS).untilAsserted {
                assertThat(rig.store.load(networkId)).isEqualTo(
                        EvmReadCursor(160.toBigInteger(), mapOf(address1.wrap() to skipToHeightLow, address2.wrap() to skipToHeightLow)))
            }
        } finally {
            rig.eventFetcher.shutdown()
        }
    }

    @Test
    fun `should start from last processed event of a contract with processed events that the persisted read cursor does not cover`() {
        val rig = createRig(
                contracts = listOf(contract1Low, contract2Low),
                lastEventHeights = mapOf(address1.wrap() to 120L, address2.wrap() to 130L),
                persistedCursor = persistedCursorAt(150L),
                headHeight = 160L,
        )
        try {
            await.atMost(Duration.TEN_SECONDS).untilAsserted {
                assertThat(rig.filters.isNotEmpty()).isTrue()
            }
            assertThat(rig.filters.first()).isEqualTo(131L to 160L)
        } finally {
            rig.eventFetcher.shutdown()
        }
    }

    @Test
    fun `shouldn't use the persisted read cursor for a contract whose skip-to-height has changed`() {
        val rig = createRig(
                contracts = listOf(contract1Low),
                lastEventHeights = mapOf(address1.wrap() to 120L),
                persistedCursor = EvmReadCursor(150.toBigInteger(), mapOf(address1.wrap() to skipToHeightHigh)),
                headHeight = 160L,
        )
        try {
            await.atMost(Duration.TEN_SECONDS).untilAsserted {
                assertThat(rig.filters.isNotEmpty()).isTrue()
            }
            assertThat(rig.filters.first()).isEqualTo(121L to 160L)
        } finally {
            rig.eventFetcher.shutdown()
        }
    }

    @Test
    fun `should keep the skip-to-height a contract entered the filter with when it is changed in place`() {
        val contractsToFetch = mutableListOf(contract1High)
        val rig = createRig(contracts = contractsToFetch, headHeight = 250L, maxReadAhead = 100L, delayWhenNoNewBlocks = 50L)
        try {
            await.atMost(Duration.TEN_SECONDS).untilAsserted {
                assertThat(rig.store.load(networkId)).isEqualTo(EvmReadCursor(250.toBigInteger(), mapOf(address1.wrap() to skipToHeightHigh)))
            }
            val savesBeforeChange = rig.store.savedCursors.size

            // Same address, lower skip-to-height, no removal in between
            contractsToFetch[0] = contract1Low

            await.atMost(Duration.TEN_SECONDS).untilAsserted {
                assertThat(rig.store.savedCursors.size).isGreaterThan(savesBeforeChange + 1)
            }
            // No rewind, and the cursor still records the skip-to-height the contract was read with
            assertThat(rig.eventFetcher.newContractFlushes.get()).isEqualTo(0L)
            assertThat(rig.eventProcessor.lastReadLogBlockHeight).isEqualTo(250.toBigInteger())
            assertThat(rig.store.load(networkId)).isEqualTo(EvmReadCursor(250.toBigInteger(), mapOf(address1.wrap() to skipToHeightHigh)))
        } finally {
            rig.eventFetcher.shutdown()
        }
    }

    @Test
    fun `should start from the lowest of last processed event and skip-to-height without a persisted read cursor`() {
        val rig = createRig(
                contracts = listOf(contract1Low, contract2High),
                lastEventHeights = mapOf(address1.wrap() to 120L),
                headHeight = 250L,
        )
        try {
            await.atMost(Duration.TEN_SECONDS).untilAsserted {
                assertThat(rig.filters.isNotEmpty()).isTrue()
            }
            // address1: last event at 120; address2: no events, skip-to-height 200. One read window is maxReadAhead + 1 blocks
            assertThat(rig.filters.first()).isEqualTo(121L to 221L)
        } finally {
            rig.eventFetcher.shutdown()
        }
    }

    @Test
    fun `should start from the lowest last processed event without a persisted read cursor`() {
        val rig = createRig(
                contracts = listOf(contract1Low, contract2High),
                lastEventHeights = mapOf(address1.wrap() to 150L, address2.wrap() to 130L),
                headHeight = 160L,
        )
        try {
            await.atMost(Duration.TEN_SECONDS).untilAsserted {
                assertThat(rig.filters.isNotEmpty()).isTrue()
            }
            assertThat(rig.filters.first()).isEqualTo(131L to 160L)
        } finally {
            rig.eventFetcher.shutdown()
        }
    }

    @Test
    fun `should persist the read cursor as reading progresses`() {
        val rig = createRig(contracts = listOf(contract1Low), headHeight = 160L, delayWhenNoNewBlocks = 50L)
        try {
            // Everything up to the head has been read and contains no events
            await.atMost(Duration.TEN_SECONDS).untilAsserted {
                assertThat(rig.store.load(networkId)?.height).isEqualTo(160.toBigInteger())
            }
            assertThat(rig.store.load(networkId)!!.contracts).isEqualTo(mapOf(address1.wrap() to skipToHeightLow))

            // A block with an event that is not committed yet holds the cursor back
            rig.logs.set(listOf(logObject(170L, address1)))
            rig.headHeight.set(175L)
            await.atMost(Duration.TEN_SECONDS).untilAsserted {
                assertThat(rig.eventProcessor.lastReadLogBlockHeight).isEqualTo(175.toBigInteger())
                assertThat(rig.store.load(networkId)?.height).isEqualTo(169.toBigInteger())
            }
            assertThat(rig.eventProcessor.getEventData().map { it.evmBlockHeight }).isEqualTo(listOf(170.toBigInteger()))

            // Consumed and committed: the cursor moves past it
            rig.eventProcessor.markAsProcessed(rig.eventProcessor.getEventData())
            rig.eventProcessor.blockCommitted(170.toBigInteger())
            await.atMost(Duration.TEN_SECONDS).untilAsserted {
                assertThat(rig.store.load(networkId)?.height).isEqualTo(175.toBigInteger())
            }
        } finally {
            rig.eventFetcher.shutdown()
        }
    }

    @Test
    fun `should lower the persisted read cursor when events are flushed`() {
        val rig = createRig(
                contracts = listOf(contract1Low),
                persistedCursor = persistedCursorAt(150L),
                headHeight = 160L,
                delayWhenNoNewBlocks = 50L,
        )
        try {
            await.atMost(Duration.TEN_SECONDS).untilAsserted {
                assertThat(rig.store.load(networkId)?.height).isEqualTo(160.toBigInteger())
            }
            val savesBeforeFlush = rig.store.savedCursors.size

            rig.eventFetcher.flushEvents(140.toBigInteger())

            // The first persisted height that differs from the one before the flush is the flush height
            await.atMost(Duration.TEN_SECONDS).untilAsserted {
                val heightsAfterFlush = rig.store.savedCursors.drop(savesBeforeFlush).map { it.height.toLong() }
                assertThat(heightsAfterFlush.firstOrNull { it != 160L }).isEqualTo(140L)
            }
            // ... and reading resumes from there
            await.atMost(Duration.TEN_SECONDS).untilAsserted {
                assertThat(rig.store.load(networkId)?.height).isEqualTo(160.toBigInteger())
            }
            assertThat(rig.filters.last()).isEqualTo(141L to 160L)
            assertThat(rig.eventFetcher.validationConflictFlushes.get()).isEqualTo(1L)
        } finally {
            rig.eventFetcher.shutdown()
        }
    }

    @Test
    fun `should hold the persisted read cursor below a validation conflict without flushing`() {
        val rig = createRig(
                contracts = listOf(contract1Low),
                persistedCursor = persistedCursorAt(150L),
                headHeight = 150L,
                headAdvancesPerPoll = true,
                maxReadAhead = 1L,
                delayWhenNoNewBlocks = 50L,
        )
        try {
            await.atMost(Duration.TEN_SECONDS).untilAsserted {
                assertThat(rig.eventProcessor.lastReadLogBlockHeight.toLong()).isGreaterThan(152L)
            }

            rig.eventFetcher.recordValidationConflict(120.toBigInteger())

            await.atMost(Duration.TEN_SECONDS).untilAsserted {
                assertThat(rig.store.load(networkId)!!.height).isEqualTo(119.toBigInteger())
            }
            assertThat(rig.eventFetcher.lastPersistedCursorHeight).isEqualTo(119.toBigInteger())
            assertThat(rig.eventFetcher.signerValidationConflicts.get()).isEqualTo(1L)
            assertThat(rig.eventFetcher.validationConflictFlushes.get()).isEqualTo(0L)

            // Reading continues in memory, the conflict only affects what is persisted
            val position = rig.eventProcessor.lastReadLogBlockHeight
            await.atMost(Duration.TEN_SECONDS).untilAsserted {
                assertThat(rig.eventProcessor.lastReadLogBlockHeight).isGreaterThan(position + 2.toBigInteger())
            }
            assertThat(rig.store.load(networkId)!!.height).isEqualTo(119.toBigInteger())
            assertThat(rig.filters.zipWithNext().all { (previous, next) -> next.first > previous.first }).isTrue()

            // Once the conflicting EVM block has been committed the persisted cursor follows the read position again
            rig.eventProcessor.blockCommitted(125.toBigInteger())
            await.atMost(Duration.TEN_SECONDS).untilAsserted {
                assertThat(rig.store.load(networkId)!!.height).isGreaterThan(position)
            }

            // A conflict at a height that is already confirmed is only counted
            rig.eventFetcher.recordValidationConflict(110.toBigInteger())
            assertThat(rig.eventFetcher.signerValidationConflicts.get()).isEqualTo(2L)
            val positionAfterIgnoredConflict = rig.eventProcessor.lastReadLogBlockHeight
            await.atMost(Duration.TEN_SECONDS).untilAsserted {
                assertThat(rig.store.load(networkId)!!.height).isGreaterThan(positionAfterIgnoredConflict)
            }
        } finally {
            rig.eventFetcher.shutdown()
        }
    }

    @Test
    fun `should backfill a contract added at runtime from its skip-to-height and cover it afterwards`() {
        val contractsToFetch = mutableListOf(contract1High)
        val rig = createRig(contracts = contractsToFetch, headHeight = 250L, maxReadAhead = 100L, delayWhenNoNewBlocks = 50L)
        try {
            await.atMost(Duration.TEN_SECONDS).untilAsserted {
                assertThat(rig.store.load(networkId)).isEqualTo(EvmReadCursor(250.toBigInteger(), mapOf(address1.wrap() to skipToHeightHigh)))
            }
            assertThat(rig.filters.first()).isEqualTo(201L to 250L)
            val requestsBeforeAdd = rig.filters.size

            contractsToFetch.add(contract2Low)

            await.atMost(Duration.TEN_SECONDS).untilAsserted {
                assertThat(rig.store.load(networkId)).isEqualTo(
                        EvmReadCursor(250.toBigInteger(), mapOf(address1.wrap() to skipToHeightHigh, address2.wrap() to skipToHeightLow)))
            }
            assertThat(rig.eventFetcher.newContractFlushes.get()).isEqualTo(1L)
            assertThat(rig.filters.drop(requestsBeforeAdd).take(2)).isEqualTo(listOf(101L to 201L, 202L to 250L))
            // The backfill start was persisted before the backfill began
            assertThat(rig.store.savedCursors.map { it.height.toLong() }.contains(100L)).isTrue()
        } finally {
            rig.eventFetcher.shutdown()
        }
    }

    // ---- Logging ---------------------------------------------------------------------------------------------------

    @Test
    fun `should warn when the read position is far behind the chain head`() {
        val logAppender = TestLogAppender.addAppender(listOf(Level.INFO, Level.WARN, Level.ERROR))
        logAppender.clear()
        val rig = createRig(
                contracts = listOf(contract1Low),
                persistedCursor = persistedCursorAt(150L),
                headHeight = 10_000L,
                maxReadAhead = 100L,
                getLogsLatency = 20L,
        )
        try {
            await.atMost(Duration.TEN_SECONDS).untilAsserted {
                logAppender.assertWarn("blocks behind the readable EVM chain head")
            }
        } finally {
            rig.eventFetcher.shutdown()
        }
    }

    @Test
    fun `shouldn't warn when the read position is within one read window of the chain head`() {
        val logAppender = TestLogAppender.addAppender(listOf(Level.INFO, Level.WARN, Level.ERROR))
        logAppender.clear()
        val rig = createRig(
                contracts = listOf(contract1Low),
                persistedCursor = persistedCursorAt(150L),
                headHeight = 160L,
                maxReadAhead = 100L,
        )
        try {
            await.atMost(Duration.TEN_SECONDS).untilAsserted {
                logAppender.assertInfo("blocks behind the readable EVM chain head")
            }
            assertThat(logAppender.hasEvent(Level.WARN, "blocks behind the readable EVM chain head")).isFalse()
        } finally {
            rig.eventFetcher.shutdown()
        }
    }

    // ---- Test rig --------------------------------------------------------------------------------------------------

    private val depositEvent = Event("Deposit", listOf())
    private val depositEventConfig = gtv(mapOf("name" to gtv("Deposit"), "inputs" to gtv(listOf())))


    private fun persistedCursorAt(height: Long) = EvmReadCursor(height.toBigInteger(), mapOf(address1.wrap() to skipToHeightLow))

    private fun logObject(blockNumber: Long, address: ByteArray): EthLog.LogObject = EthLog.LogObject(
            false,
            "0x0",
            "0x0",
            "0x" + "ab".repeat(32),
            "0x" + "cd".repeat(32),
            "0x" + blockNumber.toString(16),
            "0x" + address.toHex(),
            "0x",
            "mined",
            listOf(EventEncoder.encode(depositEvent)),
    )

    private class MutableClock(startMillis: Long) : Clock() {
        private val now = AtomicLong(startMillis)

        fun advance(millis: Long) {
            now.addAndGet(millis)
        }

        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(now.get())
        override fun millis(): Long = now.get()
    }

    private class RecordingEvmReadCursorStore : EvmReadCursorStore {
        private val delegate = InMemoryEvmReadCursorStore()
        val savedCursors = CopyOnWriteArrayList<EvmReadCursor>()

        override fun load(networkId: Long): EvmReadCursor? = delegate.load(networkId)

        override fun save(networkId: Long, cursor: EvmReadCursor) {
            delegate.save(networkId, cursor)
            savedCursors.add(cursor)
        }
    }

    private class Rig(
            val eventFetcher: EvmEventFetcher,
            val eventProcessor: EvmEventProcessor,
            val store: RecordingEvmReadCursorStore,
            val headHeight: AtomicLong,
            /** from/to of every eth_getLogs request, in order */
            val filters: CopyOnWriteArrayList<Pair<Long, Long>>,
            val clock: MutableClock,
            /** logs returned by the next eth_getLogs request, consumed once */
            val logs: AtomicReference<List<EthLog.LogResult<*>>>,
    )

    @Suppress("UNCHECKED_CAST")
    private fun createRig(
            contracts: List<Gtv>,
            lastEventHeights: Map<WrappedByteArray, Long> = emptyMap(),
            persistedCursor: EvmReadCursor? = null,
            headHeight: Long,
            headAdvancesPerPoll: Boolean = false,
            maxReadAhead: Long = 100L,
            delayWhenNoNewBlocks: Long = 100L,
            getLogsLatency: Long = 0L,
            ignoreReadCursorBelow: Long = 0L,
    ): Rig {
        val blockQueries = mock<BlockQueries> {
            on { query(eq(EIF_CONFIG_CONTRACTS_TO_FETCH_QUERY), any()) } doAnswer {
                CompletableFuture.completedStage(gtv(contracts))
            }
            on { query(eq(EIF_LAST_EVM_EVENT_HEIGHT_QUERY), any()) } doAnswer { invocation ->
                val address = (invocation.arguments[1] as Gtv)["contract_address"]!!.asByteArray().wrap()
                CompletableFuture.completedStage(lastEventHeights[address]?.let { gtv(it.toBigInteger()) } ?: GtvNull)
            }
            on { query(eq(EIF_CONFIG_EVENTS_QUERY), any()) } doReturn CompletableFuture.completedStage(gtv(listOf(depositEventConfig)))
        }
        val blockchainEngine = mock<BlockchainEngine> {
            on { getBlockQueries() } doReturn blockQueries
        }

        // Mock web3j: the head advances (if at all) once per eth_blockNumber request.
        // Mocks that are not verified are stub-only so that Mockito does not record every invocation of the fetch loop.
        val clock = MutableClock(1_000_000L)
        val head = AtomicLong(headHeight)
        val blockNumber = mock<EthBlockNumber>(stubOnly = true) {
            on { blockNumber }.thenAnswer { head.get().toBigInteger() }
        }
        val pendingLogs = AtomicReference<List<EthLog.LogResult<*>>>(emptyList())
        val ethLog = mock<EthLog>(stubOnly = true) {
            on { logs } doAnswer { pendingLogs.getAndSet(emptyList()) }
        }
        val filters = CopyOnWriteArrayList<Pair<Long, Long>>()
        val ethBlockNumberRequest = Request<String, EthBlockNumber>("eth_blockNumber", emptyList(), mock(), EthBlockNumber::class.java)
        val ethGetLogsRequest = Request<String, EthLog>("eth_getLogs", emptyList(), mock(), EthLog::class.java)
        val web3j: Web3j = mock(stubOnly = true) {
            on { ethBlockNumber() } doAnswer {
                if (headAdvancesPerPoll) head.incrementAndGet()
                ethBlockNumberRequest
            }
            on { ethGetLogs(any()) } doAnswer { invocation ->
                val filter = invocation.arguments[0] as EthFilter
                filters.add((filter.fromBlock as DefaultBlockParameterNumber).blockNumber.toLong() to (filter.toBlock as DefaultBlockParameterNumber).blockNumber.toLong())
                if (getLogsLatency > 0) Thread.sleep(getLogsLatency)
                ethGetLogsRequest
            }
        }
        val web3jRequestHandler = mock<Web3jRequestHandler>(stubOnly = true) {
            onBlocking { sendWeb3jRequestWithRetry<Response<*>>(any()) } doAnswer { invocation ->
                val requestFunc = invocation.arguments[0] as (Web3j) -> Request<*, *>
                val request = requestFunc.invoke(web3j)
                when (request.method) {
                    "eth_blockNumber" -> blockNumber
                    "eth_getLogs" -> ethLog
                    else -> mock()
                }
            }
        }

        val store = RecordingEvmReadCursorStore()
        persistedCursor?.let { store.save(networkId, it) }
        store.savedCursors.clear()

        // Test subject
        val eventProcessor = EvmEventProcessor(
                networkId = networkId,
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
                maxReadAhead = maxReadAhead,
                networkSkipToHeight = networkSkipToHeight,
                blockchainEngine = blockchainEngine,
                web3jRequestHandler = web3jRequestHandler,
                delayWhenNoNewBlocks = delayWhenNoNewBlocks,
                hasLastEvmEventHeightQuery = true,
                evmEventProcessor = eventProcessor,
                readCursorStore = store,
                ignoreReadCursorBelow = ignoreReadCursorBelow.toBigInteger(),
                cursorPersistInterval = 0L,
                clock = clock,
        ).also(fetchers::add)

        return Rig(eventFetcher, eventProcessor, store, head, filters, clock, pendingLogs)
    }

    @Suppress("UNCHECKED_CAST", "SameParameterValue")
    private fun createMockEvmEventSystem(networkSkipToHeight: Long, currentNetworkHeight: Long, contractsToFetch: List<Gtv>): Mocks {
        // Mocks
        val blockQueries = mockBlockQueries(contractsToFetch)
        val blockQueriesHolder = AtomicReference(blockQueries)
        val blockchainEngine = mock<BlockchainEngine> {
            on { getBlockQueries() } doAnswer { blockQueriesHolder.get() }
        }

        // Mock web3j: the network produces one block per eth_blockNumber request.
        // Mocks that are not verified are stub-only so that Mockito does not record every invocation of the fetch loop.
        val currentBlock = AtomicLong(currentNetworkHeight)
        val blockNumber = mock<EthBlockNumber>(stubOnly = true) {
            on { blockNumber }.thenAnswer { currentBlock.get().toBigInteger() }
        }
        val events = mock<EthLog>(stubOnly = true) {
            on { logs } doReturn emptyList()
        }
        val ethBlockNumberRequest = Request<String, EthBlockNumber>("eth_blockNumber", emptyList(), mock(), EthBlockNumber::class.java)
        val ethGetLogsRequest = Request<String, EthLog>("eth_getLogs", emptyList(), mock(), EthLog::class.java)
        val web3j: Web3j = mock(stubOnly = true) {
            on { ethBlockNumber() } doAnswer {
                currentBlock.incrementAndGet()
                ethBlockNumberRequest
            }
            on { ethGetLogs(any()) } doReturn ethGetLogsRequest
        }
        val web3jRequestHandler = mock<Web3jRequestHandler>(stubOnly = true) {
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
                evmEventProcessor = eventProcessor,
                readCursorStore = InMemoryEvmReadCursorStore(),
        ).also(fetchers::add)

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