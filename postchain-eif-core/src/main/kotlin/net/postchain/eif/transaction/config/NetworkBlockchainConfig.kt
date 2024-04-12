package net.postchain.eif.transaction.config

import net.postchain.gtv.mapper.Name

data class NetworkBlockchainConfig(
        @Name("network_id")
        val networkId: Long,
        @Name("max_gas_price")
        val maxGasPrice: Long,
        @Name("min_wallet_balance")
        val minWalletBalance: Long
)
