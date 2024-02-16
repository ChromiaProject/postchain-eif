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
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class TransactionSubmitter(
        private val web3jRequestHandler: Web3jRequestHandler,
        private val transactionManager: TransactionManager,
        private val gasProvider: ContractGasProvider,
        private val databaseOperations: TransactionSubmitterDatabaseOperations,
        private val storage: Storage,
        private val chainId: Long,
        private val networkId: Long
) : Shutdownable {

    companion object : KLogging()

    private val txSubmitJob: Job
    private val txStatusPollJob: Job

    private val queue = LinkedBlockingQueue<EvmSubmitTransactionRequest>()
    private val pendingTransactions = mutableMapOf<String, EvmSubmitTransactionRequest>()
    private val completedTransactions = ConcurrentHashMap<Long, TRANSACTION_STATUS>()

    init {
        txSubmitJob = CoroutineScope(Dispatchers.IO).launch(CoroutineName("$networkId-transaction-submitter") + MDCContext()) {
            while (isActive) {
                try {
                    val txToSubmit = queue.poll()
                    try {
                        sendTransaction(txToSubmit)
                    } catch (e: Exception) {
                        logger.error("Failed to submit EVM transaction: ${e.message}", e)
                        completedTransactions[txToSubmit.rowId] = TRANSACTION_STATUS.FAILURE
                    }
                } catch (e: CancellationException) {
                    break
                }
            }
        }
        txStatusPollJob = CoroutineScope(Dispatchers.IO).launch(CoroutineName("$networkId-transaction-submitter") + MDCContext()) {
            while (isActive) {
                try {
                    pollPendingTransactions()
                    // TODO make this configurable
                    delay(30.toDuration(DurationUnit.SECONDS))
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    logger.error("Unable to poll status on pending EVM transactions: ${e.message}", e)
                }
            }
        }
    }

    private fun pollPendingTransactions() {
        pendingTransactions.forEach { txHash, txRequest ->
            val txReceipt = web3jRequestHandler.sendWeb3jRequest { it.ethGetTransactionReceipt(txHash) }
        }
    }

    fun sendTransaction(transactionRequest: EvmSubmitTransactionRequest): EthSendTransaction {
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
                        databaseOperations.recordTransaction(it, transactionRequest, gasPrice, gasLimit, response.transactionHash, networkId)
                        true
                    }
                    pendingTransactions[response.transactionHash] = transactionRequest
                }
            }
            throw ProgrammerMistake("Failed to send web3j request")
        } catch (e: Exception) {
            withWriteConnection(storage, chainId) {
                databaseOperations.recordFailedTransaction(it, transactionRequest, gasPrice, gasLimit, e.message
                        ?: "Unknown error", networkId)
                true
            }
            throw e
        }
    }

    fun enqueue(it: EvmSubmitTransactionRequest) {
        queue.offer(it)
    }

    fun fetchCompletedTransactions() : Map<Long, TRANSACTION_STATUS> {
        return completedTransactions;
    }

    fun removeCompletedTransaction(rowId: Long) {
        completedTransactions.remove(rowId)
    }

    override fun shutdown() {
        txSubmitJob.cancel()
        txStatusPollJob.cancel()
        web3jRequestHandler.close()
    }
}