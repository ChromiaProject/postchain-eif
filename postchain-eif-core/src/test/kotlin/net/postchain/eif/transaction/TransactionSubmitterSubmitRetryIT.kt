package net.postchain.eif.transaction

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotNull
import net.postchain.devtools.getModules
import net.postchain.eif.EifBaseIntegrationTest
import net.postchain.eif.EvmType
import net.postchain.eif.contracts.Validator
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_ERRORS_COLUMN_MESSAGE
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_ERRORS_COLUMN_REQUEST_ID
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_ERRORS_COLUMN_RPC_URL
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_ERRORS_COLUMN_STACK_TRACE
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_ERRORS_COLUMN_TIMESTAMP
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
import java.math.BigInteger

@Testcontainers(disabledWithoutDocker = true)
class TransactionSubmitterSubmitRetryIT : EifBaseIntegrationTest(
    EvmType.GETH,
    prependUrls = listOf("http://127.0.0.1:1", "http://127.0.0.1:2")
) {

    @BeforeEach
    override fun setup() {
        super.setup()

        with(configOverrides) {
            setProperty("ethereum.privateKey", "0x53914554952e5473a54b211a31303078abde83b8128995785901eed28df3f610")
            setProperty("evm.txPollInterval", 1000)
        }
    }

    @Test
    fun `submit transaction fails 2 times and then succeeds`() {

        // Deploy validator contract
        val encodedConstructor =
                FunctionEncoder.encodeConstructor(listOf(DynamicArray(Address::class.java, Address(BigInteger.ONE))))
        val contract = Contract.deployRemoteCall(
            Validator::class.java,
            web3j,
            transactionManager,
            gasProvider,
            validatorBinary,
            encodedConstructor
        ).send()
        val contractAddress = contract.contractAddress.substring(2)

        val nodes = createNodes(1, "/net/postchain/eif/transaction/blockchain_config.xml")
        val node = nodes[0]

        val txSubmitterTestModule = node.getModules().filterIsInstance<TransactionSubmitterTestGTXModule>().first()

        val evmSubmitTxRellRequest = mkEvmSubmitTxRellRequest(0, contractAddress)
        txSubmitterTestModule.addTransactionsAvailableToTake(evmSubmitTxRellRequest)

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertNoQueuedTxs(txSubmitterTestModule)
            assertStatusOperation(txSubmitterTestModule, 0, RellTransactionStatus.TAKEN)
        }

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertTrue(txSubmitterTestModule.conf.successfulTxs.contains(0))
        }

        // Verify errors in table
        withDbErrors(node, evmSubmitTxRellRequest.rowId) {

            assertThat(it.size).isGreaterThanOrEqualTo(2) // We might have received pending errors also at this point

            assertThat(it[0].get(EVM_TX_ERRORS_COLUMN_TIMESTAMP)).isNotNull()
            assertThat(it[0].get(EVM_TX_ERRORS_COLUMN_REQUEST_ID)).isEqualTo(0)
            assertThat(it[0].get(EVM_TX_ERRORS_COLUMN_RPC_URL)).isEqualTo("http://127.0.0.1:1")
            assertThat(it[0].get(EVM_TX_ERRORS_COLUMN_MESSAGE)).isEqualTo("Failed to send transaction 0: Failed to connect to /127.0.0.1:1")
            assertThat(it[0].get(EVM_TX_ERRORS_COLUMN_STACK_TRACE)).isNotNull()

            assertThat(it[1].get(EVM_TX_ERRORS_COLUMN_TIMESTAMP)).isNotNull()
            assertThat(it[1].get(EVM_TX_ERRORS_COLUMN_REQUEST_ID)).isEqualTo(0)
            assertThat(it[1].get(EVM_TX_ERRORS_COLUMN_RPC_URL)).isEqualTo("http://127.0.0.1:2")
            assertThat(it[1].get(EVM_TX_ERRORS_COLUMN_MESSAGE)).isEqualTo("Failed to send transaction 0: Failed to connect to /127.0.0.1:2")
            assertThat(it[1].get(EVM_TX_ERRORS_COLUMN_STACK_TRACE)).isNotNull()
        }

        // Operation was successfully on 3rd try
        assertStatusOperation(txSubmitterTestModule, 0, RellTransactionStatus.SUCCESS)
    }
}
