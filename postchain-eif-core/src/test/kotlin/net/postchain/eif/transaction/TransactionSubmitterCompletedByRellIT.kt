package net.postchain.eif.transaction

import net.postchain.devtools.getModules
import net.postchain.eif.EifBaseIntegrationTest
import net.postchain.eif.contracts.Validator
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
    This will emulate a node starting up but finds a transaction taken by the node now it completed by another node.

    1. Node starts up
    2. Finds and adds a pending TX.
    3. Poll and verify TX.
    4. Node updates BC status to PENDING. <- This test fails here due to BC status is already set to SUCCESS
    4. Node polls TX to verify it.
     */
    // TODO refactor due to change ot loading transactions on startup
//    @Test
//    fun `verify continue pending txs on startup drop due to rell status has been changed`() {
//
//        val node = createNodes(1, "/net/postchain/eif/transaction/blockchain_config_pending.xml")[0]
//        val txSubmitterTestModule = node.getModules().filterIsInstance<TransactionSubmitterTestGTXModule>().first()
//
//        // Set BC status to FAILURE
//        val txSubmitOnBc = mkEvmSubmitTxRellRequest(0, contractAddress,
//                status = RellTransactionStatus.SUCCESS
//        )
//        txSubmitterTestModule.addTransaction(txSubmitOnBc)
//
//        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
//            buildBlock(1L)
//
//            // It will fail since the transaction is not actually submitted
//            testLogAppender.assertWarn("Transaction 0 blockchain status is set to completed. This node will stop processing this transaction.")
//        }
//    }
}
