package net.postchain.eif.metrics

import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.Metrics
import net.postchain.common.BlockchainRid
import net.postchain.eif.EventFetcher
import net.postchain.eif.EventProcessor
import net.postchain.eif.EvmEventFetcher
import net.postchain.eif.EvmEventProcessor
import net.postchain.logging.BLOCKCHAIN_RID_TAG
import net.postchain.logging.CHAIN_IID_TAG
import java.math.BigInteger

const val NETWORK_ID_TAG = "network_id"

class EifMetricsRegistry {

    private val meters = mutableMapOf<BlockchainRid, MutableList<Meter>>()

    fun registerMetrics(chainIID: Long, blockchainRid: BlockchainRid, networkId: Long, eventProcessor: EventProcessor, eventFetcher: EventFetcher) {
        if (eventProcessor is EvmEventProcessor) {
            val gauge = Gauge.builder("eif.last_read_evm_block_height") { eventProcessor.lastReadLogBlockHeight }
                    .description(
                            "Last read evm block height. " +
                                    "Note that we will not attempt to process events from this block until we have seen the configured 'read_offset' more blocks."
                    )
                    .tag(CHAIN_IID_TAG, chainIID.toString())
                    .tag(BLOCKCHAIN_RID_TAG, blockchainRid.toHex())
                    .tag(NETWORK_ID_TAG, networkId.toString())
                    .register(Metrics.globalRegistry)

            val headGauge = Gauge.builder("eif.evm_head_block_height") { eventProcessor.lastSeenEvmHeadHeight }
                    .description("Last EVM chain head height reported by the RPC node, before any read offset is applied.")
                    .tag(CHAIN_IID_TAG, chainIID.toString())
                    .tag(BLOCKCHAIN_RID_TAG, blockchainRid.toHex())
                    .tag(NETWORK_ID_TAG, networkId.toString())
                    .register(Metrics.globalRegistry)

            val lagGauge = Gauge.builder("eif.evm_read_lag_blocks") { eventProcessor.readLagBlocks }
                    .description("Number of readable EVM blocks (head minus 'evm_read_offset') not yet read. 0 when the reader is caught up.")
                    .tag(CHAIN_IID_TAG, chainIID.toString())
                    .tag(BLOCKCHAIN_RID_TAG, blockchainRid.toHex())
                    .tag(NETWORK_ID_TAG, networkId.toString())
                    .register(Metrics.globalRegistry)

            meters.getOrPut(blockchainRid) { mutableListOf() }.addAll(listOf(gauge, headGauge, lagGauge))
        }
        if (eventFetcher is EvmEventFetcher) {
            val cursorGauge = Gauge.builder("eif.evm_read_cursor_height") { eventFetcher.lastPersistedCursorHeight ?: BigInteger.ZERO }
                    .description("Height of the last persisted EVM read cursor. A restarted reader resumes after this height. 0 until the reader has persisted anything.")
                    .tag(CHAIN_IID_TAG, chainIID.toString())
                    .tag(BLOCKCHAIN_RID_TAG, blockchainRid.toHex())
                    .tag(NETWORK_ID_TAG, networkId.toString())
                    .register(Metrics.globalRegistry)

            val flushCounters = mapOf(
                    "new_contract" to eventFetcher.newContractFlushes,
                    "validation_conflict" to eventFetcher.validationConflictFlushes,
            ).map { (reason, counter) ->
                FunctionCounter.builder("eif.evm_read_flush_total", counter) { it.get().toDouble() }
                        .description("Number of times the EVM read position was moved backwards, by reason.")
                        .tag(CHAIN_IID_TAG, chainIID.toString())
                        .tag(BLOCKCHAIN_RID_TAG, blockchainRid.toHex())
                        .tag(NETWORK_ID_TAG, networkId.toString())
                        .tag("reason", reason)
                        .register(Metrics.globalRegistry)
            }

            val signerConflictCounter = FunctionCounter.builder("eif.evm_signer_validation_conflict_total", eventFetcher.signerValidationConflicts) { it.get().toDouble() }
                    .description("Number of block proposals this node, as a signer, could not validate against the EVM events it fetched.")
                    .tag(CHAIN_IID_TAG, chainIID.toString())
                    .tag(BLOCKCHAIN_RID_TAG, blockchainRid.toHex())
                    .tag(NETWORK_ID_TAG, networkId.toString())
                    .register(Metrics.globalRegistry)

            meters.getOrPut(blockchainRid) { mutableListOf() }.addAll(flushCounters + cursorGauge + signerConflictCounter)
        }
    }

    fun unregisterMetrics(blockchainRid: BlockchainRid) {
        meters.remove(blockchainRid)?.onEach { Metrics.globalRegistry.remove(it) }
    }

    fun unregisterAllMetrics() {
        meters.keys.forEach(this::unregisterMetrics)
    }
}
