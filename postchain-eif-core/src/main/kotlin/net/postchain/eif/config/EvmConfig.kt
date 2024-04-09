package net.postchain.eif.config

import net.postchain.config.app.AppConfig

data class EvmConfig(
        // Can be HTTP address or socket file path for IPC
        val urls: List<String>,
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
        const val EIF_CONFIG_ENV_PREFIX = "POSTCHAIN_EIF_"
        private const val EVM_CONNECT_TIMEOUT = "${EIF_CONFIG_ENV_PREFIX}EVM_CONNECT_TIMEOUT"
        private const val EVM_READ_TIMEOUT = "${EIF_CONFIG_ENV_PREFIX}EVM_READ_TIMEOUT"
        private const val EVM_WRITE_TIMEOUT = "${EIF_CONFIG_ENV_PREFIX}EVM_WRITE_TIMEOUT"
        private const val EVM_MIN_RETRY_DELAY = "${EIF_CONFIG_ENV_PREFIX}EVM_MIN_RETRY_DELAY"
        private const val EVM_MAX_RETRY_DELAY = "${EIF_CONFIG_ENV_PREFIX}EVM_MAX_RETRY_DELAY"
        private const val EVM_DELAY_WHEN_NO_NEW_BLOCKS = "${EIF_CONFIG_ENV_PREFIX}EVM_DELAY_WHEN_NO_NEW_BLOCKS"
        private const val EVM_MAX_TRY_ERRORS = "${EIF_CONFIG_ENV_PREFIX}EVM_MAX_TRY_ERRORS"

        @JvmStatic
        fun fromAppConfig(chain: String, config: AppConfig): EvmConfig {
            return EvmConfig(
                    config.getEnvOrListProperty("${EIF_CONFIG_ENV_PREFIX}${chain.uppercase()}_URLS", "$chain.urls", listOf()),
                    config.getEnvOrLong("${EIF_CONFIG_ENV_PREFIX}${chain.uppercase()}_LAST_EVM_BLOCK_HEIGHT", "$chain.lastEvmBlockHeight", 0),
                    config.getEnvOrLong(EVM_CONNECT_TIMEOUT, "evm.connectTimeout", 10),
                    config.getEnvOrLong(EVM_READ_TIMEOUT, "evm.readTimeout", 10),
                    config.getEnvOrLong(EVM_WRITE_TIMEOUT, "evm.writeTimeout", 10),
                    config.getEnvOrLong("${EIF_CONFIG_ENV_PREFIX}${chain.uppercase()}_MAX_READ_AHEAD", "$chain.maxReadAhead", 2_000L),
                    config.getEnvOrLong("${EIF_CONFIG_ENV_PREFIX}${chain.uppercase()}_MAX_QUEUE_SIZE", "$chain.maxQueueSize", 2_000L),
                    config.getEnvOrLong(EVM_MIN_RETRY_DELAY, "evm.minRetryDelay", 500L),
                    config.getEnvOrLong(EVM_MAX_RETRY_DELAY, "evm.maxRetryDelay", 60_000L),
                    config.getEnvOrLong(EVM_DELAY_WHEN_NO_NEW_BLOCKS, "evm.delayWhenNoNewBlocks", 500L),
                    config.getEnvOrLong(EVM_MAX_TRY_ERRORS, "evm.maxTryErrors", 10L)
            )
        }
    }

    fun toEnvironmentKeyValueMap(chain: String): Map<String, String> = buildMap {
        put("${EIF_CONFIG_ENV_PREFIX}${chain.uppercase()}_URLS", urls.joinToString(","))
        put("${EIF_CONFIG_ENV_PREFIX}${chain.uppercase()}_LAST_EVM_BLOCK_HEIGHT", lastEvmBlockHeight.toString())
        put(EVM_CONNECT_TIMEOUT, connectTimeout.toString())
        put(EVM_READ_TIMEOUT, readTimeout.toString())
        put(EVM_WRITE_TIMEOUT, writeTimeout.toString())
        put("${EIF_CONFIG_ENV_PREFIX}${chain.uppercase()}_MAX_READ_AHEAD", maxReadAhead.toString())
        put("${EIF_CONFIG_ENV_PREFIX}${chain.uppercase()}_MAX_QUEUE_SIZE", maxQueueSize.toString())
        put(EVM_MIN_RETRY_DELAY, minRetryDelay.toString())
        put(EVM_MAX_RETRY_DELAY, maxRetryDelay.toString())
        put(EVM_DELAY_WHEN_NO_NEW_BLOCKS, delayWhenNoNewBlocks.toString())
        put(EVM_MAX_TRY_ERRORS, maxTryErrors.toString())
    }
}
