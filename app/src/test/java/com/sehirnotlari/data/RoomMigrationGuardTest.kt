package com.sehirnotlari.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RoomMigrationGuardTest {
    @Test
    fun webViewDoesNotEnableAutomaticRoomImport() {
        val html = sourceFile("src/main/assets/index.html").readText()
        assertTrue(html.contains("The legacy import is intentionally not triggered automatically."))
        assertFalse(Regex("""Android\.saveNotesToRoom\s*\(""").containsMatchIn(html))
        assertTrue(html.contains("window.onRoomImportResult"))
    }

    @Test
    fun databaseDoesNotUseDestructiveMigration() {
        val source = sourceFile("src/main/java/com/sehirnotlari/data/CityNotesDatabase.kt").readText()
        assertFalse(source.contains("fallbackToDestructiveMigration"))
        assertFalse(source.contains("fallbackToDestructiveMigrationOnDowngrade"))
        assertTrue(source.contains("city_notes.db"))
        assertTrue(source.contains("version = 2, exportSchema = true"))
        assertTrue(source.contains(".addMigrations(*NoteSchemaMigrations.ALL)"))
    }

    @Test
    fun everySchemaVersionHasExportedSchemaAndExplicitMigration() {
        val migrations = sourceFile("src/main/java/com/sehirnotlari/data/NoteSchemaMigrations.kt").readText()
        assertTrue(migrations.contains("Migration(1, 2)"))
        assertTrue(migrations.contains("arrayOf(MIGRATION_1_2)"))
        assertTrue(sourceFile("schemas/com.sehirnotlari.data.CityNotesDatabase/1.json").isFile)
        assertTrue(sourceFile("schemas/com.sehirnotlari.data.CityNotesDatabase/2.json").isFile)
    }

    @Test
    fun roomWritesUseProcessWideSequencer() {
        val source = sourceFile("src/main/java/com/sehirnotlari/MainActivity.kt").readText()
        assertTrue(source.contains("RoomNoteWriteSequencer.shared("))
        assertTrue(source.contains("RoomNoteWriteSequencer.writeScope.launch"))
        assertFalse(source.contains("roomWriteMutex"))
    }

    @Test
    fun webViewCreatesNotesThroughCollisionSafeIds() {
        val html = sourceFile("src/main/assets/index.html").readText()
        assertTrue(html.contains("<script src=\"note-ids.js\"></script>"))
        assertFalse(Regex("""\bid\s*:\s*Date\.now\(\)""").containsMatchIn(html))
        assertFalse(html.contains("\${n.id})"))
        assertFalse(Regex("""\.id\s*[!=]==""").containsMatchIn(html))
        assertTrue(Regex("""NoteIds\.mergeNotes\(""").findAll(html).count() >= 2)
    }

    private fun sourceFile(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.first { it.isFile }
    }
}
