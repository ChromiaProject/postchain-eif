package net.postchain.eif.transaction

import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_SUBMIT_COLUMN_BC_PERSISTED
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_SUBMIT_COLUMN_CONTRACT
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_SUBMIT_COLUMN_FUNCTION
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_SUBMIT_COLUMN_HASH
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_SUBMIT_COLUMN_MAX_FEE_PER_GAS
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_SUBMIT_COLUMN_MAX_PRIORITY_FEE_PER_GAS
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_SUBMIT_COLUMN_NETWORK_ID
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_SUBMIT_COLUMN_PARAMETER_TYPES
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_SUBMIT_COLUMN_PARAMETER_VALUES
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_SUBMIT_COLUMN_REQUEST_ID
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_SUBMIT_COLUMN_SENDER
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_SUBMIT_COLUMN_CREATED
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvDecoder
import net.postchain.gtv.mapper.Name
import net.postchain.gtv.mapper.Nullable
import org.jooq.Record
import org.jooq.RecordMapper
import java.math.BigInteger

enum class RellTransactionStatus {
    QUEUED,
    TAKEN,
    PENDING,
    SUCCESS,
    FAILURE
}

open class EvmSubmitTxRellRequest(
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
    @Name("max_priority_fee_per_gas")
    var maxPriorityFeePerGas: BigInteger,
    @Name("max_fee_per_gas")
    var maxFeePerGas: BigInteger,
    @Name("sender")
    val sender: ByteArray,
    @Name("created")
    val created: Long,
    @Nullable
    @Name("tx_hash")
    var txHash: String?,
    @Nullable
    @Name("status")
    val status: RellTransactionStatus?,
)

class EvmSubmitTxRequest(
    rellRequest: EvmSubmitTxRellRequest,
    var bcPersisted: Boolean = false,
): EvmSubmitTxRellRequest(
    rellRequest.rowId,
    rellRequest.contractAddress,
    rellRequest.functionName,
    rellRequest.parameterTypes,
    rellRequest.parameterValues,
    rellRequest.networkId,
    rellRequest.maxPriorityFeePerGas,
    rellRequest.maxFeePerGas,
    rellRequest.sender,
    rellRequest.created,
    rellRequest.txHash,
    rellRequest.status
)  {
    companion object {
        fun fromRell(rellRequest: EvmSubmitTxRellRequest): EvmSubmitTxRequest {
            return EvmSubmitTxRequest(rellRequest)
        }
    }
}

class EvmPendingTx(
    // From BC
    val rowId: Long,
    val networkId: Long,
    val contractAddress: String,
    val functionName: String,
    val parameterTypes: List<String>,
    val parameterValues: List<Gtv>,
    val txHash: String,

    // Internal
    var created: Long = System.currentTimeMillis(),
    var blockNumber: BigInteger? = null,
    var blockHash: String? = null,
    var status: PendingTxStatus = PendingTxStatus.VERIFYING,
    var effectiveGasPrice: BigInteger? = null,
    var gasUsed: BigInteger? = null,
) {
    companion object {
        fun fromEvmSubmitTxRellRequest(txPending: EvmSubmitTxRellRequest, txHash: String): EvmPendingTx {
            return EvmPendingTx(
                txPending.rowId,
                txPending.networkId,
                txPending.contractAddress,
                txPending.functionName,
                txPending.parameterTypes,
                txPending.parameterValues,
                txHash,
            )
        }
    }
}

val evmSubmitTxRequestRecordMapper = RecordMapper<Record, EvmSubmitTxRequest> {
    EvmSubmitTxRequest(
        EvmSubmitTxRellRequest(
            it.get(EVM_TX_SUBMIT_COLUMN_REQUEST_ID),
            it.get(EVM_TX_SUBMIT_COLUMN_CONTRACT),
            it.get(EVM_TX_SUBMIT_COLUMN_FUNCTION),
            it.get(EVM_TX_SUBMIT_COLUMN_PARAMETER_TYPES).split(","),
            GtvDecoder.decodeGtv(it.get(EVM_TX_SUBMIT_COLUMN_PARAMETER_VALUES)).asArray().toList(),
            it.get(EVM_TX_SUBMIT_COLUMN_NETWORK_ID),
            BigInteger.valueOf(it.get(EVM_TX_SUBMIT_COLUMN_MAX_PRIORITY_FEE_PER_GAS)),
            BigInteger.valueOf(it.get(EVM_TX_SUBMIT_COLUMN_MAX_FEE_PER_GAS)),
            it.get(EVM_TX_SUBMIT_COLUMN_SENDER),
            it.get(EVM_TX_SUBMIT_COLUMN_CREATED),
            it.get(EVM_TX_SUBMIT_COLUMN_HASH),
            null
        ),
        it.get(EVM_TX_SUBMIT_COLUMN_BC_PERSISTED)
    )
}
