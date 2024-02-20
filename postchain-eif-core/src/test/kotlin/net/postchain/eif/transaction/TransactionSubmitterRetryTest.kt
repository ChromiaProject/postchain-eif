package net.postchain.eif.transaction

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotNull
import net.postchain.common.BlockchainRid
import net.postchain.devtools.getModules
import net.postchain.eif.EifBaseIntegrationTest
import net.postchain.eif.EvmType
import net.postchain.eif.contracts.Validator
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.ERRORS_COLUMN_ERROR_MESSAGE
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.ERRORS_COLUMN_REQUEST_ID
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.ERRORS_COLUMN_SERVICE_URL
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.ERRORS_COLUMN_STACK_TRACE
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.ERRORS_COLUMN_TIMESTAMP
import net.postchain.gtv.GtvFactory.gtv
import org.awaitility.Awaitility
import org.awaitility.Duration
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.tx.Contract

@Testcontainers(disabledWithoutDocker = true)
class TransactionSubmitterRetryTest : EifBaseIntegrationTest(
    EvmType.GETH,
    prependUrls = listOf("http://127.0.0.1:8888", "http://127.0.0.1:9999")
) {

    @BeforeEach
    override fun setup() {
        super.setup()

        with(configOverrides) {
            setProperty("evm.privateKey", "0x53914554952e5473a54b211a31303078abde83b8128995785901eed28df3f610")
            setProperty("evm.txPollInterval", 1000)
        }
    }

    @Test
    fun `submit transaction fails 2 times and then succeeds`() {

        // Deploy validator contract
        val postchainValidator = "659e4a3726275edFD125F52338ECe0d54d15BD99"
        val encodedConstructor =
            FunctionEncoder.encodeConstructor(listOf(DynamicArray(Address::class.java, Address(postchainValidator))))
        Contract.deployRemoteCall(
            Validator::class.java,
            web3j,
            transactionManager,
            gasProvider,
            validatorBinary,
            encodedConstructor
        ).send()

        val nodes = createNodes(1, "/net/postchain/eif/transaction/blockchain_config.xml")
        val node = nodes[0]

        val txSubmitterTestModule = node.getModules().filterIsInstance<TransactionSubmitterTestGTXModule>().first()

        val evmSubmitTransactionRequest = EvmSubmitTransactionRequest(
            0,
            postchainValidator,
            "addValidator",
            listOf("uint", "address"),
            listOf( gtv(1), gtv(ByteArray(20))),
            1337,
            BlockchainRid.ZERO_RID.data,
            RellTransactionStatus.QUEUED
        )
        txSubmitterTestModule.addTxToQueue(evmSubmitTransactionRequest)
        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertTrue(txSubmitterTestModule.conf.queue.isEmpty())
        }

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertTrue(txSubmitterTestModule.conf.successfulTxs.contains(0))
        }

        // Verify errors in table
        withDbErrors(node, evmSubmitTransactionRequest.rowId) {

            assertThat(it.size).isEqualTo(2)

            assertThat(it[0].get(ERRORS_COLUMN_TIMESTAMP)).isNotNull()
            assertThat(it[0].get(ERRORS_COLUMN_REQUEST_ID)).isEqualTo(0)
            assertThat(it[0].get(ERRORS_COLUMN_SERVICE_URL)).isEqualTo("http://127.0.0.1:8888")
            assertThat(it[0].get(ERRORS_COLUMN_ERROR_MESSAGE)).isEqualTo("Failed to send transaction 0: Failed to connect to /127.0.0.1:8888")
            assertThat(it[0].get(ERRORS_COLUMN_STACK_TRACE)).isNotNull()

            assertThat(it[1].get(ERRORS_COLUMN_TIMESTAMP)).isNotNull()
            assertThat(it[1].get(ERRORS_COLUMN_REQUEST_ID)).isEqualTo(0)
            assertThat(it[1].get(ERRORS_COLUMN_SERVICE_URL)).isEqualTo("http://127.0.0.1:9999")
            assertThat(it[1].get(ERRORS_COLUMN_ERROR_MESSAGE)).isEqualTo("Failed to send transaction 0: Failed to connect to /127.0.0.1:9999")
            assertThat(it[1].get(ERRORS_COLUMN_STACK_TRACE)).isNotNull()
        }

        // Operation was successfully on 3rd try
        withDbTransaction(node, evmSubmitTransactionRequest.rowId) {
            assertThat(it.get(TransactionSubmitterDatabaseOperationsImpl.TRANSACTIONS_COLUMN_STATUS)).isEqualTo(TransactionStatus.SUCCESS.name)
            assertThat(it.get(TransactionSubmitterDatabaseOperationsImpl.TRANSACTIONS_COLUMN_BLOCK_HASH)).isNotNull()
            assertThat(it.get(TransactionSubmitterDatabaseOperationsImpl.TRANSACTIONS_COLUMN_EFFECTIVE_GAS_PRICE)).isNotNull()
            assertThat(it.get(TransactionSubmitterDatabaseOperationsImpl.TRANSACTIONS_COLUMN_GAS_USAGE)).isGreaterThan(0)
        }
    }
}
