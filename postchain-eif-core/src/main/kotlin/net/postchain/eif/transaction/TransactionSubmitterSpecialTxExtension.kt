package net.postchain.eif.transaction

import mu.KLogging
import net.postchain.base.SpecialTransactionPosition
import net.postchain.common.BlockchainRid
import net.postchain.core.BlockEContext
import net.postchain.core.EContext
import net.postchain.crypto.CryptoSystem
import net.postchain.crypto.KeyPair
import net.postchain.crypto.SigMaker
import net.postchain.crypto.Signature
import net.postchain.gtv.GtvByteArray
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import net.postchain.gtv.mapper.toObject
import net.postchain.gtv.merkle.GtvMerkleHashCalculatorV1
import net.postchain.gtv.merkleHash
import net.postchain.gtx.GTXModule
import net.postchain.gtx.data.OpData
import net.postchain.gtx.special.GTXSpecialTxExtension
import java.math.BigInteger

class TransactionSubmitterSpecialTxExtension : GTXSpecialTxExtension {
    companion object : KLogging() {
        const val UPDATE_EVM_TRANSACTION_STATUS = "__update_evm_transaction_status"
        const val UPDATE_EVM_TRANSACTION_RECEIPT = "__update_evm_transaction_receipt"
        const val EVM_TX_NO_OP = "__evm_tx_no_op"

        const val FETCH_OLDEST_QUEUED_TRANSACTIONS_PER_CONTRACT = "fetch_oldest_queued_transactions_per_contract"
        const val GET_TRANSACTION = "get_transaction"
        const val GET_PENDING_TRANSACTIONS = "get_pending_transactions"
        const val GET_TRANSACTION_STATUS = "get_evm_transaction_status"
    }

    private lateinit var cryptoSystem: CryptoSystem
    private lateinit var merkelHashCalculator: GtvMerkleHashCalculatorV1
    private lateinit var sigMaker: SigMaker
    private val transactionSubmitters = mutableMapOf<Long, TransactionSubmitter>()
    private lateinit var module: GTXModule
    private lateinit var privKey: ByteArray
    private lateinit var pubKey: ByteArray
    private var validateSpecialOps: () -> Boolean = { false }
    private var pollEvmReceipts = false
    private var startupJobsRun = false

    override fun createSpecialOperations(position: SpecialTransactionPosition, bctx: BlockEContext): List<OpData> {

        val operations = mutableListOf<OpData>()

        startup(bctx)
        removeCompletedTxs(bctx)
        removeTxsWithUpdatedProcessedByValue(bctx)
        takeTransactions(bctx, operations)
        updateTransactionStatuses(bctx, operations)

        return operations
    }

    // Stop processing transactions locally if already been marked as completed on blockchain
    private fun removeCompletedTxs(bctx: BlockEContext) {

        transactionSubmitters.values.forEach { txSubmitter ->

            val txIds = txSubmitter.getSubmitTxUpdates().map(EvmSubmitTransactionResult::requestId) +
                    txSubmitter.getPendingTxs().values.map(EvmPendingTx::rowId)

            txIds.forEach { tx ->
                removeTxIfAlreadyCompleted(module, bctx, tx)
            }
        }
    }

