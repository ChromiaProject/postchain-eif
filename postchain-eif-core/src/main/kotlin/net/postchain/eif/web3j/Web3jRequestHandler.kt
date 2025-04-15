package net.postchain.eif.web3j

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import mu.KLogging
import net.postchain.eif.metrics.RpcUsageMetrics
import net.postchain.eif.transaction.TransactionSubmitterException
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.Response
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.core.methods.response.EthBlock
import org.web3j.protocol.core.methods.response.EthBlockNumber
import org.web3j.protocol.core.methods.response.EthEstimateGas
import org.web3j.protocol.core.methods.response.EthGetBalance
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt
import org.web3j.protocol.core.methods.response.EthMaxPriorityFeePerGas
import org.web3j.protocol.core.methods.response.EthTransaction
import org.web3j.protocol.exceptions.ClientConnectionException
import java.io.Closeable
import kotlin.coroutines.coroutineContext
import kotlin.math.min

open class Web3jRequestHandler(
        private val baseTimeout: Long,
        private val maxTimeout: Long,
        private val maxTryErrors: Long, // Attempts per RPC
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
        val rpcErrors = mutableListOf<String>()
        for (request in requests) {

            try {
                val response = request.send()

                if (response.hasError()) {
                    val errorMessage = "RPC/EVM error code: ${response.error.code} and message: ${response.error.message}"
                    rpcErrors.add(errorMessage)
                    throw RuntimeException(errorMessage)
                }

                return response
            } catch (e: Exception) {
                logger.error("Web3j request failed: ${e.message}", e)
            }
        }

        // Include RCP errors in chain message - should be safe
        val tse = TransactionSubmitterException(
                "Failed to send web3j request to all ${web3jServices.size} RPC nodes. RPC/EVM errors: ${rpcErrors.joinToString()}")
        logger.error(tse) { tse.message }
        throw tse
    }

    /**
     * Sends request to first RPC. Will retry on failure and eventually move on to the next
     * RPC in list. If calls to all RPCs fail it will throw an exception.
     */
    suspend fun <T : Response<*>> sendWeb3jRequestWithRetry(
            requestFactory: (Web3j) -> Request<*, T>
    ): T {
        val requests = web3jServices.map(requestFactory)
        val retryTimeouts = Array(requests.size) { baseTimeout }
        var tryErrors = 0L
        var index = 0
        val rpcErrors = mutableMapOf<Int, String>()
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
                rpcErrors[index] = response?.error?.message ?: "Unknown"
                tryErrors++
                if (tryErrors >= maxTryErrors) {
                    logger.error { "Web3j request failed after $tryErrors tries on ${requests[index].method}/${urls[index]}" }
                    index = (index + 1) % requests.size
                    if (index == 0) {
                        val rpcErrorSummary = rpcErrors.map { "${it.key}=${it.value}" }.joinToString()
                        // Include RCP errors in chain message - should be safe
                        val tse = TransactionSubmitterException(
                                "Request has failed on all ${requests.size} RPCs. No more nodes to try. Giving up. Last error for each RPC: $rpcErrorSummary")
                        logger.error(tse) { tse.message }
                        throw tse
                    }
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

    open fun ethBlockNumber(): EthBlockNumber {
        return sendWeb3jRequest { it.ethBlockNumber() }
    }

    open fun ethEstimateGas(transaction: Transaction): EthEstimateGas {
        return sendWeb3jRequest { it.ethEstimateGas(transaction) }
    }

    open fun ethGetTransactionReceipt(transactionHash: String): EthGetTransactionReceipt {
        return sendWeb3jRequest { it.ethGetTransactionReceipt(transactionHash) }
    }

    open fun ethMaxPriorityFeePerGas(): EthMaxPriorityFeePerGas {
        return sendWeb3jRequest { it.ethMaxPriorityFeePerGas() }
    }

    open fun ethGetBalance(address: String, defaultBlockParameter: DefaultBlockParameter): EthGetBalance {
        return sendWeb3jRequest { it.ethGetBalance(address, defaultBlockParameter) }
    }

    open fun ethGetBlockByNumber(defaultBlockParameter: DefaultBlockParameter, returnFullTransactionObjects: Boolean): EthBlock {
        return sendWeb3jRequest { it.ethGetBlockByNumber(defaultBlockParameter, returnFullTransactionObjects) }
    }
    
    open fun ethGetTransactionByHash(transactionHash: String): EthTransaction {
        return sendWeb3jRequest { it.ethGetTransactionByHash(transactionHash) }
    }
}
