package net.postchain.eif

/**
 * What serves one EVM network in an event receiver chain: the fetcher that reads the network's events and the
 * processor that proposes and validates them in blocks.
 */
data class EventPipeline(
        val fetcher: EventFetcher,
        val processor: EventProcessor
)
