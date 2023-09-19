package net.postchain.eif.config

import net.postchain.gtv.Gtv
import net.postchain.gtv.mapper.DefaultValue
import net.postchain.gtv.mapper.Name
import net.postchain.gtv.mapper.RawGtv

data class EifSnapshotConfig(
        @RawGtv
        val rawGtv: Gtv,
        @Name("levels_per_page")
        @DefaultValue(defaultLong = 2)
        val levelsPerPage: Long,
        @Name("snapshots_to_keep")
        @DefaultValue(defaultLong = 10)
        val snapshotsToKeep: Long
)