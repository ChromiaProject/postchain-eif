package net.postchain.eif.config

import net.postchain.gtv.Gtv
import net.postchain.gtv.mapper.DefaultValue
import net.postchain.gtv.mapper.Name
import net.postchain.gtv.mapper.RawGtv
import java.math.BigInteger

data class EifBlockchainConfig(
    @RawGtv
    val rawGtv: Gtv,
    @Name("contracts")
    val contracts: List<String>,
    @Name("events")
    val events: Gtv,
    @Name("skip_to_height")
    @DefaultValue(defaultBigInteger = "0")
    val skipToHeight: BigInteger,
    @Name("ethereum_read_offset")
    @DefaultValue(defaultLong = 12)
    val ethereumReadOffset: Long,
    @Name("read_offset")
    @DefaultValue(defaultLong = 100)
    val readOffset: Long
)
