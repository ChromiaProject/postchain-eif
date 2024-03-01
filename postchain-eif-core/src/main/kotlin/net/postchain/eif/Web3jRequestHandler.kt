package net.postchain.eif

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import mu.KLogging
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.eif.metrics.RpcUsageMetrics
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.Response
import org.web3j.protocol.exceptions.ClientConnectionException
import java.io.Closeable
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.random.Random

open class Web3jRequestHandler(
    private val baseTimeout: Long,
    private val maxTimeout: Long,
    private val maxTryErrors: Long,
    private val urls: List<String>,
    private val web3jServices: List<Web3j>,
    private val metrics: RpcUsageMetrics? = null
) : Closeable {
    companion object : KLogging() {
        const val DELAY_POWER_BASE = 1.2
    }

    open fun <T : Response<*>> sendWeb3jRequest(
        requestFactory: (Web3j) -> Request<*, T>
    ): T {
        val requests = web3jServices.map(requestFactory)
        for (request in requests) {

            try {
                val response = request.send()

                if (response.hasError()) {
                    val errorMessage = "Web3J error code: ${response.error.code} and message: ${response.error.message}"
                    throw ProgrammerMistake(errorMessage)
                }

                return response
            } catch (e: Exception) {
                logger.error("Web3j request failed: ${e.message}", e)
            }
        }

        throw ProgrammerMistake("Failed to send web3j request to all ${web3jServices.size} nodes")
    }

    suspend fun <T : Response<*>> sendWeb3jRequestWithRetry(
        requestFactory: (Web3j) -> Request<*, T>
    ): T {
        val requests = web3jServices.map(requestFactory)
        val retryTimeouts = Array(requests.size) { baseTimeout }
        var tryErrors = 0L
        var index = if (requests.size > 1) Random.nextInt(0, requests.size - 1) else 0
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
                retryTimeouts[currentIndex] =
                    min((retryTimeouts[currentIndex].toDouble() * DELAY_POWER_BASE).toLong(), maxTimeout)
            } else {
                return response
            }
        }
    }

    override fun close() {
        web3jServices.forEach { it.shutdown() }
    }
}
