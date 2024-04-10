package net.postchain.eif

import mu.KLogging
import net.postchain.PostchainContext
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.exception.UserMistake
import net.postchain.core.BlockchainConfiguration
import net.postchain.core.BlockchainEngine
import net.postchain.core.BlockchainProcess
import net.postchain.core.SynchronizationInfrastructureExtension
import net.postchain.eif.config.EifEventReceiverConfig
import net.postchain.eif.config.EifEvmBlockchainConfig
import net.postchain.eif.config.EvmConfig
import net.postchain.eif.metrics.EifMetricsRegistry
import net.postchain.eif.metrics.RpcUsageMetrics
import net.postchain.gtv.mapper.toObject
import net.postchain.gtx.GTXModuleAware
import java.math.BigInteger

@Suppress("unused")
class EifSynchronizationInfrastructureExtension(
        private val postchainContext: PostchainContext
) : SynchronizationInfrastructureExtension {
    private val eventProcessors = mutableMapOf<String, MutableMap<Long, EventProcessor>>()
    private val eifMetricsRegistry = EifMetricsRegistry()

    companion object : KLogging()

    override fun connectProcess(process: BlockchainProcess) {
        val engine = process.blockchainEngine
        val cfg = engine.getConfiguration()
        if (cfg is GTXModuleAware) {
            val exs = cfg.module.getSpecialTxExtensions()
            val ext = exs.find { it is EifSpecialTxExtension }
            if (ext is EifSpecialTxExtension) {
                val eventReceiverConfig = cfg.rawConfig["eif"]?.toObject<EifEventReceiverConfig>()
                        ?: throw UserMistake("No EIF config present")

                eventProcessors[cfg.blockchainRid.toHex()] = mutableMapOf()
                for ((evmBlockchainName, evmBlockchainConfig) in eventReceiverConfig.chains) {
                    if (evmBlockchainConfig.skipToHeight == 0L) {
                        logger.warn("Skip to height config is set to 0. Consider changing it to avoid redundant queries.")
                    }

                    val evmConfig = EvmConfig.fromAppConfig(evmBlockchainName, postchainContext.appConfig)
                    val eventProcessor = initializeEventProcessor(cfg, evmBlockchainConfig, engine, evmConfig)
                    ext.addEventProcessor(evmBlockchainConfig.networkId, eventProcessor)
                    eventProcessors[cfg.blockchainRid.toHex()]?.set(evmBlockchainConfig.networkId, eventProcessor)
                    eifMetricsRegistry.registerMetrics(cfg.chainID, cfg.blockchainRid, evmBlockchainConfig.networkId, eventProcessor)
                }
            }
        }
    }

    override fun disconnectProcess(process: BlockchainProcess) {
        val blockchainRid = process.blockchainEngine.getConfiguration().blockchainRid
        val eventProcessors = eventProcessors.remove(blockchainRid.toHex())
                ?: throw ProgrammerMistake("Blockchain $blockchainRid not attached")
        eifMetricsRegistry.unregisterMetrics(blockchainRid)
        eventProcessors.values.forEach { it.shutdown() }
    }

    override fun shutdown() {
        eifMetricsRegistry.unregisterAllMetrics()
        eventProcessors.values.forEach { it.values.forEach { eventProcessor -> eventProcessor.shutdown() } }
        eventProcessors.clear()
    }

    private fun initializeEventProcessor(cfg: BlockchainConfiguration, eifEvmBlockchainConfig: EifEvmBlockchainConfig, engine: BlockchainEngine, evmConfig: EvmConfig): EventProcessor {
        return if ("ignore".equals(evmConfig.urls.first(), ignoreCase = true)) {
            logger.warn("EIF is running in disconnected mode. No events will be validated against ethereum.")
            NoOpEventProcessor()
        } else {
            val web3jServices = Web3jServiceFactory.buildServices(evmConfig.urls, evmConfig.connectTimeout, evmConfig.readTimeout, evmConfig.writeTimeout)
            val events = eifEvmBlockchainConfig.events.asArray().map(GtvToEventMapper::map)
            val metrics = RpcUsageMetrics(cfg.chainID, cfg.blockchainRid, eifEvmBlockchainConfig.networkId)
            EvmEventProcessor(
                    eifEvmBlockchainConfig.networkId,
                    eifEvmBlockchainConfig.contracts,
                    events,
                    BigInteger.valueOf(eifEvmBlockchainConfig.evmReadOffset),
                    BigInteger.valueOf(eifEvmBlockchainConfig.readOffset),
                    evmConfig.maxReadAhead,
                    evmConfig.maxQueueSize,
                    BigInteger.valueOf(eifEvmBlockchainConfig.skipToHeight),
                    BigInteger.valueOf(evmConfig.lastEvmBlockHeight),
                    engine,
                    Web3jRequestHandler(evmConfig.minRetryDelay, evmConfig.maxRetryDelay, evmConfig.maxTryErrors, evmConfig.urls, web3jServices, metrics),
                    evmConfig.delayWhenNoNewBlocks
            )
        }
    }
}