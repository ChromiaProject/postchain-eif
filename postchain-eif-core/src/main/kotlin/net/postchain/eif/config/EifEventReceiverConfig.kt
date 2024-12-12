package net.postchain.eif.config

import net.postchain.gtv.mapper.DefaultValue
import net.postchain.gtv.mapper.Name

data class EifEventReceiverConfig(
        @Name("max_event_delay")
        @DefaultValue(defaultLong = 1000)
        val maxEventDelay: Long,
        @Name("number_of_events_to_trigger_block_building")
        @DefaultValue(defaultLong = 100)
        val numberOfEventsToTriggerBlockBuilding: Long,
        @Name("chains")
        val chains: Map<String, EifEvmBlockchainConfig>,
)
