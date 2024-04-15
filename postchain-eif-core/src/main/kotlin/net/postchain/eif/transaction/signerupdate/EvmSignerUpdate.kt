package net.postchain.eif.transaction.signerupdate

import net.postchain.gtv.mapper.Name

data class EvmSignerUpdate(
        @Name("rowid")
        val rowId: Long,
        @Name("serial")
        val serial: Long,
        @Name("blockchain_rid")
        val blockchainRid: ByteArray,
        @Name("signers")
        val signers: ByteArray,
        @Name("confirmed_in_directory_at_height")
        val confirmedInDirectoryAtHeight: Long
)
