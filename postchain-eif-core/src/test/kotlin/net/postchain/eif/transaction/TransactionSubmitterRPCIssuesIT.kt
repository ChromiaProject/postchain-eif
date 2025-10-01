package net.postchain.eif.transaction

import net.postchain.devtools.ManagedModeTest
import net.postchain.devtools.getModules
import org.awaitility.Awaitility
import org.awaitility.Duration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.concurrent.TimeUnit

@Timeout(value = 5, unit = TimeUnit.MINUTES)
@Testcontainers(disabledWithoutDocker = true)
class TransactionSubmitterRPCIssuesIT : ManagedModeTest() {

    @BeforeEach
    fun setup() {
        with(configOverrides) {
            setProperty("ethereum.privateKey", "0x53914554952e5473a54b211a31303078abde83b8128995785901eed28df3f610")
            setProperty("evm.txPollInterval", 1000)
            setProperty("ethereum.urls", "http://localhost:1")
            setProperty("api.port", -1)
            setProperty("messaging.port", 0)
            setProperty("evm.healthCheckInterval", -1)
        }
    }

    @Test
    fun `submit transaction fails immediate due to unreachable rpc and sets a failure reason`() {

        val nodes = createNodes(1, "/net/postchain/eif/transaction/blockchain_config.xml")
        val node = nodes[0]

        val txSubmitterTestModule = node.getModules().filterIsInstance<TransactionSubmitterTestGTXModule>().first()

        val evmSubmitTxRellRequest = mkEvmSubmitTxRellRequest(0, "0x8A2279d4A90B6fe1C4B30fa660cC9f926797bAA2")
        txSubmitterTestModule.addTransactionsAvailableToTake(evmSubmitTxRellRequest)

        Awaitility.await().atMost(Duration.TEN_SECONDS.multiply(2)).untilAsserted {
            buildBlock(1L)
            assertStatusOperation(txSubmitterTestModule, 0, RellTransactionStatus.QUEUED)
            assertNodeFailureOperation(txSubmitterTestModule, evmSubmitTxRellRequest.rowId,
"Failed to get gas estimate")
        }
    }
}
