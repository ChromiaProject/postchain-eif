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

enum class TransactionStatus {
    QUEUED,
    PENDING,
    SUCCESS,
    FAILURE
}

fun DatabaseAccess.tableEvmTransaction(ctx: EContext) = tableName(ctx,
    TransactionSubmitterDatabaseOperationsImpl.EVM_TX_TRANSACTIONS_TABLE_NAME
)

fun DatabaseAccess.tableEvmErrors(ctx: EContext) = tableName(ctx,
    TransactionSubmitterDatabaseOperationsImpl.EVM_TX_ERRORS_TABLE_NAME
)

open class TransactionSubmitterDatabaseOperationsImpl : TransactionSubmitterDatabaseOperations {

    companion object {
        private const val PREFIX: String = "sys.x.evm_tx" // This name should not clash with Rell
        const val EVM_TX_TRANSACTIONS_TABLE_NAME: String = "${PREFIX}.transactions"
        const val EVM_TX_ERRORS_TABLE_NAME: String = "${PREFIX}.errors"

        val TRANSACTIONS_COLUMN_REQUEST_ID: Field<Long> = field("request_id", PostgresDataType.BIGINT.nullable(false))
        val TRANSACTIONS_COLUMN_ACTIVE: Field<Boolean> = field("active", PostgresDataType.BOOLEAN.nullable(false))
        val TRANSACTIONS_COLUMN_CONTRACT: Field<String> = field("contract", PostgresDataType.TEXT.nullable(false))
        val TRANSACTIONS_COLUMN_FUNCTION: Field<String> = field("function", PostgresDataType.TEXT.nullable(false))
        val TRANSACTIONS_COLUMN_PARAMETER_TYPES: Field<String> = field("parameter_types", PostgresDataType.TEXT.nullable(false))
        val TRANSACTIONS_COLUMN_PARAMETER_VALUES: Field<ByteArray> = field("parameter_values", PostgresDataType.BYTEA.nullable(false))
        val TRANSACTIONS_COLUMN_GAS_PRICE: Field<Long> = field("gas_price", PostgresDataType.BIGINT.nullable(true))
        val TRANSACTIONS_COLUMN_GAS_LIMIT: Field<Long> = field("gas_limit", PostgresDataType.BIGINT.nullable(true))
        val TRANSACTIONS_COLUMN_TX_HASH: Field<String> = field("tx_hash", PostgresDataType.TEXT.nullable(true))
        val TRANSACTIONS_COLUMN_STATUS: Field<String> = field("status", PostgresDataType.TEXT.nullable(false))
        val TRANSACTIONS_COLUMN_TIMESTAMP: Field<Timestamp> = field("timestamp", PostgresDataType.TIMESTAMP.nullable(false))
        val TRANSACTIONS_COLUMN_NETWORK_ID: Field<Long> = field("network_id", PostgresDataType.BIGINT.nullable(false))
        val TRANSACTIONS_COLUMN_SENDER: Field<ByteArray> = field("sender", PostgresDataType.BYTEA.nullable(false))
        val TRANSACTIONS_COLUMN_BLOCK_HASH: Field<String> = field("receipt_block_hash", PostgresDataType.VARCHAR.length(2 + 64).nullable(true))
        val TRANSACTIONS_COLUMN_EFFECTIVE_GAS_PRICE: Field<Long> = field("receipt_effective_gas_price", PostgresDataType.BIGINT.nullable(true))
        val TRANSACTIONS_COLUMN_GAS_USAGE: Field<Long> = field("receipt_gas_usage", PostgresDataType.BIGINT.nullable(true))

        val ERRORS_COLUMN_TIMESTAMP: Field<Timestamp> = field("timestamp", PostgresDataType.TIMESTAMP.nullable(false)
            .defaultValue(currentTimestamp()))
        val ERRORS_COLUMN_REQUEST_ID: Field<Long> = field("request_id", PostgresDataType.BIGINT.nullable(false))
        val ERRORS_COLUMN_RPC_URL: Field<String> = field("rpc_url", PostgresDataType.TEXT.nullable(true))
        val ERRORS_COLUMN_MESSAGE: Field<String> = field("message", PostgresDataType.TEXT.nullable(false))
        val ERRORS_COLUMN_STACK_TRACE: Field<String> = field("stack_trace", PostgresDataType.TEXT.nullable(true))
    }

