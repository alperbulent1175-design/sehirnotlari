package com.sehirnotlari.cloud

import org.json.JSONObject
import kotlin.random.Random

/** Where backups live: `users/{uid}/backups/{id}` with pieces in `chunks/{index}`, readable only by that user. */
interface CloudBackupStore {
    /** Valid meta documents read from the server, never from a local cache. */
    suspend fun list(uid: String): List<BackupMeta>

    /** Meta document and every piece in one atomic write: afterwards the backup exists whole or not at all. */
    suspend fun create(uid: String, backup: PreparedBackup)

    suspend fun chunks(uid: String, meta: BackupMeta): List<ByteArray>

    /** Meta document and pieces in one atomic delete. */
    suspend fun delete(uid: String, meta: BackupMeta)
}

class BackupOutcome(
    val backup: BackupMeta?,
    val skipped: AutoSkip?,
    val pruned: Int = 0,
    val pruneFailed: Int = 0,
    val contentHash: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("backup", backup?.toJson() ?: JSONObject.NULL)
        .put("skipped", skipped?.name ?: JSONObject.NULL)
        .put("pruned", pruned)
        .put("pruneFailed", pruneFailed)

    fun logLine(): String = "skipped=${skipped ?: "-"} notes=${backup?.noteCount ?: 0} chunks=${backup?.chunkCount ?: 0} pruned=$pruned pruneFailed=$pruneFailed"
}

class CloudBackupService(
    private val store: CloudBackupStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
) {
    class AutoState(val enabled: Boolean, val lastAt: Long, val lastHash: String?)

    /**
     * Writes a new backup, reads it back from the server and checks it piece by piece; only then may the
     * oldest backups beyond the last [CloudBackupFormat.KEEP] be deleted. [auto] null: manual backup.
     */
    suspend fun backup(uid: String, notesJson: String, deleted: List<DeletedNote>, appVersion: String, auto: AutoState?): BackupOutcome {
        val now = clock()
        val prepared = try {
            CloudBackupFormat.prepare(notesJson, deleted, appVersion, now, CloudBackupFormat.newId(now, random))
        } catch (e: CloudBackupException) {
            if (auto != null && e.empty) return BackupOutcome(null, AutoSkip.EMPTY) else throw e
        }
        if (auto != null) {
            AutoBackupPolicy.localSkip(auto.enabled, now, auto.lastAt, auto.lastHash, prepared.contentHash)?.let { return BackupOutcome(null, it) }
        }
        val existing = store.list(uid)
        if (auto != null) AutoBackupPolicy.remoteSkip(prepared.noteCount, existing)?.let { return BackupOutcome(null, it) }
        store.create(uid, prepared)
        val listed = store.list(uid)
        val stored = listed.firstOrNull { it.id == prepared.id && it.sha256 == prepared.sha256 }
            ?: throw CloudBackupException("Yedeğin tamamlandığı doğrulanamadı. Yedek listesinde yalnız tamamlanmış yedekler görünür; yeniden deneyin.")
        try {
            CloudBackupFormat.verify(stored, store.chunks(uid, stored))
        } catch (e: CloudBackupException) {
            runCatching { store.delete(uid, stored) }
            throw CloudBackupException("Yeni yedek doğrulanamadı ve kaldırıldı; eski yedekleriniz korunuyor. Yeniden deneyin.")
        }
        var pruned = 0
        var pruneFailed = 0
        CloudBackupFormat.beyondKept(listed).filter { it.id != stored.id }.forEach { old ->
            if (runCatching { store.delete(uid, old) }.isSuccess) pruned++ else pruneFailed++
        }
        return BackupOutcome(stored, null, pruned, pruneFailed, prepared.contentHash)
    }

    suspend fun list(uid: String): List<BackupMeta> = CloudBackupFormat.newestFirst(store.list(uid))

    /** Downloads one backup and proves it whole; nothing local is changed here. */
    suspend fun fetch(uid: String, id: String): RestoredBackup {
        if (!CloudBackupFormat.ID.matches(id)) throw CloudBackupException("Yedek bulunamadı.")
        val meta = store.list(uid).firstOrNull { it.id == id }
            ?: throw CloudBackupException("Yedek bulunamadı; silinmiş olabilir. Listeyi yenileyin.")
        return CloudBackupFormat.verify(meta, store.chunks(uid, meta))
    }
}
