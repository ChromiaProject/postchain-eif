package net.postchain.eif.transaction

import mu.KLogging
import net.postchain.base.SpecialTransactionPosition
import net.postchain.common.BlockchainRid
import net.postchain.core.BlockEContext
import net.postchain.crypto.CryptoSystem
import net.postchain.crypto.KeyPair
import net.postchain.crypto.SigMaker
import net.postchain.crypto.Signature
import net.postchain.crypto.devtools.KeyPairHelper
import net.postchain.gtv.GtvByteArray
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.GtvNull
import net.postchain.gtv.mapper.toObject
import net.postchain.gtv.merkle.GtvMerkleHashCalculator
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
        const val GET_PENDING_TRANSACTIONS = "get_pending_transactions"
        const val GET_TRANSACTION_STATUS = "get_evm_transaction_status"
    }

    private lateinit var cryptoSystem: CryptoSystem
    private lateinit var merkelHashCalculator: GtvMerkleHashCalculator
    private lateinit var sigMaker: SigMaker
    private val transactionSubmitters = mutableMapOf<Long, TransactionSubmitter>()
    private lateinit var module: GTXModule
    private lateinit var pubKey: ByteArray
    private var txVerificationTime: Long = Long.MAX_VALUE

    override fun createSpecialOperations(position: SpecialTransactionPosition, bctx: BlockEContext): List<OpData> {

        addNewPendingTransactions(bctx)

        val operations = mutableListOf<OpData>()

        operations.addAll(takeTransactions(bctx))
        operations.addAll(updateTransactionStatuses(bctx))

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
                val signer = op.args[3].asByteArray()
                val signedRowId = op.args[4].asByteArray()

                if (!cryptoSystem.verifyDigest(signatureDataHash(requestId, newTxStatus), Signature(signer, signedRowId))) {
                    logger.warn { "Validation failed. Invalid signature" }
                    return false
                }

                val currentTxStatus = getTransactionBcStatus(bctx, requestId)

                if (currentTxStatus == null) {
                    logger.warn { "Validation failed. Transaction $requestId not found" }
                    return false
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
                                bctx.addAfterCommitHook { txSubmitter.removePendingTx(requestId) }
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

                    val match = txPending.blockHash != null && txPending.blockHash == blockHash &&
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
            }
        }

        return true
    }

    private fun updateTransactionStatuses(bctx: BlockEContext): Collection<OpData> {

        val operations = mutableListOf<OpData>()

        transactionSubmitters.values.forEach { txSubmitter ->

            // Transaction status updates
            txSubmitter.getSubmitTxUpdates().forEach { (rowId, result) ->
                operations.add(
                    buildTxUpdateOp(rowId, result.status, result.txHash)
                )

                // Status QUEUE can be set multiple times due to retry - add a no op for them
                if (result.status == RellTransactionStatus.QUEUED) {
                    addNoOp(operations, bctx)
                }

                // We have processed this TX - cleanup
                if (result.status != RellTransactionStatus.TAKEN) {
                    txSubmitter.setSubmitBCPersisted(bctx, rowId)
                }
            }

            bctx.addAfterCommitHook { txSubmitter.clearSubmitTxUpdates() }

            txSubmitter.getVerifiedTransactions(txVerificationTime).forEach {

                val rellStatus =
                    if (it.status == PendingTxStatus.SUCCESS) RellTransactionStatus.SUCCESS else RellTransactionStatus.FAILURE

                operations.add(
                    buildTxReceiptOp(it)
                )

                operations.add(
                    buildTxUpdateOp(it.rowId, rellStatus)
                )
            }
        }

        return operations
    }

    private fun buildTxReceiptOp(txPending: EvmPendingTx): OpData {

        return OpData(
            UPDATE_EVM_TRANSACTION_RECEIPT, arrayOf(
                gtv(txPending.rowId),
                gtv(txPending.blockHash ?: ""),
                gtv(txPending.effectiveGasPrice ?: BigInteger.ZERO),
                gtv(txPending.gasUsed ?: BigInteger.ZERO)
            )
        )
    }

    private fun buildTxUpdateOp(rowId: Long, rellStatus: RellTransactionStatus, txHash: String? = null): OpData {

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

    private fun addNewPendingTransactions(bctx: BlockEContext) {

        val queryResult = module.query(bctx, GET_PENDING_TRANSACTIONS, gtv(mapOf()))
        val transactions = queryResult.asArray().map {
            it.toObject<EvmPendingRellTx>()
        }

        transactions.forEach { transaction ->
            withTxSubmitter(transaction.networkId) {
                it.addPendingTransaction(transaction)
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
            logger.warn("Ignoring tx since there is no submitter for ${networkId}")
        } else if (!txSubmitter.isHealthy()) {
            logger.warn("Ignoring tx since the submitter for ${networkId} is unhealthy")
        } else {
            function(txSubmitter)
        }
    }

    private fun takeTransactions(bctx: BlockEContext): MutableList<OpData> {

        val entities =
                module.query(bctx, FETCH_OLDEST_QUEUED_TRANSACTIONS_PER_CONTRACT, gtv("exclude_taken_by_key" to GtvByteArray(pubKey)))
        val queuedTransactions = entities.asArray().map {
            it.toObject<EvmSubmitTxRellRequest>()
        }

        val operations = mutableListOf<OpData>()
        queuedTransactions.forEach { transaction ->

            withTxSubmitter(transaction.networkId) {
                bctx.addAfterCommitHook { it.enqueue(EvmSubmitTxRequest.fromRell(transaction)) }
                operations.add(
                    buildTxUpdateOp(transaction.rowId, RellTransactionStatus.TAKEN)
                )

                // Status TAKEN can be set multiple times due to retry - add a no op for them
                addNoOp(operations, bctx)
            }
        }
        return operations
    }

    override fun getRelevantOps(): Set<String> {
        return setOf(UPDATE_EVM_TRANSACTION_STATUS, UPDATE_EVM_TRANSACTION_RECEIPT, EVM_TX_NO_OP)
    }

    override fun init(module: GTXModule, chainID: Long, blockchainRID: BlockchainRid, cs: CryptoSystem) {
        this.module = module
        this.cryptoSystem = cs
        this.merkelHashCalculator = GtvMerkleHashCalculator(cryptoSystem)
        this.sigMaker = cs.buildSigMaker(KeyPair(KeyPairHelper.pubKey(0), KeyPairHelper.privKey(0)))
    }

    override fun needsSpecialTransaction(position: SpecialTransactionPosition): Boolean {
        return when (position) {
            SpecialTransactionPosition.Begin -> true
            SpecialTransactionPosition.End -> false
        }
    }

    private fun getTransactionBcStatus(bctx: BlockEContext, requestId: Long): RellTransactionStatus? {

        val queryResult = module.query(bctx, GET_TRANSACTION_STATUS, gtv("row_id" to gtv(requestId)))
        if (queryResult.isNull()) {
            return null
        }

        return RellTransactionStatus.valueOf(queryResult.asString())
    }

    fun addTransactionSubmitter(transactionSubmitter: TransactionSubmitter, networkId: Long) {

        transactionSubmitters[networkId] = transactionSubmitter
    }

    fun getTransactionSubmitter(networkId: Long) = transactionSubmitters[networkId]

    fun setConfig(pubKey: ByteArray, txVerificationTime: Long) {
        this.pubKey = pubKey
        this.txVerificationTime = txVerificationTime
    }

    fun cleanupDb() {
        transactionSubmitters.forEach { it.value.cleanupDb() }
    }

    private fun addNoOp(
        operations: MutableList<OpData>,
        bctx: BlockEContext
    ) {

        if (operations.none { it.opName == EVM_TX_NO_OP }) {
            operations.add(OpData(EVM_TX_NO_OP, arrayOf(gtv(bctx.height))))
        }
    }

    private fun createSignature(value: Long, status: RellTransactionStatus) =
        sigMaker.signDigest(signatureDataHash(value, status))

    private fun signatureDataHash(value: Long, status: RellTransactionStatus) =
        gtv(gtv(value), gtv(status.ordinal.toLong())).merkleHash(GtvMerkleHashCalculator(cryptoSystem))
}
