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

class TransactionSubmitterDatabaseOperationsImpl : TransactionSubmitterDatabaseOperations {

    companion object {
        const val PREFIX: String = "sys.x.evm_tx" // This name should not clash with Rell
        const val EVM_TX_TRANSACTIONS_TABLE_NAME: String = "${PREFIX}.transactions"

        val COLUMN_REQUEST_ID: Field<Long> = field("request_id", PostgresDataType.BIGINT.nullable(false))
        val COLUMN_ACTIVE: Field<Boolean> = field("active", PostgresDataType.BOOLEAN.nullable(false))
        val COLUMN_CONTRACT: Field<String> = field("contract", PostgresDataType.TEXT.nullable(false))
        val COLUMN_FUNCTION: Field<String> = field("function", PostgresDataType.TEXT.nullable(false))
        val COLUMN_PARAMETER_TYPES: Field<String> = field("parameter_types", PostgresDataType.TEXT.nullable(false))
        val COLUMN_PARAMETER_VALUES: Field<ByteArray> = field("parameter_values", PostgresDataType.BYTEA.nullable(false))
        val COLUMN_GAS_PRICE: Field<Long> = field("gas_price", PostgresDataType.BIGINT.nullable(true))
        val COLUMN_GAS_LIMIT: Field<Long> = field("gas_limit", PostgresDataType.BIGINT.nullable(true))
        val COLUMN_TX_HASH: Field<String> = field("tx_hash", PostgresDataType.TEXT.nullable(true))
        val COLUMN_ERROR_MESSAGE: Field<String> = field("error_message", PostgresDataType.TEXT)
        val COLUMN_STATUS: Field<String> = field("status", PostgresDataType.TEXT.nullable(false))
        val COLUMN_TIMESTAMP: Field<Timestamp> = field("timestamp", PostgresDataType.TIMESTAMP.nullable(false))
        val COLUMN_NETWORK_ID: Field<Long> = field("network_id", PostgresDataType.BIGINT.nullable(false))
        val COLUMN_SENDER: Field<ByteArray> = field("sender", PostgresDataType.BYTEA.nullable(false))
        val COLUMN_BLOCK_HASH: Field<String> = field("receipt_block_hash", PostgresDataType.VARCHAR.length(2 + 64).nullable(true))
        val COLUMN_EFFECTIVE_GAS_PRICE: Field<Long> = field("receipt_effective_gas_price", PostgresDataType.BIGINT.nullable(true))
        val COLUMN_GAS_USAGE: Field<Long> = field("receipt_gas_usage", PostgresDataType.BIGINT.nullable(true))
    }

