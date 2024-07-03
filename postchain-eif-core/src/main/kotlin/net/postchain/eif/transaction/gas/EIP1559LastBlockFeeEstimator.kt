package net.postchain.eif.transaction.gas

import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.exception.UserMistake
import net.postchain.eif.Web3jRequestHandler
import net.postchain.eif.transaction.EvmSubmitTxRequest
import net.postchain.eif.transaction.TransactionSubmitter.Companion.logger
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction
import java.math.BigDecimal
import java.math.BigInteger

class EIP1559LastBlockFeeEstimator(
        private val web3jRequestHandler: Web3jRequestHandler,
        val gasLimit: BigInteger,
        val maxGasPrice: BigInteger,
        val gasLimitMargin: BigDecimal,
        contractAddress: String,
        functionData: String,
        fromAddress: String,
        chainId: Long
) : EIP1559FeeEstimator {

    override val blockNumber: BigInteger
    override val baseFeePerGas: BigInteger
    override val maxPriorityFeePerGas: BigInteger
    override val maxFeePerGas: BigInteger
    override val estimatedGasUsage = getEstimatedGasUsage(gasLimit, contractAddress, functionData, fromAddress, chainId)
    override val estimatedGasLimit = getEstimatedGasLimit(estimatedGasUsage, gasLimitMargin)

    override val estimatedTotalGasFee: BigInteger

    private val walletBalance = getWalletBalance(fromAddress)

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
        estimatedTotalGasFee = estimatedGasUsage * maxFeePerGas
    }

    override fun validateRequestFees(txRequest: EvmSubmitTxRequest) {

        if (txRequest.maxFeePerGas < baseFeePerGas) {
            throw UserMistake("Max fee per gas less than block base fee. maxFeePerGas: ${txRequest.maxFeePerGas} evm baseFeePerGas: $baseFeePerGas")
        }

        if (txRequest.maxFeePerGas < maxFeePerGas) {
            logger.warn { "Transaction ${txRequest.rowId} fee might be too low. maxFeePerGas: ${txRequest.maxFeePerGas} estimated require: $maxFeePerGas" }
        }

        if (txRequest.maxPriorityFeePerGas < maxPriorityFeePerGas) {
            logger.warn { "Transaction ${txRequest.rowId} fee might be too low. maxPriorityFeePerGas: ${txRequest.maxPriorityFeePerGas} estimated require: $maxPriorityFeePerGas" }
        }

        if (estimatedGasUsage > gasLimit) {
            throw UserMistake("Estimated gas usage $estimatedGasUsage for tx ${txRequest.rowId} exceeds configured limit of $gasLimit")
        }

        if (estimatedTotalGasFee > maxGasPrice) {
            throw UserMistake("Estimated total gas fee $estimatedTotalGasFee for tx exceeds configured limit of $maxGasPrice")
        }

        if (walletBalance < estimatedTotalGasFee) {
            throw UserMistake("Insufficient wallet balance (estimatedTotalGasFee: $estimatedTotalGasFee, wallet balance: $walletBalance")
        }

        if (estimatedGasLimit > gasLimit) {
            throw UserMistake("Estimated gas limit $estimatedGasLimit exceeds configured gas limit $gasLimit")
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

    private fun getWalletBalance(fromAddress: String): BigInteger {
        return try {
            web3jRequestHandler.sendWeb3jRequest { it.ethGetBalance(fromAddress, DefaultBlockParameterName.LATEST) }
                    .balance
        } catch (e: Exception) {
            val errorMessage = "Failed to get balance for request: ${e.message}"
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

    private fun getEstimatedGasLimit(estimatedGasUsage: BigInteger, gasLimitMargin: BigDecimal): BigInteger {

        return estimatedGasUsage.add(estimatedGasUsage
                .toBigDecimal()
                .times(gasLimitMargin)
                .toBigInteger())
    }

    override fun toString(): String {
        return "gasLimit=$gasLimit, maxGasPrice=$maxGasPrice, blockNumber=$blockNumber, baseFeePerGas=$baseFeePerGas, maxPriorityFeePerGas=$maxPriorityFeePerGas, maxFeePerGas=$maxFeePerGas, estimatedGasUsage=$estimatedGasUsage, estimatedTotalGasFee=$estimatedTotalGasFee, estimatedGasLimit=$estimatedGasLimit, gasLimitMargin=$gasLimitMargin, walletBalance=$walletBalance"
    }
}