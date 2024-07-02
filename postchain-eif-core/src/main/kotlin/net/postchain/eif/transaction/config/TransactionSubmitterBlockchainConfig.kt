package net.postchain.eif.transaction.config

import net.postchain.gtv.mapper.DefaultValue
import net.postchain.gtv.mapper.Name

data class TransactionSubmitterBlockchainConfig(
        @Name("chains")
        val chains: Map<String, NetworkBlockchainConfig>,
        @Name("node_tx_verification_timeout")           // Timeout for verifying TX
        @DefaultValue(240000)
        val nodeTxVerificationTimeout: Long,
)
