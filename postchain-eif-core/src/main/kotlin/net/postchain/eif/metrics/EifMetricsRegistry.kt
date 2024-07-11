package net.postchain.eif.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.Metrics
import net.postchain.common.BlockchainRid
import net.postchain.eif.EventProcessor
import net.postchain.eif.EvmEventProcessor
import net.postchain.logging.BLOCKCHAIN_RID_TAG
import net.postchain.logging.CHAIN_IID_TAG

const val NETWORK_ID_TAG = "network_id"

class EifMetricsRegistry {

    private val meters = mutableMapOf<BlockchainRid, MutableList<Meter>>()

    fun registerMetrics(chainIID: Long, blockchainRid: BlockchainRid, networkId: Long, eventProcessor: EventProcessor) {
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

            meters.getOrPut(blockchainRid) { mutableListOf(gauge) }.add(gauge)
        }
    }

    fun unregisterMetrics(blockchainRid: BlockchainRid) {
        meters.remove(blockchainRid)?.onEach { Metrics.globalRegistry.remove(it) }
    }

    fun unregisterAllMetrics() {
        meters.keys.forEach(this::unregisterMetrics)
    }
}
