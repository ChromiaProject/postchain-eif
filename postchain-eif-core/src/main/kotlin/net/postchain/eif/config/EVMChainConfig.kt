package net.postchain.eif.config

import net.postchain.gtv.Gtv
import net.postchain.gtv.mapper.Name
import net.postchain.gtv.mapper.RawGtv

data class EVMChainConfig(
        @RawGtv
        val rawGtv: Gtv,
        @Name("chains")
        val chains: List<String>,
)
