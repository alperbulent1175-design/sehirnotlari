package com.sehirnotlari.cloud

import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Firestore layout and the only code that talks to it. Reads always go to the server. */
class FirestoreBackupStore(private val db: FirebaseFirestore) : CloudBackupStore {
    private fun backups(uid: String): CollectionReference = db.collection("users").document(uid).collection("backups")

    override suspend fun list(uid: String): List<BackupMeta> = remote {
        backups(uid).limit(LIST_LIMIT).get(Source.SERVER).await().documents.mapNotNull { doc ->
            CloudBackupFormat.metaFrom(doc.id, doc.data ?: return@mapNotNull null, doc.getTimestamp("createdAt")?.toDate()?.time)
        }
    }

    override suspend fun create(uid: String, backup: PreparedBackup) = remote {
        val meta = backups(uid).document(backup.id)
        val batch = db.batch()
        batch.set(meta, backup.metaFields() + ("createdAt" to FieldValue.serverTimestamp()))
        backup.chunks.forEachIndexed { i, bytes ->
            batch.set(meta.collection(CHUNKS).document(i.toString()), mapOf("index" to i, "data" to Blob.fromBytes(bytes)))
        }
        batch.commit().await()
        Unit
    }

    override suspend fun chunks(uid: String, meta: BackupMeta): List<ByteArray> = remote {
        val pieces = backups(uid).document(meta.id).collection(CHUNKS)
        (0 until meta.chunkCount).map { i ->
            val doc = pieces.document(i.toString()).get(Source.SERVER).await()
            val index = (doc.get("index") as? Number)?.toInt()
            val data = doc.getBlob("data")
            if (!doc.exists() || index != i || data == null) throw CloudBackupException("Yedek eksik veya bozuk; geri yüklenmedi. Notlarınız değişmedi.")
            data.toBytes()
        }
    }

    override suspend fun delete(uid: String, meta: BackupMeta) = remote {
        val ref = backups(uid).document(meta.id)
        val batch = db.batch()
        batch.delete(ref)
        (0 until meta.chunkCount).forEach { batch.delete(ref.collection(CHUNKS).document(it.toString())) }
        batch.commit().await()
        Unit
    }

    private suspend fun <T> remote(block: suspend () -> T): T = try {
        withTimeout(TIMEOUT_MS) { block() }
    } catch (e: CloudBackupException) {
        throw e
    } catch (_: TimeoutCancellationException) {
        throw CloudBackupException(CloudErrors.TIMEOUT)
    } catch (e: FirebaseFirestoreException) {
        throw CloudBackupException(CloudErrors.forCode(e.code.name))
    } catch (_: FirebaseNetworkException) {
        throw CloudBackupException(CloudErrors.forCode("UNAVAILABLE"))
    }

    companion object {
        const val CHUNKS = "chunks"
        private const val LIST_LIMIT = 50L
        private const val TIMEOUT_MS = 60_000L
    }
}

/** User-facing texts for Firebase failures; they name what to do, never data or identifiers. */
object CloudErrors {
    const val TIMEOUT = "Sunucu zamanında yanıt vermedi. İşlemin tamamlandığı doğrulanamadı; listede yalnız tamamlanmış yedekler görünür."
    const val NOT_CONFIGURED = "Bu derlemede bulut yedekleme yapılandırılmamış."
    const val SIGNED_OUT = "Bulut yedekleme için önce Google ile oturum açın."

    fun forCode(code: String): String = when (code) {
        "UNAVAILABLE" -> "İnternet bağlantısı yok veya sunucuya ulaşılamadı. Bağlantıyı denetleyip yeniden deneyin."
        "DEADLINE_EXCEEDED" -> TIMEOUT
        "PERMISSION_DENIED" -> "Bulut erişimi reddedildi. Firestore güvenlik kuralları yayımlanmamış olabilir."
        "UNAUTHENTICATED" -> "Oturumun süresi dolmuş; yeniden oturum açın."
        "NOT_FOUND", "FAILED_PRECONDITION" -> "Bulut veritabanı hazır değil. Firebase Console'da Firestore oluşturulmamış olabilir."
        "RESOURCE_EXHAUSTED" -> "Bulut kotası doldu; daha sonra yeniden deneyin."
        else -> "Bulut işlemi tamamlanamadı; yeniden deneyin."
    }
}

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            error != null -> cont.resumeWithException(error)
            task.isCanceled -> cont.cancel()
            else -> cont.resume(task.result)
        }
    }
}
