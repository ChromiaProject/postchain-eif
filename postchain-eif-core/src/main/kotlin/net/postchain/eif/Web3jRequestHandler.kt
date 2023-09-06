package net.postchain.eif

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import mu.KLogging
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.Response
import org.web3j.protocol.exceptions.ClientConnectionException
import kotlin.coroutines.coroutineContext
import kotlin.math.min

class Web3jRequestHandler(
        private val baseTimeout: Long,
        private val maxTimeout: Long
) {
    companion object : KLogging() {
        const val DELAY_POWER_BASE = 1.2
    }

    suspend fun <T : Response<*>> sendWeb3jRequestWithRetry(
            request: Request<*, T>
    ): T {
        var retryTimeout = baseTimeout
        while (true) {
            val response = try {
                val response = request.send()
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
                coroutineContext.ensureActive()

                delay(retryTimeout)
                retryTimeout = min((retryTimeout.toDouble() * DELAY_POWER_BASE).toLong(), maxTimeout)
            } else {
                return response
            }
        }
    }
}
