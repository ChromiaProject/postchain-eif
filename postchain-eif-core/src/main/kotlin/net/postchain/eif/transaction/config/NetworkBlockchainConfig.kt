package net.postchain.eif.transaction.config

import net.postchain.gtv.mapper.DefaultValue
import net.postchain.gtv.mapper.Name

data class NetworkBlockchainConfig(
        @Name("network_id")
        val networkId: Long,
        @Name("max_gas_price")
        val maxGasPrice: Long,
        @Name("min_wallet_balance")
        val minWalletBalance: Long,
        @Name("gas_limit")
        val gasLimit: Long,
        @Name("node_tx_verification_evm_blocks")        // Number of EVM blocks to await before verification
        @DefaultValue(100)
        val nodeTxVerificationEvmBlocks: Long,
        @Name("tx_verification_time")                   // Elapsed time before verification consensus begins
        @DefaultValue(60000)
        val txVerificationTime: Long,
)