    override fun validateSpecialOperations(
            position: SpecialTransactionPosition,
            bctx: BlockEContext,
            ops: List<OpData>
    ): Boolean {

        startup(bctx)
        removeCompletedTxs(bctx)
        removeTxsWithUpdatedProcessedByValue(bctx)

        if (!isNoOpValid(ops, bctx.height)) {
            logger.warn { "Validation failed. Invalid no op. Operations: $ops" }
            return false
        }

        for (op in ops) {

            if (op.opName == UPDATE_EVM_TRANSACTION_STATUS) {

                val requestId = op.args[0].asInteger()
                val newTxStatus = RellTransactionStatus.entries[op.args[1].asInteger().toInt()]
                val txHash = if (op.args[2].isNull()) null else op.args[2].asString()
                val signer = op.args[3].asByteArray()
                val signedData = op.args[4].asByteArray()

                if (!cryptoSystem.verifyDigest(signatureDataHash(requestId, newTxStatus), Signature(signer, signedData))) {
                    logger.warn { "Validation failed. Invalid signature" }
                    return false
                }

                val currentTxStatus = getBcTransactionStatus(module, bctx, requestId)

                if (currentTxStatus == null) {
                    logger.warn { "Validation failed. Transaction $requestId not found" }
                    return false
                }

                if (validateSpecialOps()) {
                    if (newTxStatus == RellTransactionStatus.TAKEN && currentTxStatus != RellTransactionStatus.QUEUED) {
                        logger.warn { "Validation failed. Transaction $requestId can not be set to status ${RellTransactionStatus.TAKEN} from current status $currentTxStatus" }
                        return false
                    }
                    if (newTxStatus == RellTransactionStatus.QUEUED && currentTxStatus != RellTransactionStatus.TAKEN) {
                        logger.warn { "Validation failed. Only rell or the node submitting a transaction can set status to ${RellTransactionStatus.QUEUED}" }
                        return false
                    }
                }

                if (newTxStatus == RellTransactionStatus.PENDING) {

                    if (validateSpecialOps() && currentTxStatus != RellTransactionStatus.QUEUED && currentTxStatus != RellTransactionStatus.TAKEN) {
                        logger.warn { "Validation failed. Transaction $requestId can not be set to status ${RellTransactionStatus.PENDING} from current status $currentTxStatus" }
                        return false
                    }

                    if (txHash == null) {
                        logger.warn { "Validation failed. Transaction $requestId did not set any transaction hash when changing status to ${RellTransactionStatus.PENDING}" }
                        return false
                    }

                    val txPending = getBcTransaction(bctx, requestId)

                    if (pollEvmReceipts) {
                        bctx.addAfterCommitHook {
                            withTxSubmitter(txPending.networkId) {
                                it.removeSubmitTx(bctx, requestId)
                                it.addPendingTransaction(EvmPendingTx.fromEvmSubmitTxRellRequest(txPending, txHash))
                            }
                        }
                    }
                }

                if (newTxStatus == RellTransactionStatus.FAILURE || newTxStatus == RellTransactionStatus.SUCCESS) {
                    if (validateSpecialOps()) {

                        // A pending transaction must be verified
                        val txStatusMatchesThisNode = withTxPending(requestId) { txSubmitter, txPending ->

                            val acceptable =
                                    (newTxStatus == RellTransactionStatus.SUCCESS && txPending.status == PendingTxStatus.SUCCESS) ||
                                            (newTxStatus == RellTransactionStatus.FAILURE && txPending.status == PendingTxStatus.REVERTED)

                            if (acceptable) {
                                bctx.addAfterCommitHook { txSubmitter.removePendingTx(requestId) }
                            }

                            acceptable
                        }
                        if (txStatusMatchesThisNode != true) {
                            logger.warn { "Validation failed. Transaction $requestId can not be set to status $newTxStatus because the status can't be approved by this node" }
                            return false
                        }

                        // A SUCCESS must contain a receipt in the same transaction
                        if (newTxStatus == RellTransactionStatus.SUCCESS && getOpsForTx(ops, UPDATE_EVM_TRANSACTION_RECEIPT, requestId).isEmpty()) {
                            logger.warn { "Validation failed. Transaction $requestId is set to ${RellTransactionStatus.SUCCESS.name} but without a receipt" }
                            return false
                        }

                        logger.info { "Transaction $requestId is verified as $newTxStatus" }
                    } else {
                        withTxPending(requestId) { txSubmitter, _ ->
                            bctx.addAfterCommitHook { txSubmitter.removePendingTx(requestId) }
                        }
                    }
                }
            } else if (validateSpecialOps() && op.opName == UPDATE_EVM_TRANSACTION_RECEIPT) {

                val requestId = op.args[0].asInteger()
                val blockHash = op.args[1].asString()
                val effectiveGasPrice = op.args[2].asBigInteger()
                val gasUsage = op.args[3].asBigInteger()

                val valid = withTxPending(requestId) { _, txPending ->

                    val match = txPending.blockHash != null && txPending.blockHash.equals(blockHash, ignoreCase = true) &&
                            txPending.effectiveGasPrice != null && txPending.effectiveGasPrice!! == effectiveGasPrice &&
                            txPending.gasUsed != null && txPending.gasUsed!! == gasUsage

                    if (!match) {
                        logger.warn { "Validation failed. Receipt for transaction $requestId does not match this nodes receipt. Op receipt: block hash: $blockHash, effective gas price: $effectiveGasPrice, gas usage: $gasUsage. This nodes receipt: block hash: ${txPending.blockHash}, effective gas price: ${txPending.effectiveGasPrice}, gas usage: ${txPending.gasUsed}" }
                    }

                    match
                }

                if (valid == false) {
                    return false
                }

                // A receipt requires a SUCCESS update status
                if (!getOpsForTx(ops, UPDATE_EVM_TRANSACTION_STATUS, requestId)
                                .any { RellTransactionStatus.entries[it.args[1].asInteger().toInt()] == RellTransactionStatus.SUCCESS }) {
                    logger.warn { "Validation failed. Receipt for transaction $requestId set without any ${RellTransactionStatus.SUCCESS.name} status update op" }
                    return false
                }
            }
        }

        return true
    }

