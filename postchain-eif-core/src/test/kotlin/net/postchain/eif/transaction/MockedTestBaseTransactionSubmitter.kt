package net.postchain.eif.transaction

import net.postchain.core.EContext
import net.postchain.core.Storage
import net.postchain.devtools.IntegrationTestSetup
import net.postchain.eif.TestLogAppender
import net.postchain.eif.Web3jRequestHandler
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.GET_TRANSACTION
import net.postchain.eif.transaction.gas.EIP1559FeeEstimatorFactory
import net.postchain.eif.transaction.gas.EIP1559LastBlockFeeEstimatorMock
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtx.GTXModule
import org.apache.logging.log4j.Level
import org.junit.jupiter.api.BeforeEach
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.web3j.protocol.core.Response
import org.web3j.protocol.core.methods.response.EthBlockNumber
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt
import org.web3j.protocol.core.methods.response.EthSendTransaction
import org.web3j.protocol.core.methods.response.EthTransaction
import org.web3j.protocol.core.methods.response.Transaction
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.tx.TransactionManager
import java.math.BigInteger
import java.util.Optional
import java.util.concurrent.LinkedBlockingQueue

open class MockedTestBaseTransactionSubmitter : IntegrationTestSetup() {

    lateinit var module: GTXModule
    lateinit var storage: Storage
    lateinit var databaseOperations: TransactionSubmitterDatabaseOperations
    lateinit var testLogAppender: TestLogAppender

    @BeforeEach
    fun setup() {

        module = mock<GTXModule>()
        storage = mock<Storage> {
            on { openWriteConnection(anyLong()) } doAnswer {
                mock<EContext>()
            }
            on { openReadConnection(anyLong()) } doAnswer {
                mock<EContext>()
            }
        }

        databaseOperations = mock<TransactionSubmitterDatabaseOperations>()

        testLogAppender = TestLogAppender.addAppender(listOf(Level.WARN, Level.ERROR))
        testLogAppender.clear()
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
                    sendEIP1559Transaction(
                            anyLong(),
                            any(),
                            any(),
                            any(),
                            anyString(),
                            anyString(),
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
                    sendEIP1559Transaction(
                            anyLong(),
                            any(),
                            any(),
                            any(),
                            anyString(),
                            anyString(),
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

    fun mockBlockNumber(number: BigInteger): EthBlockNumber {
        return mock<EthBlockNumber> {
            on { blockNumber } doReturn number
        }
    }

    fun createTransactionSubmitter(
            web3jRequestHandler: Web3jRequestHandler,
            transactionManagers: Map<String, TransactionManager>,
            feeEstimatorFactory: EIP1559FeeEstimatorFactory
    ): TransactionSubmitter {

        return TransactionSubmitter(
                web3jRequestHandler,
                transactionManagers,
                feeEstimatorFactory,
                databaseOperations,
                storage,
                0,
                0,
                Long.MAX_VALUE,
                LinkedBlockingQueue(),
                BigInteger.valueOf(10),
                -1,
                5,
                0,
        )
    }

    fun mockTakenBy() {
        Mockito.`when`(module.query(any(), eq(GET_TRANSACTION), any())).thenReturn(gtv(mapOf(
                "status" to gtv(RellTransactionStatus.TAKEN.name),
                "processed_by" to gtv(ByteArray(32) { 0 })
        )))
    }

    fun mockFeeEstimatorFactory(
            gasLimitValue: Long = 1,
            maxGasPriceValue: Long = 1,
            gasUsed: Long? = 1L,
            walletBalance: Long? = 10000000L,
    ): EIP1559FeeEstimatorFactory {

        val feeEstimatorFactoryMock = mock<EIP1559FeeEstimatorFactory> {
            on { gasLimit } doReturn gasLimitValue.toBigInteger()
            on { maxGasPrice } doReturn maxGasPriceValue.toBigInteger()
            on { createEstimate(anyString(), anyString(), anyString(), anyLong(), any(), any()) } doAnswer {
                EIP1559LastBlockFeeEstimatorMock(1.toBigInteger(), 1.toBigInteger(), 1.toBigInteger(), 1.toBigInteger(), gasUsed = gasUsed, gasLimitValue, maxGasPriceValue, walletBalance = walletBalance)
            }
        }
        return feeEstimatorFactoryMock
    }
}