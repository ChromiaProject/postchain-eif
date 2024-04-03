package net.postchain.eif.transaction

import net.postchain.base.data.DatabaseAccess
import net.postchain.core.EContext
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.GtvFactory
import org.jooq.Field
import org.jooq.SQLDialect
import org.jooq.impl.DSL.currentTimestamp
import org.jooq.impl.DSL.field
import org.jooq.impl.DSL.table
import org.jooq.impl.DSL.using
import org.jooq.util.postgres.PostgresDataType
import java.math.BigInteger
import java.sql.Timestamp

enum class PendingTxStatus {
    VERIFYING,
    SUCCESS,
    REVERTED;

    fun isCompleted(): Boolean {
        return this == SUCCESS || this == REVERTED
    }
}

fun DatabaseAccess.tableEvmTxSubmit(ctx: EContext) = tableName(ctx,
    TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_TABLE_NAME
)

fun DatabaseAccess.tableEvmTxErrors(ctx: EContext) = tableName(ctx,
    TransactionSubmitterDatabaseOperationsImpl.EVM_TX_ERRORS_TABLE_NAME
)

open class TransactionSubmitterDatabaseOperationsImpl : TransactionSubmitterDatabaseOperations {

    companion object {
        private const val PREFIX: String = "sys.x.evm_tx" // This name should not clash with Rell
        const val EVM_TX_SUBMIT_TABLE_NAME: String = "${PREFIX}.submit"
        const val EVM_TX_ERRORS_TABLE_NAME: String = "${PREFIX}.errors"

        val EVM_TX_SUBMIT_COLUMN_REQUEST_ID: Field<Long> = field("request_id", PostgresDataType.BIGINT.nullable(false))
        val EVM_TX_SUBMIT_COLUMN_CONTRACT: Field<String> = field("contract", PostgresDataType.TEXT.nullable(false))
        val EVM_TX_SUBMIT_COLUMN_FUNCTION: Field<String> = field("function", PostgresDataType.TEXT.nullable(false))
        val EVM_TX_SUBMIT_COLUMN_PARAMETER_TYPES: Field<String> = field("parameter_types", PostgresDataType.TEXT.nullable(false))
        val EVM_TX_SUBMIT_COLUMN_PARAMETER_VALUES: Field<ByteArray> = field("parameter_values", PostgresDataType.BYTEA.nullable(false))
        val EVM_TX_SUBMIT_COLUMN_MAX_GAS_PRICE: Field<Long> = field("max_gas_price", PostgresDataType.BIGINT.nullable(true))
        val EVM_TX_SUBMIT_COLUMN_MAX_PRIORITY_FEE_PER_GAS: Field<Long> = field("max_priority_fee_per_gas", PostgresDataType.BIGINT.nullable(false))
        val EVM_TX_SUBMIT_COLUMN_MAX_FEE_PER_GAS: Field<Long> = field("max_fee_per_gas", PostgresDataType.BIGINT.nullable(false))
        val EVM_TX_SUBMIT_COLUMN_GAS_LIMIT: Field<Long> = field("gas_limit", PostgresDataType.BIGINT.nullable(true))
        val EVM_TX_SUBMIT_COLUMN_CREATED: Field<Long> = field("created", PostgresDataType.BIGINT.nullable(false))
        val EVM_TX_SUBMIT_COLUMN_NETWORK_ID: Field<Long> = field("network_id", PostgresDataType.BIGINT.nullable(false))
        val EVM_TX_SUBMIT_COLUMN_SENDER: Field<ByteArray> = field("sender", PostgresDataType.BYTEA.nullable(false))
        val EVM_TX_SUBMIT_COLUMN_HASH: Field<String> = field("hash", PostgresDataType.TEXT.nullable(true))
        val EVM_TX_SUBMIT_COLUMN_BC_PERSISTED: Field<Boolean> = field("bc_persisted", PostgresDataType.BOOLEAN.nullable(false).defaultValue(false))

        val EVM_TX_ERRORS_COLUMN_TIMESTAMP: Field<Timestamp> = field("timestamp", PostgresDataType.TIMESTAMP.nullable(false)
            .defaultValue(currentTimestamp()))
        val EVM_TX_ERRORS_COLUMN_REQUEST_ID: Field<Long> = field("request_id", PostgresDataType.BIGINT.nullable(false))
        val EVM_TX_ERRORS_COLUMN_RPC_URL: Field<String> = field("rpc_url", PostgresDataType.TEXT.nullable(true))
        val EVM_TX_ERRORS_COLUMN_MESSAGE: Field<String> = field("message", PostgresDataType.TEXT.nullable(false))
        val EVM_TX_ERRORS_COLUMN_STACK_TRACE: Field<String> = field("stack_trace", PostgresDataType.TEXT.nullable(true))
    }

