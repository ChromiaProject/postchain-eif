package net.postchain.eif.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Metrics
import net.postchain.common.BlockchainRid
import net.postchain.logging.BLOCKCHAIN_RID_TAG
import net.postchain.logging.CHAIN_IID_TAG

class RpcUsageMetrics(val chainIID: Long, val blockchainRid: BlockchainRid, val networkId: Long) {

    fun createUsageCounter(index: Int): Counter = Counter.builder("rpc.counter.${index}")
            .description("Number of RPC calls")
            .tag(CHAIN_IID_TAG, chainIID.toString())
            .tag(BLOCKCHAIN_RID_TAG, blockchainRid.toHex())
            .tag(NETWORK_ID_TAG, networkId.toString())
            .register(Metrics.globalRegistry)
}