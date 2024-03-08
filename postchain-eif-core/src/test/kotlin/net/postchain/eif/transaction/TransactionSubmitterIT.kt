package net.postchain.eif.transaction

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import net.postchain.common.BlockchainRid
import net.postchain.devtools.getModules
import net.postchain.devtools.utils.configuration.NodeSeqNumber
import net.postchain.eif.EifBaseIntegrationTest
import net.postchain.eif.EvmType
import net.postchain.eif.contracts.Validator
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_ERRORS_COLUMN_MESSAGE
import net.postchain.gtv.GtvFactory.gtv
import org.apache.commons.configuration2.MapConfiguration
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
class TransactionSubmitterIT : EifBaseIntegrationTest(
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

        val evmSubmitTxRellRequest = EvmSubmitTxRellRequest(
            0,
            contractAddress,
            "updateValidators",
            listOf("address[]"),
            listOf(gtv(listOf(gtv(ByteArray(20) { 1 })))),
            1337,
            BlockchainRid.ZERO_RID.data,
            System.currentTimeMillis()
        )
        txSubmitterTestModule.addTxToQueue(evmSubmitTxRellRequest)

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertTrue(txSubmitterTestModule.conf.queue.isEmpty())
            assertTrue(txSubmitterTestModule.conf.taken.contains(0))
            assertStatusOperation(txSubmitterTestModule, evmSubmitTxRellRequest.rowId, RellTransactionStatus.TAKEN)
        }

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertStatusOperation(
                txSubmitterTestModule,
                evmSubmitTxRellRequest.rowId,
                RellTransactionStatus.PENDING
            )
        }

        withDbErrors(node, evmSubmitTxRellRequest.rowId) {
            assertThat(it.size).isEqualTo(0)
        }
    }

    @Test
    fun `verify pending transaction`() {

        val nodes = createNodes(1, "/net/postchain/eif/transaction/blockchain_config.xml")
        val node = nodes[0]

        val txSubmitterTestModule = node.getModules().filterIsInstance<TransactionSubmitterTestGTXModule>().first()

        val sendResult =
            sendTransaction(contractAddress)

        val evmSubmitTransactionRequest = mkEvmPendingRellTx(sendResult!!.transactionHash, contractAddress)

        txSubmitterTestModule.addGetPendingTransactions(evmSubmitTransactionRequest)

        // Exists and might have the first receipt - but we don't know since it is asynchronous
        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)

            val txExistsAsPending: Boolean? = withTxSubmitter(txSubmitterTestModule, 0) { _, _ ->
                true
            }
            assertThat(txExistsAsPending).isNotNull().isEqualTo(true)
        }

        // After a few evm blocks (0 in this test) verify receipt
        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertStatusOperation(
                txSubmitterTestModule,
                evmSubmitTransactionRequest.rowId,
                RellTransactionStatus.SUCCESS
            )
            withUpdateEvmTransactionReceipt(txSubmitterTestModule, evmSubmitTransactionRequest.rowId) {
                assertThat(it.size).isEqualTo(1)
                assertThat(it[0].blockHash).isNotNull()
                assertThat(it[0].effectiveGasPrice).isEqualTo(4100000000)
                assertThat(it[0].gasUsage).isEqualTo(58575)
            }
        }
    }

    @Test
    fun `verify submit and verify transaction`() {

        val nodes = createNodes(1, "/net/postchain/eif/transaction/blockchain_config.xml")
        val node = nodes[0]

        val txSubmitterTestModule = node.getModules().filterIsInstance<TransactionSubmitterTestGTXModule>().first()

        val evmSubmitTxRellRequest = EvmSubmitTxRellRequest(
            0,
            contractAddress,
            "updateValidators",
            listOf("address[]"),
            listOf(gtv(listOf(gtv(ByteArray(20) { 1 })))),
            1337,
            BlockchainRid.ZERO_RID.data,
            System.currentTimeMillis()
        )
        txSubmitterTestModule.addTxToQueue(evmSubmitTxRellRequest)

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertTrue(txSubmitterTestModule.conf.queue.isEmpty())
            assertTrue(txSubmitterTestModule.conf.taken.contains(0))
            assertStatusOperation(txSubmitterTestModule, evmSubmitTxRellRequest.rowId, RellTransactionStatus.TAKEN)
        }

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertStatusOperation(
                txSubmitterTestModule,
                evmSubmitTxRellRequest.rowId,
                RellTransactionStatus.PENDING
            )
        }

        withDbErrors(node, evmSubmitTxRellRequest.rowId) {
            assertThat(it.size).isEqualTo(0)
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
                evmSubmitTxRellRequest.rowId,
                RellTransactionStatus.SUCCESS
            )
            withUpdateEvmTransactionReceipt(txSubmitterTestModule, evmSubmitTxRellRequest.rowId) {
                assertThat(it.size).isEqualTo(1)
                assertThat(it[0].blockHash).isNotNull()
                assertThat(it[0].effectiveGasPrice).isEqualTo(4100000000)
                assertThat(it[0].gasUsage).isEqualTo(58575)
            }
        }
    }

    @Test
    fun `Tx should fail if estimated gas usage is above limit`() {
        val nodes = createNodes(1, "/net/postchain/eif/transaction/blockchain_config_low_gas_limit.xml")
        val node = nodes[0]

        val txSubmitterTestModule = node.getModules().filterIsInstance<TransactionSubmitterTestGTXModule>().first()

        val evmSubmitTxRellRequest = EvmSubmitTxRellRequest(
            0,
            contractAddress,
            "updateValidators",
            listOf("address[]"),
            listOf(gtv(listOf(gtv(ByteArray(20) { 1 })))),
            1337,
            BlockchainRid.ZERO_RID.data,
            System.currentTimeMillis()
        )
        txSubmitterTestModule.addTxToQueue(evmSubmitTxRellRequest)

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertTrue(txSubmitterTestModule.conf.queue.isEmpty())
        }

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertTrue(txSubmitterTestModule.conf.queuedTxs.contains(0))
        }
    }

    @Test
    fun `cleanup old transactions`() {
        val nodes = createNodes(1, "/net/postchain/eif/transaction/blockchain_config_cleanup.xml")
        val node = nodes[0]

        assertThat(countDbSubmit(node)).isEqualTo(2)
        assertThat(countDErrors(node)).isEqualTo(2)

        buildBlock(1L)

        assertThat(countDbSubmit(node)).isEqualTo(1)
        assertThat(countDErrors(node)).isEqualTo(1)
    }

    @Test
    fun `submit transaction in multi node env - successfully`() {

        val nodes = createNodes(4, "/net/postchain/eif/transaction/blockchain_config_4_nodes.xml")

        val allTxSubmitterTestModules =
            nodes.map { it.getModules().filterIsInstance<TransactionSubmitterTestGTXModule>().first() }

        val txSubmit = EvmSubmitTxRellRequest(
            0,
            contractAddress,
            "updateValidators",
            listOf("address[]"),
            listOf(gtv(listOf(gtv(ByteArray(20) { 1 })))),
            1337,
            BlockchainRid.ZERO_RID.data,
            System.currentTimeMillis()
        )

        // Mock rell status for other nodes to be able to verify the operation
        allTxSubmitterTestModules.forEach { it.addGetTransactionStatus(txSubmit.rowId, RellTransactionStatus.QUEUED) }

        // Make it available for node[0]
        allTxSubmitterTestModules[0].addTxToQueue(txSubmit)

        // node[0] will give it a try and succeed
        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertTrue(allTxSubmitterTestModules[0].conf.queue.isEmpty())
            assertStatusOperation(allTxSubmitterTestModules[0], txSubmit.rowId, RellTransactionStatus.TAKEN)
        }

        // Mock rell status for other nodes to be able to verify the operation
        allTxSubmitterTestModules.forEach { it.addGetTransactionStatus(txSubmit.rowId, RellTransactionStatus.TAKEN) }

        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertStatusOperation(allTxSubmitterTestModules[0], txSubmit.rowId, RellTransactionStatus.PENDING)
        }

        // Mock rell status for other nodes to be able to verify the operation
        allTxSubmitterTestModules.forEach { it.addGetTransactionStatus(txSubmit.rowId, RellTransactionStatus.PENDING) }

        // Make sure node[1] updates the status to PENDING with a tx hash
        val txHash: String? = withTxOperations(
            allTxSubmitterTestModules[0],
            TransactionSubmitterSpecialTxExtension.UPDATE_EVM_TRANSACTION_STATUS
        ) { operations ->
            val statusOperations = operations
                .filter {
                    it.args[0].asInteger() == txSubmit.rowId &&
                            RellTransactionStatus.values()[it.args[1].asInteger()
                                .toInt()] == RellTransactionStatus.PENDING &&
                            it.args.size == 3
                }
                .map { it.args[2].asString() }

            if (statusOperations.isNotEmpty()) statusOperations[0] else null
        }

        // Make the pending transaction available for all nodes to verify
        allTxSubmitterTestModules.forEach {
            it.addGetPendingTransactions(
                EvmPendingRellTx(
                    txSubmit.rowId,
                    txSubmit.networkId,
                    txSubmit.contractAddress,
                    txSubmit.functionName,
                    txSubmit.parameterTypes,
                    txSubmit.parameterValues,
                    txHash!!
                )
            )
        }

