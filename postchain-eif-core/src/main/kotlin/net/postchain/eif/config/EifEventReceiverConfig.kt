package net.postchain.eif.config

import net.postchain.gtv.mapper.Name

data class EifEventReceiverConfig(
        @Name("chains")
        val chains: Map<String, EifEvmBlockchainConfig>,
)