    override fun initialize(ctx: EContext) {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            val transactionTable = table(tableEvmTransaction(ctx))
            jooq.createTableIfNotExists(transactionTable)
                    .column(TRANSACTIONS_COLUMN_REQUEST_ID)
                    .column(TRANSACTIONS_COLUMN_ACTIVE)
                    .column(TRANSACTIONS_COLUMN_CONTRACT)
                    .column(TRANSACTIONS_COLUMN_FUNCTION)
                    .column(TRANSACTIONS_COLUMN_PARAMETER_TYPES)
                    .column(TRANSACTIONS_COLUMN_PARAMETER_VALUES)
                    .column(TRANSACTIONS_COLUMN_GAS_PRICE)
                    .column(TRANSACTIONS_COLUMN_GAS_LIMIT)
                    .column(TRANSACTIONS_COLUMN_TX_HASH)
                    .column(TRANSACTIONS_COLUMN_STATUS)
                    .column(TRANSACTIONS_COLUMN_TIMESTAMP)
                    .column(TRANSACTIONS_COLUMN_NETWORK_ID)
                    .column(TRANSACTIONS_COLUMN_SENDER)
                    .column(TRANSACTIONS_COLUMN_BLOCK_HASH)
                    .column(TRANSACTIONS_COLUMN_EFFECTIVE_GAS_PRICE)
                    .column(TRANSACTIONS_COLUMN_GAS_USAGE)
                    .execute()

            val errorsTable = table(tableEvmErrors(ctx))
            jooq.createTableIfNotExists(errorsTable)
                .column(ERRORS_COLUMN_TIMESTAMP)
                .column(ERRORS_COLUMN_REQUEST_ID)
                .column(ERRORS_COLUMN_RPC_URL)
                .column(ERRORS_COLUMN_MESSAGE)
                .column(ERRORS_COLUMN_STACK_TRACE)
                .execute()
        }
    }

    override fun queueTransaction(ctx: EContext, transactionRequest: EvmSubmitTransactionRequest, networkId: Long) {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            jooq.insertInto(table(tableEvmTransaction(ctx)))
                .set(TRANSACTIONS_COLUMN_REQUEST_ID, transactionRequest.rowId)
                .set(TRANSACTIONS_COLUMN_ACTIVE, true)
                .set(TRANSACTIONS_COLUMN_CONTRACT, transactionRequest.contractAddress)
                .set(TRANSACTIONS_COLUMN_FUNCTION, transactionRequest.functionName)
                .set(TRANSACTIONS_COLUMN_PARAMETER_TYPES, transactionRequest.parameterTypes.joinToString(","))
                .set(TRANSACTIONS_COLUMN_PARAMETER_VALUES, GtvEncoder.encodeGtv(GtvFactory.gtv(transactionRequest.parameterValues)))
                .set(TRANSACTIONS_COLUMN_STATUS, TransactionStatus.QUEUED.name)
                .set(TRANSACTIONS_COLUMN_TIMESTAMP, currentTimestamp())
                .set(TRANSACTIONS_COLUMN_NETWORK_ID, networkId)
                .set(TRANSACTIONS_COLUMN_SENDER, transactionRequest.sender)
                .execute()
        }
    }

    override fun pendTransaction(ctx: EContext, requestId: Long, transactionHash: String) {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            jooq.update(table(tableEvmTransaction(ctx)))
                    .set(TRANSACTIONS_COLUMN_STATUS, TransactionStatus.PENDING.name)
                    .set(TRANSACTIONS_COLUMN_TX_HASH, transactionHash)
                    .where(TRANSACTIONS_COLUMN_REQUEST_ID.eq(requestId))
                    .execute()
        }
    }

    override fun failTransaction(ctx: EContext, requestId: Long) {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            jooq.update(table(tableEvmTransaction(ctx)))
                    .set(TRANSACTIONS_COLUMN_STATUS, TransactionStatus.FAILURE.name)
                    .where(TRANSACTIONS_COLUMN_REQUEST_ID.eq(requestId))
                    .execute()
        }
    }

    override fun recordTransactionGas(ctx: EContext, requestId: Long, gasPrice: BigInteger, gasLimit: BigInteger) {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            jooq.update(table(tableEvmTransaction(ctx)))
                .set(TRANSACTIONS_COLUMN_GAS_PRICE, gasPrice.longValueExact())
                .set(TRANSACTIONS_COLUMN_GAS_LIMIT, gasLimit.longValueExact())
                .where(TRANSACTIONS_COLUMN_REQUEST_ID.eq(requestId))
                .execute()
        }
    }

    override fun recordTransactionFailure(
        ctx: EContext,
        requestId: Long,
        rpcUrl: String?,
        message: String,
        stackTrace: String?
    ) {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            jooq.insertInto(table(tableEvmErrors(ctx)))
                .set(ERRORS_COLUMN_TIMESTAMP, currentTimestamp())
                .set(ERRORS_COLUMN_REQUEST_ID, requestId)
                .set(ERRORS_COLUMN_RPC_URL, rpcUrl)
                .set(ERRORS_COLUMN_MESSAGE, message)
                .set(ERRORS_COLUMN_STACK_TRACE, stackTrace)
                .execute()
        }
    }

    override fun succeedTransaction(ctx: EContext, requestId: Long, effectiveGasPrice: BigInteger, gasUsed: BigInteger, blockHash: String) {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            jooq.update(table(tableEvmTransaction(ctx)))
                    .set(TRANSACTIONS_COLUMN_STATUS, TransactionStatus.SUCCESS.name)
                    .set(TRANSACTIONS_COLUMN_BLOCK_HASH, blockHash)
                    .set(TRANSACTIONS_COLUMN_EFFECTIVE_GAS_PRICE, effectiveGasPrice.longValueExact())
                    .set(TRANSACTIONS_COLUMN_GAS_USAGE, gasUsed.longValueExact())
                    .where(TRANSACTIONS_COLUMN_REQUEST_ID.eq(requestId))
                    .execute()

        }
    }

    override fun deactivateTransaction(ctx: EContext, requestId: Long) {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            jooq.update(table(tableEvmTransaction(ctx)))
                    .set(TRANSACTIONS_COLUMN_ACTIVE, false)
                    .where(TRANSACTIONS_COLUMN_REQUEST_ID.eq(requestId))
                    .execute()
        }
    }

    override fun getQueuedTransactions(ctx: EContext, networkId: Long) : List<EvmSubmitTransactionRequest> {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            return jooq.select().from(tableEvmTransaction(ctx))
                    .where(TRANSACTIONS_COLUMN_STATUS.eq(TransactionStatus.QUEUED.name).and(TRANSACTIONS_COLUMN_NETWORK_ID.eq(networkId)))
                    .fetch(evmSubmitTransactionRequestRecordMapper)
        }
    }

    override fun getPendingTransactions(ctx: EContext, networkId: Long) : MutableMap<String, EvmSubmitTransactionRequest>{
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            return jooq.select().from(tableEvmTransaction(ctx))
                    .where(TRANSACTIONS_COLUMN_STATUS.eq(TransactionStatus.PENDING.name).and(TRANSACTIONS_COLUMN_NETWORK_ID.eq(networkId)))
                    .fetchMap(TRANSACTIONS_COLUMN_TX_HASH, evmSubmitTransactionRequestRecordMapper)
        }
    }

    override fun getCompletedTransactions(ctx: EContext, networkId: Long): MutableMap<Long, EvmSubmitTransactionResult> {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            return jooq.select().from(tableEvmTransaction(ctx))
                    .where(TRANSACTIONS_COLUMN_STATUS.eq(TransactionStatus.SUCCESS.name).or(TRANSACTIONS_COLUMN_STATUS.eq(TransactionStatus.FAILURE.name)).and(TRANSACTIONS_COLUMN_NETWORK_ID.eq(networkId).and(TRANSACTIONS_COLUMN_ACTIVE.eq(true))))
                    .fetchMap(TRANSACTIONS_COLUMN_REQUEST_ID, evmSubmitTransactionResultRecordMapper)
        }
    }

    override fun getTransactionErrors(ctx: EContext, requestId: Long): List<EvmSubmitTransactionError> {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            return jooq.select().from(tableEvmErrors(ctx))
                .where(ERRORS_COLUMN_REQUEST_ID.eq(requestId))
                .fetch { EvmSubmitTransactionError(
                    it.get(ERRORS_COLUMN_REQUEST_ID),
                    it.get(ERRORS_COLUMN_TIMESTAMP),
                    it.get(ERRORS_COLUMN_RPC_URL),
                    it.get(ERRORS_COLUMN_MESSAGE)
                ) }
        }
    }

    private fun createJooq(ctx: EContext) = using(ctx.conn, SQLDialect.POSTGRES)
}
