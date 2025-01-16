package net.postchain.eif

import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.hasSize
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
    private val sigMaker = myCS.buildSigMaker(KeyPair(KeyPairHelper.pubKey(0), KeyPairHelper.privKey(0)))

    @Test
    fun testEifBuildBlock() {
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

    @Test
    fun testResettingOnConflictingEvents() {
        val nodes = createNodes(4, "/net/postchain/eif/test_blockchain_config_4.xml")

        val nodeProcessors = nodes.map { getEventProcessor(it, 1) }

        nodeProcessors.subList(0, 3).forEach {
            it.processLogEventsAndUpdateOffsets(listOf(
                    EvmBlockOp(1, BigInteger.ONE, "01".hexStringToByteArray().wrap(), listOf()),
                    EvmBlockOp(1, BigInteger.TWO, "02".hexStringToByteArray().wrap(), listOf())
            ), BigInteger.TWO)
        }

        // Insert conflicting EVM block 2 for node4
        nodeProcessors[3].processLogEventsAndUpdateOffsets(listOf(
                EvmBlockOp(1, BigInteger.ONE, "01".hexStringToByteArray().wrap(), listOf()),
                EvmBlockOp(1, BigInteger.TWO, "03".hexStringToByteArray().wrap(), listOf())
        ), BigInteger.TWO)

        buildBlock(nodes.toList().subList(0, 3), 1, 1)
        assertThat(nodeProcessors[3].lastReadLogBlockHeight).isEqualTo(BigInteger.TWO)
        assertThat(nodeProcessors[3].getEventData()).hasSize(2)
        buildBlock(nodes.toList().subList(0, 3), 1, 2)
        assertThat(nodeProcessors[3].lastReadLogBlockHeight).isEqualTo(BigInteger.TWO)
        assertThat(nodeProcessors[3].getEventData()).hasSize(2)
        buildBlock(nodes.toList().subList(0, 3), 1, 3)
        // Now we should fall into fast sync and reset conflicting event
        await().atMost(Duration.ONE_MINUTE).untilAsserted {
            assertThat(nodeProcessors[3].lastReadLogBlockHeight).isEqualTo(BigInteger.ONE)
            assertThat(nodeProcessors[3].getEventData()).containsOnly(EvmBlockOp(1, BigInteger.ONE, "01".hexStringToByteArray().wrap(), listOf()))
        }

        // Assert that we can sync up again
        nodeProcessors[3].processLogEventsAndUpdateOffsets(listOf(
                EvmBlockOp(1, BigInteger.TWO, "02".hexStringToByteArray().wrap(), listOf())
        ), BigInteger.TWO)
        await().atMost(Duration.ONE_MINUTE).untilAsserted {
            assertThat(nodes[3].blockQueries().getLastBlockHeight().get()).isEqualTo(3L)
        }
    }

    @Test
    fun testResettingWhenMissingEvents() {
        val nodes = createNodes(4, "/net/postchain/eif/test_blockchain_config_4.xml")

        val nodeProcessors = nodes.map { getEventProcessor(it, 1) }

        nodeProcessors.subList(0, 3).forEach {
            it.processLogEventsAndUpdateOffsets(listOf(
                    EvmBlockOp(1, BigInteger.ONE, "01".hexStringToByteArray().wrap(), listOf())
            ), BigInteger.TWO)
        }

        // Insert nothing for node4
        nodeProcessors[3].processLogEventsAndUpdateOffsets(listOf(), BigInteger.TWO)

        buildBlock(nodes.toList().subList(0, 3), 1, 1)
        assertThat(nodeProcessors[3].lastReadLogBlockHeight).isEqualTo(BigInteger.TWO)
        buildBlock(nodes.toList().subList(0, 3), 1, 2)
        assertThat(nodeProcessors[3].lastReadLogBlockHeight).isEqualTo(BigInteger.TWO)
        buildBlock(nodes.toList().subList(0, 3), 1, 3)
        // Now we should fall into fast sync and reset conflicting event
        await().atMost(Duration.ONE_MINUTE).untilAsserted {
            assertThat(nodeProcessors[3].lastReadLogBlockHeight).isEqualTo(BigInteger.ZERO)
        }

        // Assert that we can sync up again by providing correct history
        nodeProcessors[3].processLogEventsAndUpdateOffsets(listOf(
                EvmBlockOp(1, BigInteger.ONE, "01".hexStringToByteArray().wrap(), listOf())
        ), BigInteger.TWO)
        await().atMost(Duration.ONE_MINUTE).untilAsserted {
            assertThat(nodes[3].blockQueries().getLastBlockHeight().get()).isEqualTo(3L)
        }
    }

    @Test
    fun testResettingOnMisMatchingEvents() {
        val nodes = createNodes(4, "/net/postchain/eif/test_blockchain_config_4.xml")

        val nodeProcessors = nodes.map { getEventProcessor(it, 1) }

        nodeProcessors.subList(0, 3).forEach {
            it.processLogEventsAndUpdateOffsets(listOf(
                    EvmBlockOp(1, BigInteger.ONE, "01".hexStringToByteArray().wrap(), listOf()),
                    EvmBlockOp(1, BigInteger.valueOf(3), "03".hexStringToByteArray().wrap(), listOf())
            ), BigInteger.valueOf(4))
        }

        // Insert conflicting EVM blocks for node4 (should revert back to height two)
        nodeProcessors[3].processLogEventsAndUpdateOffsets(listOf(
                EvmBlockOp(1, BigInteger.ONE, "01".hexStringToByteArray().wrap(), listOf()),
                EvmBlockOp(1, BigInteger.valueOf(4), "04".hexStringToByteArray().wrap(), listOf())
        ), BigInteger.valueOf(4))

        buildBlock(nodes.toList().subList(0, 3), 1, 1)
        assertThat(nodeProcessors[3].lastReadLogBlockHeight).isEqualTo(BigInteger.valueOf(4))
        assertThat(nodeProcessors[3].getEventData()).hasSize(2)
        buildBlock(nodes.toList().subList(0, 3), 1, 2)
        assertThat(nodeProcessors[3].lastReadLogBlockHeight).isEqualTo(BigInteger.valueOf(4))
        assertThat(nodeProcessors[3].getEventData()).hasSize(2)
        buildBlock(nodes.toList().subList(0, 3), 1, 3)
        // Now we should fall into fast sync and reset conflicting event
        await().atMost(Duration.ONE_MINUTE).untilAsserted {
            assertThat(nodeProcessors[3].lastReadLogBlockHeight).isEqualTo(BigInteger.TWO)
            assertThat(nodeProcessors[3].getEventData()).containsOnly(EvmBlockOp(1, BigInteger.ONE, "01".hexStringToByteArray().wrap(), listOf()))
        }

        // Insert conflicting EVM blocks for node4 (should revert back to height one)
        nodeProcessors[3].processLogEventsAndUpdateOffsets(listOf(
                EvmBlockOp(1, BigInteger.TWO, "02".hexStringToByteArray().wrap(), listOf())
        ), BigInteger.valueOf(4))
        await().atMost(Duration.ONE_MINUTE).untilAsserted {
            assertThat(nodeProcessors[3].lastReadLogBlockHeight).isEqualTo(BigInteger.ONE)
            assertThat(nodeProcessors[3].getEventData()).containsOnly(EvmBlockOp(1, BigInteger.ONE, "01".hexStringToByteArray().wrap(), listOf()))
        }

        // Assert that we can sync up again with correct history
        nodeProcessors[3].processLogEventsAndUpdateOffsets(listOf(
                EvmBlockOp(1, BigInteger.valueOf(3), "03".hexStringToByteArray().wrap(), listOf())
        ), BigInteger.valueOf(4))
        await().atMost(Duration.ONE_MINUTE).untilAsserted {
            assertThat(nodes[3].blockQueries().getLastBlockHeight().get()).isEqualTo(3L)
        }
    }

    private fun getEventProcessor(node: PostchainTestNode, networkId: Long) = (node.getBlockchainInstance().blockchainEngine
            .getConfiguration() as GTXModuleAware).module.getSpecialTxExtensions()
            .filterIsInstance<EifSpecialTxExtension>().first()
            .processors[networkId]?.first as EvmEventProcessor

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
        val b = GtxBuilder(bcRid, listOf(KeyPairHelper.pubKey(0)), myCS)
        b.addOperation("gtx_test", gtv(id), gtv(value))
        return b.finish()
                .sign(sigMaker)
                .buildGtx()
                .encode()
    }
}
