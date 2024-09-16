package net.postchain.eif

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.postchain.config.app.AppConfig
import net.postchain.config.app.ConfigTest
import net.postchain.eif.config.EvmConfig
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.response.EthGetBalance
import org.web3j.protocol.http.HttpService
import java.math.BigInteger
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class EifConfigTest(
    val clock: Clock = Clock.systemDefaultZone(),
) : ConfigTest {

    override fun test(appConfig: AppConfig): Int {
        var result = 0
        val evmChains =
            appConfig.getEnvOrListProperty("${EvmConfig.EIF_CONFIG_ENV_PREFIX}EVM_CHAINS", "evm.chains", emptyList())
        if (evmChains.isEmpty()) {
            println("Error: EVM chains configuration is missing. Please specify the EVM chains in the configuration.")
        } else {
            for (evmChain in evmChains) {
                println("Checking $evmChain configuration ...")
                val evmConfig = EvmConfig.fromAppConfig(evmChain, appConfig)
                if (evmConfig.urls.isEmpty()) {
                    println("Error: No URLs specified for the $evmChain node. Please provide a valid URL for the $evmChain node in the configuration.")
                    result = 1
                } else {
                    for (url in evmConfig.urls) {
                        println("Checking node: $url")
                        var nodeValid = true
                        val web3j = Web3j.build(HttpService(url))
                        try {
                            val request = web3j.web3ClientVersion().send()
                            if (request.hasError()) {
                                println("Error: Unable to reach node. Please verify that the URL is correct and that the node is accessible.")
                                result = 1
                            } else {
                                if (!hasFullHistoricalState(web3j)) {
                                    println("Error: The node does not provide access to full historical state. This may indicate that the node is pruned. Please ensure the node has the full transaction history.")
                                    nodeValid = false
                                    result = 1
                                }
                                val blockSyncDelay = getBlockSyncDelay(web3j)
                                if (blockSyncDelay >= 10) {
                                    println("Error: The node is out of sync. The latest block timestamp is $blockSyncDelay minutes behind the local time, exceeding the allowed 10-minute threshold. Please ensure the node is fully synchronized before proceeding.")
                                    nodeValid = false
                                    result = 1
                                }
                                val (check, latency) = checkRateLimit(web3j)
                                if (check.not()) {
                                    println("Error: The node does not meet the required rate limit. The node is unable to handle the minimum of 100 requests per second. Please ensure the node supports a higher rate limit to avoid performance issues.")
                                    nodeValid = false
                                    result = 1
                                }
                                if (latency > 500) {
                                    if (latency > 1000) {
                                        println("Error: The node latency is very high at $latency ms. Consider using a node with lower latency.")
                                        nodeValid = false
                                        result = 1
                                    } else {
                                        println("Warning: The node latency is high at $latency ms. This could affect performance. Consider using a node with lower latency for better response times.")
                                    }
                                }
                                if (nodeValid)
                                    println("Success: The node has successfully passed all integrity checks!")
                            }
                        } catch (e: Exception) {
                            println("Error: Failed to connect. The node did not respond or returned an error. Please check the URL and ensure that the node is online and accessible.")
                            result = 1
                        }
                    }
                }
            }
        }
        return result
    }

    private fun hasFullHistoricalState(web3j: Web3j): Boolean {
        try {
            val blockParam = DefaultBlockParameter.valueOf(BigInteger.valueOf(0))
            val balanceResponse: EthGetBalance = web3j.ethGetBalance("0x0000000000000000000000000000000000000000", blockParam).send()
            return balanceResponse.balance != null
        } catch (e: Exception) {
            return false
        }
    }

    private fun getBlockSyncDelay(web3j: Web3j): Long {
        val timestamp =
            web3j.ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false).send().result.timestamp.toLong()
        return (clock.millis() / 1000 - timestamp) / 60
    }

    private fun checkRateLimit(web3j: Web3j) = runBlocking { performRateLimitCheck(web3j) }

    private suspend fun performRateLimitCheck(web3j: Web3j): Pair<Boolean, Int> {
        val requestsPerSecond = 100
        val durationInSeconds = 3
        val successCount = AtomicInteger(0)
        val failureCount = AtomicInteger(0)
        val latencySum = AtomicInteger(0)

        val blockHeight = web3j.ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false).send().result.number
        for (i in 1..durationInSeconds) {
            coroutineScope {
                val jobs = List(requestsPerSecond) {
                    launch {
                        try {
                            val blockParameter = DefaultBlockParameter.valueOf(Random.nextInt(0, blockHeight.toInt() + 1).toBigInteger())
                            val startTime = System.currentTimeMillis()
                            val response = web3j.ethGetBlockByNumber(blockParameter, true).send()
                            val endTime = System.currentTimeMillis()

                            if (response.hasError()) {
                                failureCount.incrementAndGet()
                            } else {
                                successCount.incrementAndGet()
                                val latency = (endTime - startTime).toInt()
                                latencySum.addAndGet(latency)
                            }
                        } catch (e: Exception) {
                            failureCount.incrementAndGet()
                        }
                    }
                }
                jobs.forEach { it.join() }
            }
            delay(1000)
        }
        return (failureCount.get() == 0) to (latencySum.get() / successCount.get())
    }

}
