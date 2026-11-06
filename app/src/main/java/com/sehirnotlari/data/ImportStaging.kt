package com.sehirnotlari.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** One note of a staged import: the page's note object as imported, under its RESTORE record's ticket. */
data class StagedNote(val city: String, val noteId: String, val ticket: Long, val fingerprint: String, val noteJson: String) {
    val key: String get() = RoomNoteKeys.key(city, noteId)
}

/** A staged import as read back; [notes] is null when the file is unreadable or altered. */
class StagedImport(val requestId: String, val notes: List<StagedNote>?)

/**
 * The notes of a confirmed JSON import, kept in app-private storage from just before the page writes
 * them to localStorage until a later process start finds them there ([ImportResume]). A successful
 * `localStorage.setItem` is not on disk yet (WebView commits it seconds later and a killed process
 * loses it), so this copy is written to a temporary name, synced and renamed before the page may
 * store anything.
 */
class ImportStaging(private val dir: File) {

    fun stage(requestId: String, notes: List<StagedNote>) {
        if (!ID.matches(requestId) || notes.isEmpty()) throw RoomBridgeFailure("Invalid staging request")
        if (!dir.isDirectory && !dir.mkdirs()) throw RoomBridgeFailure("Staging directory unavailable")
        val body = JSONArray().apply {
            notes.forEach {
                put(
                    JSONObject().put("city", it.city).put("id", it.noteId).put("ticket", it.ticket)
                        .put("fingerprint", it.fingerprint).put("note", JSONObject(it.noteJson))
                )
            }
        }.toString()
        val text = JSONObject().put("format", FORMAT).put("requestId", requestId).put("notes", body)
            .put("sha256", RoomFirstBackups.sha256(body.toByteArray(Charsets.UTF_8))).toString()
        val target = File(dir, "$requestId.json")
        val tmp = File(dir, "$requestId.json.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) throw RoomBridgeFailure("Import could not be staged")
        } catch (e: Exception) {
            tmp.delete()
            throw e as? RoomBridgeFailure ?: RoomBridgeFailure("Import could not be staged")
        }
    }

    /** True when nothing is staged under [requestId] any more. */
    fun discard(requestId: String): Boolean {
        if (!ID.matches(requestId)) return true
        val file = File(dir, "$requestId.json")
        return !file.exists() || file.delete()
    }

    /**
     * Every staged import, oldest request first. A leftover temporary file is removed: its stage call
     * never returned, so the page did not store those notes.
     */
    fun all(): List<StagedImport> {
        val files = dir.listFiles().orEmpty()
        files.filter { it.name.endsWith(".json.tmp") }.forEach { it.delete() }
        return files.mapNotNull { file -> file.name.removeSuffix(".json").takeIf { file.name.endsWith(".json") && ID.matches(it) } }
            .sorted()
            .map { StagedImport(it, read(it)) }
    }

