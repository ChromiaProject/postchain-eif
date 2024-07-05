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
import net.postchain.base.withReadWriteConnection
import net.postchain.base.withWriteConnection
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.core.BlockEContext
import net.postchain.core.Shutdownable
import net.postchain.core.Storage
import net.postchain.eif.GtvToTypeMapper
import net.postchain.eif.Web3jRequestHandler
import net.postchain.eif.transaction.gas.EIP1559FeeEstimatorFactory
import net.postchain.gtv.Gtv
import okhttp3.internal.toImmutableList
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Function
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.tx.TransactionManager
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

open class TransactionSubmitter(
        private val web3jRequestHandler: Web3jRequestHandler,
        private val transactionManagers: Map<String, TransactionManager>,
        private val feeEstimatorFactory: EIP1559FeeEstimatorFactory,
        private val databaseOperations: TransactionSubmitterDatabaseOperations,
        val storage: Storage,
        val chainId: Long,
        val networkId: Long,
        val txPollInterval: Long,
        initQueue: Collection<EvmSubmitTxRequest>,
        val minWalletBalance: BigInteger,
        val healthCheckInterval: Long,
        val nodeTxVerificationTimeout: Long,
        val nodeTxVerificationEvmBlocks: Long,
        val txVerificationTime: Long,
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
    private val queue = LinkedBlockingQueue<EvmSubmitTxRequest>()
    private val pendingTransactions = ConcurrentHashMap<String, EvmPendingTx>()
    private val submitTxUpdates: MutableList<EvmSubmitTransactionResult> = Collections.synchronizedList(mutableListOf())

    init {

        logger.info {
            "Initializing transaction submitter - chainId: $chainId, networkId: $networkId, " +
                    "txPollInterval: $txPollInterval, healthCheckInterval: $healthCheckInterval, " +
                    "nodeTxVerificationTimeout: $nodeTxVerificationTimeout" +
                    ", nodeTxVerificationEvmBlocks: $nodeTxVerificationEvmBlocks, txVerificationTime: $txVerificationTime"
        }

        // Add transactions to queue and recover states lost on node restart
        for (txSubmit in initQueue) {

            if (txSubmit.txHash == null) {
                // Transaction taken but not yet submitted

                logger.info { "Adding transaction ${txSubmit.rowId} to the queue to be submitted" }
                queue.offer(txSubmit)

            } else if (!txSubmit.bcPersisted) {
                // Transaction submitted but status not yet persisted to BC

                logger.info { "Adding transaction ${txSubmit.rowId} to the status update queue to be set to ${RellTransactionStatus.PENDING}" }
                submitTxUpdates.add(EvmSubmitTransactionResult(txSubmit.rowId, RellTransactionStatus.PENDING, txSubmit.txHash!!))
            }
        }

        txSubmitJob =
                CoroutineScope(Dispatchers.IO).launch(CoroutineName("$networkId-transaction-submitter") + MDCContext()) {
                    while (isActive) {
                        try {
                            val txToSubmit = queue.take()
                            try {
                                submitTransaction(txToSubmit)
                            } catch (e: Exception) {
                                logger.error("Failed to submit EVM transaction ${txToSubmit.rowId}: ${e.message}", e)
                                submitTxUpdates.add(EvmSubmitTransactionResult(txToSubmit.rowId, RellTransactionStatus.QUEUED))
                            }
                        } catch (e: CancellationException) {
                            break
                        }
                    }
                }
        txStatusPollJob =
                CoroutineScope(Dispatchers.IO).launch(CoroutineName("$networkId-transaction-status-poller") + MDCContext()) {
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
        healthCheckJob =
                CoroutineScope(Dispatchers.IO).launch(CoroutineName("$networkId-health-check") + MDCContext()) {
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
            web3jRequestHandler.sendWeb3jRequest {
                it.ethGetBalance(
                        transactionManagers.values.first().fromAddress,
                        DefaultBlockParameterName.LATEST
                )
            }
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

    internal fun pollPendingTransactions() {

        if (pendingTransactions.isNotEmpty()) {

            val currentBlockHeight = try {
                web3jRequestHandler.sendWeb3jRequest { it.ethBlockNumber() }.blockNumber
            } catch (e: Exception) {
                logger.error("Unable to query for current EVM block number. Will retry next poll.", e)
                return
            }

            pendingTransactions.forEach { (_, txPending) ->
                try {
                    pollPendingTransaction(txPending, currentBlockHeight)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to process pending transaction ${txPending.rowId}" }
                } finally {

                    try {
                        checkTransactionTimeout(txPending)
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to verify timeout of transaction ${txPending.rowId}" }
                    }
                }
            }
        }
    }

    internal fun pollPendingTransaction(txPending: EvmPendingTx, currentBlockHeight: BigInteger) {

        if (txPending.status.isCompleted()) {
            return
        }

        var txReceipt: TransactionReceipt? = null

        if (txPending.blockNumber == null) {
            logger.info { "Fetching receipt for pending transaction ${txPending.rowId} / ${txPending.txHash}" }

            val txReceiptResult = fetchTransactionReceipt(txPending.txHash, txPending.rowId)

            if (txReceiptResult != null && txReceiptResult.transactionReceipt.isPresent) {

                txReceipt = txReceiptResult.transactionReceipt.get()
                txPending.blockNumber = txReceipt.blockNumber

                logger.info { "Transaction ${txPending.rowId} first receipt found at block number ${txPending.blockNumber}: $txReceipt" }
            }
        }

        val blocksSinceReceipt = currentBlockHeight.minus(txPending.blockNumber!!).longValueExact()
        if (blocksSinceReceipt >= nodeTxVerificationEvmBlocks) {
            if (txReceipt == null) {
                logger.info { "Re-fetching receipt for pending transaction ${txPending.rowId}" }

                val txReceiptResult = fetchTransactionReceipt(txPending.txHash, txPending.rowId)

                if (txReceiptResult != null && txReceiptResult.transactionReceipt.isPresent) {
                    txReceipt = txReceiptResult.transactionReceipt.get()
                }
            } else {

                logger.info { "Receipt for transaction ${txPending.rowId} is old ($blocksSinceReceipt blocks) - verify" }
            }

            if (txReceipt != null) {

                verifyTxStructure(txPending)

                if (!txPending.status.isCompleted()) {
                    verifyTxReceipt(txReceipt, txPending, currentBlockHeight)
                }
            }
        }
    }

    private fun checkTransactionTimeout(txPending: EvmPendingTx) {
        if (!txPending.status.isCompleted()) {
            try {
                isTimeout(txPending.rowId, txPending.created, nodeTxVerificationTimeout)
            } catch (e: EvmTransactionTimeoutException) {
                logger.warn { "Transaction ${txPending.rowId} timed out" }

                txPending.status = PendingTxStatus.REVERTED
                submitTxUpdates.add(EvmSubmitTransactionResult(txPending.rowId, RellTransactionStatus.QUEUED))
                return
            }
        }
    }

    private fun verifyTxReceipt(
            txReceipt: TransactionReceipt,
            txPending: EvmPendingTx,
            currentBlockHeight: BigInteger
    ) {

        logger.info { "Verify receipt of transaction ${txPending.rowId}" }

        if (txReceipt.blockNumber != txPending.blockNumber) {
            // Some kind of re-org happened, store the new block number and return, will be polled again
            txPending.blockNumber = txReceipt.blockNumber
            return
        }

        txPending.status = if (txReceipt.isStatusOK) PendingTxStatus.SUCCESS else PendingTxStatus.REVERTED
        txPending.blockNumber = txReceipt.blockNumber
        txPending.blockHash = txReceipt.blockHash
        txPending.effectiveGasPrice = Numeric.decodeQuantity(txReceipt.effectiveGasPrice)
        txPending.gasUsed = txReceipt.gasUsed

        if (txPending.status == PendingTxStatus.REVERTED) {
            logger.warn { "Transaction ${txPending.rowId} got reverted" }
        }

        logger.info { "Pending transaction ${txPending.rowId} verified at block number $currentBlockHeight: ${txPending.status} - block hash: ${txPending.blockHash}, effective gas price ${txPending.effectiveGasPrice}, gas usage: ${txPending.gasUsed}" }
    }

    private fun verifyTxStructure(txPending: EvmPendingTx) {

        logger.info { "Verify structure of transaction ${txPending.rowId}" }

        val transactionByHashResponse =
                web3jRequestHandler.sendWeb3jRequest { it.ethGetTransactionByHash(txPending.txHash) }
        if (transactionByHashResponse.transaction.isPresent) {

            val transaction = transactionByHashResponse.transaction.get()
            val functionData =
                    encodeFunction(txPending.functionName, txPending.parameterTypes, txPending.parameterValues)

            val transactionToAddress = transaction.to.uppercase()
            if (
                    functionData != transaction.input ||
                    !transactionToAddress.contains(txPending.contractAddress)
            ) {
                txPending.status = PendingTxStatus.REVERTED
                logger.error {
                    "Transaction ${txPending.rowId} does not match original. " +
                            "Node function data: $functionData Node contract address: ${txPending.contractAddress} " +
                            "EVM function data: ${transaction.input} EVM contract address: $transactionToAddress"
                }
                logger.info { "Transaction ${txPending.rowId} functionName: ${txPending.functionName}, parameterTypes: ${txPending.parameterTypes}, parameterValues: ${txPending.parameterValues}" }
            }
        }
    }

    private fun fetchTransactionReceipt(txHash: String, rowId: Long) =
            try {
                web3jRequestHandler.sendWeb3jRequest { it.ethGetTransactionReceipt(txHash) }
            } catch (e: Exception) {
                val errorMessage = "Failed to poll for receipt for request id $rowId: ${e.message}"
                logger.error(e) { errorMessage }
                throw ProgrammerMistake(errorMessage, e)
            }

    internal fun submitTransaction(transactionRequest: EvmSubmitTxRequest) {
        try {
            sendTransaction(transactionRequest)
        } catch (e: Exception) {

            val errorMessage = e.message ?: "Unknown error"
            logger.error(e) { errorMessage }
            throw e
        }
    }

    private fun sendTransaction(txRequest: EvmSubmitTxRequest) {

        logger.info { "Submitting transaction ${txRequest.rowId}" }

        val fromAddress = transactionManagers.values.first().fromAddress

        val functionData = encodeFunction(txRequest.functionName, txRequest.parameterTypes, txRequest.parameterValues)

        withWriteConnection(storage, chainId) {
            databaseOperations.recordTransactionGas(it, txRequest.rowId, feeEstimatorFactory.maxGasPrice, feeEstimatorFactory.gasLimit)
            true
        }

        val feeEstimator = feeEstimatorFactory.createEstimate(txRequest.contractAddress, functionData, fromAddress, chainId)

        logger.info { "Estimated gas and fees for transaction ${txRequest.rowId}: $feeEstimator" }

        feeEstimator.validateRequestFees(txRequest)

        for ((rpcUrl, transactionManager) in transactionManagers) {
            try {

                val response = transactionManager.sendEIP1559Transaction(
                        networkId,
                        feeEstimator.maxPriorityFeePerGas,
                        feeEstimator.maxFeePerGas,
                        feeEstimatorFactory.gasLimit,
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

                withReadWriteConnection(storage, chainId) {
                    databaseOperations.recordTransactionHash(it, txRequest.rowId, response.transactionHash)
                }

                submitTxUpdates.add(EvmSubmitTransactionResult(txRequest.rowId, RellTransactionStatus.PENDING, response.transactionHash))

                logger.info { "Transaction ${txRequest.rowId} submitted successfully with maxPriorityFeePerGas=${feeEstimator.maxPriorityFeePerGas}, maxFeePerGas=${feeEstimator.maxFeePerGas}, gasLimit=$feeEstimatorFactory.gasLimit" }

                return

            } catch (e: Exception) {

                val error = "Failed to send transaction ${txRequest.rowId} to $rpcUrl: ${e.message}"
                logger.error { error }
            }
        }

        val errorMessage = "Failed to send transaction to all ${transactionManagers.size} nodes"
        throw ProgrammerMistake(errorMessage)
    }

    fun enqueue(evmSubmitTxRellRequest: EvmSubmitTxRequest) {

        logger.info { "Enqueue transaction ${evmSubmitTxRellRequest.rowId}" }

        withWriteConnection(storage, chainId) {
            databaseOperations.queueTransaction(it, evmSubmitTxRellRequest, networkId)
            true
        }
        queue.offer(evmSubmitTxRellRequest)
    }

    fun getSubmitTxUpdates(): List<EvmSubmitTransactionResult> {
        return submitTxUpdates.toImmutableList()
    }

    fun clearSubmitTxUpdates(updatesToRemove: List<EvmSubmitTransactionResult>) {
        this.submitTxUpdates.removeAll(updatesToRemove)
    }

    // One for submitting and one for polling?
    private fun isTimeout(requestId: Long, time: Long, timeoutMs: Long) {
        if (System.currentTimeMillis() - time > timeoutMs) {
            val message =
                    "Transaction $requestId with timestamp $time was not processed within $timeoutMs ms and timed out"
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

    fun addPendingTransaction(txPending: EvmPendingTx) {

        if (!pendingTransactions.containsKey(txPending.txHash)) {

            pendingTransactions[txPending.txHash] = txPending

            logger.info { "Transaction ${txPending.rowId} / ${txPending.txHash} added for verification" }
        }
    }

    fun getVerifiedTransactions(): List<EvmPendingTx> {

        return pendingTransactions.values
                .filter {
                    it.networkId == networkId &&
                            it.status.isCompleted() &&
                            (it.completedTime ?: Long.MAX_VALUE) <= Instant.now().minus(txVerificationTime, ChronoUnit.MILLIS).toEpochMilli()
                }
    }

    open fun getPendingTx(requestId: Long): EvmPendingTx? {

        return pendingTransactions.values.firstOrNull { it.rowId == requestId }
    }

    fun setSubmitBCPersisted(bctx: BlockEContext, rowId: Long) {
        databaseOperations.setSubmitTxBCPersisted(bctx, rowId)
    }

    fun removePendingTx(requestId: Long) {

        logger.info { "Removed pending transaction $requestId" }

        pendingTransactions
                .filterValues { it.rowId == requestId }
                .keys
                .forEach { pendingTransactions.remove(it) }
    }

    fun removeSubmitTx(bctx: BlockEContext, requestId: Long) {

        logger.info { "Removed submit transaction $requestId" }

        databaseOperations.removeTransaction(bctx, requestId)
    }
}

class EvmTransactionTimeoutException(message: String) : RuntimeException(message)