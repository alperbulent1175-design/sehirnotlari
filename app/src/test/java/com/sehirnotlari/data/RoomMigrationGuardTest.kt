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
        assertTrue(source.contains("version = 3, exportSchema = true"))
        assertTrue(source.contains(".addMigrations(*NoteSchemaMigrations.ALL)"))
    }

    @Test
    fun everySchemaVersionHasExportedSchemaAndExplicitMigration() {
        val migrations = sourceFile("src/main/java/com/sehirnotlari/data/NoteSchemaMigrations.kt").readText()
        assertTrue(migrations.contains("Migration(1, 2)"))
        assertTrue(migrations.contains("Migration(2, 3)"))
        assertTrue(migrations.contains("arrayOf(MIGRATION_1_2, MIGRATION_2_3)"))
        (1..3).forEach { assertTrue(sourceFile("schemas/com.sehirnotlari.data.CityNotesDatabase/$it.json").isFile) }
    }

    @Test
    fun roomLogsAndBridgeErrorsCarryNoNoteData() {
        val source = sourceFile("src/main/java/com/sehirnotlari/MainActivity.kt").readText()
        val roomLogs = source.lines().filter { it.contains("Log.") && it.contains("ROOM_LOG") }
        assertTrue(roomLogs.size >= 10)
        roomLogs.forEach { line ->
            assertFalse("throwable logged: $line", Regex(""",\s*e\)""").containsMatchIn(line))
            assertFalse("message logged: $line", line.contains("e.message"))
            listOf("json", "noteJson", "title", "content", "draft", "item").forEach { name ->
                assertFalse("$name logged: $line", Regex("""\$\{?$name\b""").containsMatchIn(line))
            }
        }
        val bridgeResults = source.lines().filter { it.contains("RoomBridgeScripts.") }
        bridgeResults.forEach { assertFalse("raw message sent: $it", it.contains("e.message")) }
    }

    @Test
    fun appLogsCarryNoKeyMaterialStackTracesOrModelText() {
        val source = sourceFile("src/main/java/com/sehirnotlari/MainActivity.kt").readText()
        assertFalse(source.contains("getStackTraceString"))
        assertFalse(source.contains("keyPrefix"))
        source.lines().filter { it.contains("Log.") }.forEach { line ->
            assertFalse("key logged: $line", line.contains("GEMINI_API_KEY"))
            assertFalse("message logged: $line", line.contains("e.message"))
            listOf("prompt", "responseText", "aiResponse", "response").forEach { name ->
                assertFalse("$name logged: $line", Regex("""\$\{?$name\b(?!\.length)""").containsMatchIn(line))
            }
        }
    }

    @Test
    fun webViewReconcilesReadOnlyAndLogsCountsOnly() {
        val html = sourceFile("src/main/assets/index.html").readText()
        assertTrue(html.contains("window.addEventListener('load',()=>requestRoomReconcile(true));"))
        assertTrue(html.contains("window.Android.reconcileRoomNotes(JSON.stringify(DB),requestId,apply!==false)"))
        val handler = html.substringAfter("window.onRoomReconcileResult=function").substringBefore("\n};")
        assertFalse(handler.contains("sv("))
        assertFalse(handler.contains("localStorage"))
        assertFalse(handler.contains("DB"))
        val logs = Regex("""console\.\w+\([^;]*""").findAll(handler).map { it.value }.toList()
        assertTrue(logs.any { it.contains("report.summary") })
        logs.forEach { assertFalse("item details logged: $it", it.contains("items") || it.contains("key")) }
    }

    @Test
    fun roomWritesUseProcessWideSequencer() {
        val source = sourceFile("src/main/java/com/sehirnotlari/MainActivity.kt").readText()
        assertTrue(source.contains("RoomNoteWriteSequencer.shared("))
        assertTrue(source.contains("RoomNoteWriteSequencer.writeScope.launch"))
        assertFalse(source.contains("roomWriteMutex"))
    }

    @Test
    fun webViewDebuggingIsLimitedToDebuggableBuilds() {
        val source = sourceFile("src/main/java/com/sehirnotlari/MainActivity.kt").readText()
        val guarded = Regex(
            """if \(applicationInfo\.flags and ApplicationInfo\.FLAG_DEBUGGABLE != 0\) \{\s*WebView\.setWebContentsDebuggingEnabled\(true\)\s*}"""
        )
        assertTrue(guarded.containsMatchIn(source))
        assertTrue(Regex("""setWebContentsDebuggingEnabled""").findAll(source).count() == 1)
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
