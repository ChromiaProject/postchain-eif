package net.postchain.eif.transaction.config

import net.postchain.gtv.mapper.Name
import net.postchain.gtv.mapper.Nullable

data class TransactionSubmitterBlockchainConfig(
        @Name("chains")
        val chains: Map<String, NetworkBlockchainConfig>,
        @Name("gas_limit")
        val gasLimit: Long,
        @Name("node_tx_timeout")
        val nodeTxTimeout: Long,
        @Name("system_anchoring_brid")
        @Nullable
        val systemAnchoringBrid: ByteArray?
)
