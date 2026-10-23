package net.postchain.eif

import mu.KLogging
import mu.withLoggingContext
import net.postchain.PostchainContext
import net.postchain.common.BlockchainRid
import net.postchain.common.exception.UserMistake
import net.postchain.core.BlockchainConfiguration
import net.postchain.core.BlockchainEngine
import net.postchain.core.BlockchainProcess
import net.postchain.core.SynchronizationInfrastructureExtension
import net.postchain.eif.config.EifEventReceiverConfig
import net.postchain.eif.config.EifEvmBlockchainConfig
import net.postchain.eif.config.EifEvmContractConfig
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
const val EIF_CONFIG_CONTRACTS_TO_FETCH_QUERY = "eif.get_contracts_to_fetch"
const val EIF_CONFIG_EVENTS_QUERY = "eif.get_events"
const val EIF_LAST_EVM_BLOCK_QUERY = "get_last_evm_block"
const val EIF_LAST_EVM_EVENT_HEIGHT_QUERY = "get_last_evm_event_height"
const val EIF_SHOULD_PROCESS_EVM_EVENTS_QUERY = "should_process_evm_events"

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

                if (eventReceiverConfig.maxEventDelay < 0) throw UserMistake("maxEventDelay cannot be negative")
                if (eventReceiverConfig.numberOfEventsToTriggerBlockBuilding < 0) throw UserMistake("numberOfEventsToTriggerBlockBuilding cannot be negative")
                if (eventReceiverConfig.maxEventsPerBlock < 0) throw UserMistake("maxEventsPerBlock cannot be negative")

                ext.config = eventReceiverConfig
                ext.isSigner = process::isSigner

                // The queries the dapp exposes decide which of them the fetcher may use, same for every EVM network
                val queries = (cfg.module as? CompositeGTXModule)?.getQueries() ?: emptySet()
                val hasLastEvmEventHeightQuery = EIF_LAST_EVM_EVENT_HEIGHT_QUERY in queries
                val hasLegacyDynamicContracts = EIF_CONFIG_CONTRACTS_QUERY in queries
                val hasDynamicContracts = EIF_CONFIG_CONTRACTS_TO_FETCH_QUERY in queries
                val hasDynamicEvents = EIF_CONFIG_EVENTS_QUERY in queries

                eventFetchers[cfg.blockchainRid] = mutableMapOf()
                try {
                    for ((evmBlockchainName, evmBlockchainConfig) in eventReceiverConfig.chains) {
                        val evmConfig = EvmConfig.fromAppConfig(evmBlockchainName, postchainContext.appConfig)
                        if (evmConfig.urls.isEmpty()) {
                            throw UserMistake("Node does not have any URLs configured for EVM network: $evmBlockchainName")
                        }
                        if (eventReceiverConfig.maxEventsPerBlock > evmConfig.maxQueueSize) throw UserMistake("maxEventsPerBlock cannot be larger than maxQueueSize")

                        val pipeline = createEventPipeline(cfg, evmBlockchainName, evmBlockchainConfig, engine, evmConfig, hasLegacyDynamicContracts, hasDynamicContracts, hasDynamicEvents, hasLastEvmEventHeightQuery)
                        ext.addPipeline(evmBlockchainConfig.networkId, pipeline)
                        eventFetchers[cfg.blockchainRid]?.set(evmBlockchainConfig.networkId, pipeline.fetcher)
                        eifMetricsRegistry.registerMetrics(cfg.chainID, cfg.blockchainRid, evmBlockchainConfig.networkId, pipeline)
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

    private fun createEventPipeline(
            cfg: BlockchainConfiguration,
            evmBlockchainName: String,
            evmBlockchainConfig: EifEvmBlockchainConfig,
            engine: BlockchainEngine,
            evmConfig: EvmConfig,
            hasLegacyDynamicContracts: Boolean,
            hasDynamicContacts: Boolean,
            hasDynamicEvents: Boolean,
            hasLastEvmEventHeightQuery: Boolean,
    ): EventPipeline {

        if (evmConfig.maxQueueSize < 0) throw UserMistake("maxQueueSize cannot be negative")
        if (evmConfig.ignoreReadCursorBelow < 0) throw UserMistake("ignoreReadCursorBelow for EVM network: $evmBlockchainName is negative")
        if (evmConfig.minPollInterval < 0) throw UserMistake("minPollInterval for EVM network: $evmBlockchainName is negative")
        if (evmBlockchainConfig.skipToHeight < 0) throw UserMistake("skip-to-height for EVM network: $evmBlockchainName is negative")
        if (evmBlockchainConfig.evmReadOffset < 0) throw UserMistake("evmReadOffset cannot be negative")
        if (evmBlockchainConfig.readOffset < 0) throw UserMistake("readOffset cannot be negative")

        if ("ignore".equals(evmConfig.urls.first(), ignoreCase = true)) {
            logger.warn("EIF is running in disconnected mode. No events will be fetched from or validated against Ethereum.")
            val processor = NoOpEventProcessor()
            return EventPipeline(NoOpEventFetcher(processor), processor)
        }

        if (evmBlockchainConfig.skipToHeight == 0L) {
            withLoggingContext(NETWORK_ID_TAG to evmBlockchainConfig.networkId.toString()) {
                logger.warn("Skip to height config is set to 0 for EVM network: $evmBlockchainName. Consider changing it to avoid redundant queries.")
            }
        }

        val staticContractConfig = (evmBlockchainConfig.contractsToFetch ?: listOf()) +
                (evmBlockchainConfig.contracts?.map { EifEvmContractConfig(it.toHex(), 0) } ?: listOf())
        if (staticContractConfig.isEmpty() && !hasLegacyDynamicContracts && !hasDynamicContacts) throw UserMistake("No contracts configured for EVM network: $evmBlockchainName")
        val staticContracts = staticContractConfig.map { contractConfig: EifEvmContractConfig ->
            if (contractConfig.skipToHeight < 0) throw UserMistake("skip-to-height for contract ${contractConfig.address} is negative")
            val skipToHeight = if (contractConfig.skipToHeight == 0L) evmBlockchainConfig.skipToHeight else contractConfig.skipToHeight
            if (skipToHeight < evmBlockchainConfig.skipToHeight) {
                throw UserMistake("skip-to-height for contract ${contractConfig.address} is lower than skip-to-height for EVM network: $evmBlockchainName")
            }
            contractConfig.address to skipToHeight
        }
        val staticEvents = evmBlockchainConfig.events.let { if (it.isNull()) listOf() else it.asArray().map(GtvToEventMapper::map) }
        if (staticEvents.isEmpty() && !hasDynamicEvents) throw UserMistake("No events configured for EVM network: $evmBlockchainName")
        val web3jServices = Web3jServiceFactory.buildServices(
                evmConfig.urls, evmConfig.connectTimeout, evmConfig.readTimeout, evmConfig.writeTimeout, cfg.blockchainRid
        )

        val metrics = RpcUsageMetrics(cfg.chainID, cfg.blockchainRid, evmBlockchainConfig.networkId)

        val readCursorStore = DatabaseEvmReadCursorStore(postchainContext.sharedStorage, cfg.chainID).apply { initialize() }

        val eventProcessor = EvmEventProcessor(
                evmBlockchainConfig.networkId,
                BigInteger.valueOf(evmBlockchainConfig.readOffset),
                evmConfig.maxQueueSize,
        )

        val eventFetcher = EvmEventFetcher(
                evmBlockchainConfig.networkId,
                staticContracts,
                hasLegacyDynamicContracts,
                hasDynamicContacts,
                staticEvents,
                hasDynamicEvents,
                BigInteger.valueOf(evmBlockchainConfig.evmReadOffset),
                evmConfig.maxReadAhead,
                evmBlockchainConfig.skipToHeight,
                engine,
                Web3jRequestHandler(evmConfig.minRetryDelay, evmConfig.maxRetryDelay, evmConfig.maxTryErrors, evmConfig.urls, web3jServices, metrics),
                evmConfig.delayWhenNoNewBlocks,
                hasLastEvmEventHeightQuery = hasLastEvmEventHeightQuery,
                eventProcessor,
                readCursorStore,
                evmConfig.minPollInterval,
                ignoreReadCursorBelow = BigInteger.valueOf(evmConfig.ignoreReadCursorBelow),
        )

        return EventPipeline(eventFetcher, eventProcessor)
    }
}
