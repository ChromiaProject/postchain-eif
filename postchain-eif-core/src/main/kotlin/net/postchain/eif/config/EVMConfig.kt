package net.postchain.eif.config

import net.postchain.config.app.AppConfig

data class EVMConfig(
        // Can be HTTP address or socket file path for IPC
        val url: String,
        val connectTimeout: Long,
        val readTimeout: Long,
        val writeTimeout: Long
) {
    companion object {
        @JvmStatic
        fun fromAppConfig(chain: String, config: AppConfig): EVMConfig {
            return EVMConfig(
                    config.getString("$chain.url", ""),
                    config.getLong("$chain.connectTimeout", 300),
                    config.getLong("$chain.readTimeout", 300),
                    config.getLong("$chain.writeTimeout", 300)
            )
        }
    }
}
