package net.postchain.eif.config

import net.postchain.config.app.AppConfig

data class EvmConfig(
        // Can be HTTP address or socket file path for IPC
        val url: String,
        val connectTimeout: Long,
        val readTimeout: Long,
        val writeTimeout: Long
) {
    companion object {
        @JvmStatic
        fun fromAppConfig(chain: String, config: AppConfig): EvmConfig {
            return EvmConfig(
                    config.getString("$chain.url", ""),
                    config.getLong("evm.connectTimeout", 300),
                    config.getLong("evm.readTimeout", 300),
                    config.getLong("evm.writeTimeout", 300)
            )
        }
    }
}
