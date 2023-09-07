package net.postchain.eif.config

import net.postchain.config.app.AppConfig

data class EvmConfig(
        // Can be HTTP address or socket file path for IPC
        val urls: String,
        val lastEvmBlockHeight: Long,
        val connectTimeout: Long,
        val readTimeout: Long,
        val writeTimeout: Long,
        val maxReadAhead: Long,
        val maxQueueSize: Long,
        val minRetryDelay: Long,
        val maxRetryDelay: Long,
        val delayWhenNoNewBlocks: Long,
        val maxTryErrors: Long
) {
    companion object {
        @JvmStatic
        fun fromAppConfig(chain: String, config: AppConfig): EvmConfig {
            return EvmConfig(
                    config.getString("$chain.urls", ""),
                    config.getLong("$chain.lastEvmBlockHeight", 0),
                    config.getLong("evm.connectTimeout", 300),
                    config.getLong("evm.readTimeout", 300),
                    config.getLong("evm.writeTimeout", 300),
                    config.getLong("$chain.maxReadAhead", 2_000L),
                    config.getLong("$chain.maxQueueSize", 2_000L),
                    config.getLong("evm.minRetryDelay", 500L),
                    config.getLong("evm.maxRetryDelay", 60_000L),
                    config.getLong("evm.delayWhenNoNewBlocks", 500L),
                    config.getLong("evm.maxTryErrors", 10L)
            )
        }
    }
}
