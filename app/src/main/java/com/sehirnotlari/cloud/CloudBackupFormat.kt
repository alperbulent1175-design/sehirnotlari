package com.sehirnotlari.cloud

import com.sehirnotlari.data.RoomNoteKeys
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlin.random.Random

/** A refusal or failure whose message is shown to the user as is; it never contains note content. */
class CloudBackupException(message: String, val empty: Boolean = false) : Exception(message)

/** A Room deletion record carried in a backup; restoring never deletes anything because of it. */
data class DeletedNote(val city: String, val id: String, val deletedAt: Long)

/** Meta document of one complete backup. [createdAt] is the server's commit time in milliseconds. */
data class BackupMeta(
    val id: String,
    val createdAt: Long,
    val noteCount: Int,
    val cityCount: Int,
    val deletedCount: Int,
    val byteLength: Int,
    val sha256: String,
    val chunkCount: Int,
    val appVersion: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("createdAt", createdAt).put("noteCount", noteCount).put("cityCount", cityCount)
        .put("deletedCount", deletedCount).put("byteLength", byteLength).put("appVersion", appVersion)
}

/** A backup ready to be written: the meta fields (without the server time) and the payload pieces. */
class PreparedBackup(
    val id: String,
    val noteCount: Int,
    val cityCount: Int,
    val deletedCount: Int,
    val byteLength: Int,
    val sha256: String,
    val appVersion: String,
    val chunks: List<ByteArray>,
    /** Identifies the notes and deletion records, not the moment: equal for an unchanged backup. */
    val contentHash: String,
) {
    /** Fields of the meta document; the store adds `createdAt` as the server's time. */
    fun metaFields(): Map<String, Any> = mapOf(
        "format" to CloudBackupFormat.FORMAT,
        "version" to CloudBackupFormat.VERSION,
        "status" to CloudBackupFormat.COMPLETE,
        "noteCount" to noteCount,
        "cityCount" to cityCount,
        "deletedCount" to deletedCount,
        "byteLength" to byteLength,
        "sha256" to sha256,
        "chunkCount" to chunks.size,
        "appVersion" to appVersion,
    )
}

/** A downloaded backup whose size, checksum, format and counts all matched its meta document. */
class RestoredBackup(val meta: BackupMeta, val notesJson: String, val deleted: List<DeletedNote>)

/**
 * Cloud backup payload: `{format, version, createdAt, appVersion, notes, deleted}` as UTF-8 JSON, where
 * `notes` is the stored `tur_v10` object byte for byte. Split into pieces that each fit in one Firestore
 * document; the meta document records length, SHA-256 and piece count so a restore can prove it is whole.
 */
object CloudBackupFormat {
    const val FORMAT = "sehirnotlari-cloud-backup"
    const val VERSION = 1
    const val COMPLETE = "complete"
    /** Firestore documents hold at most 1 MiB; a piece leaves ample room for the field names. */
    const val CHUNK_BYTES = 700_000
    const val MAX_BYTES = 5 * 1024 * 1024
    const val MAX_CHUNKS = (MAX_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES
    const val KEEP = 5
    const val MAX_NOTES = 20_000
    private const val MAX_CITY_CHARS = 120
    val ID = Regex("""b-[0-9]{13}-[0-9a-f]{8}""")
    private val SHA = Regex("""[0-9a-f]{64}""")

    fun newId(now: Long, random: Random = Random.Default): String =
        "b-%013d-%08x".format(now, random.nextInt())

    fun prepare(notesJson: String, deleted: List<DeletedNote>, appVersion: String, now: Long, id: String): PreparedBackup {
        require(ID.matches(id))
        val notes = try {
            JSONTokener(notesJson).nextValue() as? JSONObject
        } catch (_: Exception) {
            null
        } ?: throw CloudBackupException("Notlar okunamadı; yedek alınmadı.")
        var noteCount = 0
        var cityCount = 0
        val active = HashSet<String>()
        for (city in notes.keys()) {
            if (city.isBlank() || city.length > MAX_CITY_CHARS) throw CloudBackupException("Notlarda geçersiz bir şehir adı var; yedek alınmadı.")
            val list = notes.opt(city) as? JSONArray ?: throw CloudBackupException("Notlar okunamadı; yedek alınmadı.")
            for (i in 0 until list.length()) {
                val note = list.opt(i) as? JSONObject ?: throw CloudBackupException("Notlarda okunamayan bir not var; yedek alınmadı.")
                RoomNoteKeys.idFromJson(note.opt("id"))?.let { active.add(RoomNoteKeys.key(city, it)) }
            }
            if (list.length() > 0) cityCount++
            noteCount += list.length()
        }
        if (noteCount == 0) throw CloudBackupException("Yedeklenecek not yok.", empty = true)
        if (noteCount > MAX_NOTES) throw CloudBackupException("Bulut yedeği için çok fazla not var.")
        // A deletion record of a note that is back in the notes says nothing any more.
        val records = deleted.filter { RoomNoteKeys.key(it.city, it.id) !in active }.sortedWith(compareBy({ it.city }, { it.id }))
        val deletedJson = JSONArray().apply {
            records.forEach { put(JSONObject().put("city", it.city).put("id", it.id).put("deletedAt", it.deletedAt)) }
        }.toString()
        val head = JSONObject().put("format", FORMAT).put("version", VERSION).put("createdAt", now).put("appVersion", appVersion).toString()
        val payload = head.dropLast(1) + ",\"notes\":" + notesJson.trim() + ",\"deleted\":" + deletedJson + "}"
        val bytes = payload.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw CloudBackupException("Notlar bulut yedeği için çok büyük (en fazla 5 MB). JSON dosyasına yedekleyin.")
        return PreparedBackup(
            id = id,
            noteCount = noteCount,
            cityCount = cityCount,
            deletedCount = records.size,
            byteLength = bytes.size,
            sha256 = sha256(bytes),
            appVersion = appVersion,
            chunks = split(bytes),
            contentHash = sha256((notesJson.trim() + "\n" + deletedJson).toByteArray(Charsets.UTF_8)),
        )
    }

    fun split(bytes: ByteArray): List<ByteArray> =
        (bytes.indices step CHUNK_BYTES).map { bytes.copyOfRange(it, minOf(it + CHUNK_BYTES, bytes.size)) }

    /** Rejects anything that is not exactly the backup its meta document describes. */
    fun verify(meta: BackupMeta, chunks: List<ByteArray>): RestoredBackup {
        if (chunks.size != meta.chunkCount || chunks.any { it.size > CHUNK_BYTES }) throw damaged()
        val bytes = ByteArray(chunks.sumOf { it.size }).also { out ->
            var at = 0
            chunks.forEach { it.copyInto(out, at); at += it.size }
        }
        if (bytes.size != meta.byteLength || sha256(bytes) != meta.sha256) throw damaged()
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw damaged()
        }
        val payload = try {
            JSONTokener(text).nextValue() as? JSONObject
        } catch (_: Exception) {
            null
        } ?: throw damaged()
        if (payload.optString("format") != FORMAT) throw damaged()
        val version = payload.optInt("version", -1)
        if (version > VERSION) throw CloudBackupException("Bu yedek uygulamanın daha yeni bir sürümüyle alınmış; önce uygulamayı güncelleyin.")
        if (version != VERSION) throw damaged()
        val notes = payload.optJSONObject("notes") ?: throw damaged()
        var count = 0
        for (city in notes.keys()) count += (notes.opt(city) as? JSONArray ?: throw damaged()).length()
        if (count != meta.noteCount) throw damaged()
        val deletedArray = payload.optJSONArray("deleted") ?: throw damaged()
        val deleted = (0 until deletedArray.length()).map { i ->
            val d = deletedArray.optJSONObject(i) ?: throw damaged()
            DeletedNote(d.optString("city"), d.optString("id"), d.optLong("deletedAt"))
        }
        return RestoredBackup(meta, notes.toString(), deleted)
    }