//        nodes.forEach { node ->
//            Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
//
//                // Let the healthy nodes build the block
//                buildBlock(1L)
//
//                val txExistsAndVerified: Boolean? = withTxSubmitter(allTxSubmitterTestModules, 0) { txSubmitter, pendingTx ->
//                    // Pending tx found in this txSubmitter - is the verification completed?
//                    val verifiedTxs = txSubmitter.getVerifiedTransactions(0)
//                    verifiedTxs.any { it.rowId == 0L }
//                }
//                assertThat(txExistsAndVerified).isNotNull().isEqualTo(true)
//            }
//        }
//
//        // One node got to set the status SUCCESS
//        assertTrue(allTxSubmitterTestModules.any { it.conf.successfulTxs.contains(0) })

        // One node got to set the status SUCCESS
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

        val txSubmit = EvmSubmitTxRellRequest(
            0,
            contractAddress,
            "updateValidators",
            listOf("address[]"),
            listOf(gtv(listOf(gtv(ByteArray(20) { 1 })))),
            1337,
            BlockchainRid.ZERO_RID.data,
            System.currentTimeMillis()
        )

        // Mock rell status for other nodes to be able to verify the operation
        allTxSubmitterTestModules.forEach { it.addGetTransactionStatus(txSubmit.rowId, RellTransactionStatus.QUEUED) }

        // Make it available for node[0]
        txSubmitterTestModule0.addTxToQueue(txSubmit)

        // node[0] will give it a try but fail
        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertTrue(txSubmitterTestModule0.conf.queue.isEmpty())
            assertStatusOperation(txSubmitterTestModule0, txSubmit.rowId, RellTransactionStatus.TAKEN)
        }

        // Mock rell status for other nodes to be able to verify the operation
        allTxSubmitterTestModules.forEach { it.addGetTransactionStatus(txSubmit.rowId, RellTransactionStatus.TAKEN) }

        // Let the node fail
        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertStatusOperation(txSubmitterTestModule0, txSubmit.rowId, RellTransactionStatus.QUEUED)
            withDbErrors(nodes[0], 0) {
                assertThat(it.size).isEqualTo(1)
                assertThat(it[0].get(EVM_TX_ERRORS_COLUMN_MESSAGE)).isEqualTo("Failed to get balance for request id 0: Failed to send web3j request to all 1 nodes")
            }
        }

        // Mock rell status for other nodes to be able to verify the operation
        allTxSubmitterTestModules.forEach { it.addGetTransactionStatus(txSubmit.rowId, RellTransactionStatus.QUEUED) }

        //  Make it available for node[1]
        txSubmitterTestModule1.addTxToQueue(txSubmit)

        // node[1] will give it a try and succeed
        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertTrue(txSubmitterTestModule1.conf.queue.isEmpty())
            assertStatusOperation(txSubmitterTestModule1, txSubmit.rowId, RellTransactionStatus.TAKEN)
        }

        // Mock rell status for other nodes to be able to verify the operation
        allTxSubmitterTestModules.forEach { it.addGetTransactionStatus(txSubmit.rowId, RellTransactionStatus.TAKEN) }

        // Let the node fail
        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(1L)
            assertStatusOperation(txSubmitterTestModule0, txSubmit.rowId, RellTransactionStatus.PENDING)
        }

        // Mock rell status for other nodes to be able to verify the operation
        allTxSubmitterTestModules.forEach { it.addGetTransactionStatus(txSubmit.rowId, RellTransactionStatus.PENDING) }

        // Make sure node[1] updates the status to PENDING with a tx hash
        val txHash: String? = withTxOperations(
            txSubmitterTestModule1,
            TransactionSubmitterSpecialTxExtension.UPDATE_EVM_TRANSACTION_STATUS
        ) { operations ->
            val statusOperations = operations
                .filter {
                    it.args[0].asInteger() == txSubmit.rowId &&
                            RellTransactionStatus.values()[it.args[1].asInteger()
                                .toInt()] == RellTransactionStatus.PENDING &&
                            it.args.size == 3
                }
                .map { it.args[2].asString() }

            if (statusOperations.isNotEmpty()) statusOperations[0] else null
        }

        // Make the pending transaction available for all nodes to verify
        allTxSubmitterTestModules.forEach {
            it.addGetPendingTransactions(
                EvmPendingRellTx(
                    txSubmit.rowId,
                    txSubmit.networkId,
                    txSubmit.contractAddress,
                    txSubmit.functionName,
                    txSubmit.parameterTypes,
                    txSubmit.parameterValues,
                    txHash!!
                )
            )
        }

        // One node got to set the status SUCCESS
        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(nodesExceptFirst, 1L)
            assertTrue(allTxSubmitterTestModulesExceptFirst.any { it.conf.successfulTxs.contains(0) })
        }
    }
}
