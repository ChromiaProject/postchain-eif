package net.postchain.eif.config

import net.postchain.eif.parseEvmAddress
import net.postchain.gtv.Gtv
import net.postchain.gtv.mapper.DefaultValue
import net.postchain.gtv.mapper.Name
import net.postchain.gtv.mapper.RawGtv

data class EifEvmBlockchainConfig(
        @param:RawGtv
        val rawGtv: Gtv,
        @param:Name("network_id")
        val networkId: Long,
        @param:Name("contracts")
        private val _contracts: List<String>?,
        @param:Name("events")
        val events: Gtv,
        @param:Name("skip_to_height")
        @param:DefaultValue(defaultLong = 0)
        val skipToHeight: Long,
        @param:Name("evm_read_offset")
        @param:DefaultValue(defaultLong = 100)
        val evmReadOffset: Long,
        @param:Name("read_offset")
        @param:DefaultValue(defaultLong = 2)
        val readOffset: Long,
        @param:Name("contracts_to_fetch")
        val contractsToFetch: List<EifEvmContractConfig>?,
) {
    val contracts get() = _contracts?.map { parseEvmAddress(it) }
}

data class EifEvmContractConfig(
        @param:Name("address")
        private val _address: String,
        @param:Name("skip_to_height")
        @param:DefaultValue(defaultLong = 0)
        val skipToHeight: Long,
) {
    val address get() = parseEvmAddress(_address)
}
