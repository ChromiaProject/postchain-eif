package net.postchain.eif.config

import net.postchain.gtv.mapper.Name
import net.postchain.gtv.mapper.Nullable

data class EifEventConsumerConfig(
        @param:Name("snapshot")
        @param:Nullable
        val snapshot: EifSnapshotConfig?,
)
