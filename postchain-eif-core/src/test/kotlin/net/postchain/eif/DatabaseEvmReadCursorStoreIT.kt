package net.postchain.eif

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import net.postchain.StorageBuilder
import net.postchain.base.withReadWriteConnection
import net.postchain.base.withWriteConnection
import net.postchain.common.hexStringToByteArray
import net.postchain.common.wrap
import net.postchain.config.app.AppConfig
import net.postchain.core.Storage
import org.apache.commons.configuration2.BaseConfiguration
import org.jooq.SQLDialect
import org.jooq.impl.DSL.table
import org.jooq.impl.DSL.using
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigInteger

class DatabaseEvmReadCursorStoreIT {

    private val chainId = 1L
    private val address1 = "1234000000000000000000000000000000000000".hexStringToByteArray().wrap()
    private val address2 = "5678000000000000000000000000000000000000".hexStringToByteArray().wrap()
    private val cursor1 = EvmReadCursor(BigInteger.valueOf(123_456_789L), mapOf(address2 to 200L, address1 to 100L))
    private val cursor2 = EvmReadCursor(BigInteger.valueOf(987_654_321L), mapOf(address1 to 100L))

    private lateinit var storage: Storage
    private lateinit var store: DatabaseEvmReadCursorStore

    @BeforeEach
    fun setup() {
        val config = BaseConfiguration().apply {
            setProperty("database.driverclass", "org.postgresql.Driver")
            setProperty("database.url", "jdbc:postgresql://${System.getenv("POSTCHAIN_TEST_DB_HOST") ?: "localhost"}:5432/postchain")
            setProperty("database.username", "postchain")
            setProperty("database.password", "postchain")
            setProperty("database.schema", "eif_evm_read_cursor_it")
        }
        storage = StorageBuilder.buildStorage(AppConfig(config), wipeDatabase = true)
        store = DatabaseEvmReadCursorStore(storage, chainId).apply { initialize() }
    }

    @AfterEach
    fun tearDown() {
        storage.close()
    }

    @Test
    fun `load returns null when nothing has been saved`() {
        assertThat(store.load(1L)).isNull()
    }

    @Test
    fun `saved cursor is loaded back`() {
        store.save(1L, cursor1)

        assertThat(store.load(1L)).isEqualTo(cursor1)
    }

    @Test
    fun `saving again overwrites the cursor`() {
        store.save(1L, cursor1)
        store.save(1L, cursor2)

        assertThat(store.load(1L)).isEqualTo(cursor2)
    }

    @Test
    fun `cursors of different networks are independent`() {
        store.save(1L, cursor1)
        store.save(56L, cursor2)

        assertThat(store.load(1L)).isEqualTo(cursor1)
        assertThat(store.load(56L)).isEqualTo(cursor2)
        assertThat(store.load(137L)).isNull()
    }

    @Test
    fun `cursors of different chains are independent`() {
        store.save(1L, cursor1)

        val otherChainStore = DatabaseEvmReadCursorStore(storage, 2L).apply { initialize() }
        assertThat(otherChainStore.load(1L)).isNull()
    }

    @Test
    fun `initialize can be repeated without losing data`() {
        store.save(1L, cursor1)

        store.initialize()
        val reopenedStore = DatabaseEvmReadCursorStore(storage, chainId).apply { initialize() }

        assertThat(reopenedStore.load(1L)).isEqualTo(cursor1)
    }

    @Test
    fun `save is skipped while the calling thread has an open write context for the chain`() {
        store.save(1L, cursor1)

        withReadWriteConnection(storage, chainId) {
            store.save(1L, cursor2)
        }

        assertThat(store.load(1L)).isEqualTo(cursor1)
    }

    @Test
    fun `cursor without contracts is ignored`() {
        store.save(3L, EvmReadCursor(BigInteger.TEN, mapOf()))

        assertThat(store.load(3L)).isNull()
    }

    @Test
    fun `cursor with negative height is ignored`() {
        store.save(5L, cursor1)
        withWriteConnection(storage, chainId) { ctx ->
            using(ctx.conn, SQLDialect.POSTGRES)
                    .update(table(DatabaseEvmReadCursorStore.tableName(ctx)))
                    .set(DatabaseEvmReadCursorStore.COLUMN_HEIGHT, -1L)
                    .where(DatabaseEvmReadCursorStore.COLUMN_NETWORK_ID.eq(5L))
                    .execute()
            true
        }

        assertThat(store.load(5L)).isNull()
    }

    @Test
    fun `unreadable cursor is ignored`() {
        store.save(7L, cursor1)
        withWriteConnection(storage, chainId) { ctx ->
            using(ctx.conn, SQLDialect.POSTGRES)
                    .update(table(DatabaseEvmReadCursorStore.tableName(ctx)))
                    .set(DatabaseEvmReadCursorStore.COLUMN_CONTRACTS, byteArrayOf(1, 2, 3))
                    .where(DatabaseEvmReadCursorStore.COLUMN_NETWORK_ID.eq(7L))
                    .execute()
            true
        }

        assertThat(store.load(7L)).isNull()
    }
}
