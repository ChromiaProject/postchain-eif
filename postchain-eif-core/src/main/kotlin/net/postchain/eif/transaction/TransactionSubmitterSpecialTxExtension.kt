package net.postchain.eif.transaction

import mu.KLogging
import net.postchain.base.SpecialTransactionPosition
import net.postchain.common.BlockchainRid
import net.postchain.core.BlockEContext
import net.postchain.crypto.CryptoSystem
import net.postchain.gtv.GtvArray
import net.postchain.gtv.GtvByteArray
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import net.postchain.gtv.mapper.toObject
import net.postchain.gtx.GTXModule
import net.postchain.gtx.data.OpData
import net.postchain.gtx.special.GTXSpecialTxExtension
import java.math.BigInteger

class TransactionSubmitterSpecialTxExtension : GTXSpecialTxExtension {
    companion object : KLogging() {
        const val UPDATE_EVM_TRANSACTION_STATUS = "__update_evm_transaction_status"
        const val UPDATE_EVM_TRANSACTION_RECEIPT = "__update_evm_transaction_receipt"
        const val ADD_EVM_TRANSACTION_ERRORS = "__add_evm_transaction_errors"
        const val EVM_TX_NO_OP = "__evm_tx_no_op"

        const val FETCH_OLDEST_QUEUED_TRANSACTIONS_PER_CONTRACT = "fetch_oldest_queued_transactions_per_contract"
        const val GET_PENDING_TRANSACTIONS = "get_pending_transactions"
        const val GET_TRANSACTION_STATUS = "get_evm_transaction_status"
    }

    private val transactionSubmitters = mutableMapOf<Long, TransactionSubmitter>()
    private lateinit var module: GTXModule
    private lateinit var pubKey: ByteArray
    private var txVerificationTime: Long = Long.MAX_VALUE

    override fun createSpecialOperations(position: SpecialTransactionPosition, bctx: BlockEContext): List<OpData> {

        addNewPendingTransactions(bctx)

        val operations = mutableListOf<OpData>()

        operations.addAll(takeTransactions(bctx))
        operations.addAll(updateTransactionStatuses(bctx))

        transactionSubmitters.values.forEach { txSubmitter -> txSubmitter.cleanupDb() }

        return operations
    }

    override fun validateSpecialOperations(
        position: SpecialTransactionPosition,
        bctx: BlockEContext,
        ops: List<OpData>
    ): Boolean {

        // Only accept one no op per transaction
        if (ops.count { it.opName == EVM_TX_NO_OP } > 1) {
            return false
        }

        for (op in ops) {

            if (op.opName == UPDATE_EVM_TRANSACTION_STATUS) {

                val requestId = op.args[0].asInteger()
                val newTxStatus = RellTransactionStatus.values()[op.args[1].asInteger().toInt()]
                val currentTxStatus = getTransactionBcStatus(bctx, requestId)

                if (currentTxStatus == null) {
                    logger.warn { "Validation failed. Transaction $requestId not found" }
                    return false
                }

                if (newTxStatus == currentTxStatus) {
                    return true
                }

                if (newTxStatus == RellTransactionStatus.TAKEN && currentTxStatus != RellTransactionStatus.QUEUED) {
                    logger.warn { "Validation failed. Transaction $requestId can not be set to status ${RellTransactionStatus.TAKEN} from current status $currentTxStatus" }
                    return false
                }

                if (newTxStatus == RellTransactionStatus.PENDING && currentTxStatus != RellTransactionStatus.QUEUED && currentTxStatus != RellTransactionStatus.TAKEN) {
                    logger.warn { "Validation failed. Transaction $requestId can not be set to status ${RellTransactionStatus.PENDING} from current status $currentTxStatus" }
                    return false
                }

                if (newTxStatus == RellTransactionStatus.FAILURE || newTxStatus == RellTransactionStatus.SUCCESS) {
                    // A transaction can only be set to SUCCESS/FAILURE by a node when in pending state.
                    // Other FAILURE will be set by rell (as timeout, enough failing nodes etc).
                    if (currentTxStatus != RellTransactionStatus.PENDING) {
                        logger.warn { "Validation failed. Transaction $requestId can not be set to status ${RellTransactionStatus.PENDING} from current status $currentTxStatus" }
                        return false
                    }

                    // A pending transaction must be verified
                    if (withTxPending(requestId) { txSubmitter, txPending ->

                            val acceptable =
                                (newTxStatus == RellTransactionStatus.SUCCESS && txPending.status == PendingTxStatus.SUCCESS) ||
                                        (newTxStatus == RellTransactionStatus.FAILURE && txPending.status == PendingTxStatus.REVERTED)

                            if (acceptable) {
                                bctx.addAfterCommitHook { txSubmitter.setPendingBcCPersisted(requestId) }
                            }

                            acceptable
                        } == false) {
                        logger.warn { "Validation failed. Transaction $requestId can not be set to status ${newTxStatus} because the status can't be approved by this node" }
                    }
                }
            } else if (op.opName == UPDATE_EVM_TRANSACTION_RECEIPT) {

                val requestId = op.args[0].asInteger()
                val blockHash = op.args[1].asString()
                val effectiveGasPrice = op.args[2].asBigInteger()
                val gasUsage = op.args[3].asBigInteger()

                val valid = withTxPending(requestId) { _, txPending ->

                    txPending.blockHash != null && txPending.blockHash == blockHash &&
                            txPending.effectiveGasPrice != null && txPending.effectiveGasPrice!! == effectiveGasPrice &&
                            txPending.gasUsed != null && txPending.gasUsed!! == gasUsage
                }

                if (valid == false) {
                    return false
                }
            }
        }

        return true
    }

