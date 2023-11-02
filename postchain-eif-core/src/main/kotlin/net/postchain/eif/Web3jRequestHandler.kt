package net.postchain.eif

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import mu.KLogging
import net.postchain.eif.metrics.RpcUsageMetrics
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.Response
import org.web3j.protocol.exceptions.ClientConnectionException
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.random.Random

class Web3jRequestHandler(
        private val baseTimeout: Long,
        private val maxTimeout: Long,
        private val maxTryErrors: Long,
        private val urls: List<String>,
        private val metrics: RpcUsageMetrics? = null
) {
    companion object : KLogging() {
        const val DELAY_POWER_BASE = 1.2
    }

    suspend fun <T : Response<*>> sendWeb3jRequestWithRetry(
            requests: List<Request<*, T>>
    ): T {
        val retryTimeouts = Array(requests.size) { baseTimeout }
        var tryErrors = 0L
        var index = if (requests.size > 1) Random.nextInt(0, requests.size-1) else 0
        while (true) {
            val currentIndex = index
            val response = try {
                val response = requests[index].send()
                metrics?.createUsageCounter(index)?.increment()
                if (response.hasError()) {
                    logger.error("Web3j request failed with error code: ${response.error.code} and message: ${response.error.message}")
                }
                response
            } catch (e: ClientConnectionException) {
                logger.error("Web3j request failed: ${e.message}")
                null
            } catch (e: Exception) {
                logger.error("Web3j request failed unexpectedly", e)
                null
            }

            if (response == null || response.hasError()) {
                tryErrors++
                if (tryErrors >= maxTryErrors && requests.size > 1) {
                    logger.error { "Web3j request failed after $tryErrors tries on ${requests[index].method}/${urls[index]}" }
                    index = (index + 1) % requests.size
                    tryErrors = 0L
                    logger.info { "Switching to another rpc endpoint at ${urls[index]} in ${retryTimeouts[index]} ms" }
                }
                coroutineContext.ensureActive()
                delay(retryTimeouts[index])
                retryTimeouts[currentIndex] = min((retryTimeouts[currentIndex].toDouble() * DELAY_POWER_BASE).toLong(), maxTimeout)
            } else {
                return response
            }
        }
    }
}
