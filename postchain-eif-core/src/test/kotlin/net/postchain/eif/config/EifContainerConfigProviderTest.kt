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
            addProperty("ethereum.maxReadAhead", 1)
            addProperty("ethereum.ignoreReadCursorBelow", 5)
            addProperty("ethereum.minPollInterval", 5000)
        }
        val appConfig = AppConfig(config)

        val envConfigMap = EifContainerConfigProvider().getConfig(appConfig)

        assertEquals("testurl", envConfigMap["${EIF_CONFIG_ENV_PREFIX}ETHEREUM_URLS"])
        assertEquals("1", envConfigMap["${EIF_CONFIG_ENV_PREFIX}ETHEREUM_MAX_READ_AHEAD"])
        assertEquals("5", envConfigMap["${EIF_CONFIG_ENV_PREFIX}ETHEREUM_IGNORE_READ_CURSOR_BELOW"])
        assertEquals("5000", envConfigMap["${EIF_CONFIG_ENV_PREFIX}ETHEREUM_MIN_POLL_INTERVAL"])
    }

    @Test
    fun testMinPollIntervalDefaultIsForwarded() {
        val config = BaseConfiguration()
        with(config) {
            addProperty("evm.chains", "bsc")
            addProperty("bsc.urls", "testurl")
        }
        val appConfig = AppConfig(config)

        val envConfigMap = EifContainerConfigProvider().getConfig(appConfig)

        assertEquals(EvmConfig.DEFAULT_MIN_POLL_INTERVAL.toString(), envConfigMap["${EIF_CONFIG_ENV_PREFIX}BSC_MIN_POLL_INTERVAL"])
    }
}