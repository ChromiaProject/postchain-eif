package net.postchain.eif

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.Response

class Web3jRequestHandlerTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Assert that web3j requests are sent with exponential back off`() = runTest(StandardTestDispatcher()) {
        val web3jRequestHandler = Web3jRequestHandler(500, 60_000L, 10, mutableListOf())

        val requestMock: Request<*, Response<*>> = mock {
            on { send() } doThrow RuntimeException("You want me to fail")
        }
        backgroundScope.launch {
            web3jRequestHandler.sendWeb3jRequestWithRetry(mutableListOf(requestMock))
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
}
