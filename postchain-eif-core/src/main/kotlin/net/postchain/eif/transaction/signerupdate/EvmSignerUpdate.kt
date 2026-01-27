package net.postchain.eif.transaction.signerupdate

import net.postchain.gtv.mapper.DefaultValue
import net.postchain.gtv.mapper.Name

data class EvmSignerUpdate(
        @param:Name("rowid")
        val rowId: Long,
        @param:Name("serial")
        val serial: Long,
        @param:Name("blockchain_rid")
        val blockchainRid: ByteArray,
        @param:Name("signers")
        val signers: ByteArray,
        @param:Name("confirmed_in_directory_at_height")
        val confirmedInDirectoryAtHeight: Long,
        @param:Name("historical")
        @param:DefaultValue(defaultBoolean = false)
        val historical: Boolean
)
