package net.postchain.eif

import mu.KLogging
import net.postchain.base.SpecialTransactionPosition
import net.postchain.common.BlockchainRid
import net.postchain.core.BlockEContext
import net.postchain.core.block.BlockData
import net.postchain.crypto.CryptoSystem
import net.postchain.eif.config.EifEventReceiverConfig
import net.postchain.gtx.GTXModule
import net.postchain.gtx.data.OpData
import net.postchain.gtx.special.GTXBlockBuildingAffectingSpecialTxExtension
import java.math.BigInteger
import java.time.Clock

class EifSpecialTxExtension(private val clock: Clock = Clock.systemUTC()) : GTXBlockBuildingAffectingSpecialTxExtension {

    companion object : KLogging()

    lateinit var config: EifEventReceiverConfig
    lateinit var isSigner: () -> Boolean

    private var needEifTnx: Boolean = false
    internal val processors = mutableMapOf<Long, Pair<EventProcessor, EventFetcher>>()

    override fun getRelevantOps() = setOf(EvmBlockOp.OP_NAME)

    fun addEventProcessor(networkID: Long, processor: EventProcessor, eventFetcher: EventFetcher) {
        processors[networkID] = processor to eventFetcher
    }

    override fun init(module: GTXModule, chainID: Long, blockchainRID: BlockchainRid, cs: CryptoSystem) {
        needEifTnx = module.getOperations().contains(EvmBlockOp.OP_NAME)
    }

    override fun needsSpecialTransaction(position: SpecialTransactionPosition): Boolean {
        return when (position) {
            SpecialTransactionPosition.Begin -> needEifTnx
            SpecialTransactionPosition.End -> false
        }
    }

    override fun createSpecialOperations(position: SpecialTransactionPosition, bctx: BlockEContext): List<OpData> {
        if (position == SpecialTransactionPosition.Begin && processors.isNotEmpty()) {
            val index = bctx.height.mod(processors.size)
            val proc = processors.values.toList()[index].first
            val evmBlocks = proc.getEventData()

            if (evmBlocks.isEmpty()) return listOf()

            val relevantBlocks = evmBlocks.takeIf { config.maxEventsPerBlock == 0L }
                    ?: evmBlocks.runningFold(0) { acc, b -> acc + b.events.size }
                            .drop(1)
                            .zip(evmBlocks)
                            .takeWhile { (sum, _) -> sum <= config.maxEventsPerBlock }
                            .map { it.second }
                            .ifEmpty { listOf(evmBlocks.first()) }

            return relevantBlocks.map { it.toOpData() }.also {
                bctx.addAfterCommitHook { proc.markAsProcessed(relevantBlocks) }
            }
        }

        return listOf()
    }

    override fun validateSpecialOperations(position: SpecialTransactionPosition, bctx: BlockEContext, ops: List<OpData>): Boolean {
        if (position == SpecialTransactionPosition.Begin && processors.isNotEmpty()) {
            val index = bctx.height.mod(processors.size)
            val (proc, fetcher) = processors.values.toList()[index]
            val allDecodedOps = ops.map {
                if (it.opName != EvmBlockOp.OP_NAME) {
                    logger.error("Unknown operation: ${it.opName}")
                    null
                } else {
                    EvmBlockOp.fromOpData(it)
                }
            }

            // Ensure only __evm_block operations are present
            if (allDecodedOps.contains(null)) return false
            val decodedOps = allDecodedOps.filterNotNull()

            // Validate that a block does not contain too many events
            // `maxEventsPerBlock` can be exceeded if all events are from the same EVM block
            if (config.maxEventsPerBlock > 0 && isSigner()) {
                val totalEvents = decodedOps.sumOf { it.events.size }
                if (totalEvents > config.maxEventsPerBlock && decodedOps.map { it.evmBlockHeight }.toSet().count() > 1)
                    return false
            }

            // Validate event data
            val validationResult = proc.isValidEventData(decodedOps)
            if (!validationResult.valid && validationResult.conflictingHeight != null && !isSigner()) {
                fetcher.flushEvents(validationResult.conflictingHeight - BigInteger.ONE)
            }

            bctx.addAfterCommitHook { proc.markAsProcessed(decodedOps) }

            return validationResult.valid
        }
        return ops.isEmpty()
    }

    private var firstEventBlockTime = 0L

    override fun blockCommitted(blockData: BlockData) {
        firstEventBlockTime = 0
    }

    override fun shouldBuildBlock(): Boolean {
        if (!::config.isInitialized) return false

        val numberOfEvents = fetchNumberOfEvents()
        if (numberOfEvents == 0L) return false

        val now = clock.millis()
        if (firstEventBlockTime > 0 && now - firstEventBlockTime > config.maxEventDelay) return true

        if (numberOfEvents >= config.numberOfEventsToTriggerBlockBuilding) return true
        if (firstEventBlockTime == 0L) {
            firstEventBlockTime = now
        }

        return false
    }

    private fun fetchNumberOfEvents(): Long = processors.values.map { it.first }.sumOf { it.numberOfNewEvents() }
}
