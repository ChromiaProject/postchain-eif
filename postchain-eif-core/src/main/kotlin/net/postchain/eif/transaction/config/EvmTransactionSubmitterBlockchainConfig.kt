package net.postchain.eif.transaction.config

import net.postchain.gtv.mapper.Name

data class EvmTransactionSubmitterBlockchainConfig(
        @Name("network_id")
        val networkId: Long
)
