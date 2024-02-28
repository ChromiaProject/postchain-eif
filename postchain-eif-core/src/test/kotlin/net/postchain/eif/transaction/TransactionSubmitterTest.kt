package net.postchain.eif.transaction

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import net.postchain.base.data.DatabaseAccess
import net.postchain.base.withReadConnection
import net.postchain.common.BlockchainRid
import net.postchain.devtools.PostchainTestNode
import net.postchain.devtools.PostchainTestNode.Companion.DEFAULT_CHAIN_IID
import net.postchain.devtools.getModules
import net.postchain.eif.EifBaseIntegrationTest
import net.postchain.eif.EvmType
import net.postchain.eif.contracts.Validator
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.ERRORS_COLUMN_REQUEST_ID
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.TRANSACTIONS_COLUMN_ACTIVE
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.TRANSACTIONS_COLUMN_BLOCK_HASH
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.TRANSACTIONS_COLUMN_EFFECTIVE_GAS_PRICE
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.TRANSACTIONS_COLUMN_GAS_LIMIT
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.TRANSACTIONS_COLUMN_GAS_PRICE
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.TRANSACTIONS_COLUMN_GAS_USAGE
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.TRANSACTIONS_COLUMN_REQUEST_ID
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.TRANSACTIONS_COLUMN_STATUS
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.TRANSACTIONS_COLUMN_TX_HASH
import net.postchain.eif.transaction.TransactionSubmitterSpecialTxExtension.Companion.UPDATE_EVM_TRANSACTION_RECEIPT
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtx.data.ExtOpData
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
class TransactionSubmitterTest : EifBaseIntegrationTest(
        EvmType.GETH
) {

    private lateinit var contractAddress: String

    @BeforeEach
    override fun setup() {
        super.setup()

        with(configOverrides) {
            setProperty("evm.privateKey", "0x53914554952e5473a54b211a31303078abde83b8128995785901eed28df3f610")
            setProperty("evm.txPollInterval", 1000)
        }

        // Deploy validator contract
        val encodedConstructor = FunctionEncoder.encodeConstructor(listOf(DynamicArray(Address::class.java, Address(BigInteger.ONE))))
        val contract = Contract.deployRemoteCall(Validator::class.java, web3j, transactionManager, gasProvider, validatorBinary, encodedConstructor).send()
        contractAddress = contract.contractAddress.substring(2)
    }

    @Test
    fun `submit transaction`() {

        val nodes = createNodes(1, "/net/postchain/eif/transaction/blockchain_config.xml")
        val node = nodes[0]

        val txSubmitterTestModule = node.getModules().filterIsInstance<TransactionSubmitterTestGTXModule>().first()

        val evmSubmitTransactionRequest = EvmSubmitTransactionRequest(
                0,
                contractAddress,
                "updateValidators",
                listOf("address[]"),
                listOf(gtv(listOf(gtv(ByteArray(20) { 1 })))),
                1337,
                BlockchainRid.ZERO_RID.data,
                RellTransactionStatus.QUEUED
        )
        txSubmitterTestModule.addTxToQueue(evmSubmitTransactionRequest)
        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertTrue(txSubmitterTestModule.conf.queue.isEmpty())
        }

        // No receipt values set yet in DB
        withDbTransaction(node, evmSubmitTransactionRequest.rowId) {
            assertThat(it.get(TRANSACTIONS_COLUMN_BLOCK_HASH)).isNull()
            assertThat(it.get(TRANSACTIONS_COLUMN_EFFECTIVE_GAS_PRICE)).isNull()
            assertThat(it.get(TRANSACTIONS_COLUMN_GAS_USAGE)).isNull()
        }

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertTrue(txSubmitterTestModule.conf.successfulTxs.contains(0))
        }

        // Status set to operation
        assertStatusOperation(txSubmitterTestModule, evmSubmitTransactionRequest.rowId, RellTransactionStatus.SUCCESS)

        // Receipt values set in DB
        withDbTransaction(node, evmSubmitTransactionRequest.rowId) {
            assertThat(it.get(TRANSACTIONS_COLUMN_STATUS)).isEqualTo(TransactionStatus.SUCCESS.name)
            assertThat(it.get(TRANSACTIONS_COLUMN_BLOCK_HASH)).isNotNull()
            assertThat(it.get(TRANSACTIONS_COLUMN_EFFECTIVE_GAS_PRICE)).isNotNull()
            assertThat(it.get(TRANSACTIONS_COLUMN_GAS_USAGE)).isGreaterThan(0)
        }

        // Update receipt operation called
        withUpdateEvmTransactionReceipt(txSubmitterTestModule, evmSubmitTransactionRequest.rowId) {
            assertThat(it.size).isEqualTo(1)
            assertThat(it[0].blockHash).isNotNull()
            assertThat(it[0].effectiveGasPrice).isNotNull()
            assertThat(it[0].gasUsage!!).isGreaterThan(0)
        }
    }

    @Test
    fun `db queued transaction goes to pending after build block`() {
        val nodes = createNodes(1, "/net/postchain/eif/transaction/blockchain_config_queue.xml")
        val node = nodes[0]

        val txSubmitterTestModule = node.getModules().filterIsInstance<TransactionSubmitterQueuedTransactionTestGTXModule>().first()

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertTrue(txSubmitterTestModule.conf.queue.isEmpty())
        }

        withDbTransaction(node, 0) {
            assertThat(it.get(TRANSACTIONS_COLUMN_STATUS)).isEqualTo(TransactionStatus.PENDING.name)
            assertThat(it.get(TRANSACTIONS_COLUMN_GAS_PRICE)).isNotNull()
            assertThat(it.get(TRANSACTIONS_COLUMN_GAS_LIMIT)).isNotNull()
            assertThat(it.get(TRANSACTIONS_COLUMN_TX_HASH)).isNotNull()
            assertThat(it.get(TRANSACTIONS_COLUMN_ACTIVE)).isTrue()

        }
    }

    @Test
    fun `db pending transaction goes to success after build block`() {
        val sendTransaction = transactionManager.sendTransaction(BigInteger.valueOf(4100000000), BigInteger.valueOf(9000000), contractAddress, "0x4b56175300000000000000000000000000000000000000000000000000000000000000010000000000000000000000000000000000000000000000000000000000000000", BigInteger.valueOf(0))

        TransactionSubmitterPendingTransactionTestGTXModule.TRANSACTION_HASH = sendTransaction.transactionHash
        val nodes = createNodes(1, "/net/postchain/eif/transaction/blockchain_config_pending.xml")
        val node = nodes[0]

        val txSubmitterTestModule = node.getModules().filterIsInstance<TransactionSubmitterPendingTransactionTestGTXModule>().first()

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertTrue(txSubmitterTestModule.conf.successfulTxs.contains(0))
        }

        withDbTransaction(node, 0) {
            assertThat(it.get(TRANSACTIONS_COLUMN_STATUS)).isEqualTo(TransactionStatus.SUCCESS.name)
            assertThat(it.get(TRANSACTIONS_COLUMN_BLOCK_HASH).isNotEmpty())
            assertThat(it.get(TRANSACTIONS_COLUMN_EFFECTIVE_GAS_PRICE)).isGreaterThan(0)
            assertThat(it.get(TRANSACTIONS_COLUMN_GAS_USAGE)).isGreaterThan(0)
            assertThat(it.get(TRANSACTIONS_COLUMN_ACTIVE)).isFalse()
        }
    }

    @Test
    fun `db success transaction goes to inactive after build block`() {
        val nodes = createNodes(1, "/net/postchain/eif/transaction/blockchain_config_success.xml")
        val node = nodes[0]

        val txSubmitterTestModule = node.getModules().filterIsInstance<TransactionSubmitterSuccessfulTransactionTestGTXModule>().first()

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertTrue(txSubmitterTestModule.conf.successfulTxs.size == 0)
        }

        withDbTransaction(node, 0) {
            assertThat(it.get(TRANSACTIONS_COLUMN_STATUS)).isEqualTo(TransactionStatus.SUCCESS.name)
            assertThat(it.get(TRANSACTIONS_COLUMN_ACTIVE)).isFalse()
        }
    }

    @Test
    fun `db failed transaction`() {
        val nodes = createNodes(1, "/net/postchain/eif/transaction/blockchain_config_fail.xml")
        val node = nodes[0]

        val txSubmitterTestModule = node.getModules().filterIsInstance<TransactionSubmitterFailTransactionTestGTXModule>().first()

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertTrue(txSubmitterTestModule.conf.failedTxs.contains(0))
        }

        withDbTransaction(node, 0) {
            assertThat(it.get(TRANSACTIONS_COLUMN_STATUS)).isEqualTo(TransactionStatus.FAILURE.name)
        }
    }

    @Test
    fun `Assert that tx submitter becomes unhealthy when RPC nodes are unreachable`() {
        with(configOverrides) {
            setProperty("evm.healthCheckInterval", 1000)
            setProperty("ethereum.urls", listOf("http://localhost:9000"))
        }

        val nodes = createNodes(1, "/net/postchain/eif/transaction/blockchain_config.xml")
        val node = nodes[0]

        val txSubmitterTestModule = node.getModules().filterIsInstance<TransactionSubmitterTestGTXModule>().first()
        val txExtension = txSubmitterTestModule.getSpecialTxExtensions().filterIsInstance<TransactionSubmitterSpecialTxExtension>().first()

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            val txSubmitter = txExtension.getTransactionSubmitter(1337)
            assertThat(txSubmitter).isNotNull()
            assertThat(txSubmitter!!.isHealthy()).isFalse()
        }
    }

    @Test
    fun `Assert that tx submitter becomes unhealthy when wallet balance is too low`() {
        with(configOverrides) {
            // Random key with no balance
            setProperty("evm.privateKey", "0x53914554952e5473a54b211a31303078abde83b8128995785901eed28df3f611")
            setProperty("evm.healthCheckInterval", 1000)
        }

        val nodes = createNodes(1, "/net/postchain/eif/transaction/blockchain_config.xml")
        val node = nodes[0]

        val txSubmitterTestModule = node.getModules().filterIsInstance<TransactionSubmitterTestGTXModule>().first()
        val txExtension = txSubmitterTestModule.getSpecialTxExtensions().filterIsInstance<TransactionSubmitterSpecialTxExtension>().first()

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            val txSubmitter = txExtension.getTransactionSubmitter(1337)
            assertThat(txSubmitter).isNotNull()
            assertThat(txSubmitter!!.isHealthy()).isFalse()
        }
    }

    @Test
    fun `Tx should fail if estimated gas usage is above limit`() {
        val nodes = createNodes(1, "/net/postchain/eif/transaction/blockchain_config_low_gas_limit.xml")
        val node = nodes[0]

        val txSubmitterTestModule = node.getModules().filterIsInstance<TransactionSubmitterTestGTXModule>().first()

        val evmSubmitTransactionRequest = EvmSubmitTransactionRequest(
                0,
                contractAddress,
                "updateValidators",
                listOf("address[]"),
                listOf(gtv(listOf(gtv(ByteArray(20) { 1 })))),
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
            assertTrue(txSubmitterTestModule.conf.failedTxs.contains(0))
        }
    }
}

