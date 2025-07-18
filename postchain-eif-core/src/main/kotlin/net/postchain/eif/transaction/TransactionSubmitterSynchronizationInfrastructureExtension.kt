package net.postchain.eif.transaction

import mu.withLoggingContext
import net.postchain.PostchainContext
import net.postchain.base.data.DatabaseAccess
import net.postchain.base.withReadConnection
import net.postchain.base.withReadWriteConnection
import net.postchain.common.BlockchainRid
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.exception.UserMistake
import net.postchain.core.BlockchainProcess
import net.postchain.core.EContext
import net.postchain.core.NODE_ID_READ_ONLY
import net.postchain.core.SynchronizationInfrastructureExtension
import net.postchain.eif.metrics.NETWORK_ID_TAG
import net.postchain.eif.metrics.RpcUsageMetrics
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.GET_TRANSACTION
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.logger
import net.postchain.eif.transaction.anchoring.EvmAnchoringSpecialTxExtension
import net.postchain.eif.transaction.anchoring.EvmAnchoringSpecialTxExtension.Companion.GET_SYSTEM_ANCHORING_BLOCKCHAIN_RID_QUERY
import net.postchain.eif.transaction.config.EvmTransactionSubmitterConfig
import net.postchain.eif.transaction.config.TransactionSubmitterBlockchainConfig
import net.postchain.eif.transaction.gas.EIP1559FeeEstimatorFactory
import net.postchain.eif.transaction.signerupdate.EvmSignerUpdateSpecialTxExtension
import net.postchain.eif.web3j.Web3jRawTransactionHandler
import net.postchain.eif.web3j.Web3jRequestHandler
import net.postchain.eif.web3j.Web3jServiceFactory
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.mapper.toObject
import net.postchain.gtx.GTXModule
import net.postchain.gtx.GTXModuleAware
import java.math.BigInteger

class TransactionSubmitterSynchronizationInfrastructureExtension(private val postchainContext: PostchainContext) : SynchronizationInfrastructureExtension {

    private val transactionSubmitters = mutableMapOf<Long, TransactionSubmitter>()

