package net.postchain.eif.transaction.gas

import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.exception.UserMistake
import net.postchain.eif.Web3jRequestHandler
import net.postchain.eif.transaction.EvmSubmitTxRequest
import net.postchain.eif.transaction.TransactionSubmitter.Companion.logger
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.core.methods.response.EthGetBalance
import java.math.BigInteger

class EIP1559LastBlockFeeEstimator(
        private val web3jRequestHandler: Web3jRequestHandler,
        val gasLimit: BigInteger,
        val maxGasPrice: BigInteger,
) : EIP1559FeeEstimator {

    override val blockNumber: BigInteger
    override val baseFeePerGas: BigInteger
    override val maxPriorityFeePerGas: BigInteger
    override val maxFeePerGas: BigInteger

    init {

        val block = try {
            web3jRequestHandler.sendWeb3jRequest { it.ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false) }
                .block
        } catch (e: Exception) {
            val errorMessage = "Failed to get latest evm block: ${e.message}"
            logger.error(e) { errorMessage }
            throw ProgrammerMistake(errorMessage, e)
        }
        blockNumber = block.number
        baseFeePerGas = block.baseFeePerGas
        maxPriorityFeePerGas = getEstimatedMaxPriorityFeePerGas()
        maxFeePerGas = baseFeePerGas.add(maxPriorityFeePerGas)
    }

    override fun validateRequestFees(request: EvmSubmitTxRequest) {

        if (request.maxFeePerGas < baseFeePerGas) {
            throw UserMistake("Max fee per gas less than block base fee. maxFeePerGas: ${request.maxFeePerGas} evm baseFeePerGas: $baseFeePerGas")
        }

        if (request.maxFeePerGas < maxFeePerGas) {
            logger.warn { "Transaction ${request.rowId} fee might be too low. maxFeePerGas: ${request.maxFeePerGas} estimated require: $maxFeePerGas" }
        }

        if (request.maxPriorityFeePerGas < maxPriorityFeePerGas) {
            logger.warn { "Transaction ${request.rowId} fee might be too low. maxPriorityFeePerGas: ${request.maxPriorityFeePerGas} estimated require: $maxPriorityFeePerGas" }
        }
    }

    override fun estimateAndValidateRequestGasAndBalance(
            txRequest: EvmSubmitTxRequest,
            functionData: String,
            fromAddress: String,
            chainId: Long
    ) {

        val estimatedGasUsage =
                try {
                    getEstimatedGasUsage(gasLimit, txRequest.contractAddress, functionData, fromAddress, chainId)
                } catch (e: Exception) {
                    val errorMessage = "Failed to get estimated gas usage for request id ${txRequest.rowId}: ${e.message}"
                    logger.error(e) { errorMessage }
                    throw ProgrammerMistake(errorMessage, e)
                }
        val estimatedTotalGasFee = estimatedGasUsage * maxFeePerGas

        if (estimatedGasUsage > gasLimit) {
            throw UserMistake("Estimated gas usage $estimatedGasUsage for tx ${txRequest.rowId} exceeds configured limit of $gasLimit")
        }

        if (estimatedTotalGasFee > maxGasPrice) {
            throw UserMistake("Estimated total gas fee $estimatedTotalGasFee for tx exceeds configured limit of $maxGasPrice")
        }

        val walletBalance = getWalletBalance(fromAddress)

        if (walletBalance.balance < estimatedTotalGasFee) {
            throw UserMistake("Insufficient wallet balance (estimatedTotalGasFee: $estimatedTotalGasFee, wallet balance: ${walletBalance.balance}")
        }
    }

    private fun getEstimatedGasUsage(
            gasLimit: BigInteger,
            contractAddress: String,
            functionData: String,
            fromAddress: String,
            chainId: Long
    ): BigInteger {
        val transaction = Transaction(
                fromAddress,
                BigInteger.ZERO,
                null,
                gasLimit,
                "0x$contractAddress",
                BigInteger.ZERO,
                functionData,
                chainId,
                maxPriorityFeePerGas,
                maxFeePerGas
        )
        return try {
            web3jRequestHandler.sendWeb3jRequest {
                it.ethEstimateGas(transaction)
            }.amountUsed
        } catch (e: Exception) {
            val errorMessage = "Failed to estimate gas usage: ${e.message}"
            logger.error(e) { errorMessage }
            throw ProgrammerMistake(errorMessage, e)
        }
    }
    
    private fun getWalletBalance(fromAddress: String): EthGetBalance {
        return try {
            web3jRequestHandler.sendWeb3jRequest { it.ethGetBalance(fromAddress, DefaultBlockParameterName.LATEST) }
        } catch (e: Exception) {
            val errorMessage = "Failed to get balance for request id: ${e.message}"
            logger.error(e) { errorMessage }
            throw ProgrammerMistake(errorMessage, e)
        }
    }

    private fun getEstimatedMaxPriorityFeePerGas(): BigInteger {
        // estimate of how much you can pay as a priority fee to get a transaction included in the current block.
        // https://docs.alchemy.com/reference/eth-maxpriorityfeepergas
        return try {
            web3jRequestHandler.sendWeb3jRequest { it.ethMaxPriorityFeePerGas() }
                    .maxPriorityFeePerGas
        } catch (e: Exception) {
            val errorMessage = "Failed to get max priority fee per gas: ${e.message}"
            logger.error(e) { errorMessage }
            throw ProgrammerMistake(errorMessage, e)
        }
    }
}