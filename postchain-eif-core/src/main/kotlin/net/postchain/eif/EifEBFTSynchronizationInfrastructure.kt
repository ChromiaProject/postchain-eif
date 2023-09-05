package net.postchain.eif

import mu.KLogging
import net.postchain.PostchainContext
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.exception.UserMistake
import net.postchain.core.*
import net.postchain.eif.config.EifBlockchainConfig
import net.postchain.eif.config.EvmBlockchainConfig
import net.postchain.eif.config.EvmConfig
import net.postchain.eif.metrics.EifMetricsRegistry
import net.postchain.gtv.mapper.toObject
import net.postchain.gtx.GTXModuleAware
import org.web3j.protocol.Web3j
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
                val eifBlockchainConfig = cfg.rawConfig["eif"]?.toObject<EifBlockchainConfig>()
                        ?: throw UserMistake("No EIF config present")

                eventProcessors[cfg.blockchainRid.toHex()] = mutableMapOf()
                for ((evmBlockchainName, evmBlockchainConfig) in eifBlockchainConfig.chains) {
                    if (evmBlockchainConfig.skipToHeight == 0L) {
                        logger.warn("Skip to height config is set to 0. Consider changing it to avoid redundant queries.")
                    }

                    val evmConfig = EvmConfig.fromAppConfig(evmBlockchainName, postchainContext.appConfig)
                    val eventProcessor = initializeEventProcessor(evmBlockchainConfig, engine, evmConfig)
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

    private fun initializeEventProcessor(evmBlockchainConfig: EvmBlockchainConfig, engine: BlockchainEngine, evmConfig: EvmConfig): EventProcessor {
        return if ("ignore".equals(evmConfig.url, ignoreCase = true)) {
            logger.warn("EIF is running in disconnected mode. No events will be validated against ethereum.")
            NoOpEventProcessor()
        } else {
            val web3j = Web3j.build(Web3jServiceFactory.buildService(evmConfig))

            val events = evmBlockchainConfig.events.asArray().map(GtvToEventMapper::map)
            EvmEventProcessor(
                    evmBlockchainConfig.networkId,
                    web3j,
                    evmBlockchainConfig.contracts,
                    events,
                    BigInteger.valueOf(evmBlockchainConfig.evmReadOffset),
                    BigInteger.valueOf(evmBlockchainConfig.readOffset),
                    evmConfig.maxReadAhead,
                    evmConfig.maxQueueSize,
                    BigInteger.valueOf(evmBlockchainConfig.skipToHeight),
                    BigInteger.valueOf(evmConfig.lastEvmBlockHeight),
                    engine,
                    Web3jRequestHandler(evmConfig.minRetryDelay, evmConfig.maxRetryDelay),
                    evmConfig.delayWhenNoNewBlocks
            )
        }
    }
}