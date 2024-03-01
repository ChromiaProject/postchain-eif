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
import java.sql.Timestamp.from
import java.time.Instant
import java.time.temporal.ChronoUnit

enum class PendingTxStatus {
    NOTHING_VERIFIED,
    RECEIPT_FOUND,
    SUCCESS,
    REVERTED;

    fun isCompleted(): Boolean {
        return this == SUCCESS || this == REVERTED
    }
}

fun DatabaseAccess.tableEvmTxSubmit(ctx: EContext) = tableName(ctx,
    TransactionSubmitterDatabaseOperationsImpl.EVM_TX_SUBMIT_TABLE_NAME
)

fun DatabaseAccess.tableEvmTxPending(ctx: EContext) = tableName(ctx,
    TransactionSubmitterDatabaseOperationsImpl.EVM_TX_PENDING_TABLE_NAME
)

fun DatabaseAccess.tableEvmTxErrors(ctx: EContext) = tableName(ctx,
    TransactionSubmitterDatabaseOperationsImpl.EVM_TX_ERRORS_TABLE_NAME
)

open class TransactionSubmitterDatabaseOperationsImpl : TransactionSubmitterDatabaseOperations {

    companion object {
        private const val PREFIX: String = "sys.x.evm_tx" // This name should not clash with Rell
        const val EVM_TX_SUBMIT_TABLE_NAME: String = "${PREFIX}.submit"
        const val EVM_TX_PENDING_TABLE_NAME: String = "${PREFIX}.pending"
        const val EVM_TX_ERRORS_TABLE_NAME: String = "${PREFIX}.errors"

        val EVM_TX_SUBMIT_COLUMN_REQUEST_ID: Field<Long> = field("request_id", PostgresDataType.BIGINT.nullable(false))
        val EVM_TX_SUBMIT_COLUMN_CONTRACT: Field<String> = field("contract", PostgresDataType.TEXT.nullable(false))
        val EVM_TX_SUBMIT_COLUMN_FUNCTION: Field<String> = field("function", PostgresDataType.TEXT.nullable(false))
        val EVM_TX_SUBMIT_COLUMN_PARAMETER_TYPES: Field<String> = field("parameter_types", PostgresDataType.TEXT.nullable(false))
        val EVM_TX_SUBMIT_COLUMN_PARAMETER_VALUES: Field<ByteArray> = field("parameter_values", PostgresDataType.BYTEA.nullable(false))
        val EVM_TX_SUBMIT_COLUMN_GAS_PRICE: Field<Long> = field("gas_price", PostgresDataType.BIGINT.nullable(true))
        val EVM_TX_SUBMIT_COLUMN_GAS_LIMIT: Field<Long> = field("gas_limit", PostgresDataType.BIGINT.nullable(true))
        val EVM_TX_SUBMIT_COLUMN_TIMESTAMP: Field<Long> = field("timestamp", PostgresDataType.BIGINT.nullable(false))
        val EVM_TX_SUBMIT_COLUMN_NETWORK_ID: Field<Long> = field("network_id", PostgresDataType.BIGINT.nullable(false))
        val EVM_TX_SUBMIT_COLUMN_SENDER: Field<ByteArray> = field("sender", PostgresDataType.BYTEA.nullable(false))
        val EVM_TX_SUBMIT_COLUMN_BC_PERSISTED: Field<Boolean> = field("bc_persisted", PostgresDataType.BOOLEAN.nullable(false).defaultValue(false))

        val EVM_TX_PENDING_COLUMN_REQUEST_ID: Field<Long> = field("request_id", PostgresDataType.BIGINT.nullable(false))
        val EVM_TX_PENDING_COLUMN_NETWORK_ID: Field<Long> = field("network_id", PostgresDataType.BIGINT.nullable(false))
        val EVM_TX_PENDING_COLUMN_UPDATED: Field<Timestamp> = field("updated", PostgresDataType.TIMESTAMP.nullable(false))
        val EVM_TX_PENDING_COLUMN_STATUS: Field<String> = field("status", PostgresDataType.TEXT.nullable(false))
        val EVM_TX_PENDING_COLUMN_CONTRACT: Field<String> = field("contract", PostgresDataType.TEXT.nullable(false))
        val EVM_TX_PENDING_COLUMN_FUNCTION: Field<String> = field("function", PostgresDataType.TEXT.nullable(false))
        val EVM_TX_PENDING_COLUMN_PARAMETER_TYPES: Field<String> = field("parameter_types", PostgresDataType.TEXT.nullable(false))
        val EVM_TX_PENDING_COLUMN_PARAMETER_VALUES: Field<ByteArray> = field("parameter_values", PostgresDataType.BYTEA.nullable(false))
        val EVM_TX_PENDING_COLUMN_HASH: Field<String> = field("tx_hash", PostgresDataType.TEXT.nullable(false))
        val EVM_TX_PENDING_COLUMN_BLOCK_NUMBER: Field<Long> = field("block_number", PostgresDataType.BIGINT.nullable(true))
        val EVM_TX_PENDING_COLUMN_BLOCK_HASH: Field<String> = field("block_hash", PostgresDataType.VARCHAR.length(2 + 64).nullable(true))
        val EVM_TX_PENDING_COLUMN_EFFECTIVE_GAS_PRICE: Field<Long> = field("effective_gas_price", PostgresDataType.BIGINT.nullable(true))
        val EVM_TX_PENDING_COLUMN_GAS_USAGE: Field<Long> = field("gas_usage", PostgresDataType.BIGINT.nullable(true))
        val EVM_TX_PENDING_COLUMN_BC_PERSISTED: Field<Boolean> = field("bc_persisted", PostgresDataType.BOOLEAN.nullable(false).defaultValue(false))

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
                    .column(EVM_TX_SUBMIT_COLUMN_GAS_PRICE)
                    .column(EVM_TX_SUBMIT_COLUMN_GAS_LIMIT)
                    .column(EVM_TX_SUBMIT_COLUMN_TIMESTAMP)
                    .column(EVM_TX_SUBMIT_COLUMN_NETWORK_ID)
                    .column(EVM_TX_SUBMIT_COLUMN_SENDER)
                    .column(EVM_TX_SUBMIT_COLUMN_BC_PERSISTED)
                    .execute()

            val pendingTable = table(tableEvmTxPending(ctx))
            jooq.createTableIfNotExists(pendingTable)
                    .column(EVM_TX_PENDING_COLUMN_REQUEST_ID)
                    .column(EVM_TX_PENDING_COLUMN_NETWORK_ID)
                    .column(EVM_TX_PENDING_COLUMN_UPDATED)
                    .column(EVM_TX_PENDING_COLUMN_CONTRACT)
                    .column(EVM_TX_PENDING_COLUMN_FUNCTION)
                    .column(EVM_TX_PENDING_COLUMN_PARAMETER_TYPES)
                    .column(EVM_TX_PENDING_COLUMN_PARAMETER_VALUES)
                    .column(EVM_TX_PENDING_COLUMN_HASH)
                    .column(EVM_TX_PENDING_COLUMN_BLOCK_NUMBER)
                    .column(EVM_TX_PENDING_COLUMN_STATUS)
                    .column(EVM_TX_PENDING_COLUMN_BLOCK_HASH)
                    .column(EVM_TX_PENDING_COLUMN_EFFECTIVE_GAS_PRICE)
                    .column(EVM_TX_PENDING_COLUMN_GAS_USAGE)
                    .column(EVM_TX_PENDING_COLUMN_BC_PERSISTED)
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

    override fun queueTransaction(ctx: EContext, transactionRequest: EvmSubmitTransactionRequest, networkId: Long) {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            jooq.insertInto(table(tableEvmTxSubmit(ctx)))
                .set(EVM_TX_SUBMIT_COLUMN_REQUEST_ID, transactionRequest.rowId)
                .set(EVM_TX_SUBMIT_COLUMN_CONTRACT, transactionRequest.contractAddress)
                .set(EVM_TX_SUBMIT_COLUMN_FUNCTION, transactionRequest.functionName)
                .set(EVM_TX_SUBMIT_COLUMN_PARAMETER_TYPES, transactionRequest.parameterTypes.joinToString(","))
                .set(EVM_TX_SUBMIT_COLUMN_PARAMETER_VALUES, GtvEncoder.encodeGtv(GtvFactory.gtv(transactionRequest.parameterValues)))
                .set(EVM_TX_SUBMIT_COLUMN_TIMESTAMP, transactionRequest.timestamp)
                .set(EVM_TX_SUBMIT_COLUMN_NETWORK_ID, networkId)
                .set(EVM_TX_SUBMIT_COLUMN_SENDER, transactionRequest.sender)
                .execute()
        }
    }

