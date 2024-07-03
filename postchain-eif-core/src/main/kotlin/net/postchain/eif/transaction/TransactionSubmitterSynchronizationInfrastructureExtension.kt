package net.postchain.eif.transaction

import net.postchain.PostchainContext
import net.postchain.base.data.DatabaseAccess
import net.postchain.base.withReadConnection
import net.postchain.base.withReadWriteConnection
import net.postchain.common.BlockchainRid
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.exception.UserMistake
import net.postchain.core.BlockchainProcess
import net.postchain.core.NODE_ID_READ_ONLY
import net.postchain.core.SynchronizationInfrastructureExtension
import net.postchain.eif.Web3jRequestHandler
import net.postchain.eif.Web3jServiceFactory
import net.postchain.eif.metrics.RpcUsageMetrics
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.GET_TRANSACTION
import net.postchain.eif.transaction.anchoring.EvmAnchoringSpecialTxExtension
import net.postchain.eif.transaction.anchoring.EvmAnchoringSpecialTxExtension.Companion.GET_SYSTEM_ANCHORING_BLOCKCHAIN_RID_QUERY
import net.postchain.eif.transaction.config.EvmTransactionSubmitterConfig
import net.postchain.eif.transaction.config.TransactionSubmitterBlockchainConfig
import net.postchain.eif.transaction.signerupdate.EvmSignerUpdateSpecialTxExtension
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.mapper.toObject
import net.postchain.gtx.GTXModule
import net.postchain.gtx.GTXModuleAware
import net.postchain.core.EContext
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.logger
import net.postchain.eif.transaction.gas.EIP1559FeeEstimatorFactory
import org.web3j.crypto.Credentials
import org.web3j.tx.RawTransactionManager
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
                        !nodeIsReplica
                )

                // Create the tx submitters only if we build blocks
                if (!nodeIsReplica) {
                    for ((evmBlockchainName, networkBlockchainConfig) in transactionSubmitterBlockchainConfig.chains) {
                        val networkId = networkBlockchainConfig.networkId
                        val appConfig =
                                EvmTransactionSubmitterConfig.fromAppConfig(evmBlockchainName, postchainContext.appConfig)
                        val web3jServicesMap = Web3jServiceFactory.buildServicesMap(
                                appConfig.urls,
                                appConfig.connectTimeout,
                                appConfig.readTimeout,
                                appConfig.writeTimeout
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

                        val credentials = Credentials.create(appConfig.privateKey)
                        val transactionManagers =
                                web3jServicesMap.map { it.key to RawTransactionManager(it.value, credentials) }
                                        .toMap()
                        val queue = loadTxQueue(process.blockchainEngine.chainID, databaseOperations, networkId, blockchainConfig.module)
                        val feeEstimatorFactory = EIP1559FeeEstimatorFactory(web3jRequestHandler,
                                BigInteger.valueOf(networkBlockchainConfig.gasLimit),
                                BigInteger.valueOf(networkBlockchainConfig.maxGasPrice))
                        val transactionSubmitter = TransactionSubmitter(
                                web3jRequestHandler,
                                transactionManagers,
                                feeEstimatorFactory,
                                databaseOperations,
                                postchainContext.sharedStorage,
                                process.blockchainEngine.chainID,
                                networkId,
                                appConfig.txPollInterval,
                                queue,
                                BigInteger.valueOf(networkBlockchainConfig.minWalletBalance),
                                appConfig.healthCheckInterval,
                                transactionSubmitterBlockchainConfig.nodeTxVerificationTimeout,
                                networkBlockchainConfig.nodeTxVerificationEvmBlocks,
                                networkBlockchainConfig.txVerificationTime,
                        )
                        transactionSubmitters[networkId] = transactionSubmitter
                        ext.addTransactionSubmitter(transactionSubmitter, networkId)
                    }

                    withReadConnection(postchainContext.sharedStorage, process.blockchainEngine.chainID) {
                        ext.addNewPendingTransactions(it)
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

                if (txTakenByThisNode(module, it, queuedTransaction.rowId)) {
                    queue.add(queuedTransaction)
                } else {
                    logger.info { "Transaction ${queuedTransaction.rowId} is no longer processed by this node" }
                    databaseOperations.removeTransaction(it, queuedTransaction.rowId)
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