    private fun isNoOpValid(ops: List<OpData>, height: Long): Boolean {

        // Only accept one no op per transaction and with current height as argument
        val noOps = ops.filter { it.opName == EVM_TX_NO_OP }

        if (noOps.isNotEmpty()) {

            return noOps.size == 1 &&
                    noOps[0].args.size == 1 &&
                    noOps[0].args[0].asInteger() == height
        }

        return true
    }

    private fun updateTransactionStatuses(bctx: BlockEContext, operations: MutableList<OpData>) {

        transactionSubmitters.values.forEach { txSubmitter ->

            // Submitted transaction status updates
            val submitTxUpdates = txSubmitter.getSubmitTxUpdates()
            submitTxUpdates.forEach { result ->

                operations.add(
                        buildTxUpdateOp(result.requestId, result.status, result.txHash)
                )

                // Status QUEUE can be set multiple times due to retry - add a no op for them
                if (result.status == RellTransactionStatus.QUEUED) {
                    addNoOp(operations, bctx)
                }

                // We have processed this TX - cleanup
                if (result.status != RellTransactionStatus.TAKEN) {
                    bctx.addAfterCommitHook {
                        txSubmitter.setSubmitBCPersisted(bctx, result.requestId)
                    }
                }
            }

            bctx.addAfterCommitHook { txSubmitter.clearSubmitTxUpdates(submitTxUpdates) }

            // Verified transaction updates
            txSubmitter.getVerifiedTransactions().forEach {

                val rellStatus =
                        if (it.status == PendingTxStatus.SUCCESS) RellTransactionStatus.SUCCESS else RellTransactionStatus.FAILURE

                // Update receipt only if transaction has a receipt
                if (it.blockHash != null) {
                    operations.add(
                            buildTxReceiptOp(it)
                    )
                }

                operations.add(
                        buildTxUpdateOp(it.rowId, rellStatus)
                )
            }
        }
    }

    private fun removeTxIfAlreadyCompleted(module: GTXModule, bctx: BlockEContext, requestId: Long) {

        if (isTxCompletedOnBlockchain(module, bctx, requestId)) {
            transactionSubmitters.values.forEach { txSubmitter ->
                txSubmitter.removeTransactionCompletely(bctx, requestId)
            }
        }
    }

