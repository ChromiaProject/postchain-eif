package net.postchain.eif

import net.postchain.core.Shutdownable
import net.postchain.eif.EvmEventFetcher.Companion.logger
import java.math.BigInteger

interface EventFetcher : Shutdownable {
    fun flushEvents(resetToHeight: BigInteger)

    /**
     * Called when this node, as a signer, could not validate a block proposal against the events it has fetched.
     * The fetched events are kept, but a restarted reader must not resume beyond [conflictingHeight] so that a restart
     * repairs the divergence.
     */
    fun recordValidationConflict(conflictingHeight: BigInteger) {}
}

class NoOpEventFetcher(private val processor: EventProcessor) : EventFetcher {
    override fun flushEvents(resetToHeight: BigInteger) {
        logger.info("Flushing EVM events and resetting to height: $resetToHeight")
        processor.flushEvents(resetToHeight)
    }

    override fun shutdown() {}
}
