package com.sehirnotlari.data

import org.junit.Assert.assertEquals
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
        assertTrue(source.contains("version = 4,"))
        assertTrue(source.contains("exportSchema = true"))
        assertTrue(source.contains(".addMigrations(*NoteSchemaMigrations.ALL)"))
    }

    @Test
    fun everySchemaVersionHasExportedSchemaAndExplicitMigration() {
        val migrations = sourceFile("src/main/java/com/sehirnotlari/data/NoteSchemaMigrations.kt").readText()
        assertTrue(migrations.contains("Migration(1, 2)"))
        assertTrue(migrations.contains("Migration(2, 3)"))
        assertTrue(migrations.contains("Migration(3, 4)"))
        assertTrue(migrations.contains("arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)"))
        (1..4).forEach { assertTrue(sourceFile("schemas/com.sehirnotlari.data.CityNotesDatabase/$it.json").isFile) }
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
        assertTrue(html.contains("window.addEventListener('load',()=>{if(RF_MODE)rfStart();else requestRoomReconcile(true);});"))
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
        // JSON import is the only merge path; a cloud restore fills the same preview and goes through it.
        // New notes reach Room only once sv() has stored them; a failed save rolls the merge back.
        val apply = html.substringAfter("function applyImport(d){").substringBefore("\n}")
        assertTrue(apply.contains("NoteIds.mergeNotes(DB,d,(city,note)=>added.push([city,note]));"))
        // The restore is recorded before sv(), cancelled when sv() fails and committed only after it succeeded.
        val order = listOf("const restore=recordRoomRestore(added);", "try{sv();}", "cancelRoomRestore(restore);", "finishRoomRestore(restore);")
            .map { apply.indexOf(it) }
        assertTrue(order.toString(), order.all { it >= 0 } && order == order.sorted())
        assertFalse(apply.contains("mirrorNewNoteToRoom"))
        // Room is told about a restore with the stored text, never in Room-first mode.
        val record = html.substringAfter("function recordRoomRestore(notes){").substringBefore("\n}")
        assertTrue(record.contains("if(RF_MODE||!notes.length||"))
        assertTrue(record.contains("window.Android.beginRoomRestore(JSON.stringify(notes),requestId)===true"))
        val finish = html.substringAfter("function finishRoomRestore(r,retry){").substringBefore("\n}")
        assertTrue(finish.indexOf("if(!r.recorded){roomRestoreFailed(") in 0 until finish.indexOf("window.Android.restoreRoomNotes("))
        assertTrue(finish.contains("window.Android.restoreRoomNotes(localStorage.getItem('tur_v10')||'{}',r.requestId);"))
        // A restore record that could not be written warns at once; the success message must not cover it.
        val confirm = html.substringAfter("function confirmImport(){").substringBefore("\n}")
        assertTrue(confirm.indexOf("if(r.ok&&roomRestoreRetry)return;") in 0 until confirm.indexOf("içe aktarıldı"))
        assertTrue(apply.substringAfter("catch(e){").substringBefore("}").contains("DB=JSON.parse(before);"))
        // Without a staged copy on Android nothing is stored; success waits for the Room answer.
        val staged = apply.indexOf("if(restore&&!restore.recorded){DB=JSON.parse(before);")
        assertTrue(staged in apply.indexOf("const restore=recordRoomRestore(added);") until apply.indexOf("try{sv();}"))
        assertTrue(apply.substringAfter("if(restore&&!restore.recorded){").substringBefore("}").contains("return{ok:false,"))
        assertTrue(confirm.indexOf("if(r.ok&&r.pending){") in confirm.indexOf("if(r.ok&&roomRestoreRetry)return;") until confirm.indexOf("✅"))
        val result = html.substringAfter("window.onRoomRestoreResult=function(requestId,result,error){").substringBefore("\n};")
        assertTrue(result.indexOf("not içe aktarıldı") > result.indexOf("if(error||!result){roomRestoreFailed("))
        // The import and its startup resume (the same notes, from the staged copy) are the only merges.
        assertEquals(2, Regex("""NoteIds\.mergeNotes\(DB,""").findAll(html).count())
        val resume = html.substringAfter("function resumeImports(){").substringBefore("\n}")
        assertTrue(resume.contains("if(RF_MODE||!(window.Android&&typeof window.Android.resumeRoomImports==='function'))return;"))
        assertTrue(resume.contains("window.Android.resumeRoomImports(localStorage.getItem(NKEY)||'{}')"))
        assertTrue(resume.contains("const added=NoteIds.mergeNotes(DB,incoming);"))
        assertTrue(resume.substringAfter("try{sv();").substringAfter("catch(e){").substringBefore("}").contains("DB=JSON.parse(before);"))
        // Before the deletion recovery and the load-time reconciliation, both of which must see the notes.
        val call = html.indexOf("\nresumeImports();")
        assertTrue(call > html.indexOf("noteDel=RF_MODE?null:NoteDelete.create({"))
        assertTrue(call in 0 until html.indexOf("const r=noteDel.recover();"))
    }

    @Test
    fun dataSafetyScreenNeverWritesLocalStorageAndLogsCodesOnly() {
        val html = sourceFile("src/main/assets/index.html").readText()
        assertTrue(html.contains("<script src=\"data-safety.js\"></script>"))
        val block = html.substringAfter("// ── VERİ GÜVENLİĞİ").substringBefore("// Room shadow-write pilot")
        assertTrue(block.length > 2000)
        listOf("sv(", "localStorage", "DB=", "DB[", ".splice(", "delete DB", "Android.reconcileRoomNotes").forEach {
            assertFalse("data-safety block writes notes: $it", block.contains(it))
        }
        assertTrue(block.contains("requestRoomReconcile(false)"))
        val logs = Regex("""console\.\w+\(([^;]*)""").findAll(block).map { it.groupValues[1] }.toList()
        assertTrue(logs.size >= 6)
        val allowed = setOf("e", "message", "out", "code", "applied", "skipped")
        logs.forEach { log ->
            assertTrue("unexpected log: $log", log.startsWith("'[DataSafety] "))
            val values = Regex("""[A-Za-z_]\w*""").findAll(log.replace(Regex("""'[^']*'"""), "")).map { it.value }.toSet()
            assertTrue("note data logged: $log", allowed.containsAll(values))
        }
    }

    @Test
    fun dataSafetyScreenIsAccessibleAndBatchStartsDisabled() {
        val html = sourceFile("src/main/assets/index.html").readText()
        val section = html.substringAfter("<section class=\"bk-card ds-card\" id=\"dataSafety\"").substringBefore("</section>")
        val dialog = html.substringAfter("<div class=\"sub-ov\" id=\"dsOv\">").substringBefore("<!--")
        assertTrue(Regex("""id="dsHead" role="status" aria-live="polite"""").containsMatchIn(section))
        assertTrue(Regex("""role="dialog" aria-modal="true" aria-labelledby="dsDlgTitle"""").containsMatchIn(dialog))
        assertTrue(Regex("""id="dsDlgResult"[^>]*aria-live="polite"""").containsMatchIn(dialog))
        assertTrue(Regex("""<input type="checkbox" id="dsBatchToggle">""").containsMatchIn(section))
        assertTrue(section.contains("id=\"dsBatchRun\" disabled>"))
        assertTrue(dialog.contains("id=\"dsApprove\" disabled hidden>"))
        (Regex("""<button\b[^>]*>""").findAll(section) + Regex("""<button\b[^>]*>""").findAll(dialog)).forEach {
            assertTrue("button without type: ${it.value}", it.value.contains("type=\"button\""))
        }
        assertTrue(html.contains(".ds-btn{min-height:48px"))
        assertTrue(html.contains(".ds-check input{width:22px;height:22px"))
        assertTrue(Regex("""@media\(max-width:360px\)\{[^}]*\.ds-cells""").containsMatchIn(html))
        val back = html.substringAfter("function handleAndroidBack").substringBefore("\n}")
        assertTrue(back.indexOf("dsOv") in 0 until back.indexOf("'editOv'"))
    }

    @Test
    fun roomFirstModeIsDebugOnlyAndOptIn() {
        val activity = sourceFile("src/main/java/com/sehirnotlari/MainActivity.kt").readText()
        assertEquals(1, Regex("""RoomFirstSwitch\(""").findAll(activity).count())
        assertEquals(1, Regex("""available = BuildConfig\.DEBUG,""").findAll(activity).count())
        assertTrue(activity.contains("RoomFirstWriter(notesDatabase, noteWriteSequencer, enabled = roomFirst::isOn)"))
        val gradle = sourceFile("build.gradle.kts").readText()
        assertFalse(gradle.contains("isDebuggable = true"))
        assertFalse(Regex("""buildConfigField\([^)]*DEBUG""").containsMatchIn(gradle))
        val mode = sourceFile("src/main/java/com/sehirnotlari/data/RoomFirstMode.kt").readText()
        assertTrue(mode.contains("fun isOn(): Boolean = available && flags.read().enabled"))
        // Every Room write derived from localStorage is refused while Room is the source.
        listOf("import", "delete", "upsert", "reconcile", "resolution", "undo").forEach {
            assertTrue(it, activity.contains("refusedInRoomFirst(\"$it\""))
        }
    }

    @Test
    fun productionNoteStorageIsUnchangedOutsideRoomFirstMode() {
        val html = sourceFile("src/main/assets/index.html").readText()
        assertTrue(html.contains("const RF_MODE=!!(RF_INFO&&RF_INFO.available===true&&RF_INFO.on===true);"))
        assertTrue(html.contains("let DB=RF_MODE?{}:(()=>{try{return JSON.parse(localStorage.getItem(NKEY))||{};}catch{return{};}})();"))
        // storedDB() is DB plus a note inside its undo window, i.e. exactly what was stored before (note-delete.js).
        assertTrue(html.contains("const storedDB=()=>noteDel?noteDel.view(DB):DB;"))
        assertTrue(html.contains("const sv=()=>{if(RF_MODE){rfCommitFromDB();return;}localStorage.setItem(NKEY,JSON.stringify(storedDB()));};"))
        // Writes to the production key: sv() and the explicit, audited switch back from Room-first.
        assertEquals(2, Regex("""localStorage\.setItem\(NKEY""").findAll(html).count())
        val back = html.substringAfter("function rfSwitchClick").substringBefore("\n}")
        assertTrue(back.contains("localStorage.setItem(NKEY,text);"))
        assertTrue(back.indexOf("localStorage.setItem(NKEY") < back.indexOf("['DISABLE',text]"))
        listOf("mirrorNewNoteToRoom", "mirrorDeletedNoteToRoom").forEach {
            assertTrue(it, html.contains("function $it(city,"))
            assertTrue(it, html.substringAfter("function $it(").substringBefore("\n}").contains("\n  if(RF_MODE)return;"))
        }
        assertTrue(html.contains("<script src=\"room-first.js\"></script>"))
    }

    @Test
    fun roomFirstPageLogsCodesAndCountsOnly() {
        val html = sourceFile("src/main/assets/index.html").readText()
        val block = html.substringAfter("// ── ROOM-FIRST PROTOTİPİ").substringBefore("// CRUD")
        assertTrue(block.length > 2000)
        val logs = Regex("""console\.\w+\(([^;]*)""").findAll(block).map { it.groupValues[1] }.toList()
        assertTrue(logs.size >= 6)
        val allowed = setOf("e", "message", "out", "op", "result", "status", "followUp", "snap", "active", "tombstones", "resent", "code")
        logs.forEach { log ->
            assertTrue("unexpected log: $log", log.startsWith("'[RoomFirst] "))
            val values = Regex("""[A-Za-z_]\w*""").findAll(log.replace(Regex("""'[^']*'"""), "")).map { it.value }.toSet()
            assertTrue("note data logged: $log", allowed.containsAll(values))
        }
        val section = html.substringAfter("<section class=\"bk-card ds-card\" id=\"roomFirstDev\"").substringBefore("</section>")
        assertTrue(section.contains("hidden"))
        assertTrue(Regex("""id="rfHead" role="status"""").containsMatchIn(section))
        assertTrue(section.contains("id=\"rfSwitch\" disabled>"))
        Regex("""<button\b[^>]*>""").findAll(section).forEach { assertTrue(it.value, it.value.contains("type=\"button\"")) }
    }

    @Test
    fun aiFeaturesAreOffWithoutAKeyAndNeverShowRawErrors() {
        val activity = sourceFile("src/main/java/com/sehirnotlari/MainActivity.kt").readText()
        assertTrue(activity.contains("private val aiEnabled = BuildConfig.GEMINI_API_KEY.isNotBlank()"))
        assertTrue(activity.contains("fun aiAvailable(): Boolean = aiEnabled"))
        listOf("fun startRecording()", "fun startMultiModalRecording()", "fun pickPDF(city: String)").forEach { name ->
            val body = activity.substringAfter(name).substringBefore("@JavascriptInterface")
            assertTrue("$name is not gated", Regex("""^\s*\{\s*if \(refuseWithoutAi\(\)\) return""").containsMatchIn(body))
        }
        val guide = activity.substringAfter("fun getCityGuide(city: String)").substringBefore("@JavascriptInterface")
        assertTrue(guide.indexOf("if (!aiEnabled)") in 0 until guide.indexOf("generateContent"))
        assertFalse(guide.contains("e.message"))
        val html = sourceFile("src/main/assets/index.html").readText()
        listOf("'pdfBtn').onclick", "'multiBtn').onclick", "'micBtn').onclick", "function pickPDF()").forEach { handler ->
            assertTrue("$handler is not gated", html.substringAfter(handler).substringBefore("\n}").contains("aiOff()"))
        }
    }

    private fun sourceFile(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.first { it.isFile }
    }
}
