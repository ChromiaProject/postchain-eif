package net.postchain.eif.transaction

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext
import mu.KLogging
import net.postchain.base.withWriteConnection
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.exception.UserMistake
import net.postchain.core.Shutdownable
import net.postchain.core.Storage
import net.postchain.eif.GtvToTypeMapper
import net.postchain.eif.Web3jRequestHandler
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Function
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.response.EthSendTransaction
import org.web3j.protocol.exceptions.ClientConnectionException
import org.web3j.tx.TransactionManager
import org.web3j.tx.gas.ContractGasProvider
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

class TransactionSubmitter(
        private val web3jRequestHandler: Web3jRequestHandler,
        private val transactionManager: TransactionManager,
        private val gasProvider: ContractGasProvider,
        private val databaseOperations: TransactionSubmitterDatabaseOperations,
        private val storage: Storage,
        private val chainId: Long,
        private val networkId: Long,
        private val txPollInterval: Long,
        private val queue: LinkedBlockingQueue<EvmSubmitTransactionRequest>,
        private val pendingTransactions: MutableMap<String, EvmSubmitTransactionRequest>,
        private val completedTransactions: ConcurrentHashMap<Long, EvmSubmitTransactionResult>,
        private val minWalletBalance: BigInteger,
        private val healthCheckInterval: Long
) : Shutdownable {

    companion object : KLogging()

    private val txSubmitJob: Job
    private val txStatusPollJob: Job
    private val healthCheckJob: Job
    private val healthy = AtomicBoolean(true)

    init {
        txSubmitJob = CoroutineScope(Dispatchers.IO).launch(CoroutineName("$networkId-transaction-submitter") + MDCContext()) {
            while (isActive) {
                try {
                    val txToSubmit = queue.take()
                    try {
                        sendTransaction(txToSubmit)
                    } catch (e: Exception) {
                        logger.error("Failed to submit EVM transaction: ${e.message}", e)
                        completedTransactions[txToSubmit.rowId] = EvmSubmitTransactionResult(RellTransactionStatus.FAILURE)
                    }
                } catch (e: CancellationException) {
                    break
                }
            }
        }
        txStatusPollJob = CoroutineScope(Dispatchers.IO).launch(CoroutineName("$networkId-transaction-status-poller") + MDCContext()) {
            while (isActive) {
                try {
                    pollPendingTransactions()

                    delay(txPollInterval)
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    logger.error("Unable to poll status on pending EVM transactions: ${e.message}", e)
                }
            }
        }
        healthCheckJob = CoroutineScope(Dispatchers.IO).launch(CoroutineName("$networkId-transaction-status-poller") + MDCContext()) {
            while (isActive) {
                try {
                    healthCheck()

                    delay(healthCheckInterval)
                } catch (e: CancellationException) {
                    break
                }
            }
        }
    }

    fun isHealthy() = healthy.get()

    private fun healthCheck() {
        val walletBalance = try {
            // This will implicitly test our RPC connections
            web3jRequestHandler.sendWeb3jRequest { it.ethGetBalance(transactionManager.fromAddress, DefaultBlockParameterName.LATEST) }
        } catch (e: Exception) {
            val previouslyHealthy = healthy.getAndSet(false)
            if (previouslyHealthy) {
                logger.warn("Unable to check wallet balance. Marking tx submitter for network id $networkId as unhealthy")
            }
            return
        }

        if (walletBalance.balance >= minWalletBalance) {
            val previouslyHealthy = healthy.getAndSet(true)
            if (!previouslyHealthy) {
                logger.info("Marking tx submitter for network id $networkId as healthy")
            }
        } else {
            val previouslyHealthy = healthy.getAndSet(false)
            if (previouslyHealthy) {
                logger.warn("Wallet balance is below minimum balance. Marking tx submitter for network id $networkId as unhealthy")
            }
        }
    }

    private fun pollPendingTransactions() {
        val successfulTxs = mutableListOf<String>()
        pendingTransactions.forEach { (txHash, txRequest) ->
            val txReceiptOpt = web3jRequestHandler.sendWeb3jRequest { it.ethGetTransactionReceipt(txHash) }
            txReceiptOpt.transactionReceipt.ifPresent { txReceipt ->

                logger.info { "Got transaction receipt: $txReceipt" }
                val effectiveGasPrice = Numeric.decodeQuantity(txReceipt.effectiveGasPrice)

                withWriteConnection(storage, chainId) {
                    databaseOperations.succeedTransaction(it, txRequest.rowId, effectiveGasPrice, txReceipt.gasUsed, txReceipt.blockHash)
                    true
                }
                completedTransactions[txRequest.rowId] = EvmSubmitTransactionResult(
                        RellTransactionStatus.SUCCESS,
                        txReceipt.blockHash,
                        effectiveGasPrice.longValueExact(),
                        txReceipt.gasUsed.longValueExact()
                )
                successfulTxs.add(txHash)
            }
        }

        successfulTxs.forEach(pendingTransactions::remove)
    }

    private fun sendTransaction(transactionRequest: EvmSubmitTransactionRequest): EthSendTransaction {
        val walletBalance = web3jRequestHandler.sendWeb3jRequest { it.ethGetBalance(transactionManager.fromAddress, DefaultBlockParameterName.LATEST) }

        val function = Function(
                transactionRequest.functionName,
                transactionRequest.parameterValues.mapIndexed { index, value -> GtvToTypeMapper.map(value, transactionRequest.parameterTypes[index]) },
                emptyList<TypeReference<*>>()
        )
        val functionData = FunctionEncoder.encode(function)
        val gasPrice = gasProvider.getGasPrice(functionData)
        val gasLimit = gasProvider.getGasLimit(functionData)

        // TODO validate that this is the correct way to check balance and write a test
        try {
            if (walletBalance.balance < gasPrice * gasLimit) {
                throw UserMistake("Insufficient wallet balance")
            }

            val response = try {
                transactionManager.sendTransaction(gasPrice, gasLimit, transactionRequest.contractAddress, functionData, BigInteger.valueOf(0))
            } catch (e: ClientConnectionException) {
                logger.error("Web3j request failed: ${e.message}")
                // TODO investigate - fine to move on to next request?
                null
            } catch (e: Exception) {
                logger.error("Web3j request failed unexpectedly", e)
                // TODO investigate - fine to move on to next request?
                null
            }

            if (response != null) {
                if (response.hasError()) {
                    // TODO investigate
                    val errorMessage =
                            "Web3j request failed with error code: ${response.error.code} and message: ${response.error.message}"
                    logger.error(errorMessage)
                    throw ProgrammerMistake(errorMessage)
                } else {
                    withWriteConnection(storage, chainId) {
                        databaseOperations.pendTransaction(it, transactionRequest.rowId, gasPrice, gasLimit, response.transactionHash)
                        true
                    }
                    pendingTransactions[response.transactionHash] = transactionRequest
                    return response
                }
            }
            throw ProgrammerMistake("Failed to send web3j request")
        } catch (e: Exception) {
            withWriteConnection(storage, chainId) {
                databaseOperations.failTransaction(it, transactionRequest.rowId, gasPrice, gasLimit, e.message
                        ?: "Unknown error")
                true
            }
            throw e
        }
    }

    fun enqueue(evmSubmitTransactionRequest: EvmSubmitTransactionRequest) {
        withWriteConnection(storage, chainId) {
            databaseOperations.queueTransaction(it, evmSubmitTransactionRequest, networkId)
            true
        }
        queue.offer(evmSubmitTransactionRequest)
    }

    fun fetchCompletedTransactions(): Map<Long, EvmSubmitTransactionResult> = completedTransactions

    fun deactivateTransaction(rowId: Long) {
        withWriteConnection(storage, chainId) {
            databaseOperations.deactivateTransaction(it, rowId)
            true
        }
        completedTransactions.remove(rowId)
    }

    override fun shutdown() {
        txSubmitJob.cancel()
        txStatusPollJob.cancel()
        healthCheckJob.cancel()
        web3jRequestHandler.close()
    }
}