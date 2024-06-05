package net.postchain.eif.transaction.anchoring

import net.postchain.gtv.mapper.Name

open class AnchoringContractRell (
    @Name("address")
    val address: String,
    @Name("network_id")
    val networkId: Long
)