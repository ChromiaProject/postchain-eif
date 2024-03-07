package net.postchain.eif.config

import net.postchain.config.app.AppConfig
import net.postchain.eif.config.EvmConfig.Companion.EIF_CONFIG_ENV_PREFIX
import org.apache.commons.configuration2.BaseConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EifContainerConfigProviderTest {

    @Test
    fun testAppConfigParsingOfChainSpecificVars() {
        val config = BaseConfiguration()
        with(config) {
            addProperty("evm.chains", "ethereum")
            addProperty("ethereum.urls", "testurl")
            addProperty("ethereum.lastEvmBlockHeight", 0)
            addProperty("ethereum.maxReadAhead", 1)
            addProperty("ethereum.maxQueueSize", 2)
            addProperty("database.suppressCollationCheck", true)
        }
        val appConfig = AppConfig(config)

        val envConfigMap = EifContainerConfigProvider().getConfig(appConfig)

        assertEquals("testurl", envConfigMap["${EIF_CONFIG_ENV_PREFIX}ETHEREUM_URLS"])
        assertEquals("0", envConfigMap["${EIF_CONFIG_ENV_PREFIX}ETHEREUM_LAST_EVM_BLOCK_HEIGHT"])
        assertEquals("1", envConfigMap["${EIF_CONFIG_ENV_PREFIX}ETHEREUM_MAX_READ_AHEAD"])
        assertEquals("2", envConfigMap["${EIF_CONFIG_ENV_PREFIX}ETHEREUM_MAX_QUEUE_SIZE"])
    }
}