    fun buildTxReceiptOp(txPending: EvmPendingTx): OpData {

        return OpData(
                UPDATE_EVM_TRANSACTION_RECEIPT, arrayOf(
                gtv(txPending.rowId),
                gtv(txPending.blockHash ?: ""),
                gtv(txPending.effectiveGasPrice ?: BigInteger.ZERO),
                gtv(txPending.gasUsed ?: BigInteger.ZERO)
        ))
    }

    fun buildTxUpdateOp(rowId: Long, rellStatus: RellTransactionStatus, txHash: String? = null): OpData {

        val signature = createSignature(rowId, rellStatus)

        return OpData(
                UPDATE_EVM_TRANSACTION_STATUS,
                arrayOf(
                        gtv(rowId),
                        gtv(rellStatus.ordinal.toLong()),
                        if (txHash != null) gtv(txHash) else GtvNull,
                        gtv(signature.subjectID),
                        gtv(signature.data)
                )
        )
    }

    fun addNewPendingTransactions(bctx: BlockEContext) {

        val queryResult = module.query(bctx, GET_PENDING_TRANSACTIONS, gtv(mapOf()))
        val transactions = queryResult.asArray().map {
            it.toObject<EvmSubmitTxRellRequest>()
        }

        transactions.forEach { transaction ->
            withTxSubmitter(transaction.networkId) {
                it.addPendingTransaction(EvmPendingTx.fromEvmSubmitTxRellRequest(transaction, transaction.txHash!!))
            }
        }
    }