    override fun connectProcess(process: BlockchainProcess) {
        val databaseOperations = TransactionSubmitterDatabaseOperationsImpl()
        val blockchainConfig = process.blockchainEngine.getConfiguration()
        val transactionSubmitterBlockchainConfig = blockchainConfig.rawConfig["transaction_submitter"]?.toObject<TransactionSubmitterBlockchainConfig>()
                ?: throw UserMistake("No EIF config present")
        val nodeIsReplica = blockchainConfig.blockchainContext.nodeID == NODE_ID_READ_ONLY

        if (blockchainConfig is GTXModuleAware) {
            val exs = blockchainConfig.module.getSpecialTxExtensions()
            val ext = exs.find { it is TransactionSubmitterSpecialTxExtension }
            if (ext is TransactionSubmitterSpecialTxExtension) {

                ext.setConfig(
                        postchainContext.appConfig.privKeyByteArray,
                        postchainContext.appConfig.pubKeyByteArray,
                        validateSpecialOps = process::isSigner,
                        pollEvmReceipts = !nodeIsReplica,
                        blockQueries = process.blockchainEngine.getBlockQueries(),
                )

                // Do not start the transaction submitter if we are a replica node
                if (!nodeIsReplica) {
                    for ((evmBlockchainName, networkBlockchainConfig) in transactionSubmitterBlockchainConfig.chains) {
                        val networkId = networkBlockchainConfig.networkId
                        val appConfig =
                                EvmTransactionSubmitterConfig.fromAppConfig(evmBlockchainName, postchainContext.appConfig)
                        val web3jServicesMap = Web3jServiceFactory.buildServicesMap(
                                appConfig.urls,
                                appConfig.connectTimeout,
                                appConfig.readTimeout,
                                appConfig.writeTimeout,
                                blockchainConfig.blockchainRid
                        )
                        val metrics =
                                RpcUsageMetrics(blockchainConfig.chainID, blockchainConfig.blockchainRid, networkId)
                        val web3jRequestHandler = Web3jRequestHandler(
                                appConfig.minRetryDelay,
                                appConfig.maxRetryDelay,
                                appConfig.maxTryErrors,
                                appConfig.urls,
                                web3jServicesMap.map { it.value },
                                metrics
                        )

                        val transactionHandler = Web3jRawTransactionHandler(web3jServicesMap, appConfig.privateKey)
                        val queue = loadTxQueue(process.blockchainEngine.chainID, databaseOperations, networkId, blockchainConfig.module)
                        val feeEstimatorFactory = EIP1559FeeEstimatorFactory(
                                web3jRequestHandler,
                                BigInteger.valueOf(networkBlockchainConfig.gasLimit),
                                BigInteger.valueOf(networkBlockchainConfig.maxGasPrice),
                                networkBlockchainConfig.gasLimitMargin,
                                networkBlockchainConfig.baseFeePerGasMargin,
                                networkBlockchainConfig.priorityFeePerGasMargin
                        )
                        val transactionSubmitter = TransactionSubmitter(
                                web3jRequestHandler,
                                transactionHandler,
                                feeEstimatorFactory,
                                databaseOperations,
                                postchainContext.sharedStorage,
                                process.blockchainEngine.chainID,
                                networkId,
                                appConfig.txPollInterval,
                                queue,
                                BigInteger.valueOf(networkBlockchainConfig.minWalletBalance),
                                appConfig.healthCheckInterval,
                                networkBlockchainConfig.nodeTxVerificationEvmBlocks,
                                networkBlockchainConfig.txVerificationTime,
                                networkBlockchainConfig.cancelFeeMargin,
                        )
                        transactionSubmitters[networkId] = transactionSubmitter
                        ext.addTransactionSubmitter(transactionSubmitter, networkId)
                    }
                }
            }

            val anchoringExt = exs.find { it is EvmAnchoringSpecialTxExtension }
            if (anchoringExt is EvmAnchoringSpecialTxExtension) {
                anchoringExt.blockQueriesProvider = postchainContext.blockQueriesProvider
                anchoringExt.systemAnchoringBrid = withReadConnection(postchainContext.sharedStorage, process.blockchainEngine.chainID) {
                    val response = blockchainConfig.module.query(it, GET_SYSTEM_ANCHORING_BLOCKCHAIN_RID_QUERY, gtv(mapOf()))
                    if (response.isNull()) {
                        null
                    } else {
                        BlockchainRid(response.asByteArray())
                    }
                }
            }

            val signerUpdateExt = exs.find { it is EvmSignerUpdateSpecialTxExtension }
            if (signerUpdateExt is EvmSignerUpdateSpecialTxExtension) {
                signerUpdateExt.blockQueriesProvider = postchainContext.blockQueriesProvider
                signerUpdateExt.directoryChainBrid = withReadConnection(postchainContext.sharedStorage, 0L) {
                    val db = DatabaseAccess.of(it)
                    db.getBlockchainRid(it) ?: throw ProgrammerMistake("No blockchain-rid found for chain 0")
                }
            }
        }
    }

    private fun loadTxQueue(chainID: Long, databaseOperations: TransactionSubmitterDatabaseOperationsImpl, networkId: Long, module: GTXModule): Collection<EvmSubmitTxRequest> {
        val queue = mutableListOf<EvmSubmitTxRequest>()
        withReadWriteConnection(postchainContext.sharedStorage, chainID) {
            for (queuedTransaction in databaseOperations.getQueuedTransactions(it, networkId)) {

                withLoggingContext(NETWORK_ID_TAG to queuedTransaction.networkId.toString()) {

                    if (txTakenByThisNode(module, it, queuedTransaction.rowId)) {
                        queue.add(queuedTransaction)
                    } else {
                        logger.info { "Transaction ${queuedTransaction.rowId} is no longer processed by this node" }
                        databaseOperations.removeTransaction(it, queuedTransaction.rowId)
                    }
                }
            }
        }
        return queue
    }

    override fun disconnectProcess(process: BlockchainProcess) {
        transactionSubmitters.values.forEach { it.shutdown() }
    }

    override fun shutdown() {
        transactionSubmitters.values.forEach { it.shutdown() }
    }

    private fun txTakenByThisNode(module: GTXModule, eContext: EContext, requestId: Long): Boolean {

        val queryResult = module.query(eContext, GET_TRANSACTION, gtv("row_id" to gtv(requestId)))
        if (queryResult.isNull()) {
            return false
        }

        val status = RellTransactionStatus.valueOf(queryResult["status"]!!.asString())
        val processedBy = queryResult["processed_by"]

        return status == RellTransactionStatus.TAKEN &&
                processedBy != null &&
                !processedBy.isNull() &&
                postchainContext.appConfig.pubKeyByteArray.contentEquals(processedBy.asByteArray())
    }
}