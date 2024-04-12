package net.postchain.eif.config

import net.postchain.gtv.mapper.Name
import net.postchain.gtv.mapper.Nullable

data class EifEventConsumerConfig(
        @Name("snapshot")
        @Nullable
        val snapshot: EifSnapshotConfig?,
)
