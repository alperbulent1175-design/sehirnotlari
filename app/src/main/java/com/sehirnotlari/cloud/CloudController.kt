package com.sehirnotlari.cloud

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.sehirnotlari.BuildConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

private const val CLOUD_LOG = "SehirNotlariCloud"

/**
 * Google sign-in, backup, list and download for one process. Works only when the build carries the
 * Firebase project file; otherwise every call reports that cloud backup is not configured. Logs carry
 * counts and outcomes only: no note content, e-mail address or user id.
 */
class CloudController private constructor(private val app: Context) {
    private class Services(val auth: FirebaseAuth, val service: CloudBackupService, val webClientId: String?, val emulator: Boolean)

    private val services: Services? by lazy { connect() }
    private val prefs: SharedPreferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    /** One cloud operation at a time; a second request is refused rather than queued behind a slow network. */
    private val busy = Mutex()

    private fun connect(): Services? = try {
        val firebase = FirebaseApp.getApps(app).firstOrNull() ?: FirebaseApp.initializeApp(app)
        if (firebase == null) {
            null
        } else {
            val auth = FirebaseAuth.getInstance(firebase)
            val firestore = FirebaseFirestore.getInstance(firebase)
            val host = BuildConfig.FIREBASE_EMULATOR_HOST.takeIf { BuildConfig.DEBUG && it.isNotBlank() }
            if (host != null) {
                auth.useEmulator(host, 9099)
                firestore.useEmulator(host, 8080)
            }
            // No local cache: a list shows only what the server confirms, and nothing is queued to upload later.
            firestore.firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                .build()
            val webClientId = app.resources.getIdentifier("default_web_client_id", "string", app.packageName)
                .takeIf { it != 0 }?.let { app.getString(it) }
            Services(auth, CloudBackupService(FirestoreBackupStore(firestore)), webClientId, host != null)
        }
    } catch (e: Exception) {
        Log.e(CLOUD_LOG, "firebase unavailable error=${e.javaClass.simpleName}")
        null
    }

    fun status(): JSONObject {
        val s = services
        val user = s?.auth?.currentUser
        return JSONObject()
            .put("configured", s != null && (s.webClientId != null || s.emulator))
            .put("signedIn", user != null)
            .put("email", user?.email ?: JSONObject.NULL)
            .put("emulator", s?.emulator == true)
            .put("auto", autoEnabled())
            .put("busy", busy.isLocked)
            .put("lastBackupAt", user?.let { prefs.getLong(uidKey(it.uid, LAST_AT), 0L) } ?: 0L)
    }

    fun autoEnabled(): Boolean = prefs.getBoolean(AUTO, false)

    fun setAuto(enabled: Boolean) {
        prefs.edit().putBoolean(AUTO, enabled).apply()
        Log.i(CLOUD_LOG, "auto backup enabled=$enabled")
    }

    suspend fun signIn(activity: Activity): JSONObject = exclusive {
        val s = services ?: throw CloudBackupException(CloudErrors.NOT_CONFIGURED)
        val idToken = if (s.emulator) EMULATOR_ID_TOKEN else googleIdToken(activity, s.webClientId ?: throw CloudBackupException(CloudErrors.NOT_CONFIGURED))
        try {
            s.auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
        } catch (e: FirebaseNetworkException) {
            Log.e(CLOUD_LOG, "sign-in network error cause=${e.cause?.javaClass?.simpleName ?: "-"}")
            throw CloudBackupException(CloudErrors.forCode("UNAVAILABLE"))
        } catch (e: FirebaseAuthException) {
            Log.e(CLOUD_LOG, "sign-in refused code=${e.errorCode}")
            throw CloudBackupException("Oturum açılamadı. Firebase Console'da Google oturum açma yöntemi etkin olmayabilir.")
        }
        Log.i(CLOUD_LOG, "sign-in ok emulator=${s.emulator}")
        status()
    }

