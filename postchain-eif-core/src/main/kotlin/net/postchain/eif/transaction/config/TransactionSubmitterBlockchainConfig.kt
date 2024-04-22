package net.postchain.eif.transaction.config

import net.postchain.gtv.mapper.DefaultValue
import net.postchain.gtv.mapper.Name

data class TransactionSubmitterBlockchainConfig(
        @Name("chains")
        val chains: Map<String, NetworkBlockchainConfig>,
        @Name("gas_limit")
        val gasLimit: Long,
        @Name("node_tx_verification_timeout")           // Timeout for verifying TX
        @DefaultValue(240000)
        val nodeTxVerificationTimeout: Long,
        @Name("node_tx_verification_evm_blocks")        // Number of EVM blocks to await before verification
        @DefaultValue(100)
        val nodeTxVerificationEvmBlocks: Long,
        @Name("tx_verification_time")                   // Elapsed time before verification consensus begins
        @DefaultValue(60000)
        val txVerificationTime: Long,
)
