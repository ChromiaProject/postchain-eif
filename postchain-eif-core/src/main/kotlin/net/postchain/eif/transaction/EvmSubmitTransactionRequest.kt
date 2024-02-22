package net.postchain.eif.transaction

import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvDecoder
import net.postchain.gtv.mapper.Name
import org.jooq.Record
import org.jooq.RecordMapper

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
    QUEUED,
    TAKEN,
    PENDING,
    SUCCESS,
    FAILURE
}

val evmSubmitTransactionRequestRecordMapper = RecordMapper<Record, EvmSubmitTransactionRequest> {
    EvmSubmitTransactionRequest(
            it.get(TransactionSubmitterDatabaseOperationsImpl.COLUMN_REQUEST_ID),
            it.get(TransactionSubmitterDatabaseOperationsImpl.COLUMN_CONTRACT),
            it.get(TransactionSubmitterDatabaseOperationsImpl.COLUMN_FUNCTION),
            it.get(TransactionSubmitterDatabaseOperationsImpl.COLUMN_PARAMETER_TYPES).split(","),
            GtvDecoder.decodeGtv(it.get(TransactionSubmitterDatabaseOperationsImpl.COLUMN_PARAMETER_VALUES)).asArray().toList(),
            it.get(TransactionSubmitterDatabaseOperationsImpl.COLUMN_NETWORK_ID),
            it.get(TransactionSubmitterDatabaseOperationsImpl.COLUMN_SENDER),
            RellTransactionStatus.valueOf(it.get(TransactionSubmitterDatabaseOperationsImpl.COLUMN_STATUS))
    )
}

val evmSubmitTransactionResultRecordMapper = RecordMapper<Record, EvmSubmitTransactionResult> {
    EvmSubmitTransactionResult(
            RellTransactionStatus.valueOf(it.get(TransactionSubmitterDatabaseOperationsImpl.COLUMN_STATUS)),
            it.get(TransactionSubmitterDatabaseOperationsImpl.COLUMN_BLOCK_HASH),
            it.get(TransactionSubmitterDatabaseOperationsImpl.COLUMN_EFFECTIVE_GAS_PRICE),
            it.get(TransactionSubmitterDatabaseOperationsImpl.COLUMN_GAS_USAGE)
    )
}

