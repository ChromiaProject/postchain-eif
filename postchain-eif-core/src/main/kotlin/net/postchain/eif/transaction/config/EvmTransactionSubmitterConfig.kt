package net.postchain.eif.transaction.config

import net.postchain.config.app.AppConfig

data class EvmTransactionSubmitterConfig(
        // Can be HTTP address or socket file path for IPC
        val urls: List<String>,
        val connectTimeout: Long,
        val readTimeout: Long,
        val writeTimeout: Long,
        val minRetryDelay: Long,
        val maxRetryDelay: Long,
        val maxTryErrors: Long,
        val privateKey: String,
        val txPollInterval: Long,
        val healthCheckInterval: Long
) {
    companion object {
        private const val EIF_CONFIG_ENV_PREFIX = "POSTCHAIN_TRANSACTION_SUBMITTER_"
        private const val EVM_CONNECT_TIMEOUT = "${EIF_CONFIG_ENV_PREFIX}EVM_CONNECT_TIMEOUT"
        private const val EVM_READ_TIMEOUT = "${EIF_CONFIG_ENV_PREFIX}EVM_READ_TIMEOUT"
        private const val EVM_WRITE_TIMEOUT = "${EIF_CONFIG_ENV_PREFIX}EVM_WRITE_TIMEOUT"
        private const val EVM_MIN_RETRY_DELAY = "${EIF_CONFIG_ENV_PREFIX}EVM_MIN_RETRY_DELAY"
        private const val EVM_MAX_RETRY_DELAY = "${EIF_CONFIG_ENV_PREFIX}EVM_MAX_RETRY_DELAY"
        private const val EVM_MAX_TRY_ERRORS = "${EIF_CONFIG_ENV_PREFIX}EVM_MAX_TRY_ERRORS"
        private const val EVM_TX_POLL_INTERVAL = "${EIF_CONFIG_ENV_PREFIX}EVM_TX_POLL_INTERVAL"
        private const val EVM_HEALTHCHECK_INTERVAL = "${EIF_CONFIG_ENV_PREFIX}EVM_HEALTHCHECK_INTERVAL"

        @JvmStatic
        fun fromAppConfig(chain: String, config: AppConfig): EvmTransactionSubmitterConfig {
            return EvmTransactionSubmitterConfig(
                    config.getEnvOrListProperty("${EIF_CONFIG_ENV_PREFIX}${chain.uppercase()}_URLS", "$chain.urls", listOf()),
                    config.getEnvOrLong(EVM_CONNECT_TIMEOUT, "evm.connectTimeout", 300),
                    config.getEnvOrLong(EVM_READ_TIMEOUT, "evm.readTimeout", 300),
                    config.getEnvOrLong(EVM_WRITE_TIMEOUT, "evm.writeTimeout", 300),
                    config.getEnvOrLong(EVM_MAX_RETRY_DELAY, "evm.maxRetryDelay", 60_000L),
                    config.getEnvOrLong(EVM_MIN_RETRY_DELAY, "evm.minRetryDelay", 500L),
                    config.getEnvOrLong(EVM_MAX_TRY_ERRORS, "evm.maxTryErrors", 10L),
                    config.getEnvOrString("${EIF_CONFIG_ENV_PREFIX}${chain.uppercase()}_PRIVATE_KEY", "$chain.privateKey", config.privKey),
                    config.getEnvOrLong(EVM_TX_POLL_INTERVAL, "evm.txPollInterval", 10_000L),
                    config.getEnvOrLong(EVM_HEALTHCHECK_INTERVAL, "evm.healthCheckInterval", 60_000L)
            )
        }
    }
}
