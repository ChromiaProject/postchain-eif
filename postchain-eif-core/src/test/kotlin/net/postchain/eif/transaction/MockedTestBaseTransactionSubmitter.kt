package net.postchain.eif.transaction

import net.postchain.core.EContext
import net.postchain.core.Storage
import net.postchain.devtools.IntegrationTestSetup
import net.postchain.eif.Web3jRequestHandler
import org.junit.jupiter.api.BeforeEach
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
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
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt
import org.web3j.protocol.core.methods.response.EthSendTransaction
import org.web3j.protocol.core.methods.response.EthTransaction
import org.web3j.protocol.core.methods.response.Transaction
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.tx.TransactionManager
import org.web3j.tx.gas.ContractGasProvider
import java.math.BigInteger
import java.util.Optional
import java.util.concurrent.LinkedBlockingQueue
import kotlin.reflect.KClass
import kotlin.reflect.jvm.ExperimentalReflectionOnLambdas
import kotlin.reflect.jvm.reflect

open class MockedTestBaseTransactionSubmitter : IntegrationTestSetup() {

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

    @OptIn(ExperimentalReflectionOnLambdas::class)
    fun <T : Response<*>> mockWeb3jRequest(
        web3jRequestHandler: Web3jRequestHandler,
        kClass: KClass<T>,
        mock: T
    ) {

        Mockito.`when`(web3jRequestHandler.sendWeb3jRequest<T>(argThat {arg ->
            arg != null && arg.reflect()!!.returnType.arguments[1].type!!.classifier == kClass
        })).doAnswer {
            mock
        }
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

    fun mockTransactionReceiptResponse(blockNumberValue: Long, statusOk: Boolean): EthGetTransactionReceipt {
        val mockkTransactionReceipt = mockTransactionReceipt(blockNumberValue, statusOk)
        return mock<EthGetTransactionReceipt> {
            on { transactionReceipt } doReturn (Optional.of(mockkTransactionReceipt))
        }
    }

    fun mockTransactionReceipt(blockNumberValue: Long, statusOk: Boolean): TransactionReceipt {
        return mock<TransactionReceipt> {
            on { transactionHash } doReturn "0x0000000000000000000000000000000000000000000000000000000000000000"
            on { blockHash } doReturn "0x0000000000000000000000000000000000000000000000000000000000001100"
            on { blockNumber } doReturn BigInteger.valueOf(blockNumberValue)
            on { effectiveGasPrice } doReturn "0xf4610900"
            on { gasUsed } doReturn BigInteger.valueOf(58575)
            on { isStatusOK } doReturn statusOk
        }
    }

    fun mockEthTransactionResponse(toAddress: String, input: String): EthTransaction {
        val mockTransaction = mockTransaction(toAddress, input)
        return mock<EthTransaction> {
            on { transaction } doReturn (Optional.of(mockTransaction))
        }
    }

    fun mockTransaction(toAddress: String, inputValue: String): Transaction {
        return mock<Transaction> {
            on { to } doReturn toAddress
            on { input } doReturn inputValue
        }
    }

    fun createTransactionSubmitter(
        web3jRequestHandler: Web3jRequestHandler,
        transactionManagers: Map<String, TransactionManager>,
        gasProvider: ContractGasProvider
    ): TransactionSubmitter {
        return TransactionSubmitter(
            web3jRequestHandler,
            transactionManagers,
            gasProvider,
            databaseOperations,
            storage,
            0,
            0,
            Long.MAX_VALUE,
            LinkedBlockingQueue(),
            mutableMapOf(),
            BigInteger.valueOf(10),
            Long.MAX_VALUE,
            24 * 60 * 60000,
            1000 * 60 * 4,
            5,
            0
        )
    }
}