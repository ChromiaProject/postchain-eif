package net.postchain.eif.transaction.config

import net.postchain.gtv.mapper.Name

data class TransactionSubmitterBlockchainConfig(
        @param:Name("chains")
        val chains: Map<String, NetworkBlockchainConfig>,
)
