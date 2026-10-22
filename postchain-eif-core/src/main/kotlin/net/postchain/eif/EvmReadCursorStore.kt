package net.postchain.eif

import mu.KLogging
import net.postchain.base.data.DatabaseAccess
import net.postchain.base.withReadConnection
import net.postchain.base.withWriteConnection
import net.postchain.common.toHex
import net.postchain.common.types.WrappedByteArray
import net.postchain.common.wrap
import net.postchain.core.EContext
import net.postchain.core.Storage
import net.postchain.gtv.GtvDecoder
import net.postchain.gtv.GtvEncoder
import net.postchain.gtv.GtvFactory.gtv
import org.jooq.Field
import org.jooq.SQLDialect
import org.jooq.impl.DSL.field
import org.jooq.impl.DSL.table
import org.jooq.impl.DSL.using
import org.jooq.impl.SQLDataType
import java.math.BigInteger

/**
 * Node-local record of how far the EVM event reader of one network has progressed.
 *
 * @param height every EVM block at or below this height has been read with the contracts in [contracts] in the filter
 * and either contained no events for them or is included in a durably committed block on this node
 * @param contracts the contracts that were in the reader's filter when [height] was recorded, with the skip-to-height
 * each one had at the time
 */
data class EvmReadCursor(val height: BigInteger, val contracts: Map<WrappedByteArray, Long>) {
    override fun toString(): String =
            "EvmReadCursor(height=$height, contracts=${contracts.map { (address, skipToHeight) -> "0x${address.data.toHex()}:$skipToHeight" }})"
}

/**
 * Persists the [EvmReadCursor] of each EVM network so that a restarted reader can resume where it left off instead of
 * re-reading from the oldest committed event. This is node-local state and never part of consensus.
 */
interface EvmReadCursorStore {
    fun load(networkId: Long): EvmReadCursor?
    fun save(networkId: Long, cursor: EvmReadCursor)
}

class InMemoryEvmReadCursorStore : EvmReadCursorStore {
    private val cursors = mutableMapOf<Long, EvmReadCursor>()

    @Synchronized
    override fun load(networkId: Long): EvmReadCursor? = cursors[networkId]

    @Synchronized
    override fun save(networkId: Long, cursor: EvmReadCursor) {
        cursors[networkId] = cursor
    }
}

/**
 * Stores the read cursor in a table in the blockchain's database schema. The table is written outside block
 * transactions and is dropped together with the other chain tables when the blockchain is removed from the node.
 */
class DatabaseEvmReadCursorStore(private val storage: Storage, private val chainId: Long) : EvmReadCursorStore {

    companion object : KLogging() {
        // This name should not clash with Rell
        const val TABLE_NAME: String = "$PREFIX.evm_read_cursor"

        val COLUMN_NETWORK_ID: Field<Long> = field("network_id", SQLDataType.BIGINT.nullable(false))
        val COLUMN_HEIGHT: Field<Long> = field("height", SQLDataType.BIGINT.nullable(false))
        val COLUMN_CONTRACTS: Field<ByteArray> = field("contracts", SQLDataType.BLOB.nullable(false))

        fun tableName(ctx: EContext): String = DatabaseAccess.of(ctx).tableName(ctx, TABLE_NAME)

        fun encodeContracts(contracts: Map<WrappedByteArray, Long>): ByteArray =
                GtvEncoder.encodeGtv(
                        gtv(contracts.entries
                                .sortedBy { it.key.data.toHex() }
                                .map { (address, skipToHeight) -> gtv(gtv(address.data), gtv(skipToHeight)) })
                )

        fun decodeContracts(bytes: ByteArray): Map<WrappedByteArray, Long> =
                GtvDecoder.decodeGtv(bytes).asArray().associate { it[0].asByteArray().wrap() to it[1].asInteger() }
    }

    fun initialize() {
        withWriteConnection(storage, chainId) { ctx ->
            using(ctx.conn, SQLDialect.POSTGRES)
                    .createTableIfNotExists(table(tableName(ctx)))
                    .column(COLUMN_NETWORK_ID)
                    .column(COLUMN_HEIGHT)
                    .column(COLUMN_CONTRACTS)
                    .primaryKey(COLUMN_NETWORK_ID)
                    .execute()
            true
        }
    }

    override fun load(networkId: Long): EvmReadCursor? = withReadConnection(storage, chainId) { ctx ->
        val record = using(ctx.conn, SQLDialect.POSTGRES)
                .select(COLUMN_HEIGHT, COLUMN_CONTRACTS)
                .from(table(tableName(ctx)))
                .where(COLUMN_NETWORK_ID.eq(networkId))
                .fetchOne() ?: return@withReadConnection null

        val cursor = try {
            EvmReadCursor(BigInteger.valueOf(record.get(COLUMN_HEIGHT)), decodeContracts(record.get(COLUMN_CONTRACTS)))
        } catch (e: Exception) {
            logger.warn(e) { "Ignoring unreadable EVM read cursor for network $networkId: $e" }
            return@withReadConnection null
        }
        if (cursor.height.signum() < 0 || cursor.contracts.isEmpty()) {
            logger.warn { "Ignoring invalid EVM read cursor for network $networkId: $cursor" }
            return@withReadConnection null
        }
        cursor
    }

    override fun save(networkId: Long, cursor: EvmReadCursor) {
        withWriteConnection(storage, chainId) { ctx ->
            using(ctx.conn, SQLDialect.POSTGRES)
                    .insertInto(table(tableName(ctx)))
                    .set(COLUMN_NETWORK_ID, networkId)
                    .set(COLUMN_HEIGHT, cursor.height.longValueExact())
                    .set(COLUMN_CONTRACTS, encodeContracts(cursor.contracts))
                    .onConflict(COLUMN_NETWORK_ID)
                    .doUpdate()
                    .set(COLUMN_HEIGHT, cursor.height.longValueExact())
                    .set(COLUMN_CONTRACTS, encodeContracts(cursor.contracts))
                    .execute()
            true
        }
    }
}
