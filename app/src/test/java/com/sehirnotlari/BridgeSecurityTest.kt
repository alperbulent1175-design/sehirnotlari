package com.sehirnotlari

import android.webkit.JavascriptInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** What page JavaScript can reach through `window.Android`, and the limits of the Firebase cloud backup. */
class BridgeSecurityTest {
    private val bridge = Class.forName("com.sehirnotlari.MainActivity\$WebAppInterface", false, javaClass.classLoader)

    /** WebView exposes exactly the @JavascriptInterface methods; a new one must be added here on purpose. */
    @Test
    fun bridgeExposesOnlyTheReviewedMethods() {
        val exposed = bridge.declaredMethods.filter { it.isAnnotationPresent(JavascriptInterface::class.java) }.map { it.name }.sorted()
        assertEquals(
            listOf(
                "aiAvailable", "applyRoomResolution", "beginRoomRestore", "cancelRoomRestore", "checkForUpdate",
                "cloudBackup", "cloudFetchBackup", "cloudListBackups", "cloudSetAuto", "cloudSignIn", "cloudSignOut", "cloudStatus",
                "deleteRoomNote", "dismissUpdate", "downloadUpdate",
                "exportNotesFile", "getCityGuide", "getGMTPlus3Time", "inspectRoomNote", "installUpdate", "pickNotesFile", "pickPDF", "readRoomFirst",
                "reconcileRoomNotes", "requestDataSafetyStatus", "requestRoomNotes", "restoreRoomNotes", "resumeRoomImports", "retryUpdate", "roomFirstMode",
                "roomFirstSwitch", "saveNotesToRoom", "scheduleReminder", "setAutoDownloadUpdates", "setPrereleaseUpdates",
                "shareNote", "startMultiModalRecording",
                "startRecording", "stopMultiModalRecording", "stopRecording", "undoRoomResolution", "updaterSettings", "upsertRoomNote",
                "writeRoomFirst",
            ),
            exposed
        )
    }

    /**
     * The former backup (`backupToCloud` / `restoreFromCloud`) had no sign-in and keyed data by device model.
     * The cloud methods now take no user id, path or device data: the user comes only from Firebase Auth.
     */
    @Test
    fun cloudBridgeTakesNoUserIdAndTheOldUnauthenticatedMethodsAreGone() {
        val names = bridge.declaredMethods.map { it.name }
        listOf("backupToCloud", "restoreFromCloud", "importFromCloud").forEach { assertFalse(it, it in names) }
        val params = bridge.declaredMethods.associate { it.name to it.parameterTypes.map { p -> p.simpleName } }
        assertEquals(emptyList<String>(), params["cloudStatus"])
        assertEquals(listOf("boolean"), params["cloudSetAuto"])
        assertEquals(listOf("String"), params["cloudSignIn"])
        assertEquals(listOf("String"), params["cloudSignOut"])
        assertEquals(listOf("String", "boolean", "String"), params["cloudBackup"])
        assertEquals(listOf("String"), params["cloudListBackups"])
        assertEquals(listOf("String", "String"), params["cloudFetchBackup"])
        val controller = source("src/main/java/com/sehirnotlari/cloud/CloudController.kt")
        assertTrue(controller.contains("val uid = s.auth.currentUser?.uid ?: throw CloudBackupException(CloudErrors.SIGNED_OUT)"))
        val store = source("src/main/java/com/sehirnotlari/cloud/FirestoreBackupStore.kt")
        assertTrue(store.contains("db.collection(\"users\").document(uid).collection(\"backups\")"))
        // Restoring hands the verified notes to the page's confirmed import; native stores nothing.
        val main = source("src/main/java/com/sehirnotlari/MainActivity.kt")
        val fetch = main.substringAfter("fun cloudFetchBackup(backupId: String, requestId: String) {").substringBefore("@JavascriptInterface")
        listOf("restoreFromLocal", "recordRestore", "upsert(", "applyRestore", "softDelete").forEach { assertFalse(it, fetch.contains(it)) }
        assertTrue(fetch.indexOf("refusedInRoomFirst(\"cloud restore\"") in 0 until fetch.indexOf("cloud.fetch(backupId)"))
    }

