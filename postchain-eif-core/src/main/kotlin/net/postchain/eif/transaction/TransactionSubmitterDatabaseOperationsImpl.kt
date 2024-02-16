package net.postchain.eif.transaction

import net.postchain.base.data.DatabaseAccess
import net.postchain.core.EContext
import net.postchain.gtv.Gtv
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
    SUCCESS, FAILURE
}

class TransactionSubmitterDatabaseOperationsImpl : TransactionSubmitterDatabaseOperations {

    companion object {
        const val PREFIX: String = "sys.x.evm_tx" // This name should not clash with Rell

        val COLUMN_CONTRACT: Field<String> = field("contract", PostgresDataType.TEXT.nullable(false))
        val COLUMN_FUNCTION: Field<String> = field("function", PostgresDataType.TEXT.nullable(false))
        val COLUMN_PARAMETER_TYPES: Field<String> = field("parameter_types", PostgresDataType.TEXT.nullable(false))
        val COLUMN_PARAMETER_VALUES: Field<String> = field("parameter_values", PostgresDataType.TEXT.nullable(false))
        val COLUMN_GAS_PRICE: Field<Long> = field("gas_price", PostgresDataType.BIGINT.nullable(false))
        val COLUMN_GAS_LIMIT: Field<Long> = field("gas_limit", PostgresDataType.BIGINT.nullable(false))
        val COLUMN_TX_HASH: Field<String> = field("hash", PostgresDataType.TEXT)
        val COLUMN_ERROR_MESSAGE: Field<String> = field("error_message", PostgresDataType.TEXT)
        val COLUMN_STATUS: Field<String> = field("status", PostgresDataType.TEXT.nullable(false))
        val COLUMN_TIMESTAMP: Field<Timestamp> = field("timestamp", PostgresDataType.TIMESTAMP.nullable(false))
        val COLUMN_NETWORK_ID: Field<Long> = field("network_id", PostgresDataType.BIGINT.nullable(false))
    }

    private fun DatabaseAccess.tableEvmTransaction(ctx: EContext) = tableName(ctx, "${PREFIX}.transactions")

    override fun initialize(ctx: EContext) {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            val transactionTable = table(tableEvmTransaction(ctx))
            jooq.createTableIfNotExists(transactionTable)
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
                    .execute()
        }
    }

    override fun recordTransaction(ctx: EContext, transactionRequest: EvmSubmitTransactionRequest, gasPrice: BigInteger, gasLimit: BigInteger, txHash: String, networkId: Long) {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)
            
            jooq.insertInto(table(tableEvmTransaction(ctx)))
                    .set(COLUMN_CONTRACT, transactionRequest.contractAddress)
                    .set(COLUMN_FUNCTION, transactionRequest.functionName)
                    .set(COLUMN_PARAMETER_TYPES, transactionRequest.parameterTypes.joinToString(","))
                    .set(COLUMN_PARAMETER_VALUES, transactionRequest.parameterValues.joinToString(","))
                    .set(COLUMN_GAS_PRICE, gasPrice.longValueExact())
                    .set(COLUMN_GAS_LIMIT, gasLimit.longValueExact())
                    .set(COLUMN_TX_HASH, txHash)
                    .set(COLUMN_STATUS, TransactionStatus.SUCCESS.name)
                    .set(COLUMN_TIMESTAMP, currentTimestamp())
                    .set(COLUMN_NETWORK_ID, networkId)
                    .execute()
        }
    }

    override fun recordFailedTransaction(ctx: EContext, transactionRequest: EvmSubmitTransactionRequest, gasPrice: BigInteger, gasLimit: BigInteger, errorMessage: String, networkId: Long) {
        DatabaseAccess.of(ctx).apply {
            val jooq = createJooq(ctx)

            jooq.insertInto(table(tableEvmTransaction(ctx)))
                    .set(COLUMN_CONTRACT, transactionRequest.contractAddress)
                    .set(COLUMN_FUNCTION, transactionRequest.functionName)
                    .set(COLUMN_PARAMETER_TYPES, transactionRequest.parameterTypes.joinToString(","))
                    .set(COLUMN_PARAMETER_VALUES, transactionRequest.parameterValues.joinToString(","))
                    .set(COLUMN_GAS_PRICE, gasPrice.longValueExact())
                    .set(COLUMN_GAS_LIMIT, gasLimit.longValueExact())
                    .set(COLUMN_ERROR_MESSAGE, errorMessage)
                    .set(COLUMN_STATUS, TransactionStatus.FAILURE.name)
                    .set(COLUMN_TIMESTAMP, currentTimestamp())
                    .set(COLUMN_NETWORK_ID, networkId)
                    .execute()
        }
    }

    private fun createJooq(ctx: EContext) = using(ctx.conn, SQLDialect.POSTGRES)
}
