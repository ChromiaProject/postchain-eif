package net.postchain.eif.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.Metrics
import net.postchain.common.BlockchainRid
import net.postchain.eif.EthereumEventProcessor
import net.postchain.eif.EventProcessor
import net.postchain.metrics.BLOCKCHAIN_RID_TAG
import net.postchain.metrics.CHAIN_IID_TAG

class EifMetricsRegistry {
    private val meters = mutableMapOf<BlockchainRid, Meter>()

    fun registerMetrics(chainIID: Long, blockchainRid: BlockchainRid, eventProcessor: EventProcessor) {
        if (eventProcessor is EthereumEventProcessor) {
            meters[blockchainRid] = Gauge.builder("eif.last_read_ethereum_block_height") { eventProcessor.lastReadLogBlockHeight }
                .description(
                    "Last read ethereum block height. " +
                            "Note that we will not attempt to process events from this block until we have seen the configured 'read_offset' more blocks."
                )
                .tag(CHAIN_IID_TAG, chainIID.toString())
                .tag(BLOCKCHAIN_RID_TAG, blockchainRid.toHex())
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
