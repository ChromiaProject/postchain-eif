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
        @Name("gas_limit_margin")                       // Margin to be added to transaction gas limit (estimated gas limit + margin)
        @DefaultValue(defaultDecimal = "0.2")
        val gasLimitMargin: BigDecimal,
        @Name("base_fee_per_gas_margin")                // Margin to be added to transaction base fee per gas limit (base fee per gas from last transaction + margin)
        @DefaultValue(defaultDecimal = "5")
        val baseFeePerGasMargin: BigDecimal,
        @Name("priority_fee_per_gas_margin")            // Margin to be added to transaction priority fee per gas limit (estimated priority fee + margin)
        @DefaultValue(defaultDecimal = "0.000001")
        val priorityFeePerGasMargin: BigDecimal,
        @Name("node_tx_verification_evm_blocks")        // Number of EVM blocks to await before verification
        @DefaultValue(100)
        val nodeTxVerificationEvmBlocks: Long,
        @Name("tx_verification_time")                   // Elapsed time before verification consensus begins
        @DefaultValue(60000)
        val txVerificationTime: Long,
)