    private fun updateTransactionStatuses(bctx: BlockEContext): Collection<OpData> {

        val operations = mutableListOf<OpData>()

        transactionSubmitters.values.forEach { txSubmitter ->

            // Transaction status updates
            txSubmitter.fetchAndClearSubmitTxUpdates().forEach { (rowId, result) ->
                operations.add(
                    OpData(
                        UPDATE_EVM_TRANSACTION_STATUS,
                        arrayOf(gtv(rowId), gtv(result.status.ordinal.toLong()), if (result.txHash != null) gtv(result.txHash) else GtvNull)
                    )
                )

                // Status QUEUE can be set multiple times due to retry - add a no op for them
                if (result.status == RellTransactionStatus.QUEUED) {
                    operations.add(OpData(EVM_TX_NO_OP, arrayOf(gtv(bctx.height))))
                }

                addTransactionErrors(txSubmitter, rowId, operations)

                // We have processed this TX - cleanup
                if (result.status != RellTransactionStatus.TAKEN) {
                    bctx.addAfterCommitHook { txSubmitter.setSubmitBCPersisted(rowId) }
                }
            }

            txSubmitter.getVerifiedTransactions(txVerificationTime).forEach {

                val rellStatus =
                    if (it.status == PendingTxStatus.SUCCESS) RellTransactionStatus.SUCCESS else RellTransactionStatus.FAILURE

                operations.add(
                    OpData(
                        UPDATE_EVM_TRANSACTION_STATUS,
                        arrayOf(gtv(it.rowId), gtv(rellStatus.ordinal.toLong()))
                    )
                )

                if (rellStatus == RellTransactionStatus.SUCCESS) {
                    operations.add(
                        OpData(
                            UPDATE_EVM_TRANSACTION_RECEIPT, arrayOf(
                                gtv(it.rowId),
                                gtv(it.blockHash ?: ""),
                                gtv(it.effectiveGasPrice ?: BigInteger.ZERO),
                                gtv(it.gasUsed ?: BigInteger.ZERO),
                            )
                        )
                    )
                } else {

                    addTransactionErrors(txSubmitter, it.rowId, operations)
                }
            }
        }

        return operations
    }

    private fun addTransactionErrors(
        submitter: TransactionSubmitter,
        rowId: Long,
        operations: MutableList<OpData>
    ) {
        val transactionErrors = submitter
            .getTransactionErrors(rowId)
            .map {
                GtvArray(
                    arrayOf(
                        gtv(it.timestamp.time),
                        gtv(ByteArray(0)),
                        gtv(it.rpcUrl ?: ""),
                        gtv(it.message),
                    )
                )
            }

        if (transactionErrors.isNotEmpty()) {
            operations.add(
                OpData(
                    ADD_EVM_TRANSACTION_ERRORS, arrayOf(
                        gtv(rowId),
                        gtv(transactionErrors)
                    )
                )
            )
        }
    }

