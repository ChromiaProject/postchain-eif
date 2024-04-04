package net.postchain.eif.transaction

import java.sql.Timestamp

data class EvmSubmitTransactionError(
        val requestId: Long,
        val timestamp: Timestamp,
        val rpcUrl: String?,
        val message: String
)