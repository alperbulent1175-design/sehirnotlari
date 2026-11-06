package com.sehirnotlari.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSchemaContractTest {
    @Test
    fun v1SchemaMatchesShippedDatabaseIdentity() {
        assertEquals(1, RoomSchemaFiles.database(1).getInt("version"))
        assertEquals("d453335eff3e94eadbf36e25e651ec4b", RoomSchemaFiles.database(1).getString("identityHash"))
        assertTrue("updatedAt" in RoomSchemaFiles.columns(1))
        assertFalse("isAI" in RoomSchemaFiles.columns(1))
    }

    @Test
    fun migrationCreatesExactlyTheV2TableRoomExpects() {
        val expected = RoomSchemaFiles.notesEntity(2).getString("createSql").replace("\${TABLE_NAME}", "notes_v2")
        assertEquals(expected, NoteSchemaMigrations.NOTES_V2_CREATE_SQL)
        val indices = RoomSchemaFiles.notesEntity(2).getJSONArray("indices")
        val expectedIndices = (0 until indices.length())
            .map { indices.getJSONObject(it).getString("createSql").replace("\${TABLE_NAME}", "notes") }
            .toSet()
        assertEquals(expectedIndices, NoteSchemaMigrations.MIGRATION_1_2_SQL.filter { it.startsWith("CREATE INDEX") }.toSet())
    }

    @Test
    fun v2SeparatesWriteOrderFromChangeTimeAndAddsIsAI() {
        val v2 = RoomSchemaFiles.columns(2)
        assertEquals(2, RoomSchemaFiles.database(2).getInt("version"))
        assertFalse("updatedAt" in v2)
        assertTrue(listOf("writeTicket", "modifiedAt", "isAI").all { it in v2 })
        val fields = RoomSchemaFiles.notesEntity(2).getJSONArray("fields")
        val nullable = (0 until fields.length()).map { fields.getJSONObject(it) }
            .filter { !it.optBoolean("notNull") }
            .map { it.getString("columnName") }
            .toSet()
        assertEquals(setOf("isAI", "modifiedAt", "deletedAt"), nullable)
    }

    @Test
    fun v3AddsOnlyTheJournalTableAndLeavesNotesUnchanged() {
        assertEquals(3, RoomSchemaFiles.database(3).getInt("version"))
        assertEquals(listOf("notes", "note_write_journal"), RoomSchemaFiles.tables(3))
        assertEquals(RoomSchemaFiles.notesEntity(2).toString(), RoomSchemaFiles.notesEntity(3).toString())
        val journal = RoomSchemaFiles.entity(3, "note_write_journal")
        val indices = journal.getJSONArray("indices")
        val expected = listOf(journal.getString("createSql")) +
            (0 until indices.length()).map { indices.getJSONObject(it).getString("createSql") }
        assertEquals(expected.map { it.replace("\${TABLE_NAME}", "note_write_journal") }, NoteSchemaMigrations.MIGRATION_2_3_SQL)
        NoteSchemaMigrations.MIGRATION_2_3_SQL.forEach {
            assertTrue(it.startsWith("CREATE TABLE IF NOT EXISTS") || it.startsWith("CREATE INDEX IF NOT EXISTS"))
        }
    }

    @Test
    fun journalStoresFingerprintsNotNoteText() {
        val columns = RoomSchemaFiles.entity(3, "note_write_journal").getJSONArray("fields")
        val names = (0 until columns.length()).map { columns.getJSONObject(it).getString("columnName") }
        assertEquals(
            listOf("ticket", "noteKey", "city", "op", "fingerprint", "state", "createdAt", "resolvedAt", "previousJson"),
            names
        )
    }

    @Test
    fun migrationCopiesEveryV1ColumnAndLeavesUnknownsNull() {
        val copy = NoteSchemaMigrations.MIGRATION_1_2_SQL.single { it.startsWith("INSERT INTO") }
        val select = copy.substringAfter("SELECT ")
        RoomSchemaFiles.columns(1).forEach { assertTrue("v1 column $it not copied", select.contains("`$it`")) }
        val target = copy.substringAfter("(").substringBefore(")").split(",").map { it.trim().trim('`') }
        val source = select.substringBefore(" FROM").split(",").map { it.trim().trim('`') }
        val mapping = target.zip(source).toMap()
        assertEquals(RoomSchemaFiles.columns(2).toSet(), target.toSet())
        assertEquals("updatedAt", mapping["writeTicket"])
        assertEquals("NULL", mapping["modifiedAt"])
        assertEquals("NULL", mapping["isAI"])
        assertEquals("deletedAt", mapping["deletedAt"])
        assertEquals("createdAt", mapping["createdAt"])
        assertEquals("id", mapping["id"])
    }
}
