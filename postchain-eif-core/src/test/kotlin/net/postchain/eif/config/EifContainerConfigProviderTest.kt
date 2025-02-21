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
        }
        val appConfig = AppConfig(config)

        val envConfigMap = EifContainerConfigProvider().getConfig(appConfig)

        assertEquals("testurl", envConfigMap["${EIF_CONFIG_ENV_PREFIX}ETHEREUM_URLS"])
        assertEquals("1", envConfigMap["${EIF_CONFIG_ENV_PREFIX}ETHEREUM_MAX_READ_AHEAD"])
    }
}