package net.postchain.eif.transaction

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import net.postchain.common.hexStringToByteArray
import net.postchain.devtools.getModules
import net.postchain.eif.EifBaseIntegrationTest
import net.postchain.eif.contracts.Validator
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_SUBMIT_COLUMN_HASH
import org.awaitility.Awaitility
import org.awaitility.Duration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.tx.Contract
import java.math.BigInteger

@Testcontainers(disabledWithoutDocker = true)
class TransactionSubmitterCompletedByRellIT : EifBaseIntegrationTest() {

    private lateinit var contractAddress: String

    @BeforeEach
    override fun setup() {
        super.setup()

        with(configOverrides) {
            setProperty("ethereum.privateKey", "0x53914554952e5473a54b211a31303078abde83b8128995785901eed28df3f610")
            setProperty("evm.txPollInterval", 1000)
        }

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
        contractAddress = contract.contractAddress.substring(2)

        TransactionSubmitterTestGTXModule.updateTxStatus = true
    }

    /*
    This will emulate a node timing out and not submit the transaction within given time period.

    1. TX is submitted on BC.
    2. Nodes takes TX.
    3. Node submits TX. <- this test rejects the TX here due to incorrect status (we make sure it still is QUEUED)
    4. NOde updates BC status to PENDING.
    4. Node polls TX to verify it.
     */
    @Test
    fun `do not submit tx due to status set to QUEUED by rell`() {

        val nodes = createNodes(1, "/net/postchain/eif/transaction/blockchain_config.xml")
        val node = nodes[0]

        val txSubmitterTestModule = node.getModules().filterIsInstance<TransactionSubmitterTestGTXModule>().first()
        TransactionSubmitterTestGTXModule.updateTxStatus = false

        val txSubmit = mkEvmSubmitTxRellRequest(0, contractAddress)
        txSubmitterTestModule.addTransactionsAvailableToTake(txSubmit)

        Awaitility.await().atMost(Duration.ONE_MINUTE).pollInterval(Duration.ONE_SECOND).untilAsserted {

            buildBlock(1L)
            assertStatusOperation(txSubmitterTestModule, txSubmit.rowId, RellTransactionStatus.TAKEN)

            testLogAppender.assertWarn("Transaction 0 is ignored since blockchain says this is no longer taken by this node")
        }
    }

    /*
    This will emulate a node processing a transaction but it timed out in rell before the node had the chance to update the status

    1. TX is submitted on BC.
    2. Nodes takes TX.
    3. Node submits TX.
    4. Node updates BC status to PENDING. <- this test rejects the TX here when BC has the status changed unexpected to FAILED.
    4. Node polls TX to verify it.
     */
    @Test
    fun `do not update tx status due to status set to FAILED by rell`() {

        val nodes = createNodes(1, "/net/postchain/eif/transaction/blockchain_config.xml")
        val node = nodes[0]

        val txSubmitterTestModule = node.getModules().filterIsInstance<TransactionSubmitterTestGTXModule>().first()

        val txSubmit = mkEvmSubmitTxRellRequest(0, contractAddress, processedBy = node.pubKey.hexStringToByteArray())

        txSubmitterTestModule.addTransactionsAvailableToTake(txSubmit)

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertNoQueuedTxs(txSubmitterTestModule)
            assertStatusOperation(txSubmitterTestModule, txSubmit.rowId, RellTransactionStatus.TAKEN)
        }

        Awaitility.await().atMost(Duration.TEN_SECONDS).untilAsserted {
            logger.info { "Waiting for tx to be submitted..." }
            withDbTransactions(node, txSubmit.rowId) {
                assertThat(it.size).isEqualTo(1)
                assertThat(EVM_TX_SUBMIT_COLUMN_HASH.get(it[0])).isNotNull()
            }
        }

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            // Set BC status to FAILURE
            val txSubmitOnBc = mkEvmSubmitTxRellRequest(0, contractAddress,
                    status = RellTransactionStatus.FAILURE
            )
            txSubmitterTestModule.addTransaction(txSubmitOnBc)

            buildBlock(1L)

            testLogAppender.assertWarn("Transaction 0 blockchain status is set to completed. This node will stop processing this transaction.")
        }
    }

    /*
    This will emulate a node starting up but finds a transaction taken by the node now it completed by another node.

    1. Node starts up
    2. Finds and adds a pending TX.
    3. Poll and verify TX.
    4. Node updates BC status to PENDING. <- This test fails here due to BC status is already set to SUCCESS
    4. Node polls TX to verify it.
     */
    @Test
    fun `verify continue pending txs on startup drop due to rell status has been changed`() {

        val node = createNodes(1, "/net/postchain/eif/transaction/blockchain_config_pending.xml")[0]
        val txSubmitterTestModule = node.getModules().filterIsInstance<TransactionSubmitterTestGTXModule>().first()

        // Set BC status to FAILURE
        val txSubmitOnBc = mkEvmSubmitTxRellRequest(0, contractAddress,
                status = RellTransactionStatus.SUCCESS
        )
        txSubmitterTestModule.addTransaction(txSubmitOnBc)

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)

            // It will fail since the transaction is not actually submitted
            testLogAppender.assertWarn("Transaction 0 blockchain status is set to completed. This node will stop processing this transaction.")
        }
    }
}
