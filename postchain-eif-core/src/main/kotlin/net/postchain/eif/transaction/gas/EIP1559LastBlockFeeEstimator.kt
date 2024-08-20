package net.postchain.eif.transaction.gas

import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.exception.UserMistake
import net.postchain.eif.web3j.Web3jRequestHandler
import net.postchain.eif.transaction.EvmSubmitTxRequest
import net.postchain.eif.transaction.TransactionSubmitter.Companion.logger
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction
import java.math.BigDecimal
import java.math.BigInteger

class EIP1559LastBlockFeeEstimator(
        private val web3jRequestHandler: Web3jRequestHandler,
        private val gasLimit: BigInteger,
        private val maxGasPrice: BigInteger,
        private val gasLimitMargin: BigDecimal,
        private val baseFeePerGasMargin: BigDecimal,
        private val priorityFeePerGasMargin: BigDecimal,
        contractAddress: String,
        functionData: String,
        fromAddress: String,
        chainId: Long,
        private val txMaxPriorityFeePerGas: BigInteger,
        private val txMaxFeePerGas: BigInteger
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
            web3jRequestHandler.ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false)
                    .block
        } catch (e: Exception) {
            val errorMessage = "Failed to get latest evm block: ${e.message}"
            logger.error(e) { errorMessage }
            throw ProgrammerMistake(errorMessage, e)
        }
        blockNumber = block.number
        baseFeePerGas = getBaseFeePerGas(block.baseFeePerGas)
        maxPriorityFeePerGas = getEstimatedMaxPriorityFeePerGas()
        maxFeePerGas = getMaxFeePerGas(baseFeePerGas, maxPriorityFeePerGas)
        estimatedTotalGasFee = estimatedGasUsage * maxFeePerGas
    }

    override fun validateRequestFees(txRequest: EvmSubmitTxRequest) {

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
            web3jRequestHandler.ethEstimateGas(transaction).amountUsed
        } catch (e: Exception) {
            val errorMessage = "Failed to estimate gas usage: ${e.message}"
            logger.error(e) { errorMessage }
            throw ProgrammerMistake(errorMessage, e)
        }
    }

    private fun getWalletBalance(fromAddress: String): BigInteger {
        return try {
            web3jRequestHandler.ethGetBalance(fromAddress, DefaultBlockParameterName.LATEST)
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
        try {
            val maxPriorityFeePerGas = web3jRequestHandler.ethMaxPriorityFeePerGas()
                    .maxPriorityFeePerGas
            val maxPriorityFeePerGasWithMargin = maxPriorityFeePerGas
                    .add(maxPriorityFeePerGas.toBigDecimal().times(priorityFeePerGasMargin).toBigInteger())
            return maxPriorityFeePerGasWithMargin.min(txMaxPriorityFeePerGas)
        } catch (e: Exception) {
            val errorMessage = "Failed to get max priority fee per gas: ${e.message}"
            logger.error(e) { errorMessage }
            throw ProgrammerMistake(errorMessage, e)
        }
    }

    private fun getBaseFeePerGas(baseFeePerGas: BigInteger): BigInteger {
        return baseFeePerGas.add(baseFeePerGas.toBigDecimal().times(baseFeePerGasMargin).toBigInteger())
    }

    private fun getMaxFeePerGas(baseFeePerGas: BigInteger, maxPriorityFeePerGas: BigInteger): BigInteger {
        val maxFeePerGas = baseFeePerGas.add(maxPriorityFeePerGas)
        return maxFeePerGas.min(txMaxFeePerGas)
    }

    private fun getEstimatedGasLimit(estimatedGasUsage: BigInteger, gasLimitMargin: BigDecimal): BigInteger {

        return estimatedGasUsage.add(estimatedGasUsage
                .toBigDecimal()
                .times(gasLimitMargin)
                .toBigInteger())
    }

    override fun toString(): String {
        return "gasLimit=$gasLimit, maxGasPrice=$maxGasPrice, gasLimitMargin=$gasLimitMargin, baseFeePerGasMargin=$baseFeePerGasMargin, priorityFeePerGasMargin=$priorityFeePerGasMargin, txMaxPriorityFeePerGas=$txMaxPriorityFeePerGas, txMaxFeePerGas=$txMaxFeePerGas, blockNumber=$blockNumber, baseFeePerGas=$baseFeePerGas, maxPriorityFeePerGas=$maxPriorityFeePerGas, maxFeePerGas=$maxFeePerGas, estimatedGasUsage=$estimatedGasUsage, estimatedGasLimit=$estimatedGasLimit, estimatedTotalGasFee=$estimatedTotalGasFee, walletBalance=$walletBalance"
    }
}
