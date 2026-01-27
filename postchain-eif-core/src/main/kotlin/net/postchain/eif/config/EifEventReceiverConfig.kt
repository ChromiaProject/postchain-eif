package net.postchain.eif.config

import net.postchain.gtv.mapper.DefaultValue
import net.postchain.gtv.mapper.Name

data class EifEventReceiverConfig(
        @param:Name("max_event_delay")
        @param:DefaultValue(defaultLong = 1000)
        val maxEventDelay: Long,
        @param:Name("number_of_events_to_trigger_block_building")
        @param:DefaultValue(defaultLong = 100)
        val numberOfEventsToTriggerBlockBuilding: Long,
        @param:Name("max_events_per_block")
        @param:DefaultValue(defaultLong = 100)
        val maxEventsPerBlock: Long,
        @param:Name("chains")
        val chains: Map<String, EifEvmBlockchainConfig>,
)
