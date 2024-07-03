package net.postchain.eif.transaction

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isNotNull
import net.postchain.base.data.DatabaseAccess
import net.postchain.base.withReadConnection
import net.postchain.devtools.PostchainTestNode.Companion.DEFAULT_CHAIN_IID
import net.postchain.devtools.getModules
import net.postchain.devtools.utils.configuration.NodeSeqNumber
import net.postchain.eif.EifBaseIntegrationTest
import net.postchain.eif.contracts.Validator
import org.apache.commons.configuration2.MapConfiguration
import org.awaitility.Awaitility
import org.awaitility.Duration
import org.jooq.SQLDialect
import org.jooq.impl.DSL
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
class TransactionSubmitterIT : EifBaseIntegrationTest() {

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
    }

    @Test
    fun `submit transaction`() {

        val nodes = createNodes(1, "/net/postchain/eif/transaction/blockchain_config.xml")
        val node = nodes[0]

        val txSubmitterTestModule = node.getModules().filterIsInstance<TransactionSubmitterTestGTXModule>().first()

        val txSubmit = mkEvmSubmitTxRellRequest(0, contractAddress)
        txSubmitterTestModule.addTransactionsAvailableToTake(txSubmit)

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertNoQueuedTxs(txSubmitterTestModule)
            assertTrue(txSubmitterTestModule.conf.taken.contains(0))
            assertStatusOperation(txSubmitterTestModule, txSubmit.rowId, RellTransactionStatus.TAKEN)
        }

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertStatusOperation(
                    txSubmitterTestModule,
                    txSubmit.rowId,
                    RellTransactionStatus.PENDING
            )
        }

        // Verify that the transaction was removed
        val transactionsCount = withReadConnection(node.getBlockchainInstance(DEFAULT_CHAIN_IID).blockchainEngine.sharedStorage, DEFAULT_CHAIN_IID) {
            val jooq = DSL.using(it.conn, SQLDialect.POSTGRES)

            val tableName = DatabaseAccess.of(it).tableEvmTxSubmit(it)

            jooq
                    .select(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_HASH)
                    .from(tableName)
                    .where(TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_COLUMN_REQUEST_ID.eq(txSubmit.rowId))
                    .count()
        }
        assertThat(transactionsCount).isEqualTo(0)
    }

    @Test
    fun `verify submit and verify transaction`() {

        val nodes = createNodes(1, "/net/postchain/eif/transaction/blockchain_config.xml")
        val node = nodes[0]

        val txSubmitterTestModule = node.getModules().filterIsInstance<TransactionSubmitterTestGTXModule>().first()

        val txSubmit = mkEvmSubmitTxRellRequest(0, contractAddress)
        txSubmitterTestModule.addTransactionsAvailableToTake(txSubmit)

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertNoQueuedTxs(txSubmitterTestModule)
            assertTrue(txSubmitterTestModule.conf.taken.contains(0))
            assertStatusOperation(txSubmitterTestModule, txSubmit.rowId, RellTransactionStatus.TAKEN)
        }

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertStatusOperation(
                    txSubmitterTestModule,
                    txSubmit.rowId,
                    RellTransactionStatus.PENDING
            )
        }

        // Exists and might have the first receipt - but we don't know since it is asynchronous
        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)

            val txExistsAsPending: Boolean? = withTxSubmitter(txSubmitterTestModule, 0) { _, _ ->
                true
            }
            assertThat(txExistsAsPending).isNotNull().isEqualTo(true)
        }

        // Write validation result to BC after consensus is reached
        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertStatusOperation(
                    txSubmitterTestModule,
                    txSubmit.rowId,
                    RellTransactionStatus.SUCCESS
            )
            withUpdateEvmTransactionReceipt(txSubmitterTestModule, txSubmit.rowId) {
                assertThat(it.size).isEqualTo(1)
                assertThat(it[0].blockHash).isNotNull()
                assertThat(it[0].effectiveGasPrice).isLessThan(4000000000)
                assertThat(it[0].gasUsage).isEqualTo(58577)
            }
        }
    }

    @Test
    fun `Tx should fail if estimated gas usage is above limit`() {
        val nodes = createNodes(1, "/net/postchain/eif/transaction/blockchain_config_low_gas_limit.xml")
        val node = nodes[0]

        val txSubmitterTestModule = node.getModules().filterIsInstance<TransactionSubmitterTestGTXModule>().first()

        val evmSubmitTxRellRequest = mkEvmSubmitTxRellRequest(0, contractAddress)
        txSubmitterTestModule.addTransactionsAvailableToTake(evmSubmitTxRellRequest)

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertNoQueuedTxs(txSubmitterTestModule)
        }

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertTrue(txSubmitterTestModule.conf.queuedTxs.contains(0))
            assertStatusOperation(txSubmitterTestModule, evmSubmitTxRellRequest.rowId, RellTransactionStatus.QUEUED)
        }
    }

    @Test
    fun `submit transaction in multi node env - successfully`() {

        val nodes = createNodes(4, "/net/postchain/eif/transaction/blockchain_config_4_nodes.xml")

        val allTxSubmitterTestModules =
                nodes.map { it.getModules().filterIsInstance<TransactionSubmitterTestGTXModule>().first() }

        val txSubmit = mkEvmSubmitTxRellRequest(0, contractAddress)

        // Mock rell status for other nodes to be able to verify the operation
        allTxSubmitterTestModules.forEach { it.addTransaction(mkEvmSubmitTxRellRequest(txSubmit.rowId, "", RellTransactionStatus.QUEUED)) }

        // Make it available for node[0]
        allTxSubmitterTestModules[0].addTransactionsAvailableToTake(txSubmit)

        // node[0] will give it a try and succeed
        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertNoQueuedTxs(allTxSubmitterTestModules[0])
            assertStatusOperation(allTxSubmitterTestModules[0], txSubmit.rowId, RellTransactionStatus.TAKEN)
        }

        // Let node[0] submit it and verify it is set to PENDING with receipt
        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertStatusOperation(allTxSubmitterTestModules[0], txSubmit.rowId, RellTransactionStatus.PENDING)
        }

        // Make sure node[1] updates the status to PENDING with a tx hash
        withTxOperations(
                allTxSubmitterTestModules[0],
                TransactionSubmitterSpecialTxExtension.UPDATE_EVM_TRANSACTION_STATUS
        ) { operations ->
            val statusOperations = operations
                    .filter {
                        it.args[0].asInteger() == txSubmit.rowId &&
                                RellTransactionStatus.entries.toTypedArray()[it.args[1].asInteger()
                                        .toInt()] == RellTransactionStatus.PENDING &&
                                it.args.size == 5
                    }
                    .map { it.args[2].asString() }

            if (statusOperations.isNotEmpty()) statusOperations[0] else null
        }

        // One node got to set the status SUCCESS and receipt
        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(nodes.toList(), 1L)
            assertTrue(allTxSubmitterTestModules.any { it.conf.successfulTxs.contains(0) })
        }
    }

    // This test brings up 4 nodes for processing an evm transaction.
    // node[0] is configured with a incorrect evm rpc url which will make it fail
    // Once node[0] fails node[1] till retry and succeed.
    @Test
    fun `submit transaction in multi node env - successfully but with one failing node`() {

        // node[0] is the failing node - invalid rpc url
        nodeConfigOverrides[NodeSeqNumber(0)] =
                MapConfiguration(
                        mutableMapOf(
                                "ethereum.urls" to "http://127.0.0.1:1",
                                "evm.healthCheckInterval" to -1
                        )
                )

        val nodes = createNodes(4, "/net/postchain/eif/transaction/blockchain_config_4_nodes.xml")
        val nodesExceptFirst = listOf(nodes[1], nodes[2], nodes[3])

        val allTxSubmitterTestModules =
                nodes.map { it.getModules().filterIsInstance<TransactionSubmitterTestGTXModule>().first() }
        val txSubmitterTestModule0 = allTxSubmitterTestModules[0]
        val txSubmitterTestModule1 = allTxSubmitterTestModules[1]
        val allTxSubmitterTestModulesExceptFirst =
                listOf(allTxSubmitterTestModules[0], allTxSubmitterTestModules[1], allTxSubmitterTestModules[2])

        val txSubmit = mkEvmSubmitTxRellRequest(0, contractAddress)

        // Mock rell status for other nodes to be able to verify the operation
        allTxSubmitterTestModules.forEach { it.addTransaction(mkEvmSubmitTxRellRequest(txSubmit.rowId, "", RellTransactionStatus.QUEUED, processedByNode = nodes[0])) }

        // Make it available for node[0]
        txSubmitterTestModule0.addTransactionsAvailableToTake(txSubmit)

        // node[0] will give it a try but fail
        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertStatusOperation(txSubmitterTestModule0, txSubmit.rowId, RellTransactionStatus.TAKEN)
        }

        // Let the node fail
        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertStatusOperation(txSubmitterTestModule0, txSubmit.rowId, RellTransactionStatus.QUEUED)
            testLogAppender.assertError("Failed to send web3j request to all 1 nodes")
        }

        // Mock rell status for other nodes to be able to verify the operation
        allTxSubmitterTestModules.forEach { it.addTransaction(mkEvmSubmitTxRellRequest(txSubmit.rowId, "", RellTransactionStatus.QUEUED, processedByNode = nodes[0])) }

        //  Make it available for node[1]
        txSubmitterTestModule1.addTransactionsAvailableToTake(txSubmit)

        // node[1] will give it a try and succeed
        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertStatusOperation(txSubmitterTestModule1, txSubmit.rowId, RellTransactionStatus.TAKEN)
        }

        // Mock rell status for other nodes to be able to verify the operation
        allTxSubmitterTestModules.forEach { it.addTransaction(mkEvmSubmitTxRellRequest(txSubmit.rowId, "", RellTransactionStatus.TAKEN, processedByNode = nodes[0])) }

        // node[1] successfully submits it
        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertStatusOperation(txSubmitterTestModule0, txSubmit.rowId, RellTransactionStatus.PENDING)
        }

        // Make sure node[1] updates the status to PENDING with a tx hash
        withTxOperations(
                txSubmitterTestModule1,
                TransactionSubmitterSpecialTxExtension.UPDATE_EVM_TRANSACTION_STATUS
        ) { operations ->
            val statusOperations = operations
                    .filter {
                        it.args[0].asInteger() == txSubmit.rowId &&
                                RellTransactionStatus.entries.toTypedArray()[it.args[1].asInteger()
                                        .toInt()] == RellTransactionStatus.PENDING &&
                                it.args.size == 5
                    }
                    .map { it.args[2].asString() }

            if (statusOperations.isNotEmpty()) statusOperations[0] else null
        }

        // One node got to set the status SUCCESS
        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(nodesExceptFirst, 1L)
            assertTrue(allTxSubmitterTestModulesExceptFirst.any { it.conf.successfulTxs.contains(0) })
        }
    }
}
