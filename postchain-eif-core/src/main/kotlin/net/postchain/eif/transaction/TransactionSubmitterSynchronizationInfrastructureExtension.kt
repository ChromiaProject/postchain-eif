package net.postchain.eif.transaction

import net.postchain.PostchainContext
import net.postchain.core.BlockchainProcess
import net.postchain.core.SynchronizationInfrastructureExtension
import net.postchain.eif.Web3jRequestHandler
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.gas.DefaultGasProvider

class TransactionSubmitterSynchronizationInfrastructureExtension(private val postchainContext: PostchainContext) : SynchronizationInfrastructureExtension {
    override fun connectProcess(process: BlockchainProcess) {
        val databaseOperations = TransactionSubmitterDatabaseOperationsImpl()
        val web3jRequestHandler = Web3jRequestHandler()
        val transactionManager = RawTransactionManager()
        val gasProvider = DefaultGasProvider()
        val transactionSubmitter = TransactionSubmitter(
                transactionManager,
                gasProvider,
                databaseOperations,
                postchainContext.sharedStorage,
                process.blockchainEngine.chainID
        )
    }

    override fun disconnectProcess(process: BlockchainProcess) {
        TODO("Not yet implemented")
    }

    override fun shutdown() {
        TODO("Not yet implemented")
    }
}