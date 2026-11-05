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
        assertTrue(source.contains("city_notes.db"))
    }

    private fun sourceFile(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.first { it.isFile }
    }
}
