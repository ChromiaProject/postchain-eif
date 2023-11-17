package net.postchain.eif.config

import net.postchain.config.app.AppConfig
import net.postchain.containers.bpm.ContainerConfigProvider
import net.postchain.eif.config.EvmConfig.Companion.EIF_CONFIG_ENV_PREFIX

class EifContainerConfigProvider : ContainerConfigProvider {
    override fun getConfig(appConfig: AppConfig): Map<String, String> = buildMap {
        val evmChains = appConfig.getEnvOrListProperty("${EIF_CONFIG_ENV_PREFIX}EVM_CHAINS", "evm.chains", emptyList())
        evmChains.map { EvmConfig.fromAppConfig(it, appConfig).toEnvironmentKeyValueMap(it) }
                .forEach(::putAll)
    }
}
