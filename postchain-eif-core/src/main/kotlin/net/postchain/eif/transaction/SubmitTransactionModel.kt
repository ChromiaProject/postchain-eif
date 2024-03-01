package net.postchain.eif.transaction

import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_PENDING_COLUMN_BLOCK_HASH
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_PENDING_COLUMN_BLOCK_NUMBER
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_PENDING_COLUMN_CONTRACT
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_PENDING_COLUMN_EFFECTIVE_GAS_PRICE
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_PENDING_COLUMN_FUNCTION
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_PENDING_COLUMN_GAS_USAGE
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_PENDING_COLUMN_HASH
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_PENDING_COLUMN_NETWORK_ID
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_PENDING_COLUMN_PARAMETER_TYPES
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_PENDING_COLUMN_PARAMETER_VALUES
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_PENDING_COLUMN_REQUEST_ID
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_PENDING_COLUMN_STATUS
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_PENDING_COLUMN_UPDATED
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_SUBMIT_COLUMN_CONTRACT
import net.postchain.eif.transaction.TransactionSubmitterDatabaseOperationsImpl.Companion.EVM_TX_SUBMIT_COLUMN_FUNCTION
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
    @Name("timestamp")
    val timestamp: Long,
)

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
)

data class EvmPendingDbTx(
    val pendingTransaction: EvmPendingRellTx,
    var updated: Long = System.currentTimeMillis(), // Epoch millis
    var blockNumber: BigInteger? = null,
    var blockHash: String? = null,
    var status: PendingTxStatus = PendingTxStatus.NOTHING_VERIFIED,
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

enum class RellTransactionStatus {
    QUEUED,
    TAKEN,
    PENDING,
    SUCCESS,
    FAILURE
}

val evmSubmitTransactionRequestRecordMapper = RecordMapper<Record, EvmSubmitTransactionRequest> {
    EvmSubmitTransactionRequest(
        it.get(EVM_TX_SUBMIT_COLUMN_REQUEST_ID),
        it.get(EVM_TX_SUBMIT_COLUMN_CONTRACT),
        it.get(EVM_TX_SUBMIT_COLUMN_FUNCTION),
        it.get(EVM_TX_SUBMIT_COLUMN_PARAMETER_TYPES).split(","),
        GtvDecoder.decodeGtv(it.get(EVM_TX_SUBMIT_COLUMN_PARAMETER_VALUES)).asArray().toList(),
        it.get(EVM_TX_SUBMIT_COLUMN_NETWORK_ID),
        it.get(EVM_TX_SUBMIT_COLUMN_SENDER),
        it.get(EVM_TX_SUBMIT_COLUMN_TIMESTAMP)
    )
}

val evmPendingTransactionRecordMapper = RecordMapper<Record, EvmPendingDbTx> {
    EvmPendingDbTx(
        EvmPendingRellTx(
            it.get(EVM_TX_PENDING_COLUMN_REQUEST_ID),
            it.get(EVM_TX_PENDING_COLUMN_NETWORK_ID),
            it.get(EVM_TX_PENDING_COLUMN_CONTRACT),
            it.get(EVM_TX_PENDING_COLUMN_FUNCTION),
            it.get(EVM_TX_PENDING_COLUMN_PARAMETER_TYPES).split(","),
            GtvDecoder.decodeGtv(it.get(EVM_TX_PENDING_COLUMN_PARAMETER_VALUES)).asArray().toList(),
            it.get(EVM_TX_PENDING_COLUMN_HASH),
        ),
        it.get(EVM_TX_PENDING_COLUMN_UPDATED).time,
        it.get(EVM_TX_PENDING_COLUMN_BLOCK_NUMBER)?.toBigInteger(),
        it.get(EVM_TX_PENDING_COLUMN_BLOCK_HASH),
        PendingTxStatus.valueOf(it.get(EVM_TX_PENDING_COLUMN_STATUS)),
        it.get(EVM_TX_PENDING_COLUMN_EFFECTIVE_GAS_PRICE)?.toBigInteger(),
        it.get(EVM_TX_PENDING_COLUMN_GAS_USAGE)?.toBigInteger(),
    )
}
