package net.postchain.eif.config

import net.postchain.gtv.Gtv
import net.postchain.gtv.mapper.Name
import net.postchain.gtv.mapper.Nullable
import net.postchain.gtv.mapper.RawGtv

data class EifBlockchainConfig(
        @RawGtv
        val rawGtv: Gtv,
        @Name("chains")
        val chains: Map<String, EvmBlockchainConfig>,
        @Name("snapshot")
        @Nullable
        val snapshot: EifSnapshotConfig?,
)
