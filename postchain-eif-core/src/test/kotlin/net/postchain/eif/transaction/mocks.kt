package net.postchain.eif.transaction

import net.postchain.eif.Web3jRequestHandler
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.web3j.protocol.core.methods.response.EthBlock
import org.web3j.protocol.core.methods.response.EthBlockNumber
import org.web3j.protocol.core.methods.response.EthEstimateGas
import org.web3j.protocol.core.methods.response.EthGetBalance
import org.web3j.protocol.core.methods.response.EthMaxPriorityFeePerGas
import java.math.BigInteger

fun mockWeb3jRequestHandler(
        walletBalance: Long?,
        gasUsed: Long?,
        blockNumber: BigInteger = 1.toBigInteger(),
        maxPriorityFeePerGasValue: BigInteger = 1.toBigInteger(),
        baseFeePerGas: BigInteger = 3.toBigInteger(),
): Web3jRequestHandler {

    val web3jRequestHandler = mock<Web3jRequestHandler> {
        on { ethGetBalance(any(), any()) } doAnswer {
            mock<EthGetBalance> {
                if (walletBalance == null) {
                    on { balance } doThrow (RuntimeException("Failed to get wallet balance"))
                } else {
                    on { balance } doReturn walletBalance.toBigInteger()
                }
            }
        }
        on { ethEstimateGas(any()) } doAnswer {
            mock<EthEstimateGas> {
                if (gasUsed == null) {
                    on { amountUsed } doThrow (RuntimeException("Failed to get gas estimate"))
                } else {
                        on { amountUsed } doReturn (gasUsed.toBigInteger())
                }
            }
        }
        on { ethBlockNumber() } doAnswer {
            mock<EthBlockNumber> {
                on { blockNumber } doReturn BigInteger.valueOf(123)
            }
        }
        on { ethMaxPriorityFeePerGas() } doAnswer {
            mock<EthMaxPriorityFeePerGas> {
                on { maxPriorityFeePerGas } doReturn maxPriorityFeePerGasValue
            }
        }
        on { ethGetBlockByNumber(any(), any()) } doAnswer {
            mock<EthBlock> {
                val ethBlock = EthBlock()
                val resultBlock = EthBlock.Block()
                resultBlock.setNumber("$blockNumber")
                resultBlock.setBaseFeePerGas("$baseFeePerGas")
                ethBlock.result = resultBlock
                on { block } doReturn ethBlock.block
            }
        }
    }
    return web3jRequestHandler
}