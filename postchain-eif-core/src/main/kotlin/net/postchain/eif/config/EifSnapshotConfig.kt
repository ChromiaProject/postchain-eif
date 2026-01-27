package net.postchain.eif.config

import net.postchain.gtv.mapper.DefaultValue
import net.postchain.gtv.mapper.Name

data class EifSnapshotConfig(
        @param:Name("levels_per_page")
        @param:DefaultValue(defaultLong = 2)
        val levelsPerPage: Long,
        @param:Name("snapshots_to_keep")
        @param:DefaultValue(defaultLong = 0)
        val snapshotsToKeep: Long,
        @param:Name("version")
        @param:DefaultValue(defaultLong = 1)
        val version: Long,
)