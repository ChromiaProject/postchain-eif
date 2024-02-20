package net.postchain.eif.transaction

import net.postchain.gtv.Gtv
import net.postchain.gtv.mapper.Name

data class EvmSubmitTransactionRequest(
        @Name("row_id")
        val rowId: Long,
        @Name("contract_address")
        val contractAddress: String,
        @Name("function_name")
        val functionName: String,
        @Name("parameter_types")
        val parameterTypes: List<String>,
        @Name("parameter_values")
        val parameterValues: List<Gtv>,
        @Name("network_id")
        val networkId: Long,
        @Name("sender")
        val sender: ByteArray,
        @Name("status")
        val status: RellTransactionStatus
)

enum class RellTransactionStatus {
    SUCCESS,
    QUEUED,
    FAILURE,
    TAKEN
}
