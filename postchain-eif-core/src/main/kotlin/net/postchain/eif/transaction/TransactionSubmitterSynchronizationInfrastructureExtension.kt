package net.postchain.eif.transaction

import net.postchain.PostchainContext
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
import org.web3j.tx.gas.DefaultGasProvider

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
                for ((evmBlockchainName, chainConfig) in transactionSubmitterBlockchainConfig.chains) {
                    val appConfig =
                            EvmTransactionSubmitterConfig.fromAppConfig(evmBlockchainName, postchainContext.appConfig)
                    val web3jServices = Web3jServiceFactory.buildServices(
                            appConfig.urls,
                            appConfig.connectTimeout,
                            appConfig.readTimeout,
                            appConfig.writeTimeout
                    )
                    val metrics =
                            RpcUsageMetrics(blockchainConfig.chainID, blockchainConfig.blockchainRid, chainConfig.networkId)
                    val web3jRequestHandler = Web3jRequestHandler(
                            appConfig.minRetryDelay,
                            appConfig.maxRetryDelay,
                            appConfig.maxTryErrors,
                            appConfig.urls,
                            web3jServices,
                            metrics
                    )
                    val transactionManager =
                            RawTransactionManager(web3jServices.first(), Credentials.create(appConfig.privateKey))
                    val gasProvider = DefaultGasProvider()
                    val transactionSubmitter = TransactionSubmitter(
                            web3jRequestHandler,
                            transactionManager,
                            gasProvider,
                            databaseOperations,
                            postchainContext.sharedStorage,
                            process.blockchainEngine.chainID,
                            chainConfig.networkId,
                            appConfig.txPollInterval
                    )
                    transactionSubmitters[chainConfig.networkId] = transactionSubmitter
                    ext.addTransactionSubmitter(transactionSubmitter, chainConfig.networkId)
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