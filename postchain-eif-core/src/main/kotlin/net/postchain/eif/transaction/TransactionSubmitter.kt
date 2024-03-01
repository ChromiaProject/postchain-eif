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
import net.postchain.base.withReadWriteConnection
import net.postchain.base.withWriteConnection
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.exception.UserMistake
import net.postchain.core.Shutdownable
import net.postchain.core.Storage
import net.postchain.eif.GtvToTypeMapper
import net.postchain.eif.Web3jRequestHandler
import net.postchain.gtv.Gtv
import okhttp3.internal.toImmutableMap
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Function
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction
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
    initPendingTransactions: Map<String, EvmPendingDbTx>,
    private val minWalletBalance: BigInteger,
    private val healthCheckInterval: Long,
    private val txTimeout: Long,
    private val nodeTxVerificationTimeout: Long,
    private val nodeTxVerificationEvmBlocks: Long,
    private val dbRetentionTime: Long,
) : Shutdownable {

    companion object : KLogging() {
        fun encodeFunction(functionName: String, parameterTypes: List<String>, parameterValues: List<Gtv>): String {

            val function = Function(
                functionName,
                parameterValues.mapIndexed { index, value -> GtvToTypeMapper.map(value, parameterTypes[index]) },
                emptyList<TypeReference<*>>()
            )

            return FunctionEncoder.encode(function)
        }
    }

    private val txSubmitJob: Job
    private val txStatusPollJob: Job
    private val healthCheckJob: Job
    private val healthy = AtomicBoolean(true)
    private val queue = LinkedBlockingQueue<EvmSubmitTransactionRequest>()
    private val pendingTransactions = ConcurrentHashMap<String, EvmPendingDbTx>()
    private val submitTxUpdates = ConcurrentHashMap<Long, EvmSubmitTransactionResult>()

    init {

        this.pendingTransactions.putAll(initPendingTransactions)

        // Add transactions to queue and recover states lost on node restart
        for (txSubmit in initQueue) {

            val txPending = initPendingTransactions.values.firstOrNull { txPending -> txPending.rowId == txSubmit.rowId }

            if (txPending == null) {

                // Transaction taken but not yet submitted
                queue.offer(txSubmit)
            } else {

                // Transaction submitted - update might not be persisted to BC yet however
                if (!withReadConnection(storage, chainId) {
                        databaseOperations.isSubmitTxBCPersisted(it, txSubmit.rowId)
                    }) {
                    submitTxUpdates[txSubmit.rowId] = EvmSubmitTransactionResult(RellTransactionStatus.PENDING, txPending.txHash)
                }
            }
        }

        txSubmitJob = CoroutineScope(Dispatchers.IO).launch(CoroutineName("$networkId-transaction-submitter") + MDCContext()) {
            while (isActive) {
                try {
                    val txToSubmit = queue.take()
                    try {
                        submitTransaction(txToSubmit)
                    } catch (e: Exception) {
                        logger.error("Failed to submit EVM transaction: ${e.message}", e)
                        submitTxUpdates[txToSubmit.rowId] = EvmSubmitTransactionResult(RellTransactionStatus.QUEUED)
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
        healthCheckJob = CoroutineScope(Dispatchers.IO).launch(CoroutineName("$networkId-health-check") + MDCContext()) {
            while (isActive && healthCheckInterval >= 0) {
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

        pendingTransactions.forEach { (txHash, txPending) ->
            try {
                pollPendingTransaction(txHash, txPending)
            } catch (e: Exception) {
                logger.error(e) { "Failed to process pending transaction ${txPending.rowId}" }
            }
        }
    }

    internal fun pollPendingTransaction(txHash: String, txPending: EvmPendingDbTx) {

        if (txPending.status.isCompleted()) {
            return
        }

        logger.info { "Verifying pending transaction ${txPending.rowId}" }

        // Verify transaction receipt
        val txReceiptResult = try {
            web3jRequestHandler.sendWeb3jRequest { it.ethGetTransactionReceipt(txHash) }
        } catch (e: Exception) {
            val errorMessage = "Failed to poll for receipt for request id ${txPending.rowId}"
            logger.error(e) { errorMessage }
            withWriteConnection(storage, chainId) {
                databaseOperations.recordTransactionError(
                    it,
                    txPending.rowId,
                    null,
                    errorMessage,
                    e.stackTraceToString()
                )
                true
            }
            null
        }

        if (txReceiptResult != null && txReceiptResult.transactionReceipt.isPresent) {

            val txReceipt = txReceiptResult.transactionReceipt.get()
            val effectiveGasPrice = Numeric.decodeQuantity(txReceipt.effectiveGasPrice)

            if (txPending.blockNumber == null) {

                // First time we get receipt - store height and verify it later

                txPending.blockNumber = txReceipt.blockNumber

                logger.info { "Transaction ${txPending.rowId} first receipt found at block number ${txPending.blockNumber}: $txReceipt" }

            } else if (txReceipt.blockNumber.minus(txPending.blockNumber!!).longValueExact() >= nodeTxVerificationEvmBlocks) {

                // Verify transaction structure
                val transactionByHashResponse = web3jRequestHandler.sendWeb3jRequest { it.ethGetTransactionByHash(txHash) }
                if (transactionByHashResponse.transaction.isPresent) {

                    val transaction = transactionByHashResponse.transaction.get()
                    val functionData = encodeFunction(txPending.functionName, txPending.parameterTypes, txPending.parameterValues)

                    if (
                        functionData != transaction.input ||
                        !transaction.to.contains(txPending.contractAddress)
                    ) {
                        txPending.status = PendingTxStatus.REVERTED
                        withWriteConnection(storage, chainId) {
                            databaseOperations.setPendingTransactionSuccess(it, txPending.rowId, PendingTxStatus.REVERTED)
                            databaseOperations.recordTransactionError(
                                it,
                                txPending.rowId,
                                null,
                                "Transaction does not match original"
                            )
                            true
                        }
                        return
                    }
                }

                txPending.status =  if (txReceipt.isStatusOK) PendingTxStatus.SUCCESS else PendingTxStatus.REVERTED
                txPending.blockNumber = txReceipt.blockNumber
                txPending.blockHash = txReceipt.blockHash
                txPending.effectiveGasPrice = effectiveGasPrice
                txPending.gasUsed = txReceipt.gasUsed

                // Store receipt
                withWriteConnection(storage, chainId) {

                    databaseOperations.setPendingTransactionReceipt(it, txPending.rowId, txReceipt.blockNumber,
                        txReceipt.isStatusOK, effectiveGasPrice, txReceipt.gasUsed, txReceipt.blockHash)

                    databaseOperations.setPendingTransactionSuccess(it, txPending.rowId, txPending.status)

                    if (txPending.status == PendingTxStatus.REVERTED) {
                        databaseOperations.recordTransactionError(
                            it,
                            txPending.rowId,
                            null,
                            "Transaction was reverted"
                        )
                    }
                    true
                }

                logger.info { "Pending transaction ${txPending.rowId} verified: ${txPending.status}" }
            }
        }

        if (!txPending.status.isCompleted()) {
            try {
                isTimeout(txPending.rowId, txPending.updated, nodeTxVerificationTimeout)
            } catch (e: EvmTransactionTimeoutException) {
                txPending.status = PendingTxStatus.REVERTED
                withWriteConnection(storage, chainId) {
                    databaseOperations.setPendingTransactionSuccess(it, txPending.rowId, txPending.status)
                    databaseOperations.recordTransactionError(
                        it,
                        txPending.rowId,
                        null,
                        e.message ?: "Timeout",
                        e.stackTraceToString()
                    )
                    true
                }
                return
            }
        }
    }

    internal fun submitTransaction(transactionRequest: EvmSubmitTransactionRequest) {
        try {
            sendTransaction(transactionRequest)
        } catch (e: Exception) {

            val errorMessage = e.message ?: "Unknown error"
            logger.error(e) { errorMessage }

            withWriteConnection(storage, chainId) {
                databaseOperations.recordTransactionError(it, transactionRequest.rowId, null, errorMessage, e.stackTraceToString())
                true
            }
            throw e
        }
    }

    private fun sendTransaction(txRequest: EvmSubmitTransactionRequest) {

        logger.info { "Submitting transaction ${txRequest.rowId}" }

        isTimeout(txRequest.rowId, txRequest.timestamp, txTimeout)
        val fromAddress = transactionManagers.values.first().fromAddress
        val walletBalance = try {
            web3jRequestHandler.sendWeb3jRequest { it.ethGetBalance(fromAddress, DefaultBlockParameterName.LATEST) }
        } catch (e: Exception) {
            val errorMessage = "Failed to get balance for request id ${txRequest.rowId}: ${e.message}"
            logger.error(e) { errorMessage }
            throw ProgrammerMistake(errorMessage, e)
        }

        val functionData = encodeFunction(txRequest.functionName, txRequest.parameterTypes, txRequest.parameterValues)
        val gasPrice = gasProvider.getGasPrice(functionData)
        val gasLimit = gasProvider.getGasLimit(functionData)

        withWriteConnection(storage, chainId) {
            databaseOperations.recordTransactionGas(it, txRequest.rowId, gasPrice, gasLimit)
            true
        }

        val estimatedGasUsage =
            try {
                getEstimatedGasUsage(gasPrice, gasLimit, txRequest.contractAddress, functionData, fromAddress)
            } catch (e: Exception) {
                val errorMessage = "Failed to get estimated gas usage for request id ${txRequest.rowId}: ${e.message}"
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
                    txRequest.contractAddress,
                    functionData,
                    BigInteger.ZERO
                )

                if (response.hasError()) {
                    val errorMessage =
                        "Web3j request failed with error code: ${response.error.code} and message: ${response.error.message}"
                    logger.error(errorMessage)
                    throw ProgrammerMistake(errorMessage)
                }

                val txPending = EvmPendingRellTx(
                    txRequest.rowId,
                    txRequest.networkId,
                    txRequest.contractAddress,
                    txRequest.functionName,
                    txRequest.parameterTypes,
                    txRequest.parameterValues,
                    response.transactionHash
                )
                addPendingTransaction(txPending)
                submitTxUpdates[txRequest.rowId] = EvmSubmitTransactionResult(RellTransactionStatus.PENDING, response.transactionHash)

                logger.info { "Transaction ${txRequest.rowId} submitted successfully" }

                return

            } catch (e: Exception) {

                val error = "Failed to send transaction ${txRequest.rowId}: ${e.message}"
                logger.error { error }

                withWriteConnection(storage, chainId) {
                    databaseOperations.recordTransactionError(it, txRequest.rowId, rpcUrl, error, e.stackTraceToString())
                    true
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

    fun fetchAndClearSubmitTxUpdates(): Map<Long, EvmSubmitTransactionResult> {
        val copy = submitTxUpdates.toImmutableMap()
        submitTxUpdates.clear()
        return copy
    }

    // One for submitting and one for polling?
    private fun isTimeout(requestId: Long, time: Long, timeoutMs: Long) {
        if (System.currentTimeMillis() - time > timeoutMs) {
            val message = "Transaction $requestId with timestamp $time was not processed within $timeoutMs ms and timed out"
            logger.warn { message }
            throw EvmTransactionTimeoutException(message)
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

    fun addPendingTransaction(txPending: EvmPendingRellTx) {

        if (!pendingTransactions.containsKey(txPending.txHash)) {
            val evmPendingDbTx = EvmPendingDbTx(txPending, updated = System.currentTimeMillis())
            pendingTransactions[txPending.txHash] = evmPendingDbTx

            withReadWriteConnection(storage, chainId) {
                databaseOperations.addPendingTransaction(it, networkId, evmPendingDbTx)
                true
            }
        }
    }

    fun getVerifiedTransactions(minMsSinceUpdate: Long): List<EvmPendingDbTx> {

        return withReadConnection(storage, chainId) {
            databaseOperations.getVerifiedTransactions(it, networkId, minMsSinceUpdate)
        }
    }

    fun getPendingTx(requestId: Long): EvmPendingDbTx? {

        return pendingTransactions.values.firstOrNull { it.rowId == requestId }
    }

    fun setSubmitBCPersisted(rowId: Long) {
        withReadWriteConnection(storage, chainId) {
            databaseOperations.setSubmitTxBCPersisted(it, rowId)
        }
    }

    fun setPendingBcCPersisted(rowId: Long) {
        withReadWriteConnection(storage, chainId) {
            databaseOperations.setPendingTxBCPersisted(it, rowId)
        }
    }

    fun cleanupDb() {

        withReadWriteConnection(storage, chainId) {
            databaseOperations.cleanupDb(it, networkId, dbRetentionTime)
        }
    }
}

class EvmTransactionTimeoutException(message: String) : RuntimeException(message)