package net.postchain.eif.transaction

import net.postchain.PostchainContext
import net.postchain.base.withReadConnection
import net.postchain.common.exception.UserMistake
import net.postchain.core.BlockchainProcess
import net.postchain.core.SynchronizationInfrastructureExtension
import net.postchain.eif.Web3jRequestHandler
import net.postchain.eif.Web3jServiceFactory
import net.postchain.eif.metrics.RpcUsageMetrics
import net.postchain.eif.transaction.config.EvmTransactionSubmitterConfig
import net.postchain.eif.transaction.config.TransactionSubmitterBlockchainConfig
import net.postchain.gtv.mapper.toObject
import net.postchain.gtx.GTXModuleAware
import org.web3j.crypto.Credentials
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.gas.StaticGasProvider
import java.math.BigInteger
import java.util.LinkedList

class TransactionSubmitterSynchronizationInfrastructureExtension(private val postchainContext: PostchainContext) : SynchronizationInfrastructureExtension {

    private val transactionSubmitters = mutableMapOf<Long, TransactionSubmitter>()

    override fun connectProcess(process: BlockchainProcess) {
        val databaseOperations = TransactionSubmitterDatabaseOperationsImpl()
        val blockchainConfig = process.blockchainEngine.getConfiguration()
        val transactionSubmitterBlockchainConfig = blockchainConfig.rawConfig["transaction_submitter"]?.toObject<TransactionSubmitterBlockchainConfig>()
                ?: throw UserMistake("No EIF config present")

        if (blockchainConfig is GTXModuleAware) {
            val exs = blockchainConfig.module.getSpecialTxExtensions()
            val ext = exs.find { it is TransactionSubmitterSpecialTxExtension }
            if (ext is TransactionSubmitterSpecialTxExtension) {

                ext.setPubKey(postchainContext.appConfig.pubKeyByteArray)

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
                    val gasProvider = StaticGasProvider(BigInteger.valueOf(networkBlockchainConfig.maxGasPrice), BigInteger.valueOf(transactionSubmitterBlockchainConfig.gasLimit))
                    val queue = LinkedList<EvmSubmitTransactionRequest>()
                    val pendingTransactions = mutableMapOf<String, EvmSubmitTransactionRequest>()
                    val completedTransactions = mutableMapOf<Long, EvmSubmitTransactionResult>()
                    withReadConnection(postchainContext.sharedStorage, process.blockchainEngine.chainID) {
                        queue.addAll(databaseOperations.getQueuedTransactions(it, networkId))
                        pendingTransactions.putAll(databaseOperations.getPendingTransactions(it, networkId))
                        completedTransactions.putAll(databaseOperations.getCompletedTransactions(it, networkId))
                    }
                    val transactionSubmitter = TransactionSubmitter(
                            web3jRequestHandler,
                            transactionManagers,
                            gasProvider,
                            databaseOperations,
                            postchainContext.sharedStorage,
                            process.blockchainEngine.chainID,
                            networkId,
                            appConfig.txPollInterval,
                            queue,
                            pendingTransactions,
                            completedTransactions,
                            BigInteger.valueOf(networkBlockchainConfig.minWalletBalance),
                            appConfig.healthCheckInterval
                    )
                    transactionSubmitters[networkId] = transactionSubmitter
                    ext.addTransactionSubmitter(transactionSubmitter, networkId)
                }
            }
        }
    }

    override fun disconnectProcess(process: BlockchainProcess) {
        transactionSubmitters.values.forEach { it.shutdown() }
    }

    override fun shutdown() {
        transactionSubmitters.values.forEach { it.shutdown() }
    }
}