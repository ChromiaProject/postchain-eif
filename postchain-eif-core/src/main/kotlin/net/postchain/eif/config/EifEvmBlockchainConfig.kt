package net.postchain.eif.config

import net.postchain.gtv.Gtv
import net.postchain.gtv.mapper.DefaultValue
import net.postchain.gtv.mapper.Name
import net.postchain.gtv.mapper.RawGtv

data class EifEvmBlockchainConfig(
        @RawGtv
        val rawGtv: Gtv,
        @Name("network_id")
        val networkId: Long,
        @Name("contracts")
        val contracts: List<String>?,
        @Name("events")
        val events: Gtv,
        @Name("skip_to_height")
        @DefaultValue(defaultLong = 0)
        val skipToHeight: Long,
        @Name("evm_read_offset")
        @DefaultValue(defaultLong = 100)
        val evmReadOffset: Long,
        @Name("read_offset")
        @DefaultValue(defaultLong = 2)
        val readOffset: Long,
        @Name("max_queue_size")
        @DefaultValue(defaultLong = 2_000)
        val maxQueueSize: Long,
        @Name("contracts_to_fetch")
        val contractsToFetch: List<EifEvmContractConfig>?,
)

data class EifEvmContractConfig(
        @Name("address")
        val address: String,
        @Name("skip_to_height")
        @DefaultValue(defaultLong = 0)
        val skipToHeight: Long,
)