    fun <T> withTxPending(requestId: Long, action: (TransactionSubmitter, EvmPendingTx) -> T): T? {

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
            logger.warn("Ignoring tx since there is no submitter for $networkId")
        } else {
            function(txSubmitter)
        }
    }

    private fun takeTransactions(bctx: BlockEContext, operations: MutableList<OpData>) {

        val entities =
                module.query(bctx, FETCH_OLDEST_QUEUED_TRANSACTIONS_PER_CONTRACT, gtv("exclude_taken_by_key" to GtvByteArray(pubKey)))
        val queuedTransactions = entities.asArray().map {
            it.toObject<EvmSubmitTxRellRequest>()
        }

        queuedTransactions.forEach { transaction ->

            withTxSubmitter(transaction.networkId) {

                if (!it.isHealthy()) {
                    logger.warn("Node did not take this tx since the submitter for ${it.networkId} is unhealthy")
                } else {
                    bctx.addAfterCommitHook { it.enqueue(EvmSubmitTxRequest.fromRell(transaction)) }
                    operations.add(
                            buildTxUpdateOp(transaction.rowId, RellTransactionStatus.TAKEN)
                    )

                    // Status TAKEN can be set multiple times due to retry - add a no op for them
                    addNoOp(operations, bctx)

                    logger.info { "Transaction ${transaction.rowId} taken to be processed by this node" }
                }
            }
        }
    }

    override fun getRelevantOps(): Set<String> {
        return setOf(UPDATE_EVM_TRANSACTION_STATUS, UPDATE_EVM_TRANSACTION_RECEIPT, EVM_TX_NO_OP)
    }

    override fun init(module: GTXModule, chainID: Long, blockchainRID: BlockchainRid, cs: CryptoSystem) {
        this.module = module
        this.cryptoSystem = cs
        this.merkelHashCalculator = GtvMerkleHashCalculatorV1(this.cryptoSystem)
    }

    override fun needsSpecialTransaction(position: SpecialTransactionPosition): Boolean {
        return when (position) {
            SpecialTransactionPosition.Begin -> true
            SpecialTransactionPosition.End -> false
        }
    }

    private fun getBcTransaction(bctx: BlockEContext, requestId: Long): EvmSubmitTxRellRequest {

        val queryResult = module.query(bctx, GET_TRANSACTION, gtv("row_id" to gtv(requestId)))
        return queryResult.toObject<EvmSubmitTxRellRequest>()
    }

    fun addTransactionSubmitter(transactionSubmitter: TransactionSubmitter, networkId: Long) {

        transactionSubmitters[networkId] = transactionSubmitter
    }

    fun getTransactionSubmitter(networkId: Long) = transactionSubmitters[networkId]

    fun setConfig(
            privKey: ByteArray,
            pubKey: ByteArray,
            validateSpecialOps: () -> Boolean,
            pollEvmReceipts: Boolean
    ) {
        this.privKey = privKey
        this.pubKey = pubKey
        this.sigMaker = cryptoSystem.buildSigMaker(KeyPair(pubKey, privKey))
        this.validateSpecialOps = validateSpecialOps
        this.pollEvmReceipts = pollEvmReceipts

        logger.info { "Transaction submitter special tx extension config: pollEvmReceipts: ${this.pollEvmReceipts}" }
    }

    private fun addNoOp(operations: MutableList<OpData>, bctx: BlockEContext) {

        if (operations.none { it.opName == EVM_TX_NO_OP }) {
            operations.add(OpData(EVM_TX_NO_OP, arrayOf(gtv(bctx.height))))
        }
    }

    private fun createSignature(value: Long, status: RellTransactionStatus) =
            sigMaker.signDigest(signatureDataHash(value, status))

    private fun signatureDataHash(value: Long, status: RellTransactionStatus) =
            gtv(gtv(value), gtv(status.ordinal.toLong())).merkleHash(GtvMerkleHashCalculatorV1(cryptoSystem))

    // Checks if rell has marked the tx as completed
    private fun isTxCompletedOnBlockchain(module: GTXModule, eContext: EContext, requestId: Long): Boolean {

        val txStatus = getBcTransactionStatus(module, eContext, requestId)

        if (txStatus == null || txStatus.isCompleted()) {
            logger.warn { "Transaction $requestId blockchain status is set to $txStatus. This node will stop processing this transaction." }
            return true
        }

        return false
    }

    private fun getBcTransactionStatus(module: GTXModule, eContext: EContext, requestId: Long): RellTransactionStatus? {

        val queryResult = module.query(eContext, GET_TRANSACTION_STATUS, gtv("row_id" to gtv(requestId)))
        if (queryResult.isNull()) {
            return null
        }

        return RellTransactionStatus.valueOf(queryResult.asString())
    }

    private fun getOpsForTx(ops: List<OpData>, opName: String, requestId: Long): List<OpData> {
        return ops
                .filter { op -> op.opName == opName && op.args[0].asInteger() == requestId}
    }

    // If the processedBy value is updated it means another node has been assigned the task to submit this transaction
    // instead. Stop verifying this transaction and if it was submitted by this node also "cancel" it on evm side.
    private fun removeTxsWithUpdatedProcessedByValue(bctx: BlockEContext) {

        transactionSubmitters.values.forEach { txSubmitter ->

            // Check if bc process_by no longer match our cached value
            val removeTxs = txSubmitter.getPendingTxs().values.filter {
                val tx = getBcTransaction(bctx, it.rowId)
                tx.processedBy == null || !tx.processedBy.contentEquals(it.processedBy)
            }

            removeTxs.forEach { tx: EvmPendingTx ->

                logger.info { "Transaction ${tx.rowId} is retried by another node" }

                if (tx.processedBy.contentEquals(pubKey)) {
                    // This tx was submitted by us but given to someone else - send a cancel replacement tx
                    txSubmitter.cancelPendingTx(tx)
                }
                txSubmitter.removePendingTx(tx.rowId)
            }
        }
    }

    private fun startup(bctx: BlockEContext) {
        if (!startupJobsRun) {
            startupJobsRun = true
            addNewPendingTransactions(bctx)
        }
    }
}
