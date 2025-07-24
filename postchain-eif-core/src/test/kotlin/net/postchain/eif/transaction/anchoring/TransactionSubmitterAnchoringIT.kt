package net.postchain.eif.transaction.anchoring

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import net.postchain.common.toHex
import net.postchain.concurrent.util.get
import net.postchain.devtools.addBlockchainAndStart
import net.postchain.devtools.getModules
import net.postchain.eif.EifBaseIntegrationTest
import net.postchain.eif.contracts.Anchoring
import net.postchain.eif.contracts.Validator
import net.postchain.eif.getEthereumAddress
import net.postchain.eif.sendAsyncAwait
import net.postchain.eif.transaction.RellTransactionStatus
import net.postchain.eif.transaction.assertStatusOperation
import org.awaitility.Awaitility
import org.awaitility.Duration
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Testcontainers
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.tx.Contract

@Testcontainers(disabledWithoutDocker = true)
class TransactionSubmitterAnchoringIT : EifBaseIntegrationTest() {

    @Test
    fun `Anchoring blocks on EVM`() {
        with(configOverrides) {
            setProperty("ethereum.privateKey", "0x53914554952e5473a54b211a31303078abde83b8128995785901eed28df3f610")
            setProperty("evm.txPollInterval", 1000)
            setProperty("api.port", -1)
            setProperty("messaging.port", 0)
        }

        val nodes = createNodes(1, "/net/postchain/eif/transaction/anchoring/blockchain_config_sac_mock.xml")
        val node = nodes[0]
        val systemAnchoringChain = 1L
        val systemAnchoringMockBrid = node.getBlockchainRid(1)!!
        buildBlock(systemAnchoringChain, 2) // Build a few blocks

        // Deploy validator contract
        val encodedValidatorConstructor = FunctionEncoder.encodeConstructor(listOf(DynamicArray(Address::class.java, Address(getEthereumAddress(node.appConfig.pubKeyByteArray).toHex()))))
        val validatorContract = Contract.deployRemoteCall(Validator::class.java, web3j, transactionManager, gasProvider, validatorBinary, encodedValidatorConstructor).sendAsyncAwait()

        // Deploy anchoring contract
        val encodedAnchoringConstructor = FunctionEncoder.encodeConstructor(listOf(Address(validatorContract.contractAddress), Bytes32(systemAnchoringMockBrid.data)))
        val anchoringBinary = getBinaryFromArtifactResource("/net/postchain/eif/contracts/Anchoring.bin")
        val anchoringContract = Contract.deployRemoteCall(Anchoring::class.java, web3j, transactionManager, gasProvider, anchoringBinary, encodedAnchoringConstructor).sendAsyncAwait()

        val txSubmitterConfig = readBlockchainConfig("/net/postchain/eif/transaction/anchoring/blockchain_config_with_anchoring.xml")

        val txSubmitterChain = 2L
        node.addBlockchainAndStart(txSubmitterChain, txSubmitterConfig)

        val txSubmitterTestModule = node.getModules(txSubmitterChain).filterIsInstance<TransactionSubmitterAnchoringTestGTXModule>().first()

        var txHash: String? = null
        Awaitility.await().atMost(Duration.ONE_MINUTE).untilAsserted {
            buildBlock(nodes.toList(), txSubmitterChain)
            assertStatusOperation(txSubmitterTestModule, 0, RellTransactionStatus.SUCCESS)

            txHash = assertStatusOperation(txSubmitterTestModule, 0, RellTransactionStatus.PENDING)
            assertThat(txHash).isNotNull()
        }

        // Assert anchoring event was emitted
        val txReceipt = web3j.ethGetTransactionReceipt(txHash).send().transactionReceipt.get()
        val events = Anchoring.getAnchoredBlockEvents(txReceipt)

        assertThat(events).hasSize(1)

        val anchoringEvent = events.first()
        val height = anchoringEvent.blockHeader.height.value.longValueExact()

        val actualBlockAtHeight = node.blockQueries(systemAnchoringChain).getBlockAtHeight(height).get()!!
        assertThat(anchoringEvent.blockHeader.blockRid.value).isEqualTo(actualBlockAtHeight.header.blockRID)
        assertThat(anchoringEvent.blockHeader.previousBlockRid.value).isEqualTo(actualBlockAtHeight.header.prevBlockRID)

        // Verify contract state
        assertThat(anchoringContract.lastAnchoredHeight().send().value.longValueExact()).isEqualTo(height)
        assertThat(anchoringContract.lastAnchoredBlockRid().send().value).isEqualTo(actualBlockAtHeight.header.blockRID)
        // Using the function (should be the same)
        val lastAnchoredBlockResponse = anchoringContract.lastAnchoredBlock.send()
        assertThat(lastAnchoredBlockResponse.component1().value.longValueExact()).isEqualTo(height)
        assertThat(lastAnchoredBlockResponse.component2().value).isEqualTo(actualBlockAtHeight.header.blockRID)
    }
}