    private fun read(requestId: String): List<StagedNote>? = try {
        val o = JSONObject(File(dir, "$requestId.json").readText(Charsets.UTF_8))
        val body = o.getString("notes")
        if (o.getString("format") != FORMAT || o.getString("requestId") != requestId ||
            o.getString("sha256") != RoomFirstBackups.sha256(body.toByteArray(Charsets.UTF_8))
        ) {
            null
        } else {
            val array = JSONArray(body)
            (0 until array.length()).map { i ->
                val n = array.getJSONObject(i)
                val note = n.getJSONObject("note")
                val city = n.getString("city")
                val id = n.getString("id")
                if (RoomNoteKeys.idFromJson(note.opt("id")) != id || city.isBlank()) throw RoomBridgeFailure("Staged note mismatch")
                StagedNote(city, id, n.getLong("ticket"), n.getString("fingerprint"), note.toString())
            }.takeIf { it.isNotEmpty() }
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val FORMAT = "sehirnotlari-import-staging"
        const val DIR = "import-staging"
        val ID = Regex("""[A-Za-z0-9-]{1,100}""")
    }
}

enum class ResumeAction {
    /** In the stored `tur_v10` read at this start: the import reached disk. */
    CONFIRMED,
    /** Missing, with its own RESTORE record and nothing journaled after it: the page stores it again. */
    RESUMED,
    /** Deleted after the import, or staged again by a later import of the same note. */
    DROPPED,
    /** Missing, but the record does not prove it or the note was changed later: not added back. */
    REPORTED,
}

/** Counts only, plus the notes the page adds back; safe for Logcat apart from [notes]. */
class ImportResumeResult(
    val notes: List<StagedNote>,
    val confirmed: Int,
    val dropped: Int,
    val reported: Int,
    /** Staged files that could not be read; their notes are reported as a whole. */
    val unreadable: Int,
) {
    fun logLine() = "resumed=${notes.size} confirmed=$confirmed dropped=$dropped reported=$reported unreadable=$unreadable"

    fun toJson(): String = JSONObject()
        .put("notes", JSONArray().apply { notes.forEach { put(JSONArray().put(it.city).put(JSONObject(it.noteJson))) } })
        .put("confirmed", confirmed).put("dropped", dropped).put("reported", reported).put("unreadable", unreadable)
        .toString()

    companion object {
        val NONE = ImportResumeResult(emptyList(), 0, 0, 0, 0)

        /** Nothing was decided; every staged file is kept for the next start. */
        fun failedJson(): String = JSONObject(NONE.toJson()).put("error", true).toString()
    }
}

/**
 * Finishes staged imports at a process start, before the page's deletion recovery and the Room
 * reconciliation. Must only run on `tur_v10` read from disk by a new process: a page reloaded in the
 * same process may still hold an unwritten copy. A staged file is removed once none of its notes
 * still needs a later start to confirm it; notes added back stay staged until such a start.
 */
object ImportResume {
    private val RECORDED = setOf(
        NoteWriteJournalEntry.PENDING, NoteWriteJournalEntry.FAILED,
        NoteWriteJournalEntry.APPLIED, NoteWriteJournalEntry.REPAIRED,
    )

    fun decide(note: StagedNote, local: LegacyNotes, entries: List<NoteWriteJournalEntry>): ResumeAction {
        if (note.key in local.notes) return ResumeAction.CONFIRMED
        val record = entries.firstOrNull { it.ticket == note.ticket }
        if (record == null || record.op != NoteWriteJournalEntry.RESTORE || record.city != note.city ||
            record.fingerprint != note.fingerprint || record.state !in RECORDED
        ) {
            return ResumeAction.REPORTED
        }
        val later = entries.filter { it.ticket > note.ticket }.maxByOrNull { it.ticket } ?: return ResumeAction.RESUMED
        return if (later.op == NoteWriteJournalEntry.DELETE) ResumeAction.DROPPED else ResumeAction.REPORTED
    }

    suspend fun run(
        staging: ImportStaging,
        legacyJson: String,
        journalFor: suspend (String) -> List<NoteWriteJournalEntry>,
    ): ImportResumeResult {
        val local = LegacyNotes.parse(legacyJson)
        val imports = staging.all()
        if (imports.isEmpty()) return ImportResumeResult.NONE
        val newest = imports.flatMap { it.notes.orEmpty() }.groupBy { it.key }.mapValues { (_, n) -> n.maxBy { it.ticket } }
        val resumed = mutableListOf<StagedNote>()
        val counts = IntArray(ResumeAction.entries.size)
        var unreadable = 0
        for (staged in imports) {
            val notes = staged.notes
            if (notes == null) {
                unreadable++
                staging.discard(staged.requestId)
                continue
            }
            var keep = false
            for (note in notes) {
                val action = if (newest[note.key] !== note) ResumeAction.DROPPED else decide(note, local, journalFor(note.key))
                counts[action.ordinal]++
                if (action == ResumeAction.RESUMED) {
                    resumed += note
                    keep = true
                }
            }
            if (!keep) staging.discard(staged.requestId)
        }
        return ImportResumeResult(
            notes = resumed,
            confirmed = counts[ResumeAction.CONFIRMED.ordinal],
            dropped = counts[ResumeAction.DROPPED.ordinal],
            reported = counts[ResumeAction.REPORTED.ordinal],
            unreadable = unreadable,
        )
    }
}
