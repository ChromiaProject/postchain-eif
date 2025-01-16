package net.postchain.eif

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import net.postchain.common.BlockchainRid
import net.postchain.common.hexStringToByteArray
import net.postchain.common.wrap
import net.postchain.concurrent.util.get
import net.postchain.core.Transaction
import net.postchain.crypto.KeyPair
import net.postchain.crypto.Secp256K1CryptoSystem
import net.postchain.crypto.devtools.KeyPairHelper
import net.postchain.devtools.IntegrationTestSetup
import net.postchain.devtools.PostchainTestNode
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtv.merkle.GtvMerkleHashCalculatorV2
import net.postchain.gtx.GTXModuleAware
import net.postchain.gtx.Gtx
import net.postchain.gtx.GtxBuilder
import org.awaitility.Awaitility.await
import org.awaitility.Duration
import org.awaitility.kotlin.await
import org.junit.jupiter.api.Test
import java.math.BigInteger

class EifEventProcessingIT : IntegrationTestSetup() {

    private val myCS = Secp256K1CryptoSystem()
    private val merkleHashCalculator = GtvMerkleHashCalculatorV2(myCS)
    private val sigMaker = myCS.buildSigMaker(KeyPair(KeyPairHelper.pubKey(0), KeyPairHelper.privKey(0)))

    @Test
    fun testEifBuildBlock() {
        configOverrides.setProperty("ethereum.urls", "test")

        val nodes = createNodes(1, "/net/postchain/eif/test_blockchain_config.xml")
        val node = nodes[0]
        val bcRid = systemSetup.blockchainMap[1]!!.rid // Just assume we have chain 1

        // BaseBlockBuildingStrategy builds block 0 unconditionally and asynchronously.
        // This means we cannot guarantee that both the tx and the event will be included in block 0.
        // That's why we are skipping block 0.
        await.atMost(Duration.ONE_MINUTE).untilAsserted {
            assertThat(getLastHeight(node)).isEqualTo(0L)
        }

        val testProcessor = getEventProcessor(node, 1)

        // Posting the tx and the event and waiting for block 1 to be built (see `test_blockchain_config.xml`)
        enqueueTx(node, makeTestTx(1, "true", bcRid))!!
        testProcessor.processLogEventsAndUpdateOffsets(listOf(
                EvmBlockOp(1, BigInteger.ONE, "01".hexStringToByteArray().wrap(), listOf())
        ), BigInteger.valueOf(3L))

        await().atMost(Duration.ONE_MINUTE)
                .untilAsserted {
                    val ridsAtHeight = getTxRidsAtHeight(node, getLastHeight(node))

                    assertThat(ridsAtHeight.any {
                        val op = Gtx.decode(node.blockQueries().getTransactionRawData(it).get()!!).gtxBody.operations.first()
                        op.opName == "gtx_test"
                    }).isTrue()

                    assertThat(ridsAtHeight.any {
                        val op = Gtx.decode(node.blockQueries().getTransactionRawData(it).get()!!).gtxBody.operations.first()
                        op.opName == EvmBlockOp.OP_NAME && op.args[1].asBigInteger() == BigInteger.ONE
                    }).isTrue()
                }

        // Posting the new event and waiting for block 2 to be built
        testProcessor.processLogEventsAndUpdateOffsets(listOf(
                EvmBlockOp(1, BigInteger.TWO, "02".hexStringToByteArray().wrap(), listOf())
        ), BigInteger.valueOf(4L))

        await().atMost(Duration.ONE_MINUTE)
                .untilAsserted {
                    val ridsAtHeight = getTxRidsAtHeight(node, getLastHeight(node))

                    assertThat(ridsAtHeight.any {
                        val op = Gtx.decode(node.blockQueries().getTransactionRawData(it).get()!!).gtxBody.operations.first()
                        op.opName == EvmBlockOp.OP_NAME && op.args[1].asBigInteger() == BigInteger.TWO
                    }).isTrue()
                }
    }

    private fun getEventProcessor(node: PostchainTestNode, networkId: Long) = (node.getBlockchainInstance().blockchainEngine
            .getConfiguration() as GTXModuleAware).module.getSpecialTxExtensions()
            .filterIsInstance<EifSpecialTxExtension>().first()
            .processors[networkId] as EvmEventProcessor

    private fun enqueueTx(node: PostchainTestNode, data: ByteArray): Transaction? {
        try {
            val tx = node.getBlockchainInstance().blockchainEngine.getConfiguration().getTransactionFactory()
                    .decodeTransaction(data)
            node.getBlockchainInstance().blockchainEngine.getTransactionQueue().enqueue(tx)
            return tx
        } catch (e: Exception) {
            logger.error(e) { "Can't enqueue tx" }
        }
        return null
    }

    private fun makeTestTx(id: Long, value: String, bcRid: BlockchainRid): ByteArray {
        val b = GtxBuilder(bcRid, listOf(KeyPairHelper.pubKey(0)), myCS, merkleHashCalculator)
        b.addOperation("gtx_test", gtv(id), gtv(value))
        return b.finish()
                .sign(sigMaker)
                .buildGtx()
                .encode()
    }
}