    override fun recordTransactionGas(ctx: EContext, requestId: Long, gasPrice: BigInteger, gasLimit: BigInteger) {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            jooq.update(table(tableEvmTxSubmit(ctx)))
                .set(EVM_TX_SUBMIT_COLUMN_GAS_PRICE, gasPrice.longValueExact())
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

    override fun isSubmitTxBCPersisted(ctx: EContext, requestId: Long): Boolean {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            return jooq.select().from(tableEvmTxSubmit(ctx))
                .where(EVM_TX_SUBMIT_COLUMN_REQUEST_ID.eq(requestId))
                .fetchOne {
                    it.get(EVM_TX_SUBMIT_COLUMN_BC_PERSISTED)
                }
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

    override fun setPendingTransactionReceiptBlockNumber(ctx: EContext, requestId: Long, receiptHeight: BigInteger) {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            jooq.update(table(tableEvmTxPending(ctx)))
                .set(EVM_TX_PENDING_COLUMN_UPDATED, currentTimestamp())
                .set(EVM_TX_PENDING_COLUMN_BLOCK_NUMBER, receiptHeight.longValueExact())
                .where(EVM_TX_PENDING_COLUMN_REQUEST_ID.eq(requestId))
                .execute()
        }
    }

    override fun setPendingTransactionReceipt(
        ctx: EContext,
        requestId: Long,
        receiptHeight: BigInteger,
        statusOK: Boolean,
        effectiveGasPrice: BigInteger,
        gasUsed: BigInteger,
        blockHash: String
    ) {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            jooq.update(table(tableEvmTxPending(ctx)))
                .set(EVM_TX_PENDING_COLUMN_UPDATED, currentTimestamp())
                .set(EVM_TX_PENDING_COLUMN_BLOCK_NUMBER, receiptHeight.longValueExact())
                .set(EVM_TX_PENDING_COLUMN_BLOCK_HASH, blockHash)
                .set(EVM_TX_PENDING_COLUMN_EFFECTIVE_GAS_PRICE, effectiveGasPrice.longValueExact())
                .set(EVM_TX_PENDING_COLUMN_GAS_USAGE, gasUsed.longValueExact())
                .where(EVM_TX_PENDING_COLUMN_REQUEST_ID.eq(requestId))
                .execute()
        }
    }

