package net.postchain.eif

import mu.KLogging
import net.postchain.PostchainContext
import net.postchain.common.BlockchainRid
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
import net.postchain.eif.web3j.Web3jRequestHandler
import net.postchain.eif.web3j.Web3jServiceFactory
import net.postchain.gtv.mapper.toObject
import net.postchain.gtx.CompositeGTXModule
import net.postchain.gtx.GTXModuleAware
import java.math.BigInteger

const val EIF_CONFIG_CONTRACTS_QUERY = "eif.get_contracts"
const val EIF_CONFIG_EVENTS_QUERY = "eif.get_events"

@Suppress("unused")
class EifSynchronizationInfrastructureExtension(
        private val postchainContext: PostchainContext
) : SynchronizationInfrastructureExtension {
    private val eventFetchers = mutableMapOf<BlockchainRid, MutableMap<Long, EventFetcher>>()
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

                ext.config = eventReceiverConfig
                ext.isSigner = process::isSigner

                eventFetchers[cfg.blockchainRid] = mutableMapOf()
                try {
                    for ((evmBlockchainName, evmBlockchainConfig) in eventReceiverConfig.chains) {
                        if (evmBlockchainConfig.skipToHeight == 0L) {
                            logger.warn("Skip to height config is set to 0 for EVM network: $evmBlockchainName. Consider changing it to avoid redundant queries.")
                        }

                        val evmConfig = EvmConfig.fromAppConfig(evmBlockchainName, postchainContext.appConfig)
                        if (evmConfig.urls.isEmpty()) {
                            throw UserMistake("Node does not have any URLs configured for EVM network: $evmBlockchainName")
                        }

                        val hasDynamicContracts = (cfg.module as? CompositeGTXModule)?.let { compositeModule ->
                            (EIF_CONFIG_CONTRACTS_QUERY in compositeModule.getQueries())
                        } == true

                        val hasDynamicEvents = (cfg.module as? CompositeGTXModule)?.let { compositeModule ->
                            (EIF_CONFIG_EVENTS_QUERY in compositeModule.getQueries())
                        } == true

                        val (eventProcessor, eventFetcher) = initializeEventProcessor(cfg, evmBlockchainConfig, engine, evmConfig, hasDynamicContracts, hasDynamicEvents)
                        ext.addEventProcessor(evmBlockchainConfig.networkId, eventProcessor, eventFetcher)
                        eventFetchers[cfg.blockchainRid]?.set(evmBlockchainConfig.networkId, eventFetcher)
                        eifMetricsRegistry.registerMetrics(cfg.chainID, cfg.blockchainRid, evmBlockchainConfig.networkId, eventProcessor)
                    }
                } catch (e: Exception) {
                    disconnectProcess(process)
                    throw e
                }
            }
        }
    }

    override fun disconnectProcess(process: BlockchainProcess) {
        val blockchainRid = process.blockchainEngine.getConfiguration().blockchainRid
        val blockchainEventFetchers = eventFetchers.remove(blockchainRid)
        eifMetricsRegistry.unregisterMetrics(blockchainRid)
        blockchainEventFetchers?.values?.forEach {
            try {
                it.shutdown()
            } catch (e: Exception) {
                logger.error("Unexpected error when shutting down event fetcher", e)
            }
        }
    }

    override fun shutdown() {
        eifMetricsRegistry.unregisterAllMetrics()
        eventFetchers.values.forEach { blockchainEventProcessors ->
            blockchainEventProcessors.values.forEach {
                it.shutdown()
            }
        }
        eventFetchers.clear()
    }

    private fun initializeEventProcessor(cfg: BlockchainConfiguration, eifEvmBlockchainConfig: EifEvmBlockchainConfig,
                                         engine: BlockchainEngine, evmConfig: EvmConfig,
                                         hasDynamicContacts: Boolean, hasDynamicEvents: Boolean): Pair<EventProcessor, EventFetcher> {
        val eventProcessor = EvmEventProcessor(
                BigInteger.valueOf(eifEvmBlockchainConfig.readOffset),
                eifEvmBlockchainConfig.maxQueueSize,
                engine,
        )
        return if ("ignore".equals(evmConfig.urls.first(), ignoreCase = true)) {
            logger.warn("EIF is running in disconnected mode. No events will be fetched from or validated against ethereum.")
            NoOpEventProcessor().let { it to NoOpEventFetcher(it) }
        } else {
            val staticContracts = eifEvmBlockchainConfig.contracts ?: listOf()
            if (staticContracts.isEmpty() && !hasDynamicContacts) throw UserMistake("No contracts configured for ${cfg.blockchainRid}")
            val staticEvents = eifEvmBlockchainConfig.events.let { if (it.isNull()) listOf() else it.asArray().map(GtvToEventMapper::map) }
            if (staticEvents.isEmpty() && !hasDynamicEvents) throw UserMistake("No events configured for ${cfg.blockchainRid}")
            val web3jServices = Web3jServiceFactory.buildServices(evmConfig.urls, evmConfig.connectTimeout, evmConfig.readTimeout, evmConfig.writeTimeout)
            val metrics = RpcUsageMetrics(cfg.chainID, cfg.blockchainRid, eifEvmBlockchainConfig.networkId)
            val eventFetcher = EvmEventFetcher(
                    eifEvmBlockchainConfig.networkId,
                    staticContracts,
                    hasDynamicContacts,
                    staticEvents,
                    hasDynamicEvents,
                    BigInteger.valueOf(eifEvmBlockchainConfig.evmReadOffset),
                    evmConfig.maxReadAhead,
                    BigInteger.valueOf(eifEvmBlockchainConfig.skipToHeight),
                    BigInteger.valueOf(evmConfig.lastEvmBlockHeight),
                    engine,
                    Web3jRequestHandler(evmConfig.minRetryDelay, evmConfig.maxRetryDelay, evmConfig.maxTryErrors, evmConfig.urls, web3jServices, metrics),
                    evmConfig.delayWhenNoNewBlocks,
                    eventProcessor,
            )
            eventProcessor to eventFetcher
        }
    }
}