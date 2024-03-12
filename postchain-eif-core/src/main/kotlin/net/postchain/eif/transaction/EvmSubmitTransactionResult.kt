package net.postchain.eif.transaction

data class EvmSubmitTransactionResult(
    val status: RellTransactionStatus,
    val txHash: String? = null,
)