// Evaluate sent receipt operations
fun withUpdateEvmTransactionReceipt(txSubmitterTestModule: TransactionSubmitterTestGTXModule, rowId: Long, op: (List<EvmSubmitTransactionResult>) -> Unit) {
    withTxOperations(txSubmitterTestModule, UPDATE_EVM_TRANSACTION_RECEIPT) { operations ->
        val receiptOperations = operations
            .filter { it.args[0].asInteger() == rowId }
            .map {
                val blockHash = it.args[1].asString()
                val effectiveGasPrice = it.args[2].asInteger()
                val gasUsage = it.args[3].asInteger()
                EvmSubmitTransactionResult(RellTransactionStatus.SUCCESS, blockHash, effectiveGasPrice, gasUsage)
            }

        op(receiptOperations)
    }
}

// Evaluate sent transaction status
fun assertStatusOperation(txSubmitterTestModule: TransactionSubmitterTestGTXModule, rowId: Long, expectedStatus: RellTransactionStatus) {
    withTxOperations(txSubmitterTestModule,
        TransactionSubmitterSpecialTxExtension.UPDATE_EVM_TRANSACTION_STATE
    ) { operations ->
        val statusOperations = operations
            .filter { it.args[0].asInteger() == rowId }
            .map { RellTransactionStatus.values()[it.args[1].asInteger().toInt()] }

        assertThat(statusOperations).contains(expectedStatus)
    }
}

