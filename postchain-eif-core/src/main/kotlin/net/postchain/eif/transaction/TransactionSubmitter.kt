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
import net.postchain.base.withReadConnection
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
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.core.methods.response.EthSendTransaction
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
    private val transactionManagers: Map<String, TransactionManager>,
    private val gasProvider: ContractGasProvider,
    private val databaseOperations: TransactionSubmitterDatabaseOperations,
    private val storage: Storage,
    private val chainId: Long,
    private val networkId: Long,
    private val txPollInterval: Long,
    initQueue: Collection<EvmSubmitTransactionRequest>,
    initPendingTransactions: Map<String, EvmSubmitTransactionRequest>,
    initCompletedTransactions: Map<Long, EvmSubmitTransactionResult>,
    private val minWalletBalance: BigInteger,
    private val healthCheckInterval: Long,
    private val txTimeout: Long
) : Shutdownable {

    companion object : KLogging()

    private val txSubmitJob: Job
    private val txStatusPollJob: Job
    private val healthCheckJob: Job
    private val healthy = AtomicBoolean(true)
    private val queue = LinkedBlockingQueue<EvmSubmitTransactionRequest>()
    private val pendingTransactions = mutableMapOf<String, EvmSubmitTransactionRequest>()
    private val completedTransactions = ConcurrentHashMap<Long, EvmSubmitTransactionResult>()

    init {

        this.queue.addAll(initQueue)
        this.pendingTransactions.putAll(initPendingTransactions)
        this.completedTransactions.putAll(initCompletedTransactions)

        txSubmitJob = CoroutineScope(Dispatchers.IO).launch(CoroutineName("$networkId-transaction-submitter") + MDCContext()) {
            while (isActive) {
                try {
                    val txToSubmit = queue.take()
                    try {
                        submitTransaction(txToSubmit)
                    } catch (e: Exception) {
                        logger.error("Failed to submit EVM transaction: ${e.message}", e)
                        completedTransactions[txToSubmit.rowId] = EvmSubmitTransactionResult(RellTransactionStatus.QUEUED)
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
            web3jRequestHandler.sendWeb3jRequest { it.ethGetBalance(transactionManagers.values.first().fromAddress, DefaultBlockParameterName.LATEST) }
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
        val processedTransactions = mutableListOf<String>()
        pendingTransactions.forEach { (txHash, txRequest) ->
            try {
                isTimeout(txRequest)
            } catch (e: EvmTransactionTimeoutException){
                withWriteConnection(storage, chainId) {
                    databaseOperations.failTransaction(it, txRequest.rowId)
                    databaseOperations.recordTransactionFailure(
                            it,
                            txRequest.rowId,
                            null,
                            e.message ?: "Timeout",
                            e.stackTraceToString()
                    )
                    true
                }
                processedTransactions.add(txHash)
                completedTransactions[txRequest.rowId] = EvmSubmitTransactionResult(RellTransactionStatus.QUEUED)
                return
            }
            val txReceiptOpt = try {
                web3jRequestHandler.sendWeb3jRequest { it.ethGetTransactionReceipt(txHash) }
            } catch (e: Exception) {
                val errorMessage = "Failed to poll for receipt for request id ${txRequest.rowId}"
                logger.error(e) { errorMessage }
                withWriteConnection(storage, chainId) {
                    databaseOperations.recordTransactionFailure(
                        it,
                        txRequest.rowId,
                        null,
                        errorMessage,
                        e.stackTraceToString()
                    )
                    true
                }
                null
            }

            txReceiptOpt?.transactionReceipt?.ifPresent { txReceipt ->

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
                processedTransactions.add(txHash)
            }
        }

        processedTransactions.forEach(pendingTransactions::remove)
    }

    internal fun submitTransaction(transactionRequest: EvmSubmitTransactionRequest) {
        try {
            sendTransaction(transactionRequest)
        } catch (e: Exception) {

            val errorMessage = e.message ?: "Unknown error"
            logger.error(e) { errorMessage }

            withWriteConnection(storage, chainId) {
                databaseOperations.failTransaction(it, transactionRequest.rowId)
                databaseOperations.recordTransactionFailure(it, transactionRequest.rowId, null, errorMessage, e.stackTraceToString())
                true
            }
            throw e
        }
    }

    private fun sendTransaction(transactionRequest: EvmSubmitTransactionRequest) {
        isTimeout(transactionRequest)
        val fromAddress = transactionManagers.values.first().fromAddress
        val walletBalance = try {
            web3jRequestHandler.sendWeb3jRequest { it.ethGetBalance(fromAddress, DefaultBlockParameterName.LATEST) }
        } catch (e: Exception) {
            val errorMessage = "Failed to get balance for request id ${transactionRequest.rowId}: ${e.message}"
            logger.error(e) { errorMessage }
            throw ProgrammerMistake(errorMessage, e)
        }

        val function = Function(
                transactionRequest.functionName,
                transactionRequest.parameterValues.mapIndexed { index, value -> GtvToTypeMapper.map(value, transactionRequest.parameterTypes[index]) },
                emptyList<TypeReference<*>>()
        )
        val functionData = FunctionEncoder.encode(function)
        val gasPrice = gasProvider.getGasPrice(functionData)
        val gasLimit = gasProvider.getGasLimit(functionData)

        withWriteConnection(storage, chainId) {
            databaseOperations.recordTransactionGas(it, transactionRequest.rowId, gasPrice, gasLimit)
            true
        }

        val estimatedGasUsage =
            try {
                getEstimatedGasUsage(gasPrice, gasLimit, transactionRequest.contractAddress, functionData, fromAddress)
            } catch (e: Exception) {
                val errorMessage = "Failed to get estimated gas usage for request id ${transactionRequest.rowId}: ${e.message}"
                logger.error(e) { errorMessage }
                throw ProgrammerMistake(errorMessage, e)
            }
        if (estimatedGasUsage > gasLimit) {
            throw UserMistake("Estimated gas usage $estimatedGasUsage for tx exceeds limit of $gasLimit")
        }

        if (walletBalance.balance < gasPrice * gasLimit) {
            throw UserMistake("Insufficient wallet balance")
        }

        for ((rpcUrl, transactionManager) in transactionManagers) {
            try {

                val response = transactionManager.sendTransaction(
                    gasPrice,
                    gasLimit,
                    transactionRequest.contractAddress,
                    functionData,
                    BigInteger.ZERO
                )

                if (response.hasError()) {
                    // abort on any of the codes? https://www.quicknode.com/docs/ethereum/error-references
                    val errorMessage =
                        "Web3j request failed with error code: ${response.error.code} and message: ${response.error.message}"
                    logger.error(errorMessage)
                    throw ProgrammerMistake(errorMessage)
                }

                withWriteConnection(storage, chainId) {
                    databaseOperations.pendTransaction(
                        it,
                        transactionRequest.rowId,
                        response.transactionHash
                    )
                    true
                }
                pendingTransactions[response.transactionHash] = transactionRequest
                return
            } catch (e: Exception) {

                val error = "Failed to send transaction ${transactionRequest.rowId}: ${e.message}"
                logger.error { error }

                withWriteConnection(storage, chainId) {
                    databaseOperations.recordTransactionFailure(it, transactionRequest.rowId, rpcUrl, error, e.stackTraceToString())
                    true
                }

                if (e is UserMistake) {
                    break
                }
            }
        }

        val errorMessage = "Failed to send transaction to all ${transactionManagers.size} nodes"
        throw ProgrammerMistake(errorMessage)
    }

    private fun getEstimatedGasUsage(
        gasPrice: BigInteger,
        gasLimit: BigInteger,
        contractAddress: String,
        functionData: String,
        fromAddress: String
    ): BigInteger {
        val transaction = Transaction(fromAddress, BigInteger.ZERO, gasPrice, gasLimit, "0x$contractAddress", BigInteger.ZERO, functionData)
        return web3jRequestHandler.sendWeb3jRequest {
            it.ethEstimateGas(transaction)
        }.amountUsed
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

    private fun isTimeout(transactionRequest: EvmSubmitTransactionRequest) {
        if (System.currentTimeMillis() - transactionRequest.timestamp > txTimeout) {
            logger.warn{"Transaction ${transactionRequest.rowId} with status ${transactionRequest.status} and timestamp: ${transactionRequest.timestamp} is timeout."}
            throw EvmTransactionTimeoutException(transactionRequest)
        }
    }

    override fun shutdown() {
        txSubmitJob.cancel()
        txStatusPollJob.cancel()
        healthCheckJob.cancel()
        web3jRequestHandler.close()
    }

    fun getTransactionErrors(rowId: Long): List<EvmSubmitTransactionError>{

        return withReadConnection(storage, chainId) {
            databaseOperations.getTransactionErrors(it, rowId)
        }
    }
}

class EvmTransactionTimeoutException(evmTransaction: EvmSubmitTransactionRequest) : RuntimeException("${evmTransaction.status} transaction is timeout.")