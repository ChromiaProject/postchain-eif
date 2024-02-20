package net.postchain.eif.transaction

data class EvmSubmitTransactionResult(
    val status: RellTransactionStatus,
    val blockHash: String? = null,
    val effectiveGasPrice: Long? = null,
    val gasUsage: Long? = null,
)
