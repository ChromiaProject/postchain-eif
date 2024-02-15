package net.postchain.eif.transaction.config

import net.postchain.gtv.mapper.Name

data class TransactionSubmitterBlockchainConfig(
        @Name("chains")
        val chains: Map<String, EvmTransactionSubmitterBlockchainConfig>
)