    override fun initialize(ctx: EContext) {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            val transactionTable = table(tableEvmTxSubmit(ctx))
            jooq.createTableIfNotExists(transactionTable)
                    .column(EVM_TX_SUBMIT_COLUMN_REQUEST_ID)
                    .column(EVM_TX_SUBMIT_COLUMN_CONTRACT)
                    .column(EVM_TX_SUBMIT_COLUMN_FUNCTION)
                    .column(EVM_TX_SUBMIT_COLUMN_PARAMETER_TYPES)
                    .column(EVM_TX_SUBMIT_COLUMN_PARAMETER_VALUES)
                    .column(EVM_TX_SUBMIT_COLUMN_MAX_GAS_PRICE)
                    .column(EVM_TX_SUBMIT_COLUMN_MAX_PRIORITY_FEE_PER_GAS)
                    .column(EVM_TX_SUBMIT_COLUMN_MAX_FEE_PER_GAS)
                    .column(EVM_TX_SUBMIT_COLUMN_GAS_LIMIT)
                    .column(EVM_TX_SUBMIT_COLUMN_CREATED)
                    .column(EVM_TX_SUBMIT_COLUMN_NETWORK_ID)
                    .column(EVM_TX_SUBMIT_COLUMN_SENDER)
                    .column(EVM_TX_SUBMIT_COLUMN_HASH)
                    .column(EVM_TX_SUBMIT_COLUMN_BC_PERSISTED)
                    .execute()

            val errorsTable = table(tableEvmTxErrors(ctx))
            jooq.createTableIfNotExists(errorsTable)
                .column(EVM_TX_ERRORS_COLUMN_TIMESTAMP)
                .column(EVM_TX_ERRORS_COLUMN_REQUEST_ID)
                .column(EVM_TX_ERRORS_COLUMN_RPC_URL)
                .column(EVM_TX_ERRORS_COLUMN_MESSAGE)
                .column(EVM_TX_ERRORS_COLUMN_STACK_TRACE)
                .execute()
        }
    }

    override fun queueTransaction(ctx: EContext, transactionRequest: EvmSubmitTxRequest, networkId: Long) {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            jooq.insertInto(table(tableEvmTxSubmit(ctx)))
                .set(EVM_TX_SUBMIT_COLUMN_REQUEST_ID, transactionRequest.rowId)
                .set(EVM_TX_SUBMIT_COLUMN_CONTRACT, transactionRequest.contractAddress)
                .set(EVM_TX_SUBMIT_COLUMN_FUNCTION, transactionRequest.functionName)
                .set(EVM_TX_SUBMIT_COLUMN_PARAMETER_TYPES, transactionRequest.parameterTypes.joinToString(","))
                .set(EVM_TX_SUBMIT_COLUMN_PARAMETER_VALUES, GtvEncoder.encodeGtv(GtvFactory.gtv(transactionRequest.parameterValues)))
                .set(EVM_TX_SUBMIT_COLUMN_CREATED, transactionRequest.created)
                .set(EVM_TX_SUBMIT_COLUMN_NETWORK_ID, networkId)
                .set(EVM_TX_SUBMIT_COLUMN_MAX_PRIORITY_FEE_PER_GAS, transactionRequest.maxPriorityFeePerGas.longValueExact())
                .set(EVM_TX_SUBMIT_COLUMN_MAX_FEE_PER_GAS, transactionRequest.maxFeePerGas.longValueExact())
                .set(EVM_TX_SUBMIT_COLUMN_SENDER, transactionRequest.sender)
                .execute()
        }
    }

    override fun recordTransactionHash(ctx: EContext, requestId: Long, txHash: String) {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            jooq.update(table(tableEvmTxSubmit(ctx)))
                .set(EVM_TX_SUBMIT_COLUMN_HASH, txHash)
                .where(EVM_TX_SUBMIT_COLUMN_REQUEST_ID.eq(requestId))
                .execute()
        }
    }

    override fun recordTransactionGas(ctx: EContext, requestId: Long, gasPrice: BigInteger, gasLimit: BigInteger) {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            jooq.update(table(tableEvmTxSubmit(ctx)))
                .set(EVM_TX_SUBMIT_COLUMN_MAX_GAS_PRICE, gasPrice.longValueExact())
                .set(EVM_TX_SUBMIT_COLUMN_GAS_LIMIT, gasLimit.longValueExact())
                .where(EVM_TX_SUBMIT_COLUMN_REQUEST_ID.eq(requestId))
                .execute()
        }
    }

    override fun setSubmitTxBCPersisted(ctx: EContext, requestId: Long) {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            jooq.update(table(tableEvmTxSubmit(ctx)))
                .set(EVM_TX_SUBMIT_COLUMN_BC_PERSISTED, true)
                .where(EVM_TX_SUBMIT_COLUMN_REQUEST_ID.eq(requestId))
                .execute()
        }
    }

    override fun recordTransactionError(
        ctx: EContext,
        requestId: Long,
        rpcUrl: String?,
        message: String,
        stackTrace: String?
    ) {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            jooq.insertInto(table(tableEvmTxErrors(ctx)))
                .set(EVM_TX_ERRORS_COLUMN_TIMESTAMP, currentTimestamp())
                .set(EVM_TX_ERRORS_COLUMN_REQUEST_ID, requestId)
                .set(EVM_TX_ERRORS_COLUMN_RPC_URL, rpcUrl)
                .set(EVM_TX_ERRORS_COLUMN_MESSAGE, message)
                .set(EVM_TX_ERRORS_COLUMN_STACK_TRACE, stackTrace)
                .execute()
        }
    }

    override fun getQueuedTransactions(ctx: EContext, networkId: Long) : List<EvmSubmitTxRequest> {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            return jooq.select().from(tableEvmTxSubmit(ctx))
                    .where(EVM_TX_SUBMIT_COLUMN_NETWORK_ID.eq(networkId).and(EVM_TX_SUBMIT_COLUMN_BC_PERSISTED.eq(false)))
                    .fetch(evmSubmitTxRequestRecordMapper)
        }
    }

    private fun createJooq(ctx: EContext) = using(ctx.conn, SQLDialect.POSTGRES)
}
