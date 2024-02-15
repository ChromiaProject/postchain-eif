package net.postchain.eif.transaction.config

import net.postchain.gtv.Gtv
import net.postchain.gtv.mapper.DefaultValue
import net.postchain.gtv.mapper.Name
import net.postchain.gtv.mapper.RawGtv

data class EvmTransactionSubmitterBlockchainConfig(
        @Name("network_id")
        val networkId: Long
)
