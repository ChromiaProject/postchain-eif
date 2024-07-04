package net.postchain.eif.transaction.config

import net.postchain.gtv.mapper.DefaultValue
import net.postchain.gtv.mapper.Name
import java.math.BigDecimal

data class NetworkBlockchainConfig(
        @Name("network_id")
        val networkId: Long,
        @Name("max_gas_price")                          // Max gas price for submitting a transaction
        val maxGasPrice: Long,
        @Name("min_wallet_balance")                     // Wallet funds required to submit transaction
        val minWalletBalance: Long,
        @Name("gas_limit")                              // Max gas limit for any transaction
        val gasLimit: Long,
        @Name("gas_limit_margin")                       // Margin to be added transaction gas limit (estimated gas limit + margin)
        @DefaultValue(defaultDecimal = "0.1")
        val gasLimitMargin: BigDecimal,
        @Name("node_tx_verification_evm_blocks")        // Number of EVM blocks to await before verification
        @DefaultValue(100)
        val nodeTxVerificationEvmBlocks: Long,
        @Name("tx_verification_time")                   // Elapsed time before verification consensus begins
        @DefaultValue(60000)
        val txVerificationTime: Long,
)
