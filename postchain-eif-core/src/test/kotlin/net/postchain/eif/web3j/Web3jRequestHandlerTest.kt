package net.postchain.eif.web3j

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import net.postchain.common.exception.ProgrammerMistake
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.Response
import org.web3j.protocol.core.methods.response.EthBlockNumber

class Web3jRequestHandlerTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Assert that web3j requests are sent with exponential back off`() = runTest(StandardTestDispatcher()) {
        val web3jServiceMock: Web3j = mock()
        val web3jRequestHandler = Web3jRequestHandler(500, 60_000L, 10, listOf("http://localhost:8545"), listOf(web3jServiceMock))

        val requestMock: Request<*, Response<*>> = mock {
            on { send() } doThrow RuntimeException("You want me to fail")
        }
        backgroundScope.launch {
            web3jRequestHandler.sendWeb3jRequestWithRetry { requestMock }
        }

        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        // First attempt
        verify(requestMock, times(1)).send()
        // Verify that we don't try again until we have waited for base timeout
        testScheduler.advanceTimeBy(200)
        verify(requestMock, times(1)).send()
        // Verify that we have tried again after 500 millis
        testScheduler.advanceTimeBy(300)
        verify(requestMock, times(2)).send()
        // Verify that 500 is not enough next retry (because of back off)
        testScheduler.advanceTimeBy(500)
        verify(requestMock, times(2)).send()
        // Wait the extra back off time and verify that we call send
        testScheduler.advanceTimeBy((500 * Web3jRequestHandler.DELAY_POWER_BASE).toLong() - 500)
        verify(requestMock, times(3)).send()
    }

    @Test
    fun `Assert that web3j attempts all rpcs and then fails`() {

        val web3jServiceMock1: Web3j = mock()
        val web3jServiceMock2: Web3j = mock()
        val web3jRequestHandler = Web3jRequestHandler(500, 1L, 2,
                listOf("http://dummy:1", "http://dummy:2"), listOf(web3jServiceMock1, web3jServiceMock2))

        val requestMock: Request<*, Response<*>> = mock {
            on { send() } doAnswer {
                mock<EthBlockNumber> {
                    on { hasError() } doReturn true
                    on { error } doReturn Response.Error(300, "oh no")
                }
            }
        }

        val calledWeb3js = mutableSetOf<Web3j>()
        val exception = assertThrows<ProgrammerMistake> {
            runBlocking {
                web3jRequestHandler.sendWeb3jRequestWithRetry {
                    calledWeb3js.add(it)
                    requestMock
                }
            }
        }

        assertThat(calledWeb3js).hasSize(2)
        verify(requestMock, times(4)).send()
        assertThat(exception.message).isEqualTo("Request has failed on all 2 RPCs. No more nodes to try. Giving up.")
    }
}
