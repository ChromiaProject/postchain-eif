package net.postchain.eif.transaction

data class EvmSubmitTransactionResult(
        val requestId: Long,
        val status: RellTransactionStatus,
        val txHash: String? = null,
)
