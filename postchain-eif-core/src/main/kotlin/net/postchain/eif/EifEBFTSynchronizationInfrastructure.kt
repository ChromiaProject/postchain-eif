package net.postchain.eif

import mu.KLogging
import net.postchain.PostchainContext
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.exception.UserMistake
import net.postchain.core.*
import net.postchain.eif.config.EVMBlockchainConfig
import net.postchain.eif.config.EVMChainConfig
import net.postchain.eif.config.EVMConfig
import net.postchain.eif.metrics.EifMetricsRegistry
import net.postchain.gtv.mapper.toObject
import net.postchain.gtx.GTXModuleAwareness
import org.web3j.protocol.Web3j
import java.math.BigInteger

@Suppress("unused")
class EifSynchronizationInfrastructureExtension(
    private val postchainContext: PostchainContext
) : SynchronizationInfrastructureExtension {
    private val eventProcessors = mutableMapOf<String, EventProcessor>()
    private val eifMetricsRegistry = EifMetricsRegistry()

    companion object : KLogging()

    override fun connectProcess(process: BlockchainProcess) {
        val engine = process.blockchainEngine
        val cfg = engine.getConfiguration()
        if (cfg is GTXModuleAwareness) {
            val exs = cfg.module.getSpecialTxExtensions()
            val ext = exs.find { it is EifSpecialTxExtension }
            if (ext is EifSpecialTxExtension) {
                val evmChainConfig = cfg.rawConfig["eif"]?.toObject<EVMChainConfig>()
                        ?: throw UserMistake("No EIF config present")
                val chains = evmChainConfig.chains
                for (chain in chains) {
                    val evmBlockchainConfig = cfg.rawConfig[chain]?.toObject<EVMBlockchainConfig>()
                            ?: throw UserMistake("No $chain config present")
                    if (evmBlockchainConfig.skipToHeight == BigInteger.ZERO) {
                        logger.warn("Skip to height config is set to 0. Consider changing it to avoid redundant queries.")
                    }

                    val evmConfig = EVMConfig.fromAppConfig(chain, postchainContext.appConfig)
                    val eventProcessor = initializeEventProcessor(chain, evmBlockchainConfig, engine, evmConfig)
                    // TODO: Need to handle event processor, metrics for each evm chain
                    ext.useEventProcessor(eventProcessor)
                    eventProcessors[cfg.blockchainRid.toHex()] = eventProcessor
                    eifMetricsRegistry.registerMetrics(cfg.chainID, cfg.blockchainRid, evmBlockchainConfig.chainId, eventProcessor)
                }
            }
        }
    }

    override fun disconnectProcess(process: BlockchainProcess) {
        val blockchainRid = process.blockchainEngine.getConfiguration().blockchainRid
        val eventProcessor = eventProcessors.remove(blockchainRid.toHex())
            ?: throw ProgrammerMistake("Blockchain $blockchainRid not attached")
        eifMetricsRegistry.unregisterMetrics(blockchainRid)
        eventProcessor.shutdown()
    }

    override fun shutdown() {
        eifMetricsRegistry.unregisterAllMetrics()
        eventProcessors.values.forEach { it.shutdown() }
        eventProcessors.clear()
    }

    private fun initializeEventProcessor(chain: String, evmBlockchainConfig: EVMBlockchainConfig, engine: BlockchainEngine, evmConfig: EVMConfig): EventProcessor {
        return if ("ignore".equals(evmConfig.url, ignoreCase = true)) {
            logger.warn("EIF is running in disconnected mode. No events will be validated against ethereum.")
            NoOpEventProcessor()
        } else {
            val web3j = Web3j.build(Web3jServiceFactory.buildService(evmConfig))

            val events = evmBlockchainConfig.events.asArray().map(GtvToEventMapper::map)
            EVMEventProcessor(chain,
                web3j,
                evmBlockchainConfig.contracts,
                events,
                BigInteger.valueOf(evmBlockchainConfig.evmReadOffset),
                BigInteger.valueOf(evmBlockchainConfig.readOffset),
                evmBlockchainConfig.skipToHeight,
                engine
            ).apply { start() }
        }
    }
}