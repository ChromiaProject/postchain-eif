package net.postchain.eif.transaction

import net.postchain.core.EContext
import net.postchain.core.Storage
import net.postchain.devtools.IntegrationTestSetup
import net.postchain.eif.Web3jRequestHandler
import org.junit.jupiter.api.BeforeEach
import org.mockito.ArgumentMatchers
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.Response
import org.web3j.protocol.core.methods.response.EthEstimateGas
import org.web3j.protocol.core.methods.response.EthGetBalance
import org.web3j.protocol.core.methods.response.EthSendTransaction
import org.web3j.tx.TransactionManager
import org.web3j.tx.gas.ContractGasProvider
import java.math.BigInteger
import kotlin.reflect.jvm.ExperimentalReflectionOnLambdas
import kotlin.reflect.jvm.reflect

open class MockedBaseTransactionSubmitterTest : IntegrationTestSetup() {

    lateinit var storage: Storage
    lateinit var databaseOperations: TransactionSubmitterDatabaseOperations

    @BeforeEach
    fun setup() {

        storage = mock<Storage> {
            on { openWriteConnection(ArgumentMatchers.anyLong()) } doAnswer {
                mock<EContext>()
            }
        }

        databaseOperations = mock<TransactionSubmitterDatabaseOperations>()
    }

    fun mockGasProvider(gasPrice: Long, gasLimit: Long): ContractGasProvider {
        val gasProvider = mock<ContractGasProvider> {
            on { getGasPrice(ArgumentMatchers.anyString()) } doReturn (BigInteger.valueOf(gasPrice))
            on { getGasLimit(ArgumentMatchers.anyString()) } doReturn (BigInteger.valueOf(gasLimit))
        }
        return gasProvider
    }

    @OptIn(ExperimentalReflectionOnLambdas::class)
    fun mockBalanceAndEstimateGas(balanceValue: Long, amountUsedValue: Long?): Web3jRequestHandler {

        val web3jRequestHandler = mock<Web3jRequestHandler> {
            on {
                sendWeb3jRequest(argThat<(Web3j) -> Request<*, EthGetBalance>> { it ->
                    it != null && it.reflect()!!.returnType.arguments[1].type!!.classifier == EthGetBalance::class
                })
            } doAnswer {
                mock<EthGetBalance> {
                    on { balance } doReturn (BigInteger.valueOf(balanceValue))
                }
            }
            on {
                sendWeb3jRequest(argThat<(Web3j) -> Request<*, EthEstimateGas>> { it ->
                    it != null && it.reflect()!!.returnType.arguments[1].type!!.classifier == EthEstimateGas::class
                })
            } doAnswer {
                mock<EthEstimateGas> {
                    if (amountUsedValue == null) {
                        on { amountUsed } doThrow(RuntimeException("Failed to get gas estimate"))
                    } else {
                        on { amountUsed } doReturn (BigInteger.valueOf(amountUsedValue))
                    }
                }
            }
        }
        return web3jRequestHandler
    }

    fun createTransactionManager(
        url: String,
        fromAddress: String,
        exception: String? = null,
        hasErrorMsg: String? = null
    ): Pair<String, TransactionManager> {

        val transactionManager = mock<TransactionManager> {
            on { getFromAddress() } doReturn (fromAddress)
            if (exception != null) {
                on {
                    sendTransaction(
                        any(),
                        any(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString(),
                        any()
                    )
                } doThrow (RuntimeException(exception))
            }
            if (hasErrorMsg != null) {
                val result = mock<EthSendTransaction> {
                    on { getTransactionHash() } doReturn "0xtranshash"
                    on { hasError() } doReturn (true)
                    on { getError() } doReturn (Response.Error(404, "Not found"))
                }
                on {
                    sendTransaction(
                        any(),
                        any(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString(),
                        any()
                    )
                } doReturn (result)
            }
        }
        return Pair(url, transactionManager)
    }
}