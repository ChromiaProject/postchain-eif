package net.postchain.eif.config

import net.postchain.gtv.mapper.DefaultValue
import net.postchain.gtv.mapper.Name

data class EifSnapshotConfig(
        @Name("levels_per_page")
        @DefaultValue(defaultLong = 2)
        val levelsPerPage: Long,
        @Name("snapshots_to_keep")
        @DefaultValue(defaultLong = 0)
        val snapshotsToKeep: Long
)