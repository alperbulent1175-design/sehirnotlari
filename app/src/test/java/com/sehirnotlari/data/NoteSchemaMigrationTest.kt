package com.sehirnotlari.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Opens a real v1 SQLite file with the production Room builder and its explicit migration. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoteSchemaMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"
    private var room: CityNotesDatabase? = null

    @Before
    fun clean() {
        context.deleteDatabase(name)
    }

    @After
    fun close() {
        room?.close()
        context.deleteDatabase(name)
    }

    private data class V1Row(
        val id: String,
        val city: String,
        val title: String,
        val content: String = "",
        val category: String = "Genel",
        val star: Int = 0,
        val color: String = "",
        val favorite: Boolean = false,
        val dateLabel: String = "",
        val createdAt: Long,
        val updatedAt: Long,
        val deletedAt: Long? = null,
        val syncState: String = "LOCAL",
    )

    private val v1Rows = listOf(
        V1Row("Bursa::1700000000000", "Bursa", "Ulu Cami", "İçerik \"tırnak\" ve 'kesme'", "Tarih", 4, "#e74c3c", true,
            "6 Kas 2026", createdAt = 1_700_000_000_400L, updatedAt = 1_700_000_000_500L),
        V1Row("Ankara::1700000000000", "Ankara", "Aynı sayı, başka şehir", createdAt = 1_700_000_000_600L,
            updatedAt = 1_700_000_000_700L),
        V1Row("İzmir::import-a", "İzmir", "Metin kimlik", createdAt = 1_700_000_001_000L, updatedAt = 1_700_000_001_100L),
        V1Row("Bursa::1762380000000123", "Bursa", "P3 kimliği", createdAt = 1_762_380_000_000L,
            updatedAt = 1_762_380_000_001L, syncState = "PENDING"),
        V1Row("Ankara::1700000000001", "Ankara", "", createdAt = 1_700_000_000_800L, updatedAt = 1_700_000_000_900L,
            deletedAt = 1_700_000_000_900L, syncState = "PENDING"),
    )

    private fun createV1Database(rows: List<V1Row>) {
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            RoomSchemaFiles.createStatements(1).forEach(db::execSQL)
            rows.forEach { row ->
                db.insertOrThrow("notes", null, ContentValues().apply {
                    put("id", row.id)
                    put("city", row.city)
                    put("title", row.title)
                    put("content", row.content)
                    put("category", row.category)
                    put("star", row.star)
                    put("color", row.color)
                    put("favorite", if (row.favorite) 1 else 0)
                    put("dateLabel", row.dateLabel)
                    put("createdAt", row.createdAt)
                    put("updatedAt", row.updatedAt)
                    if (row.deletedAt == null) putNull("deletedAt") else put("deletedAt", row.deletedAt)
                    put("syncState", row.syncState)
                })
            }
            db.version = 1
        }
    }

    private fun openMigrated(): CityNotesDatabase =
        CityNotesDatabase.builder(context, name).allowMainThreadQueries().build().also { room = it }

    private fun expected(row: V1Row) = NoteEntity(
        id = row.id,
        city = row.city,
        title = row.title,
        content = row.content,
        category = row.category,
        star = row.star,
        color = row.color,
        favorite = row.favorite,
        isAI = null,
        dateLabel = row.dateLabel,
        createdAt = row.createdAt,
        writeTicket = row.updatedAt,
        modifiedAt = null,
        deletedAt = row.deletedAt,
        syncState = row.syncState,
    )

    @Test
    fun v1DatabaseMigratesWithoutLosingRowsIdsOrTombstones() = runBlocking {
        createV1Database(v1Rows)
        val db = openMigrated()
        val dao = db.notes()
        v1Rows.forEach { assertEquals(expected(it), dao.findAny(it.id)) }
        val active = dao.allActive()
        assertEquals(4, active.size)
        assertEquals(
            listOf("Bursa::1762380000000123", "İzmir::import-a", "Ankara::1700000000000", "Bursa::1700000000000"),
            active.map { it.id }
        )
        assertEquals(1_700_000_000_900L, dao.findAny("Ankara::1700000000001")?.deletedAt)
        assertEquals(1_762_380_000_001L, dao.maxWriteTicket())
        Unit
    }

    @Test
    fun migratedFileHasV2LayoutAndIdentity() {
        createV1Database(v1Rows)
        val sqlite = openMigrated().openHelper.writableDatabase
        sqlite.query("PRAGMA user_version").use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }
        sqlite.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use {
            it.moveToFirst()
            assertEquals(RoomSchemaFiles.database(2).getString("identityHash"), it.getString(0))
        }
        val columns = mutableListOf<String>()
        sqlite.query("PRAGMA table_info(`notes`)").use { c ->
            while (c.moveToNext()) columns += c.getString(c.getColumnIndexOrThrow("name"))
        }
        assertEquals(RoomSchemaFiles.columns(2), columns)
        val indices = mutableSetOf<String>()
        sqlite.query("PRAGMA index_list(`notes`)").use { c ->
            while (c.moveToNext()) indices += c.getString(c.getColumnIndexOrThrow("name"))
        }
        assertTrue("index_notes_city" in indices)
        assertTrue("index_notes_writeTicket" in indices)
        assertFalse("index_notes_updatedAt" in indices)
        sqlite.query("SELECT COUNT(*) FROM sqlite_master WHERE name = 'notes_v2'").use {
            it.moveToFirst(); assertEquals(0, it.getInt(0))
        }
        sqlite.query("SELECT COUNT(*) FROM notes").use { it.moveToFirst(); assertEquals(v1Rows.size, it.getInt(0)) }
    }

    @Test
    fun failedMigrationRollsBackAndLeavesV1FileIntact() {
        createV1Database(v1Rows)
        val broken = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                NoteSchemaMigrations.MIGRATION_1_2_SQL.dropLast(1).forEach(db::execSQL)
            }
        }
        val db = Room.databaseBuilder(context, CityNotesDatabase::class.java, name)
            .addMigrations(broken)
            .allowMainThreadQueries()
            .build()
        try {
            db.openHelper.writableDatabase
            fail("schema validation should reject the incomplete migration")
        } catch (_: IllegalStateException) {
        } finally {
            db.close()
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertEquals(1, raw.version)
            raw.rawQuery("SELECT COUNT(*) FROM notes", null).use { it.moveToFirst(); assertEquals(v1Rows.size, it.getInt(0)) }
            raw.rawQuery("SELECT updatedAt, deletedAt FROM notes WHERE id = 'Ankara::1700000000001'", null).use {
                it.moveToFirst()
                assertEquals(1_700_000_000_900L, it.getLong(0))
                assertEquals(1_700_000_000_900L, it.getLong(1))
            }
        }
        val dao = openMigrated().notes()
        runBlocking { v1Rows.forEach { assertEquals(expected(it), dao.findAny(it.id)) } }
    }

    @Test
    fun emptyV1DatabaseMigrates() = runBlocking {
        createV1Database(emptyList())
        val dao = openMigrated().notes()
        assertTrue(dao.allActive().isEmpty())
        assertNull(dao.maxWriteTicket())
    }

    @Test
    fun freshInstallCreatesV2Directly() = runBlocking {
        val dao = openMigrated().notes()
        val sequencer = RoomNoteWriteSequencer.forDao(dao)
        sequencer.upsert("Bursa", "1762380000000123", RoomNoteDraft(title = "Yeni", isAI = true), sequencer.nextTicket())
        val saved = dao.findAny("Bursa::1762380000000123")
        assertEquals(true, saved?.isAI)
        assertTrue(saved?.modifiedAt != null)
    }

    @Test
    fun writesAfterMigrationKeepOrderingTombstonesAndRecordRealChangeTime() = runBlocking {
        val futureTicket = 9_000_000_000_000L
        createV1Database(v1Rows + V1Row("Bursa::42", "Bursa", "Gelecek bilet", createdAt = 1L, updatedAt = futureTicket))
        val dao = openMigrated().notes()
        val wallClock = 1_800_000_000_000L
        val sequencer = RoomNoteWriteSequencer(RoomNoteWriteStore(dao), clock = { wallClock }, seed = { dao.maxWriteTicket() ?: 0L })

        val ticket = sequencer.nextTicket()
        assertTrue(ticket > futureTicket)

        assertEquals(RoomWriteAck.IgnoredStale,
            sequencer.upsert("Bursa", "1700000000000", RoomNoteDraft(title = "Gecikmiş"), 1_700_000_000_499L))
        assertEquals("Ulu Cami", dao.findAny("Bursa::1700000000000")?.title)

        assertEquals(RoomWriteAck.Applied,
            sequencer.upsert("Bursa", "1700000000000", RoomNoteDraft(title = "Düzenlendi", isAI = true), ticket))
        val edited = dao.findAny("Bursa::1700000000000")!!
        assertEquals("Düzenlendi", edited.title)
        assertEquals(true, edited.isAI)
        assertEquals(ticket, edited.writeTicket)
        assertEquals(wallClock, edited.modifiedAt)
        assertEquals(1_700_000_000_400L, edited.createdAt)

        val revive = sequencer.upsert("Ankara", "1700000000001", RoomNoteDraft(title = "Diriltme"), sequencer.nextTicket())
        assertTrue(revive is RoomWriteAck.Rejected)
        val tombstone = dao.findAny("Ankara::1700000000001")!!
        assertEquals(1_700_000_000_900L, tombstone.deletedAt)
        assertNull(tombstone.modifiedAt)

        val deleteTicket = sequencer.nextTicket()
        assertEquals(RoomWriteAck.Applied, sequencer.delete("İzmir", "import-a", deleteTicket))
        val deleted = dao.findAny("İzmir::import-a")!!
        assertEquals(wallClock, deleted.deletedAt)
        assertEquals(wallClock, deleted.modifiedAt)
        assertEquals(deleteTicket, deleted.writeTicket)
        assertEquals("Metin kimlik", deleted.title)
        assertNull(deleted.isAI)

        val untouched = dao.findAny("Bursa::1762380000000123")!!
        assertNull(untouched.modifiedAt)
        assertNull(untouched.isAI)
    }

    @Test
    fun legacyImportAfterMigrationSkipsExistingRowsAndKeepsChangeTimeUnknown() = runBlocking {
        createV1Database(v1Rows)
        val db = openMigrated()
        val dao = db.notes()
        val sequencer = RoomNoteWriteSequencer.forDao(dao)
        val outcome = sequencer.mutex.withLock {
            db.withTransaction {
                LegacyRoomImporter.importOrThrow(
                    """{"Bursa":[{"id":1700000000000,"title":"localStorage kopyası"},{"id":1762380000000999,"title":"Yapay zekâ","isAI":true}],
                       "Ankara":[{"id":1700000000001,"title":"Silinmişi geri getirme"}],
                       "İzmir":[{"id":"import-b","title":"Elle"}]}""",
                    RoomNoteImportStore(dao),
                    stamp = sequencer::nextTicket,
                    now = { 1_800_000_000_000L }
                )
            }
        }
        assertEquals(2, outcome.inserted)
        assertEquals(2, outcome.skipped)
        assertEquals("Ulu Cami", dao.findAny("Bursa::1700000000000")?.title)
        assertEquals(1_700_000_000_900L, dao.findAny("Ankara::1700000000001")?.deletedAt)
        val ai = dao.findAny("Bursa::1762380000000999")!!
        assertEquals(true, ai.isAI)
        assertNull(ai.modifiedAt)
        assertEquals(1_800_000_000_000L, ai.createdAt)
        assertTrue(ai.writeTicket > 1_762_380_000_001L)
        assertEquals(false, dao.findAny("İzmir::import-b")?.isAI)
    }
}
