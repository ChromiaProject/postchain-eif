package net.postchain.eif.transaction.gas

import net.postchain.eif.Web3jRequestHandler
import net.postchain.eif.transaction.EvmSubmitTxRequest
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.methods.response.EthBlock
import org.web3j.protocol.core.methods.response.EthEstimateGas
import org.web3j.protocol.core.methods.response.EthGetBalance
import org.web3j.protocol.core.methods.response.EthMaxPriorityFeePerGas
import java.math.BigInteger
import kotlin.reflect.jvm.ExperimentalReflectionOnLambdas
import kotlin.reflect.jvm.reflect

@OptIn(ExperimentalReflectionOnLambdas::class)
class EIP1559LastBlockFeeEstimatorMock(
        override val blockNumber: BigInteger = 1.toBigInteger(),
        override val baseFeePerGas: BigInteger,
        override val maxPriorityFeePerGas: BigInteger,
        override val maxFeePerGas: BigInteger,
        val gasUsed: Long? = 1L,
        val gasLimit: Long,
        val maxGasPrice: Long,
        val walletBalance: Long? = 10000000L
) : EIP1559FeeEstimator {

    private val feeEstimator: EIP1559LastBlockFeeEstimator

    init {

        val web3jRequestHandler = mock<Web3jRequestHandler> {
            on {
                sendWeb3jRequest(argThat<(Web3j) -> Request<*, EthEstimateGas>> { it ->
                    it != null && it.reflect()!!.returnType.arguments[1].type!!.classifier == EthEstimateGas::class
                })
            } doAnswer {
                mock<EthEstimateGas> {
                    if (gasUsed == null) {
                        on { amountUsed } doThrow (RuntimeException("Failed to get gas estimate"))
                    } else {
                        on { amountUsed } doReturn (gasUsed.toBigInteger())
                    }
                }
            }
            on {
                sendWeb3jRequest(argThat<(Web3j) -> Request<*, EthMaxPriorityFeePerGas>> { it ->
                    it != null && it.reflect()!!.returnType.arguments[1].type!!.classifier == EthMaxPriorityFeePerGas::class
                })
            } doAnswer {
                mock<EthMaxPriorityFeePerGas> {
                    on { maxPriorityFeePerGas } doReturn maxPriorityFeePerGas
                }
            }
            on {
                sendWeb3jRequest(argThat<(Web3j) -> Request<*, EthBlock>> { it ->
                    it != null && it.reflect()!!.returnType.arguments[1].type!!.classifier == EthBlock::class
                })
            } doAnswer {
                mock<EthBlock> {
                    val ethBlock = EthBlock()
                    val resultBlock = EthBlock.Block()
                    resultBlock.setNumber("$blockNumber")
                    resultBlock.setBaseFeePerGas("$baseFeePerGas")
                    ethBlock.result = resultBlock
                    on { block } doReturn ethBlock.block
                }
            }
            on {
                sendWeb3jRequest(argThat<(Web3j) -> Request<*, EthGetBalance>> { it ->
                    it != null && it.reflect()!!.returnType.arguments[1].type!!.classifier == EthGetBalance::class
                })
            } doAnswer {
                mock<EthGetBalance> {
                    if (walletBalance == null) {
                        on { balance } doThrow (RuntimeException("Failed to get wallet balance"))
                    } else {
                        on { balance } doReturn (walletBalance.toBigInteger())
                    }
                }
            }
        }

        feeEstimator = EIP1559LastBlockFeeEstimator(web3jRequestHandler, gasLimit.toBigInteger(), maxGasPrice.toBigInteger())
    }

    override fun validateRequestFees(request: EvmSubmitTxRequest) {
        feeEstimator.validateRequestFees(request)
    }

    override fun estimateAndValidateRequestGasAndBalance(
            txRequest: EvmSubmitTxRequest,
            functionData: String,
            fromAddress: String,
            chainId: Long
    ) {
        feeEstimator.estimateAndValidateRequestGasAndBalance(txRequest, functionData, fromAddress, chainId)
    }
}