    @Test
    fun firebaseIsOptionalUserScopedAndTheEmulatorIsDebugOnly() {
        val gradle = source("build.gradle.kts")
        // Without the local project file the app still builds, with cloud backup reported as not configured.
        assertTrue(gradle.contains("if (file(\"google-services.json\").isFile) {"))
        assertTrue(gradle.substringAfter("release {").substringBefore("}").contains("buildConfigField(\"String\", \"FIREBASE_EMULATOR_HOST\", \"\\\"\\\"\")"))
        val root = listOf(File("."), File("..")).map { it.absoluteFile.normalize() }.first { File(it, "settings.gradle.kts").isFile }
        assertTrue(File(root, ".gitignore").readLines().any { it.trim() == "app/google-services.json" })
        mainSources().forEach { file ->
            val text = file.readText()
            if (file.parentFile.name != "cloud") assertFalse("${file.name} uses Firebase", text.contains("com.google.firebase"))
            assertFalse("${file.name} keys data by device model", text.contains("Build.MODEL"))
        }
        val controller = source("src/main/java/com/sehirnotlari/cloud/CloudController.kt")
        assertTrue(controller.contains("BuildConfig.FIREBASE_EMULATOR_HOST.takeIf { BuildConfig.DEBUG && it.isNotBlank() }"))
        assertTrue(controller.contains(".setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())"))
        assertFalse(Regex("""Log\.[a-z]\(.*(email|uid|idToken|notesJson)""").containsMatchIn(controller))
        // Plain HTTP (Auth emulator) is allowed only in debug builds; the release manifest refuses it.
        assertTrue(source("src/main/AndroidManifest.xml").contains("android:usesCleartextTraffic=\"false\""))
        assertFalse(source("src/main/AndroidManifest.xml").contains("networkSecurityConfig"))
        assertTrue(source("src/debug/AndroidManifest.xml").contains("@xml/network_security_config"))
    }

    @Test
    fun pageOffersCloudBackupThroughTheImportPreviewAndKeepsJsonExportAndImport() {
        val html = source("src/main/assets/index.html")
        listOf("backupToCloud", "restoreFromCloud", "importFromCloud").forEach { assertFalse(it, html.contains(it)) }
        assertFalse(html.contains("Bulut yedekleme güvenlik nedeniyle kapatıldı."))
        assertTrue(html.contains("<script src=\"cloud-backup.js\"></script>"))
        val preview = html.substringAfter("function previewCloudRestore(r){").substringBefore("\n}")
        val render = html.substringAfter("function renderCloudRestore(){").substringBefore("\n}")
        listOf("applyImport", "mergeNotes", "sv(", "localStorage").forEach {
            assertFalse(it, preview.contains(it))
            assertFalse(it, render.contains(it))
        }
        assertTrue(preview.indexOf("NotesFile.parse(r.text)") < preview.indexOf("document.getElementById('impOv').classList.add('open')"))
        assertTrue(render.contains("CloudBackup.restorePlan(DB,c.data,c.localDeleted,document.getElementById('impDeleted').checked)"))
        // Deleted-here notes come back only when the user ticks the box; it starts unticked.
        assertTrue(preview.contains("document.getElementById('impDeleted').checked=false;"))
        assertTrue(html.contains("<label class=\"ds-check\" id=\"impDeletedRow\" hidden><input type=\"checkbox\" id=\"impDeleted\">"))
        // `.ds-check` sets display:flex, which would otherwise override the hidden attribute.
        assertTrue(html.contains(".ds-check[hidden]{display:none}"))
        val result = html.substringAfter("window.onCloudResult=function(op,requestId,result,error){").substringBefore("\n};")
        assertTrue(result.indexOf("else if(result.skipped){") < result.indexOf("✅ Yedek tamamlandı ve doğrulandı"))
        assertTrue(result.indexOf("if(error){") < result.indexOf("✅ Yedek tamamlandı ve doğrulandı"))
        assertTrue(html.contains("<button class=\"bk-btn primary\" onclick=\"exportJSON()\">JSON İndir</button>"))
        assertTrue(html.contains("<input type=\"file\" id=\"restoreFile\" accept=\".json\" onchange=\"importJSON(this)\">"))
        assertTrue(html.substringAfter("function exportJSON(){").substringBefore("\n}").contains("const text=JSON.stringify(DB,null,2);"))
        assertTrue(html.substringAfter("function applyImport(d){").substringBefore("\n}").contains("NoteIds.mergeNotes(DB,d,"))
    }

    /** The two file methods carry only the note JSON out and a file the user picked in; neither takes a path. */
    @Test
    fun fileBridgeTakesNoPathAndAnswersThroughFixedCallbacks() {
        val params = bridge.declaredMethods.associate { it.name to it.parameterTypes.map { p -> p.simpleName } }
        assertEquals(listOf("String", "String"), params["exportNotesFile"])
        assertEquals(emptyList<String>(), params["pickNotesFile"])
        val main = source("src/main/java/com/sehirnotlari/MainActivity.kt")
        assertTrue(main.contains("ActivityResultContracts.CreateDocument(NotesFile.MIME)"))
        assertTrue(main.contains("ActivityResultContracts.OpenDocument()"))
        val manifest = source("src/main/AndroidManifest.xml")
        listOf("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE", "READ_MEDIA_").forEach {
            assertFalse(it, manifest.contains(it))
        }
    }