// Evaluate sent operations
fun withTxOperations(txSubmitterTestModule: TransactionSubmitterTestGTXModule, operationName: String, op: (List<ExtOpData>) -> Unit) {

    val operations = txSubmitterTestModule.conf.operations
        .filter { it.opName == operationName }

    op(operations)
}

// Evaluate transactions in DB
fun withDbTransaction(node: PostchainTestNode, rowId: Long, op: (org.jooq.Record) -> Unit) {

    withReadConnection(node.getBlockchainInstance().blockchainEngine.sharedStorage, DEFAULT_CHAIN_IID) {
        val jooq = DSL.using(it.conn, SQLDialect.POSTGRES)

        val tableName = DatabaseAccess.of(it).tableEvmTransaction(it)

        val fetch = jooq
                .select()
                .from(tableName)
                .where(TRANSACTIONS_COLUMN_REQUEST_ID.eq(rowId))
                .fetchOne()

        op(fetch)
    }
}

// Evaluate errors in DB
fun withDbErrors(node: PostchainTestNode, rowId: Long, op: (List<org.jooq.Record>) -> Unit) {

    withReadConnection(node.getBlockchainInstance().blockchainEngine.sharedStorage, DEFAULT_CHAIN_IID) {
        val jooq = DSL.using(it.conn, SQLDialect.POSTGRES)

        val tableName = DatabaseAccess.of(it).tableEvmErrors(it)

        val fetch = jooq
            .select()
            .from(tableName)
            .where(ERRORS_COLUMN_REQUEST_ID.eq(rowId))
            .fetch()

        op(fetch)
    }
}