    override fun initialize(ctx: EContext) {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            val transactionTable = table(tableEvmTransaction(ctx))
            jooq.createTableIfNotExists(transactionTable)
                    .column(COLUMN_REQUEST_ID)
                    .column(COLUMN_ACTIVE)
                    .column(COLUMN_CONTRACT)
                    .column(COLUMN_FUNCTION)
                    .column(COLUMN_PARAMETER_TYPES)
                    .column(COLUMN_PARAMETER_VALUES)
                    .column(COLUMN_GAS_PRICE)
                    .column(COLUMN_GAS_LIMIT)
                    .column(COLUMN_TX_HASH)
                    .column(COLUMN_ERROR_MESSAGE)
                    .column(COLUMN_STATUS)
                    .column(COLUMN_TIMESTAMP)
                    .column(COLUMN_NETWORK_ID)
                    .column(COLUMN_SENDER)
                    .column(COLUMN_BLOCK_HASH)
                    .column(COLUMN_EFFECTIVE_GAS_PRICE)
                    .column(COLUMN_GAS_USAGE)
                    .execute()
        }
    }

    private fun recordTransaction(ctx: EContext, transactionRequest: EvmSubmitTransactionRequest, status: TransactionStatus, networkId: Long) {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            jooq.insertInto(table(tableEvmTransaction(ctx)))
                    .set(COLUMN_REQUEST_ID, transactionRequest.rowId)
                    .set(COLUMN_ACTIVE, true)
                    .set(COLUMN_CONTRACT, transactionRequest.contractAddress)
                    .set(COLUMN_FUNCTION, transactionRequest.functionName)
                    .set(COLUMN_PARAMETER_TYPES, transactionRequest.parameterTypes.joinToString(","))
                    .set(COLUMN_PARAMETER_VALUES, GtvEncoder.encodeGtv(GtvFactory.gtv(transactionRequest.parameterValues)))
                    .set(COLUMN_STATUS, status.name)
                    .set(COLUMN_TIMESTAMP, currentTimestamp())
                    .set(COLUMN_NETWORK_ID, networkId)
                    .set(COLUMN_SENDER, transactionRequest.sender)
                    .execute()
        }
    }

    override fun queueTransaction(ctx: EContext, transactionRequest: EvmSubmitTransactionRequest, networkId: Long) {
        recordTransaction(ctx, transactionRequest, TransactionStatus.QUEUED, networkId)
    }

    override fun pendTransaction(ctx: EContext, requestId: Long, gasPrice: BigInteger, gasLimit: BigInteger, transactionHash: String) {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            jooq.update(table(tableEvmTransaction(ctx)))
                    .set(COLUMN_STATUS, TransactionStatus.PENDING.name)
                    .set(COLUMN_GAS_PRICE, gasPrice.longValueExact())
                    .set(COLUMN_GAS_LIMIT, gasLimit.longValueExact())
                    .set(COLUMN_TX_HASH, transactionHash)
                    .where(COLUMN_REQUEST_ID.eq(requestId))
                    .execute()
        }
    }

    override fun failTransaction(ctx: EContext, requestId: Long, gasPrice: BigInteger, gasLimit: BigInteger, errorMessage: String) {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            jooq.update(table(tableEvmTransaction(ctx)))
                    .set(COLUMN_STATUS, TransactionStatus.FAILURE.name)
                    .set(COLUMN_GAS_PRICE, gasPrice.longValueExact())
                    .set(COLUMN_GAS_LIMIT, gasLimit.longValueExact())
                    .set(COLUMN_ERROR_MESSAGE, errorMessage)
                    .where(COLUMN_REQUEST_ID.eq(requestId))
                    .execute()
        }
    }

    override fun succeedTransaction(ctx: EContext, requestId: Long, effectiveGasPrice: BigInteger, gasUsed: BigInteger, blockHash: String) {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            jooq.update(table(tableEvmTransaction(ctx)))
                    .set(COLUMN_STATUS, TransactionStatus.SUCCESS.name)
                    .set(COLUMN_BLOCK_HASH, blockHash)
                    .set(COLUMN_EFFECTIVE_GAS_PRICE, effectiveGasPrice.longValueExact())
                    .set(COLUMN_GAS_USAGE, gasUsed.longValueExact())
                    .where(COLUMN_REQUEST_ID.eq(requestId))
                    .execute()

        }
    }

    override fun deactivateTransaction(ctx: EContext, requestId: Long) {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            jooq.update(table(tableEvmTransaction(ctx)))
                    .set(COLUMN_ACTIVE, false)
                    .where(COLUMN_REQUEST_ID.eq(requestId))
                    .execute()
        }
    }

    override fun getQueuedTransactions(ctx: EContext, networkId: Long) : List<EvmSubmitTransactionRequest> {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            return jooq.select().from(tableEvmTransaction(ctx))
                    .where(COLUMN_STATUS.eq(TransactionStatus.QUEUED.name).and(COLUMN_NETWORK_ID.eq(networkId)))
                    .fetch(evmSubmitTransactionRequestRecordMapper)
        }
    }

    override fun getPendingTransactions(ctx: EContext, networkId: Long) : MutableMap<String, EvmSubmitTransactionRequest>{
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            return jooq.select().from(tableEvmTransaction(ctx))
                    .where(COLUMN_STATUS.eq(TransactionStatus.PENDING.name).and(COLUMN_NETWORK_ID.eq(networkId)))
                    .fetchMap(COLUMN_TX_HASH, evmSubmitTransactionRequestRecordMapper)
        }
    }

    override fun getCompletedTransactions(ctx: EContext, networkId: Long): MutableMap<Long, EvmSubmitTransactionResult> {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            return jooq.select().from(tableEvmTransaction(ctx))
                    .where(COLUMN_STATUS.eq(TransactionStatus.SUCCESS.name).or(COLUMN_STATUS.eq(TransactionStatus.FAILURE.name)).and(COLUMN_NETWORK_ID.eq(networkId).and(COLUMN_ACTIVE.eq(true))))
                    .fetchMap(COLUMN_REQUEST_ID, evmSubmitTransactionResultRecordMapper)
        }
    }

    private fun createJooq(ctx: EContext) = using(ctx.conn, SQLDialect.POSTGRES)
}