    private fun addNewPendingTransactions(bctx: BlockEContext) {

        val queryResult = module.query(bctx, GET_PENDING_TRANSACTIONS, gtv(listOf()))
        val transactions = queryResult.asArray().map {
            it.toObject<EvmPendingRellTx>()
        }

        transactions.forEach { transaction ->
            withTxSubmitter(transaction.networkId) {
                it.addPendingTransaction(transaction)
            }
        }

        // TODO cleanup transactions no longer pending on BC?
    }

    private fun <T> withTxPending(requestId: Long, action: (TransactionSubmitter, EvmPendingDbTx) -> T): T? {

        for (txSubmitter in transactionSubmitters.values) {

            val txPending = txSubmitter.getPendingTx(requestId)

            if (txPending != null) {
                return action(txSubmitter, txPending)
            }
        }

        return null
    }

    private fun withTxSubmitter(networkId: Long, function: (TransactionSubmitter) -> Unit) {

        val txSubmitter = transactionSubmitters[networkId]
        if (txSubmitter == null) {
            logger.warn("Ignoring tx since there is no submitter for ${networkId}")
        } else if (!txSubmitter.isHealthy()) {
            logger.warn("Ignoring tx since the submitter for ${networkId} is unhealthy")
        } else {
            function(txSubmitter)
        }
    }

    private fun takeTransactions(bctx: BlockEContext): MutableList<OpData> {

        val entities =
            module.query(bctx, FETCH_OLDEST_QUEUED_TRANSACTIONS_PER_CONTRACT, gtv(listOf(GtvByteArray(pubKey))))
        val queuedTransactions = entities.asArray().map {
            it.toObject<EvmSubmitTransactionRequest>()
        }

        val operations = mutableListOf<OpData>()
        queuedTransactions.forEach { transaction ->

            withTxSubmitter(transaction.networkId) {
                bctx.addAfterCommitHook { it.enqueue(transaction) }
                operations.add(
                    OpData(
                        UPDATE_EVM_TRANSACTION_STATUS,
                        arrayOf(gtv(transaction.rowId), gtv(RellTransactionStatus.TAKEN.ordinal.toLong()))
                    )
                )

                // Status TAKEN can be set multiple times due to retry - add a no op for them
                operations.add(OpData(EVM_TX_NO_OP, arrayOf(gtv(bctx.height))))
            }
        }
        return operations
    }

    override fun getRelevantOps(): Set<String> {
        return setOf(UPDATE_EVM_TRANSACTION_STATUS, UPDATE_EVM_TRANSACTION_RECEIPT, ADD_EVM_TRANSACTION_ERRORS, EVM_TX_NO_OP)
    }

    override fun init(module: GTXModule, chainID: Long, blockchainRID: BlockchainRid, cs: CryptoSystem) {
        this.module = module
    }

    override fun needsSpecialTransaction(position: SpecialTransactionPosition): Boolean {
        return when (position) {
            SpecialTransactionPosition.Begin -> true
            SpecialTransactionPosition.End -> false
        }
    }

    private fun getTransactionBcStatus(bctx: BlockEContext, requestId: Long): RellTransactionStatus? {

        val queryResult = module.query(bctx, GET_TRANSACTION_STATUS, gtv(listOf(gtv(requestId))))
        if (queryResult.isNull()) {
            return null
        }

        return RellTransactionStatus.values()[queryResult.asInteger().toInt()]
    }

    fun addTransactionSubmitter(transactionSubmitter: TransactionSubmitter, networkId: Long) {

        transactionSubmitters[networkId] = transactionSubmitter
    }

    fun getTransactionSubmitter(networkId: Long) = transactionSubmitters[networkId]

    fun setConfig(pubKey: ByteArray, txVerificationTime: Long) {
        this.pubKey = pubKey
        this.txVerificationTime = txVerificationTime
    }
}
