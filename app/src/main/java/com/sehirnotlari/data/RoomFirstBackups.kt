package com.sehirnotlari.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** A verified pre-switch backup: localStorage text and every Room row, tombstones included. */
data class RoomFirstBackupInfo(
    val id: String,
    val sha256: String,
    val bytes: Long,
    val legacyNotes: Int,
    val roomRows: Int,
    val createdAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("sha256", sha256)
        put("bytes", bytes)
        put("legacyNotes", legacyNotes)
        put("roomRows", roomRows)
        put("createdAt", createdAt)
    }
}

class RoomFirstBackupContent(val info: RoomFirstBackupInfo, val localStorage: String, val rows: List<NoteEntity>)

/**
 * Backups taken before the Room-first switch, in app-private storage. Each file is written to a
 * temporary name, synced, renamed, and its SHA-256 is stored beside it; [verify] re-reads both.
 * Backups are never deleted here.
 */
class RoomFirstBackups(private val dir: File, private val clock: () -> Long = { System.currentTimeMillis() }) {

    fun create(localStorage: String, rows: List<NoteEntity>): RoomFirstBackupInfo {
        LegacyNotes.parse(localStorage)
        if (!dir.isDirectory && !dir.mkdirs()) throw RoomBridgeFailure("Backup directory unavailable")
        val createdAt = clock()
        var id = "$PREFIX$createdAt"
        var n = 1
        while (File(dir, "$id.json").exists()) id = "$PREFIX$createdAt-${n++}"
        val bytes = JSONObject().apply {
            put("format", FORMAT)
            put("version", 1)
            put("createdAt", createdAt)
            put("localStorage", localStorage)
            put("room", JSONArray().apply { rows.forEach { put(JSONObject(NoteRowJson.encode(it))) } })
        }.toString().toByteArray(Charsets.UTF_8)
        writeSynced(File(dir, "$id.json"), bytes)
        writeSynced(File(dir, "$id.sha256"), sha256(bytes).toByteArray(Charsets.US_ASCII))
        return verify(id) ?: throw RoomBridgeFailure("Backup verification failed")
    }

    /** Null when the file is missing, altered or unreadable. */
    fun verify(id: String): RoomFirstBackupInfo? = read(id)?.info

    fun read(id: String): RoomFirstBackupContent? {
        if (!ID.matches(id)) return null
        return try {
            val bytes = File(dir, "$id.json").readBytes()
            val expected = File(dir, "$id.sha256").readText(Charsets.US_ASCII).trim()
            val actual = sha256(bytes)
            if (expected != actual) return null
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            if (o.getString("format") != FORMAT) return null
            val local = o.getString("localStorage")
            val legacy = LegacyNotes.parse(local)
            val array = o.getJSONArray("room")
            val rows = (0 until array.length()).map { NoteRowJson.decode(array.getJSONObject(it).toString()) }
            RoomFirstBackupContent(
                RoomFirstBackupInfo(id, actual, bytes.size.toLong(), legacy.notes.size, rows.size, o.getLong("createdAt")),
                local,
                rows,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun ids(): List<String> =
        dir.listFiles()?.mapNotNull { file -> file.name.removeSuffix(".json").takeIf { file.name.endsWith(".json") && ID.matches(it) } }
            ?.sorted().orEmpty()

    private fun writeSynced(target: File, bytes: ByteArray) {
        val tmp = File(dir, "${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) throw RoomBridgeFailure("Backup could not be stored")
    }

    companion object {
        const val FORMAT = "sehirnotlari-room-first-backup"
        const val DIR = "room-first-backups"
        private const val PREFIX = "rf-backup-"
        val ID = Regex("""rf-backup-\d+(-\d+)?""")

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