    private fun damaged() = CloudBackupException("Yedek eksik veya bozuk; geri yüklenmedi. Notlarınız değişmedi.")

    /** Meta of a backup as listed by the store; null for anything incomplete or unknown, which is never shown. */
    fun metaFrom(id: String, fields: Map<String, Any?>, createdAt: Long?): BackupMeta? {
        fun int(name: String): Int? = (fields[name] as? Number)?.toLong()?.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
        if (!ID.matches(id) || createdAt == null) return null
        if (fields["format"] != FORMAT || int("version") != VERSION || fields["status"] != COMPLETE) return null
        val sha = fields["sha256"] as? String ?: return null
        val meta = BackupMeta(
            id = id,
            createdAt = createdAt,
            noteCount = int("noteCount") ?: return null,
            cityCount = int("cityCount") ?: return null,
            deletedCount = int("deletedCount") ?: return null,
            byteLength = int("byteLength") ?: return null,
            sha256 = sha,
            chunkCount = int("chunkCount") ?: return null,
            appVersion = (fields["appVersion"] as? String)?.take(20) ?: return null,
        )
        val consistent = SHA.matches(sha) && meta.chunkCount in 1..MAX_CHUNKS && meta.byteLength in 1..MAX_BYTES &&
            meta.chunkCount == (meta.byteLength + CHUNK_BYTES - 1) / CHUNK_BYTES
        return meta.takeIf { consistent }
    }

    fun newestFirst(backups: List<BackupMeta>): List<BackupMeta> =
        backups.sortedWith(compareByDescending<BackupMeta> { it.createdAt }.thenByDescending { it.id })

    /** Complete backups beyond the newest [keep]; called only after a new backup was confirmed complete. */
    fun beyondKept(backups: List<BackupMeta>, keep: Int = KEEP): List<BackupMeta> = newestFirst(backups).drop(keep)

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Why an automatic backup did not run; manual backups are never skipped. */
enum class AutoSkip { DISABLED, NOT_DUE, UNCHANGED, EMPTY, FEWER_NOTES }

object AutoBackupPolicy {
    const val INTERVAL_MS = 24L * 60 * 60 * 1000

    /** Before any network access: off, too soon after the last automatic backup, or nothing changed. */
    fun localSkip(enabled: Boolean, now: Long, lastAt: Long, lastHash: String?, contentHash: String): AutoSkip? = when {
        !enabled -> AutoSkip.DISABLED
        lastAt in 1..now && now - lastAt < INTERVAL_MS -> AutoSkip.NOT_DUE
        lastHash == contentHash -> AutoSkip.UNCHANGED
        else -> null
    }

    /**
     * Much fewer notes than the newest backup (e.g. after an accidental "delete all"): an automatic backup
     * would push the good backups out of the last five, so only a manual backup may do that.
     */
    fun remoteSkip(noteCount: Int, existing: List<BackupMeta>): AutoSkip? {
        val newest = CloudBackupFormat.newestFirst(existing).firstOrNull() ?: return null
        return if (noteCount * 2 < newest.noteCount) AutoSkip.FEWER_NOTES else null
    }
}
