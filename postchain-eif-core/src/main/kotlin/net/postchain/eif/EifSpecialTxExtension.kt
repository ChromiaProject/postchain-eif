package net.postchain.eif

import mu.KLogging
import net.postchain.base.BaseBlockHeader
import net.postchain.base.SpecialTransactionPosition
import net.postchain.common.BlockchainRid
import net.postchain.common.exception.UserMistake
import net.postchain.core.BlockEContext
import net.postchain.core.block.BlockData
import net.postchain.crypto.CryptoSystem
import net.postchain.eif.config.EifEventReceiverConfig
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtx.GTXModule
import net.postchain.gtx.data.OpData
import net.postchain.gtx.special.GTXBlockBuildingAffectingSpecialTxExtension
import java.math.BigInteger
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

class EifSpecialTxExtension(private val clock: Clock = Clock.systemUTC()) : GTXBlockBuildingAffectingSpecialTxExtension {

    companion object : KLogging()

    private var hasShouldProcessEvmEventsQuery = false
    private lateinit var module: GTXModule

    @Volatile
    private var lastSeenShouldProcessEvents = true

    lateinit var config: EifEventReceiverConfig
    lateinit var isSigner: () -> Boolean

    private var needEifTnx: Boolean = false
    internal val processors = mutableMapOf<Long, Pair<EventProcessor, EventFetcher>>()

    /** Chain block height -> (network id, highest EVM block height) of the EVM blocks in the block being built or validated at that height */
    private val pendingEvmBlockHeights = ConcurrentHashMap<Long, Pair<Long, BigInteger>>()

    override fun getRelevantOps() = setOf(EvmBlockOp.OP_NAME)

    fun addEventProcessor(networkID: Long, processor: EventProcessor, eventFetcher: EventFetcher) {
        processors[networkID] = processor to eventFetcher
    }

    override fun init(module: GTXModule, chainID: Long, blockchainRID: BlockchainRid, cs: CryptoSystem) {
        this.module = module
        needEifTnx = module.getOperations().contains(EvmBlockOp.OP_NAME)
        hasShouldProcessEvmEventsQuery = module.getQueries().contains(EIF_SHOULD_PROCESS_EVM_EVENTS_QUERY)
    }

    override fun needsSpecialTransaction(position: SpecialTransactionPosition): Boolean {
        return when (position) {
            SpecialTransactionPosition.Begin -> needEifTnx
            SpecialTransactionPosition.End -> false
        }
    }

    override fun createSpecialOperations(position: SpecialTransactionPosition, bctx: BlockEContext): List<OpData> {
        if (position == SpecialTransactionPosition.Begin && processors.isNotEmpty() && shouldProcessEvmEvents(bctx)) {
            val index = bctx.height.mod(processors.size)
            val (networkId, procAndFetcher) = processors.entries.toList()[index]
            val proc = procAndFetcher.first
            val evmBlocks = proc.getEventData()

            if (evmBlocks.isEmpty()) return listOf()

            val relevantBlocks = evmBlocks.takeIf { config.maxEventsPerBlock == 0L }
                    ?: evmBlocks.runningFold(0) { acc, b -> acc + b.events.size }
                            .drop(1)
                            .zip(evmBlocks)
                            .takeWhile { (sum, _) -> sum <= config.maxEventsPerBlock }
                            .map { it.second }
                            // An EVM block cannot be split: if the first one alone exceeds the limit it is proposed on its own
                            .ifEmpty { listOf(evmBlocks.first()) }

            return relevantBlocks.map { it.toOpData() }.also {
                recordPendingEvmBlockHeight(bctx.height, networkId, relevantBlocks)
                bctx.addAfterCommitHook { proc.markAsProcessed(relevantBlocks) }
            }
        }

        return listOf()
    }

