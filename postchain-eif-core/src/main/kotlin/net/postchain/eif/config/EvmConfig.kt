package net.postchain.eif.config

import net.postchain.config.app.AppConfig

data class EvmConfig(
        // Can be HTTP address or socket file path for IPC
        val url: String,
        val lastEvmBlockHeight: Long,
        val connectTimeout: Long,
        val readTimeout: Long,
        val writeTimeout: Long,
        val maxReadAhead: Long,
        val maxQueueSize: Long
) {
    companion object {
        @JvmStatic
        fun fromAppConfig(chain: String, config: AppConfig): EvmConfig {
            return EvmConfig(
                    config.getString("$chain.url", ""),
                    config.getLong("$chain.lastEvmBlockHeight", 0),
                    config.getLong("evm.connectTimeout", 300),
                    config.getLong("evm.readTimeout", 300),
                    config.getLong("evm.writeTimeout", 300),
                    config.getLong("$chain.maxReadAhead", 2_000L),
                    config.getLong("$chain.maxQueueSize", 1_000L),
            )
        }
    }
}