    private suspend fun googleIdToken(activity: Activity, webClientId: String): String {
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(webClientId).build())
            .build()
        val credential = try {
            CredentialManager.create(activity).getCredential(activity, request).credential
        } catch (e: GetCredentialCancellationException) {
            throw CloudBackupException("Oturum açma iptal edildi.")
        } catch (e: NoCredentialException) {
            throw CloudBackupException("Telefonda kullanılabilir bir Google hesabı bulunamadı.")
        } catch (e: GetCredentialException) {
            Log.e(CLOUD_LOG, "credential failed type=${e.type}")
            throw CloudBackupException("Google oturumu açılamadı. Uygulamanın imza parmak izi Firebase'e eklenmemiş olabilir.")
        }
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return GoogleIdTokenCredential.createFrom(credential.data).idToken
        }
        throw CloudBackupException("Google hesabı alınamadı.")
    }

    suspend fun signOut(activity: Activity): JSONObject = exclusive {
        val s = services ?: throw CloudBackupException(CloudErrors.NOT_CONFIGURED)
        s.auth.signOut()
        if (!s.emulator) runCatching { CredentialManager.create(activity).clearCredentialState(ClearCredentialStateRequest()) }
        Log.i(CLOUD_LOG, "sign-out ok")
        status()
    }

    /** [auto]: the automatic backup, which also checks the switch, the daily limit and changes. */
    suspend fun backup(notesJson: String, deleted: List<DeletedNote>, appVersion: String, auto: Boolean): JSONObject {
        if (auto && !autoEnabled()) return BackupOutcome(null, AutoSkip.DISABLED).toJson()
        return exclusive {
            val (s, uid) = signedIn()
            val state = if (auto) {
                CloudBackupService.AutoState(true, prefs.getLong(uidKey(uid, LAST_AUTO_AT), 0L), prefs.getString(uidKey(uid, LAST_HASH), null))
            } else {
                null
            }
            val outcome = s.service.backup(uid, notesJson, deleted, appVersion, state)
            if (outcome.backup != null) {
                val edit = prefs.edit()
                    .putLong(uidKey(uid, LAST_AT), outcome.backup.createdAt)
                    .putString(uidKey(uid, LAST_HASH), outcome.contentHash)
                if (auto) edit.putLong(uidKey(uid, LAST_AUTO_AT), System.currentTimeMillis())
                edit.apply()
            }
            Log.i(CLOUD_LOG, "backup auto=$auto ${outcome.logLine()}")
            outcome.toJson()
        }
    }

    suspend fun list(): JSONObject = exclusive {
        val (s, uid) = signedIn()
        val backups = s.service.list(uid)
        Log.i(CLOUD_LOG, "list backups=${backups.size}")
        JSONObject().put("backups", org.json.JSONArray().apply { backups.forEach { put(it.toJson()) } })
    }

    suspend fun fetch(id: String): RestoredBackup = exclusive {
        val (s, uid) = signedIn()
        s.service.fetch(uid, id).also { Log.i(CLOUD_LOG, "fetch ok notes=${it.meta.noteCount} chunks=${it.meta.chunkCount}") }
    }

    private fun signedIn(): Pair<Services, String> {
        val s = services ?: throw CloudBackupException(CloudErrors.NOT_CONFIGURED)
        val uid = s.auth.currentUser?.uid ?: throw CloudBackupException(CloudErrors.SIGNED_OUT)
        return s to uid
    }

    private suspend fun <T> exclusive(block: suspend () -> T): T {
        if (!busy.tryLock()) throw CloudBackupException("Başka bir bulut işlemi sürüyor; bitince yeniden deneyin.")
        return try {
            block()
        } finally {
            busy.unlock()
        }
    }

    // Per account, without keeping the account id itself in the preferences file.
    private fun uidKey(uid: String, name: String) = "$name." + CloudBackupFormat.sha256(uid.toByteArray()).take(16)

    companion object {
        private const val PREFS = "cloud_backup"
        private const val AUTO = "auto_enabled"
        private const val LAST_AT = "last_backup_at"
        private const val LAST_AUTO_AT = "last_auto_at"
        private const val LAST_HASH = "last_hash"
        // Accepted only by the Auth emulator, which debug builds use when FIREBASE_EMULATOR_HOST is set.
        private const val EMULATOR_ID_TOKEN = """{"sub":"emulator-user-1","email":"test.user@example.com","email_verified":true}"""

        @Volatile private var instance: CloudController? = null

        fun get(context: Context): CloudController =
            instance ?: synchronized(this) { instance ?: CloudController(context.applicationContext).also { instance = it } }
    }
}
