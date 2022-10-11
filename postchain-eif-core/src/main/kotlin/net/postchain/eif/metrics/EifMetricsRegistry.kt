package net.postchain.eif.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.Metrics
import net.postchain.common.BlockchainRid
import net.postchain.eif.EvmEventProcessor
import net.postchain.eif.EventProcessor
import net.postchain.metrics.BLOCKCHAIN_RID_TAG
import net.postchain.metrics.CHAIN_IID_TAG

private const val CHAIN_ID_TAG = "chain_id"

class EifMetricsRegistry {

    private val meters = mutableMapOf<BlockchainRid, Meter>()

    fun registerMetrics(chainIID: Long, blockchainRid: BlockchainRid, chainId: Long, eventProcessor: EventProcessor) {
        if (eventProcessor is EvmEventProcessor) {
            meters[blockchainRid] = Gauge.builder("eif.last_read_evm_block_height") { eventProcessor.lastReadLogBlockHeight }
                .description(
                    "Last read evm block height. " +
                            "Note that we will not attempt to process events from this block until we have seen the configured 'read_offset' more blocks."
                )
                .tag(CHAIN_IID_TAG, chainIID.toString())
                .tag(BLOCKCHAIN_RID_TAG, blockchainRid.toHex())
                .tag(CHAIN_ID_TAG, chainId.toString())
                .register(Metrics.globalRegistry)
        }
    }

    fun unregisterMetrics(blockchainRid: BlockchainRid) {
        meters.remove(blockchainRid)?.also {
            Metrics.globalRegistry.remove(it)
        }
    }

    fun unregisterAllMetrics() {
        meters.keys.forEach(this::unregisterMetrics)
    }
}
