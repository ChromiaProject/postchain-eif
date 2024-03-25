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
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_SUBMIT_COLUMN_TIMESTAMP
import net.postchain.gtv.Gtv
import net.postchain.gtv.GtvDecoder
import net.postchain.gtv.mapper.Name
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
    @Name("timestamp")
    val timestamp: Long,
)

class EvmSubmitTxRequest(
    rellRequest: EvmSubmitTxRellRequest,
    var txHash: String? = null,
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
    rellRequest.timestamp,
)  {

    companion object {
        fun fromRell(rellRequest: EvmSubmitTxRellRequest): EvmSubmitTxRequest {
            return EvmSubmitTxRequest(
                rellRequest
            )
        }
    }
}

open class EvmPendingRellTx(
    @Name("row_id")
    val rowId: Long,
    @Name("network_id")
    val networkId: Long,
    @Name("contract_address")
    val contractAddress: String,
    @Name("function_name")
    val functionName: String,
    @Name("parameter_types")
    val parameterTypes: List<String>,
    @Name("parameter_values")
    val parameterValues: List<Gtv>,
    @Name("tx_hash")
    val txHash: String,
) {
    companion object {

        fun fromRellRequestAndHash(submitTxRequest: EvmSubmitTxRequest, txHash: String): EvmPendingRellTx {
            return EvmPendingRellTx(
                submitTxRequest.rowId,
                submitTxRequest.networkId,
                submitTxRequest.contractAddress,
                submitTxRequest.functionName,
                submitTxRequest.parameterTypes,
                submitTxRequest.parameterValues,
                txHash
            )
        }
    }
}

class EvmPendingTx(
    pendingTransaction: EvmPendingRellTx,
    var created: Long = System.currentTimeMillis(),
    var blockNumber: BigInteger? = null,
    var blockHash: String? = null,
    var status: PendingTxStatus = PendingTxStatus.VERIFYING,
    var effectiveGasPrice: BigInteger? = null,
    var gasUsed: BigInteger? = null,
) : EvmPendingRellTx(
    pendingTransaction.rowId,
    pendingTransaction.networkId,
    pendingTransaction.contractAddress,
    pendingTransaction.functionName,
    pendingTransaction.parameterTypes,
    pendingTransaction.parameterValues,
    pendingTransaction.txHash,
)

val evmSubmitTxRellRequestRecordMapper = RecordMapper<Record, EvmSubmitTxRequest> {
    val submitTx = EvmSubmitTxRequest.fromRell(
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
            it.get(EVM_TX_SUBMIT_COLUMN_TIMESTAMP),
        )
    )
    submitTx.txHash = it.get(EVM_TX_SUBMIT_COLUMN_HASH)
    submitTx.bcPersisted = it.get(EVM_TX_SUBMIT_COLUMN_BC_PERSISTED)
    submitTx
}