    /**
     * Reviving Room deletion records: refused in Room-first mode, size-checked, and recorded in the
     * journal (all notes or none) before the page stores the notes; the commit restores only recorded notes.
     */
    @Test
    fun restoreBridgeKeepsRoomFirstAndJournalSafeguards() {
        val params = bridge.declaredMethods.associate { it.name to it.parameterTypes.map { p -> p.simpleName } }
        assertEquals(listOf("String", "String"), params["beginRoomRestore"])
        assertEquals(listOf("String", "String"), params["restoreRoomNotes"])
        assertEquals(listOf("String"), params["cancelRoomRestore"])
        val main = source("src/main/java/com/sehirnotlari/MainActivity.kt")
        val begin = main.substringAfter("fun beginRoomRestore(notesJson: String, requestId: String): Boolean {").substringBefore("@JavascriptInterface")
        val beginOrder = listOf(
            "LegacyRoomImporter.oversizedPayloadMessage(notesJson)",
            "refusedInRoomFirst(\"restore\", requestId)",
            "RestoreNote.parseList(notesJson)",
            "importStaging.stage(requestId,",
            "notesDatabase.runInTransaction(Runnable {",
            "noteWriteJournal.recordRestore(item.ticket",
            "recordedRestores[requestId] = items",
        ).map { begin.indexOf(it) }
        assertTrue(beginOrder.toString(), beginOrder.all { it >= 0 } && beginOrder == beginOrder.sorted())
        // A failed stage returns false before any record exists; a failed record removes the staged copy.
        assertTrue(begin.substringAfter("importStaging.stage(").substringBefore("return try").contains("return fail("))
        assertTrue(begin.substringAfter("return try").substringAfter("} catch (e: Exception) {").contains("importStaging.discard(requestId)"))
        val cancel = main.substringAfter("fun cancelRoomRestore(requestId: String) {").substringBefore("@JavascriptInterface")
        assertTrue(cancel.indexOf("importStaging.discard(requestId)") in 0 until cancel.indexOf("noteWriteJournal.cancelled("))
        assertEquals(listOf("String"), params["resumeRoomImports"])
        val resume = main.substringAfter("fun resumeRoomImports(legacyJson: String): String {").substringBefore("@JavascriptInterface")
        val resumeOrder = listOf(
            "importResumeChecked.compareAndSet(false, true)",
            "LegacyRoomImporter.oversizedPayloadMessage(legacyJson)",
            "refusedInRoomFirst(\"resume\"",
            "ImportResume.run(importStaging, legacyJson)",
        ).map { resume.indexOf(it) }
        assertTrue(resumeOrder.toString(), resumeOrder.all { it >= 0 } && resumeOrder == resumeOrder.sorted())
        assertTrue(main.contains("private val importResumeChecked = AtomicBoolean(false)"))
        val commit = main.substringAfter("fun restoreRoomNotes(legacyJson: String, requestId: String) {").substringBefore("@JavascriptInterface")
        val commitOrder = listOf(
            "recordedRestores.remove(requestId)",
            "if (items == null) return fail(\"Unknown restore request\")",
            "LegacyRoomImporter.oversizedPayloadMessage(legacyJson)",
            "refusedInRoomFirst(\"restore\", requestId)",
            "notesDatabase.restoreFromLocal(sequencer, legacyJson, items)",
        ).map { commit.indexOf(it) }
        assertTrue(commitOrder.toString(), commitOrder.all { it >= 0 } && commitOrder == commitOrder.sorted())
        assertTrue(commit.contains("items.forEach { runCatching { noteWriteJournal.failed(it.ticket) } }"))
        assertFalse(commit.contains("recordUpsert"))
        // Only the restore step (run for the bridge and at startup) may revive a tombstone.
        val sources = mainSources().filter { it.readText().contains("applyRestore(") }.map { it.name }.sorted()
        assertEquals(listOf("RoomNoteRestorer.kt", "RoomNoteWriteSequencer.kt"), sources)
        val callers = mainSources().filter { it.readText().contains("restoreNote(sequencer") }.map { it.name }.sorted()
        assertEquals(listOf("RoomNoteReconciler.kt", "RoomNoteRestorer.kt"), callers)
        listOf("RoomNoteReconciler.kt", "RoomNoteRestorer.kt").forEach { name ->
            val text = mainSources().single { it.name == name }.readText()
            assertTrue(name, text.contains("provesRestore("))
        }
    }

    private fun mainSources(): List<File> {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.first { it.exists() }.readText().replace("\r\n", "\n")
    }
}
