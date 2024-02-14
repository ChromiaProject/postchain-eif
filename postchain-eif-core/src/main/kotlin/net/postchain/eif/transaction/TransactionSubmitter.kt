package net.postchain.eif.transaction

import net.postchain.common.exception.ProgrammerMistake
import net.postchain.eif.GtvToTypeMapper
import net.postchain.eif.Web3jRequestHandler
import net.postchain.gtv.Gtv
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Function
import org.web3j.protocol.core.methods.response.EthSendTransaction
import org.web3j.protocol.exceptions.ClientConnectionException
import org.web3j.tx.TransactionManager
import org.web3j.tx.gas.ContractGasProvider
import java.math.BigInteger

class TransactionSubmitter(private val transactionManager: TransactionManager, private val gasProvider: ContractGasProvider) {

    fun sendTransaction(contractAddress: String, functionName: String, parameterTypes: List<String>, parameterValues: List<Gtv>): EthSendTransaction {

        val function = Function(
                functionName,
                parameterValues.mapIndexed { index, value -> GtvToTypeMapper.map(value, parameterTypes[index]) },
                emptyList<TypeReference<*>>()
        )
        val response = try {
            val functionData = FunctionEncoder.encode(function)
            val gasPrice = gasProvider.getGasPrice(functionData)
            val gasLimit = gasProvider.getGasLimit(functionData)
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
                return response
            }
        }
        throw ProgrammerMistake("Failed to send web3j request")
    }
}