    override fun getQueuedTransactions(ctx: EContext, networkId: Long) : List<EvmSubmitTransactionRequest> {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            return jooq.select().from(tableEvmTxSubmit(ctx))
                    .where(EVM_TX_SUBMIT_COLUMN_NETWORK_ID.eq(networkId))
                    .fetch(evmSubmitTransactionRequestRecordMapper)
        }
    }

    override fun addPendingTransaction(ctx: EContext, networkId: Long, txPending: EvmPendingDbTx) {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            jooq.insertInto(table(tableEvmTxPending(ctx)))
                .set(EVM_TX_PENDING_COLUMN_REQUEST_ID, txPending.rowId)
                .set(EVM_TX_PENDING_COLUMN_NETWORK_ID, txPending.networkId)
                .set(EVM_TX_PENDING_COLUMN_UPDATED, Timestamp(txPending.updated))
                .set(EVM_TX_PENDING_COLUMN_STATUS, PendingTxStatus.NOTHING_VERIFIED.name)
                .set(EVM_TX_PENDING_COLUMN_CONTRACT, txPending.contractAddress)
                .set(EVM_TX_PENDING_COLUMN_FUNCTION, txPending.functionName)
                .set(EVM_TX_PENDING_COLUMN_PARAMETER_TYPES, txPending.parameterTypes.joinToString(","))
                .set(EVM_TX_PENDING_COLUMN_PARAMETER_VALUES, GtvEncoder.encodeGtv(GtvFactory.gtv(txPending.parameterValues)))
                .set(EVM_TX_PENDING_COLUMN_HASH, txPending.txHash)
                .execute()
        }
    }

    override fun getPendingTransactions(ctx: EContext, networkId: Long) : Map<String, EvmPendingDbTx>{
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            return jooq.select().from(tableEvmTxPending(ctx))
                    .where(
                        EVM_TX_SUBMIT_COLUMN_NETWORK_ID.eq(networkId)
                            .and(EVM_TX_PENDING_COLUMN_STATUS.eq(PendingTxStatus.NOTHING_VERIFIED.name)
                                .or(EVM_TX_PENDING_COLUMN_STATUS.eq(PendingTxStatus.RECEIPT_FOUND.name))))
                    .fetchMap(EVM_TX_PENDING_COLUMN_HASH, evmPendingTransactionRecordMapper)
        }
    }

    override fun setPendingTransactionSuccess(ctx: EContext, requestId: Long, status: PendingTxStatus) {

        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            jooq.update(table(tableEvmTxPending(ctx)))
                .set(EVM_TX_PENDING_COLUMN_UPDATED, currentTimestamp())
                .set(EVM_TX_PENDING_COLUMN_STATUS, status.name)
                .where(EVM_TX_PENDING_COLUMN_REQUEST_ID.eq(requestId))
                .execute()
        }
    }

    override fun getVerifiedTransactions(ctx: EContext, networkId: Long, minMsSinceUpdate: Long): List<EvmPendingDbTx> {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            val requiredUpdateTime = from(Instant.now().minus(minMsSinceUpdate, ChronoUnit.MILLIS))

            return jooq.select().from(tableEvmTxPending(ctx))
                .where(
                    EVM_TX_PENDING_COLUMN_NETWORK_ID.eq(networkId)
                        .and(EVM_TX_PENDING_COLUMN_BC_PERSISTED.eq(false))
                        .and(EVM_TX_PENDING_COLUMN_STATUS.eq(PendingTxStatus.REVERTED.name).or(
                            EVM_TX_PENDING_COLUMN_STATUS.eq(PendingTxStatus.SUCCESS.name)))
                        .and(EVM_TX_PENDING_COLUMN_UPDATED.lessOrEqual(requiredUpdateTime))
                )
                .fetch(evmPendingTransactionRecordMapper)
        }
    }

    override fun setPendingTxBCPersisted(ctx: EContext, requestId: Long) {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            jooq.update(table(tableEvmTxPending(ctx)))
                .set(EVM_TX_PENDING_COLUMN_BC_PERSISTED, true)
                .where(EVM_TX_PENDING_COLUMN_REQUEST_ID.eq(requestId))
                .execute()
        }
    }

    override fun getTransactionErrors(ctx: EContext, requestId: Long): List<EvmSubmitTransactionError> {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            return jooq.select().from(tableEvmTxErrors(ctx))
                .where(EVM_TX_ERRORS_COLUMN_REQUEST_ID.eq(requestId))
                .fetch { EvmSubmitTransactionError(
                    it.get(EVM_TX_ERRORS_COLUMN_REQUEST_ID),
                    it.get(EVM_TX_ERRORS_COLUMN_TIMESTAMP),
                    it.get(EVM_TX_ERRORS_COLUMN_RPC_URL),
                    it.get(EVM_TX_ERRORS_COLUMN_MESSAGE)
                ) }
        }
    }

    override fun cleanupDb(ctx: EContext, networkId: Long, dbRetentionTime: Long) {

        val expireTime = from(Instant.now().minus(dbRetentionTime, ChronoUnit.MILLIS))

        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)
            val requestIds = mutableSetOf<Long>()

            requestIds.addAll(
                jooq.select().from(table(tableEvmTxSubmit(ctx)))
                    .where(
                        EVM_TX_SUBMIT_COLUMN_TIMESTAMP.lessOrEqual(expireTime.time)
                            .and(EVM_TX_SUBMIT_COLUMN_NETWORK_ID.eq(networkId))
                            .and(EVM_TX_SUBMIT_COLUMN_BC_PERSISTED.eq(true))
                    )
                    .fetch { it.get(EVM_TX_SUBMIT_COLUMN_REQUEST_ID) })

            requestIds.addAll(
                jooq.select().from(table(tableEvmTxPending(ctx)))
                    .where(
                        EVM_TX_PENDING_COLUMN_UPDATED.lessOrEqual(expireTime)
                            .and(EVM_TX_PENDING_COLUMN_NETWORK_ID.eq(networkId))
                            .and(EVM_TX_PENDING_COLUMN_STATUS.eq(PendingTxStatus.REVERTED.name).or(
                                EVM_TX_PENDING_COLUMN_STATUS.eq(PendingTxStatus.SUCCESS.name)))
                    )
                    .fetch { it.get(EVM_TX_SUBMIT_COLUMN_REQUEST_ID) })

            if (requestIds.isNotEmpty()) {
                jooq.delete(table(tableEvmTxSubmit(ctx)))
                    .where(EVM_TX_SUBMIT_COLUMN_REQUEST_ID.`in`(requestIds))
                    .execute()

                jooq.delete(table(tableEvmTxPending(ctx)))
                    .where(EVM_TX_SUBMIT_COLUMN_REQUEST_ID.`in`(requestIds))
                    .execute()

                jooq.delete(table(tableEvmTxErrors(ctx)))
                    .where(EVM_TX_SUBMIT_COLUMN_REQUEST_ID.`in`(requestIds))
                    .execute()
            }
        }
    }

    private fun createJooq(ctx: EContext) = using(ctx.conn, SQLDialect.POSTGRES)
}