    override fun validateSpecialOperations(position: SpecialTransactionPosition, bctx: BlockEContext, ops: List<OpData>): Boolean {
        if (position == SpecialTransactionPosition.Begin && processors.isNotEmpty()) {
            val index = bctx.height.mod(processors.size)
            val (networkId, procAndFetcher) = processors.entries.toList()[index]
            val (proc, fetcher) = procAndFetcher
            val decodedOps = ops.map { EvmBlockOp.fromOpData(it) }

            val totalEvents = decodedOps.sumOf { it.events.size }
            if (!shouldProcessEvmEvents(bctx) && totalEvents > 0) {
                throw UserMistake("EVM events received when EIF is paused")
            }

            // A block may hold more than `maxEventsPerBlock` events only when they all come from a single EVM block,
            // since an EVM block cannot be split and the proposer includes it whole in that case
            if (config.maxEventsPerBlock > 0 && isSigner()) {
                val evmBlockCount = decodedOps.map { it.evmBlockHeight }.toSet().size
                if (totalEvents > config.maxEventsPerBlock && evmBlockCount > 1)
                    throw UserMistake("Block contains $totalEvents EVM events from $evmBlockCount EVM blocks, more than the maxEventsPerBlock limit of ${config.maxEventsPerBlock}")
            }

            // Validate event data
            val validationResult = proc.isValidEventData(decodedOps)
            if (!validationResult.valid && validationResult.conflictingHeight != null) {
                if (isSigner()) {
                    // A signer keeps the EVM blocks it read itself, otherwise a faulty proposer could make every signer
                    // re-read at will. The conflict is recorded so that a restart re-reads from before it.
                    logger.warn {
                        "EVM blocks proposed by another signer differ from the EVM blocks this node read from the EVM network: ${validationResult.errorMessage}. " +
                                "A restart of the chain will re-read EVM events from before height ${validationResult.conflictingHeight}"
                    }
                    fetcher.recordValidationConflict(validationResult.conflictingHeight)
                } else {
                    // Replicas and validators that are still syncing simply re-read from the conflicting height
                    fetcher.flushEvents(validationResult.conflictingHeight - BigInteger.ONE)
                }
            }

            recordPendingEvmBlockHeight(bctx.height, networkId, decodedOps)
            bctx.addAfterCommitHook { proc.markAsProcessed(decodedOps) }

            if (!validationResult.valid && validationResult.errorMessage != null) {
                throw UserMistake(validationResult.errorMessage)
            }
            return validationResult.valid
        }
        return ops.isEmpty()
    }

    @Volatile
    private var firstEventBlockTime = 0L

    private fun recordPendingEvmBlockHeight(height: Long, networkId: Long, evmBlocks: List<EvmBlockOp>) {
        if (evmBlocks.isEmpty()) {
            pendingEvmBlockHeights.remove(height)
        } else {
            pendingEvmBlockHeights[height] = networkId to evmBlocks.maxOf { it.evmBlockHeight }
        }
    }

    /**
     * The block is durable now, so the EVM blocks it includes can be confirmed to the event processor of their network.
     * Only the EVM blocks of this very block are confirmed, so that a block whose commit failed after the after-commit
     * hooks ran does not get confirmed by a later block.
     */
    override fun blockCommitted(blockData: BlockData) {
        firstEventBlockTime = 0
        val height = (blockData.header as? BaseBlockHeader)?.blockHeaderRec?.getHeight()
        val committed = height?.let { pendingEvmBlockHeights.remove(it) }
        height?.let { h -> pendingEvmBlockHeights.keys.removeIf { it < h } }
        processors.forEach { (networkId, procAndFetcher) ->
            procAndFetcher.first.blockCommitted(committed?.takeIf { it.first == networkId }?.second)
        }
    }

    override fun shouldBuildBlock(): Boolean {
        if (!::config.isInitialized || !lastSeenShouldProcessEvents) return false

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

    private fun shouldProcessEvmEvents(bctx: BlockEContext): Boolean {
        lastSeenShouldProcessEvents = !hasShouldProcessEvmEventsQuery ||
                module.query(bctx, EIF_SHOULD_PROCESS_EVM_EVENTS_QUERY, gtv(mapOf())).asBoolean()
        return lastSeenShouldProcessEvents
    }
}
