package net.postchain.eif.transaction

import net.postchain.base.withWriteConnection
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.exception.UserMistake
import net.postchain.core.Storage
import net.postchain.eif.GtvToTypeMapper
import net.postchain.eif.Web3jRequestHandler
import net.postchain.gtv.Gtv
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Function
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.response.EthSendTransaction
import org.web3j.protocol.exceptions.ClientConnectionException
import org.web3j.tx.TransactionManager
import org.web3j.tx.gas.ContractGasProvider
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

class TransactionSubmitter(
        private val web3jRequestHandler: Web3jRequestHandler,
        private val transactionManager: TransactionManager,
        private val gasProvider: ContractGasProvider,
        private val databaseOperations: TransactionSubmitterDatabaseOperations,
        private val storage: Storage,
        private val chainId: Long,
        private val networkId: Long
) {

    fun sendTransaction(contractAddress: String, functionName: String, parameterTypes: List<String>, parameterValues: List<Gtv>): EthSendTransaction {
        val walletBalance = web3jRequestHandler.sendWeb3jRequest { it.ethGetBalance(transactionManager.fromAddress, DefaultBlockParameterName.LATEST) }

        val function = Function(
                functionName,
                parameterValues.mapIndexed { index, value -> GtvToTypeMapper.map(value, parameterTypes[index]) },
                emptyList<TypeReference<*>>()
        )
        val functionData = FunctionEncoder.encode(function)
        val gasPrice = gasProvider.getGasPrice(functionData)
        val gasLimit = gasProvider.getGasLimit(functionData)

        // TODO validate that this is the correct way to check balance and write a test
        try {
            if (walletBalance.balance < gasPrice * gasLimit) {
                throw UserMistake("Insufficient wallet balance")
            }

            val response = try {
                transactionManager.sendTransaction(gasPrice, gasLimit, contractAddress, functionData, BigInteger.valueOf(0))
            } catch (e: ClientConnectionException) {
                Web3jRequestHandler.logger.error("Web3j request failed: ${e.message}")
                // TODO investigate - fine to move on to next request?
                null
            } catch (e: Exception) {
                Web3jRequestHandler.logger.error("Web3j request failed unexpectedly", e)
                // TODO investigate - fine to move on to next request?
                null
            }

            if (response != null) {
                if (response.hasError()) {
                    // TODO investigate
                    val errorMessage =
                            "Web3j request failed with error code: ${response.error.code} and message: ${response.error.message}"
                    Web3jRequestHandler.logger.error(errorMessage)
                    throw ProgrammerMistake(errorMessage)
                } else {
                    withWriteConnection(storage, chainId) {
                        databaseOperations.recordTransaction(it, contractAddress, functionName, parameterTypes, parameterValues, gasPrice, gasLimit, response.transactionHash, networkId)
                        true
                    }
                    return response
                }
            }
            throw ProgrammerMistake("Failed to send web3j request")
        } catch (e: Exception) {
            withWriteConnection(storage, chainId) {
                databaseOperations.recordFailedTransaction(it, contractAddress, functionName, parameterTypes, parameterValues, gasPrice, gasLimit, e.message
                        ?: "Unknown error", networkId)
                true
            }
            throw e
        }
    }
    private val queue = LinkedBlockingQueue<EvmSubmitTransactionRequest>();
    fun enqueue(it: EvmSubmitTransactionRequest) {
        queue.offer(it)
    }

    private val completedTransactions = ConcurrentHashMap<Long, TRANSACTION_STATUS>();
    fun fetchCompletedTransactions() : Map<Long, TRANSACTION_STATUS> {
        return completedTransactions;
    }

    fun removeCompletedTransaction(rowId: Long) {
        completedTransactions.remove(rowId)
    }
}