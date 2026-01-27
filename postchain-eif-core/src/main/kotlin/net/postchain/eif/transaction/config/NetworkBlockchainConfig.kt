package net.postchain.eif.transaction.config

import net.postchain.gtv.mapper.DefaultValue
import net.postchain.gtv.mapper.Name
import java.math.BigDecimal

data class NetworkBlockchainConfig(
        @param:Name("network_id")
        val networkId: Long,
        @param:Name("max_gas_price")                          // Max gas price for submitting a transaction
        val maxGasPrice: Long,
        @param:Name("min_wallet_balance")                     // Wallet funds required to submit transaction
        val minWalletBalance: Long,
        @param:Name("gas_limit")                              // Max gas limit for any transaction
        val gasLimit: Long,
        @param:Name("gas_limit_margin")                       // Margin to be added to transaction gas limit (estimated gas limit + margin)
        @param:DefaultValue(defaultDecimal = "0.2")
        val gasLimitMargin: BigDecimal,
        @param:Name("base_fee_per_gas_margin")                // Margin to be added to transaction base fee per gas limit (base fee per gas from last transaction + margin)
        @param:DefaultValue(defaultDecimal = "5")
        val baseFeePerGasMargin: BigDecimal,
        @param:Name("priority_fee_per_gas_margin")            // Margin to be added to transaction priority fee per gas limit (estimated priority fee + margin)
        @param:DefaultValue(defaultDecimal = "0.000001")
        val priorityFeePerGasMargin: BigDecimal,
        @param:Name("cancel_fee_margin")                      // Margin to be added to fee (base and priority) for cancel transactions
        @param:DefaultValue(defaultDecimal = "1.1")
        val cancelFeeMargin: BigDecimal,
        @param:Name("node_tx_verification_evm_blocks")        // Number of EVM blocks to await before verification
        @param:DefaultValue(100)
        val nodeTxVerificationEvmBlocks: Long,
        @param:Name("tx_verification_time")                   // Elapsed time before verification consensus begins
        @param:DefaultValue(60000)
        val txVerificationTime: Long,
)
