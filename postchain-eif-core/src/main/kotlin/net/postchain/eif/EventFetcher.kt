package net.postchain.eif

import net.postchain.core.Shutdownable
import net.postchain.eif.EvmEventFetcher.Companion.logger
import java.math.BigInteger

interface EventFetcher : Shutdownable {
    fun flushEvents(resetToHeight: BigInteger)
}

class NoOpEventFetcher(private val processor: EventProcessor) : EventFetcher {
    override fun flushEvents(resetToHeight: BigInteger) {
        logger.info("Flushing EVM events and resetting to height: $resetToHeight")
        processor.flushEvents(resetToHeight)
    }

    override fun shutdown() {}
}
