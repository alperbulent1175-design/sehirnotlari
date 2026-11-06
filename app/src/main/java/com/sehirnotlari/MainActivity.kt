package com.sehirnotlari

import com.sehirnotlari.cloud.CloudBackupException
import com.sehirnotlari.cloud.CloudController
import com.sehirnotlari.cloud.CloudScripts
import com.sehirnotlari.cloud.DeletedNote
import com.sehirnotlari.data.CityNotesDatabase
import com.sehirnotlari.data.ImportResume
import com.sehirnotlari.data.ImportResumeResult
import com.sehirnotlari.data.ImportStaging
import com.sehirnotlari.data.LegacyRoomImporter
import com.sehirnotlari.data.NoteWriteJournal
import com.sehirnotlari.data.RoomBridgeErrors
import com.sehirnotlari.data.RoomBridgeScripts
import com.sehirnotlari.data.RoomNoteDraft
import com.sehirnotlari.data.RoomNoteImportStore
import com.sehirnotlari.data.RoomNoteKeys
import com.sehirnotlari.data.RoomNoteWriteSequencer
import com.sehirnotlari.data.PrefsRoomFirstFlagStore
import com.sehirnotlari.data.ResolutionAction
import com.sehirnotlari.data.RoomBridgeFailure
import com.sehirnotlari.data.RoomFirstBackups
import com.sehirnotlari.data.RoomFirstSwitch
import com.sehirnotlari.data.RoomFirstWrite
import com.sehirnotlari.data.RoomFirstWriter
import com.sehirnotlari.data.RestoreItem
import com.sehirnotlari.data.RestoreNote
import com.sehirnotlari.data.applyResolution
import com.sehirnotlari.data.restoreFromLocal
import com.sehirnotlari.data.dataSafetyStatus
import com.sehirnotlari.data.inspectNote
import com.sehirnotlari.data.reconcileWithLegacy
import com.sehirnotlari.data.undoResolution
import com.sehirnotlari.data.toBridgeError
import com.sehirnotlari.data.toBridgeSuccess
import androidx.room.withTransaction
import org.json.JSONArray
import kotlinx.coroutines.sync.withLock
import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.GeolocationPermissions
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.RequestOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

private const val ROOM_LOG = "SehirNotlariRoom"
private const val FILE_LOG = "SehirNotlariFile"
// A started file write finishes even if the activity is recreated meanwhile.
private val notesFileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
// Per process: only the first page load reads localStorage from disk (see resumeRoomImports).
private val importResumeChecked = AtomicBoolean(false)
private const val CLOUD_LOG = "SehirNotlariCloud"
private const val CLOUD_ROOM_FIRST = "Room ana kaynak modunda bulut yedekleme kullanılamaz."
// A started backup finishes (or fails whole) even if the activity is recreated meanwhile.
private val cloudScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private const val ROOM_FIRST_ON = "Room-first mode is on"
private const val ROOM_FIRST_OFF = "Room-first mode is off"
private const val AI_OFF_MESSAGE = "Yapay zekâ özellikleri bu sürümde kullanılamıyor."
private const val AI_OFF_GUIDE = "<i>Akıllı rehber bu sürümde kullanılamıyor.</i>"
private const val AI_GUIDE_FAILED = "<i>Rehber şu an hazırlanamadı. Daha sonra yeniden deneyin.</i>"

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val notesDatabase by lazy { CityNotesDatabase.get(applicationContext) }
    private val noteWriteSequencer by lazy { RoomNoteWriteSequencer.shared(notesDatabase) }
    private val noteWriteJournal by lazy { NoteWriteJournal(notesDatabase.journal()) }
    private val importStaging by lazy { ImportStaging(File(filesDir, ImportStaging.DIR)) }
    // Prototype only: release builds can neither read the mode as on nor switch it on.
    private val roomFirst by lazy {
        RoomFirstSwitch(
            db = notesDatabase,
            sequencer = noteWriteSequencer,
            flags = PrefsRoomFirstFlagStore(getSharedPreferences(PrefsRoomFirstFlagStore.FILE, Context.MODE_PRIVATE)),
            backups = RoomFirstBackups(File(filesDir, RoomFirstBackups.DIR)),
            available = BuildConfig.DEBUG,
        )
    }
    private val roomFirstWriter by lazy { RoomFirstWriter(notesDatabase, noteWriteSequencer, enabled = roomFirst::isOn) }
    private val cloud by lazy { CloudController.get(applicationContext) }
    private lateinit var releaseUpdater: GithubReleaseUpdater
    private var selectedCityForPdf: String? = null

    // Media
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var photoFile: File? = null
    private var photoUri: Uri? = null

    // Builds without GEMINI_API_KEY (public releases) must not call the API at all.
    private val aiEnabled = BuildConfig.GEMINI_API_KEY.isNotBlank()

    private val generativeModel = GenerativeModel(
        modelName = "gemini-2.5-flash",
        apiKey = BuildConfig.GEMINI_API_KEY.trim(),
        requestOptions = RequestOptions(timeout = kotlin.time.Duration.parse("5m"))
    )

    private val guideModel = GenerativeModel(
        modelName = "gemini-2.5-flash",
        apiKey = BuildConfig.GEMINI_API_KEY.trim(),
        requestOptions = RequestOptions(timeout = kotlin.time.Duration.parse("5m"))
    )

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            pageReady = false
            webView.reload()
        }
    }

    private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecordingInternal() else webView.evaluateJavascript("toast('Mikrofon izni reddedildi')", null)
    }

    private val multimodalPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        
        if (cameraGranted && micGranted) {
            takePhotoForMultiModal()
        } else {
            val missing = mutableListOf<String>()
            if (!cameraGranted) missing.add("Kamera")
            if (!micGranted) missing.add("Mikrofon")
            webView.evaluateJavascript("toast('${missing.joinToString(" ve ")} izni gerekli')", null)
        }
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            // Fotoğraf çekildi, şimdi ses kaydını başlat
            startRecordingInternal()
            webView.evaluateJavascript("toast('Fotoğraf alındı, şimdi konuşun...')", null)
        } else {
            webView.evaluateJavascript("toast('Fotoğraf çekimi iptal edildi')", null)
        }
    }

    private val pdfPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) extractTextFromPdf(uri) else webView.evaluateJavascript("toast('PDF seçimi iptal edildi')", null)
    }

    private val unknownSourcesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (::releaseUpdater.isInitialized) releaseUpdater.onInstallPermissionResult()
    }

    // JSON backup through the system file screens: no storage permission, the user picks name and place.
    private val notesExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument(NotesFile.MIME)) { uri ->
        onNotesExportTarget(uri)
    }
    private val notesImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        onNotesImportSource(uri)
    }
    // The text to save waits in private cache, so it survives the activity being recreated behind the picker.
    private val pendingExportFile by lazy { File(cacheDir, "notes-export.pending") }
    private var pageReady = false
    private var pendingFileScript: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        createNotificationChannel()
        webView = findViewById(R.id.webView)
        releaseUpdater = GithubReleaseUpdater(
            activity = this,
            webView = { webView },
            requestInstallPermission = { openUnknownInstallSources() }
        )
        setupWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("handleAndroidBack()") { result ->
                    if (result == "false" || result == "null") showExitConfirmation()
                }
            }
        })

        checkLocationPermissions()
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onResume() {
        super.onResume()
        releaseUpdater.onResume()
    }

    // Leaving the app is the moment for the automatic backup; the page decides whether it is on.
    override fun onStop() {
        super.onStop()
        if (pageReady) webView.evaluateJavascript("window.cloudAutoBackup && window.cloudAutoBackup()", null)
    }

    override fun onDestroy() {
        releaseUpdater.onDestroy()
        super.onDestroy()
    }

    private fun extractTextFromPdf(uri: Uri) {
        Log.d("SehirNotlari", "PDF Analysis started")
        webView.evaluateJavascript("showLoading('PDF işleniyor...')", null)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputStream = contentResolver.openInputStream(uri) ?: throw Exception("Dosya açılamadı")
                val bytes = inputStream.readBytes()
                inputStream.close()

                val cityName = selectedCityForPdf ?: ""
                val promptCityPart = if (cityName.isNotEmpty()) "ve '$cityName' şehri ile ilgili" else "ve ilgili şehir(ler) ile ilgili"

                val inputContent = content {
                    blob("application/pdf", bytes)
                    text("""
                        Sen dünya çapında tecrübeye sahip profesyonel bir gezi rehberi ve gurmesin. 
                        Analiz et $promptCityPart vizyoner bir özet çıkar.
                        
                        Sadece kuru bir özet yapma; şu başlıkları kullanarak ilham verici bir içerik oluştur:
                        - NEDEN GİTMELİSİN?: (Şehrin ruhunu ve en çekici yanını anlat)
                        - MUTLAKA TADILMASI GEREKEN TEK ŞEY: (Oraya özgü, unutulmaz bir lezzet önerisi)
                        - GİZLİ KALMIŞ BİR İPUCU: (Turistlerin pek bilmediği özel bir nokta veya pratik bir bilgi)
                        
                        Yanıtını YALNIZCA şu formatta ver, başka hiçbir şey yazma:
                        BAŞLIK: [Şehir Adı ve Kısa Motto]
                        İÇERİK: [Yukarıdaki maddeleri içeren şık ve öz metin]
                    """.trimIndent())
                }

                val response = generativeModel.generateContent(inputContent)
                val aiResponse = response.text
                if (aiResponse == null) {
                    Log.e("SehirNotlari", "PDF Analysis: AI returned null text")
                    throw Exception("AI yanıt vermedi")
                }

                val title = if (aiResponse.contains("BAŞLIK:")) aiResponse.substringAfter("BAŞLIK:").substringBefore("İÇERİK:").trim() else "AI Özeti"
                val summary = if (aiResponse.contains("İÇERİK:")) aiResponse.substringAfter("İÇERİK:").trim() else aiResponse

                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript("onAISummaryReady(${JSONObject.quote(title)}, ${JSONObject.quote(summary)})", null)
                }
            } catch (e: Exception) {
                Log.e("SehirNotlari", "PDF Analysis failed error=${e.javaClass.simpleName}")
                withContext(Dispatchers.Main) {
                    val errorMsg = e.message ?: e.toString()
                    webView.evaluateJavascript("hideLoading()", null)
                    webView.evaluateJavascript("toast(${JSONObject.quote("PDF hatası: $errorMsg")})", null)
                }
            }
        }
    }

    private fun takePhotoForMultiModal() {
        val storageDir = externalCacheDir
        photoFile = File.createTempFile("temp_photo", ".jpg", storageDir)
        photoUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile!!)

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
        }
        cameraLauncher.launch(intent)
    }

    private fun startRecordingInternal() {
        try {
            audioFile = File(externalCacheDir, "temp_voice.m4a")
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(audioFile?.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setGeolocationEnabled(true)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url != null && url.contains("index.html")) {
                    releaseUpdater.onWebViewReady()
                    pageReady = true
                    pendingFileScript?.let { pendingFileScript = null; webView.evaluateJavascript(it, null) }
                }
            }
        }
        webView.addJavascriptInterface(WebAppInterface(this), "Android")
        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                callback?.invoke(origin, true, false)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("REMINDER_CHANNEL", "Reminders", NotificationManager.IMPORTANCE_HIGH)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun checkLocationPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    private fun openUnknownInstallSources() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:$packageName")
        )
        unknownSourcesLauncher.launch(intent)
    }

    private fun showExitConfirmation() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Türkiye Şehir Notları")
            .setMessage("Uygulamadan çıkmak istediğinize emin misiniz?")
            .setPositiveButton("Evet") { _, _ -> finish() }
            .setNegativeButton("Hayır", null)
            .show()
    }

    // A result arriving while the page (re)loads is handed over once it has finished loading.
    private fun postFileResult(script: String) {
        runOnUiThread {
            if (isDestroyed || !::webView.isInitialized) return@runOnUiThread
            if (pageReady) webView.evaluateJavascript(script, null) else pendingFileScript = script
        }
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0).take(120) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun declaredSize(uri: Uri): Long? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun startNotesExport(json: String, fileName: String) {
        val bytes = try {
            NotesFile.exportBytes(json)
        } catch (e: NotesFile.Refused) {
            postFileResult(NotesFile.script(NotesFile.SAVED_CALLBACK, NotesFile.failed(e.message ?: "Yedek hazırlanamadı.")))
            return
        }
        notesFileScope.launch {
            try {
                pendingExportFile.writeBytes(bytes)
            } catch (e: Exception) {
                Log.e(FILE_LOG, "export prepare failed error=${e.javaClass.simpleName}")
                postFileResult(NotesFile.script(NotesFile.SAVED_CALLBACK, NotesFile.failed("Yedek hazırlanamadı. Telefonda yer açıp yeniden deneyin.")))
                return@launch
            }
            runOnUiThread {
                try {
                    notesExportLauncher.launch(NotesFile.suggestedName(fileName))
                } catch (e: ActivityNotFoundException) {
                    pendingExportFile.delete()
                    postFileResult(NotesFile.script(NotesFile.SAVED_CALLBACK, NotesFile.failed("Bu cihazda dosya kaydetme ekranı bulunamadı.")))
                }
            }
        }
    }

    private fun onNotesExportTarget(uri: Uri?) {
        if (uri == null) {
            pendingExportFile.delete()
            Log.i(FILE_LOG, "export cancelled")
            postFileResult(NotesFile.script(NotesFile.SAVED_CALLBACK, NotesFile.cancelled()))
            return
        }
        notesFileScope.launch {
            val result = try {
                if (!pendingExportFile.isFile) throw NotesFile.Refused("Kaydetme yarıda kaldı; lütfen yeniden deneyin.")
                val bytes = pendingExportFile.readBytes()
                val stream = try {
                    contentResolver.openOutputStream(uri, "wt")
                } catch (_: FileNotFoundException) {
                    contentResolver.openOutputStream(uri, "w")
                } catch (_: IllegalArgumentException) {
                    contentResolver.openOutputStream(uri, "w")
                } ?: throw IOException("No output stream")
                stream.use {
                    it.write(bytes)
                    it.flush()
                }
                Log.i(FILE_LOG, "export ok bytes=${bytes.size}")
                NotesFile.saved(displayName(uri), bytes.size)
            } catch (e: Exception) {
                Log.e(FILE_LOG, "export failed error=${e.javaClass.simpleName}")
                // Do not leave an empty or partial file that looks like a backup.
                try { DocumentsContract.deleteDocument(contentResolver, uri) } catch (_: Exception) {}
                NotesFile.failed((e as? NotesFile.Refused)?.message ?: "Dosya yazılamadı; yedek oluşturulmadı. Başka bir konum seçip yeniden deneyin.")
            } finally {
                pendingExportFile.delete()
            }
            postFileResult(NotesFile.script(NotesFile.SAVED_CALLBACK, result))
        }
    }

    private fun onNotesImportSource(uri: Uri?) {
        if (uri == null) {
            Log.i(FILE_LOG, "import cancelled")
            postFileResult(NotesFile.script(NotesFile.OPENED_CALLBACK, NotesFile.cancelled()))
            return
        }
        notesFileScope.launch {
            val result = try {
                NotesFile.checkDeclaredSize(declaredSize(uri))
                val bytes = contentResolver.openInputStream(uri)?.use { NotesFile.readLimited(it) } ?: throw IOException("No input stream")
                val text = NotesFile.decode(bytes)
                Log.i(FILE_LOG, "import read ok bytes=${bytes.size}")
                NotesFile.opened(displayName(uri), text)
            } catch (e: NotesFile.Refused) {
                Log.w(FILE_LOG, "import refused")
                NotesFile.failed(e.message ?: "Dosya okunamadı.")
            } catch (e: Exception) {
                Log.e(FILE_LOG, "import read failed error=${e.javaClass.simpleName}")
                NotesFile.failed("Dosya okunamadı.")
            }
            postFileResult(NotesFile.script(NotesFile.OPENED_CALLBACK, result))
        }
    }

    // Room writes run in a process scope; a recreated activity's page no longer awaits the old result.
    private fun refuseWithoutAi(): Boolean {
        if (aiEnabled) return false
        runOnUiThread { webView.evaluateJavascript("toast(${JSONObject.quote(AI_OFF_MESSAGE)})", null) }
        return true
    }

    private fun postRoomResult(script: String) {
        runOnUiThread {
            if (!isDestroyed && ::webView.isInitialized) webView.evaluateJavascript(script, null)
        }
    }

    private fun postCloudResult(script: String) = postRoomResult(script)

    private suspend fun runCloud(op: String, requestId: String, block: suspend () -> JSONObject) {
        val script = try {
            CloudScripts.result(op, requestId, block(), null)
        } catch (e: CloudBackupException) {
            Log.w(CLOUD_LOG, "$op refused request=$requestId")
            CloudScripts.result(op, requestId, null, e.message)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(CLOUD_LOG, "$op failed request=$requestId error=${e.javaClass.simpleName}")
            CloudScripts.result(op, requestId, null, "Bulut işlemi tamamlanamadı; yeniden deneyin.")
        }
        postCloudResult(script)
    }

    /** Room deletion records (tombstones) as `city` + WebView id; read only, the rows stay as they are. */
    private suspend fun roomDeletionRecords(): List<DeletedNote> =
        notesDatabase.notes().allRows().mapNotNull { row ->
            row.deletedAt?.let { DeletedNote(row.city, row.id.removePrefix("${row.city}::"), it) }
        }

    inner class WebAppInterface(private val mContext: Context) {
        // Explicit bridge: UI will be switched to Room only after startup hydration is tested.
        @JavascriptInterface
        fun saveNotesToRoom(json: String) {
            // Legacy import remains opt-in. The WebView does not invoke it automatically.
            LegacyRoomImporter.oversizedPayloadMessage(json)?.let { message ->
                postRoomResult(RoomBridgeScripts.importResult(false, 0, 0, message))
                return
            }
            if (refusedInRoomFirst("import", "-")) {
                postRoomResult(RoomBridgeScripts.importResult(false, 0, 0, ROOM_FIRST_ON))
                return
            }
            val sequencer = noteWriteSequencer
            RoomNoteWriteSequencer.writeScope.launch {
                var inserted = 0
                var skipped = 0
                try {
                    sequencer.mutex.withLock {
                        notesDatabase.withTransaction {
                            val outcome = LegacyRoomImporter.importOrThrow(
                                json,
                                RoomNoteImportStore(notesDatabase.notes()),
                                stamp = sequencer::nextTicket
                            )
                            inserted = outcome.inserted
                            skipped = outcome.skipped
                        }
                    }
                    Log.i(ROOM_LOG, "import ok inserted=$inserted skipped=$skipped")
                    postRoomResult(RoomBridgeScripts.importResult(true, inserted, skipped, null))
                } catch (e: Exception) {
                    Log.e(ROOM_LOG, "import failed error=${RoomBridgeErrors.safe(e)}")
                    postRoomResult(RoomBridgeScripts.importResult(false, 0, 0, RoomBridgeErrors.safe(e)))
                }
            }
        }

        // A deletion is recorded as a tombstone for future cloud synchronization.
        @JavascriptInterface
        fun deleteRoomNote(city: String, id: String, requestId: String) {
            if (city.isBlank() || id.isBlank() || requestId.isBlank()) return
            if (refusedInRoomFirst("delete", requestId)) {
                postRoomResult(RoomBridgeScripts.deleteResult(requestId, false, ROOM_FIRST_ON))
                return
            }
            val sequencer = noteWriteSequencer
            val ticket = try {
                sequencer.nextTicket()
            } catch (e: Exception) {
                Log.e(ROOM_LOG, "open failed request=$requestId error=${RoomBridgeErrors.safe(e)}")
                postRoomResult(RoomBridgeScripts.deleteResult(requestId, false, RoomBridgeErrors.safe(e)))
                return
            }
            journalWrite(requestId) { noteWriteJournal.recordDelete(ticket, city, id) }
            RoomNoteWriteSequencer.writeScope.launch {
                try {
                    val ack = sequencer.delete(city, id, ticket, requestId)
                    runCatching { noteWriteJournal.resolve(ticket, ack) }
                    Log.i(ROOM_LOG, "delete request=$requestId ticket=$ticket ack=$ack")
                    postRoomResult(RoomBridgeScripts.deleteResult(requestId, ack.toBridgeSuccess(), ack.toBridgeError()))
                } catch (e: Exception) {
                    runCatching { noteWriteJournal.failed(ticket) }
                    Log.e(ROOM_LOG, "delete failed request=$requestId error=${RoomBridgeErrors.safe(e)}")
                    postRoomResult(RoomBridgeScripts.deleteResult(requestId, false, RoomBridgeErrors.safe(e)))
                }
            }
        }

        // Opt-in single-note Room write for end-to-end bridge verification.
        // Does not alter the current localStorage write path.
        @JavascriptInterface
        fun upsertRoomNote(city: String, noteJson: String, requestId: String) {
            if (city.isBlank() || city.length > 120 || noteJson.length > 100_000 || requestId.length > 100) return
            if (refusedInRoomFirst("upsert", requestId)) {
                postRoomResult(RoomBridgeScripts.writeResult(requestId, false, ROOM_FIRST_ON))
                return
            }
            val item = try {
                JSONObject(noteJson)
            } catch (_: Exception) {
                null
            }
            val id = item?.let { RoomNoteKeys.idFromJson(it.opt("id")) }
            if (item == null || id == null) {
                Log.e(ROOM_LOG, "upsert failed request=$requestId error=Invalid note payload")
                postRoomResult(RoomBridgeScripts.writeResult(requestId, false, "Invalid note payload"))
                return
            }
            val draft = RoomNoteDraft.fromJson(item)
            val sequencer = noteWriteSequencer
            val ticket = try {
                sequencer.nextTicket()
            } catch (e: Exception) {
                Log.e(ROOM_LOG, "open failed request=$requestId error=${RoomBridgeErrors.safe(e)}")
                postRoomResult(RoomBridgeScripts.writeResult(requestId, false, RoomBridgeErrors.safe(e)))
                return
            }
            journalWrite(requestId) { noteWriteJournal.recordUpsert(ticket, city, id, draft) }
            RoomNoteWriteSequencer.writeScope.launch {
                try {
                    val ack = sequencer.upsert(city, id, draft, ticket, requestId.takeIf { it.isNotBlank() })
                    runCatching { noteWriteJournal.resolve(ticket, ack) }
                    Log.i(ROOM_LOG, "upsert request=$requestId ticket=$ticket ack=$ack")
                    postRoomResult(RoomBridgeScripts.writeResult(requestId, ack.toBridgeSuccess(), ack.toBridgeError()))
                } catch (e: Exception) {
                    runCatching { noteWriteJournal.failed(ticket) }
                    Log.e(ROOM_LOG, "upsert failed request=$requestId error=${RoomBridgeErrors.safe(e)}")
                    postRoomResult(RoomBridgeScripts.writeResult(requestId, false, RoomBridgeErrors.safe(e)))
                }
            }
        }

        /**
         * First step of a JSON import the user confirmed, called synchronously before the page stores
         * the notes. [notesJson] is `[[city, note], …]` of exactly the imported notes. Stages the notes
         * on disk ([ImportStaging]), then records one RESTORE journal entry per note, all or none, so an
         * import cut off by a crash is finished at the next start. False: nothing was staged or
         * recorded, and the page must not store the notes.
         */
        @JavascriptInterface
        fun beginRoomRestore(notesJson: String, requestId: String): Boolean {
            if (!ImportStaging.ID.matches(requestId)) return false
            fun fail(message: String): Boolean {
                Log.e(ROOM_LOG, "restore record failed request=$requestId error=$message")
                return false
            }
            LegacyRoomImporter.oversizedPayloadMessage(notesJson)?.let { return fail(it) }
            if (refusedInRoomFirst("restore", requestId)) return false
            val notes = RestoreNote.parseList(notesJson) ?: return fail("Invalid restore payload")
            val sequencer = noteWriteSequencer
            val items = try {
                notes.map { RestoreItem(it.city, it.noteId, sequencer.nextTicket()) }
            } catch (e: Exception) {
                return fail(RoomBridgeErrors.safe(e))
            }
            try {
                importStaging.stage(requestId, notes.mapIndexed { i, note -> note.staged(items[i].ticket) })
            } catch (e: Exception) {
                return fail(RoomBridgeErrors.safe(e))
            }
            return try {
                notesDatabase.runInTransaction(Runnable {
                    items.forEachIndexed { i, item -> noteWriteJournal.recordRestore(item.ticket, item.city, item.noteId, notes[i].draft) }
                })
                synchronized(recordedRestores) { recordedRestores[requestId] = items }
                Log.i(ROOM_LOG, "restore recorded request=$requestId notes=${items.size}")
                true
            } catch (e: Exception) {
                if (!importStaging.discard(requestId)) Log.e(ROOM_LOG, "restore staging kept request=$requestId")
                fail(RoomBridgeErrors.safe(e))
            }
        }

        /**
         * Room side of a recorded restore, called after the page stored the notes. [legacyJson] is the
         * stored `tur_v10` text and gives each note's content; only notes recorded by [beginRoomRestore]
         * for [requestId] are restored, and their old Room deletion records may be revived.
         */
        @JavascriptInterface
        fun restoreRoomNotes(legacyJson: String, requestId: String) {
            if (requestId.isBlank() || requestId.length > 100) return
            val items = synchronized(recordedRestores) { recordedRestores.remove(requestId) }
            // Records stay open (FAILED) on every failure, so the next start can still finish the restore.
            fun fail(message: String) {
                items?.let { RoomNoteWriteSequencer.writeScope.launch { it.forEach { item -> runCatching { noteWriteJournal.failed(item.ticket) } } } }
                Log.e(ROOM_LOG, "restore failed request=$requestId error=$message")
                postRoomResult(RoomBridgeScripts.restoreResult(requestId, null, message))
            }
            if (items == null) return fail("Unknown restore request")
            LegacyRoomImporter.oversizedPayloadMessage(legacyJson)?.let { return fail(it) }
            if (refusedInRoomFirst("restore", requestId)) return fail(ROOM_FIRST_ON)
            val sequencer = noteWriteSequencer
            RoomNoteWriteSequencer.writeScope.launch {
                try {
                    val outcome = notesDatabase.restoreFromLocal(sequencer, legacyJson, items)
                    Log.i(ROOM_LOG, "restore request=$requestId ${outcome.logLine()}")
                    postRoomResult(RoomBridgeScripts.restoreResult(requestId, outcome.toJson(), null))
                } catch (e: Exception) {
                    items.forEach { runCatching { noteWriteJournal.failed(it.ticket) } }
                    Log.e(ROOM_LOG, "restore failed request=$requestId error=${RoomBridgeErrors.safe(e)}")
                    postRoomResult(RoomBridgeScripts.restoreResult(requestId, null, RoomBridgeErrors.safe(e)))
                }
            }
        }

        /**
         * The page could not store the notes of a recorded restore. The staged copy goes first, so a
         * crash in between can never bring back notes the user was told were not added; then the
         * records are closed.
         */
        @JavascriptInterface
        fun cancelRoomRestore(requestId: String) {
            if (requestId.isBlank() || requestId.length > 100) return
            val items = synchronized(recordedRestores) { recordedRestores.remove(requestId) } ?: return
            if (!importStaging.discard(requestId)) Log.e(ROOM_LOG, "restore staging kept request=$requestId")
            RoomNoteWriteSequencer.writeScope.launch {
                items.forEach { runCatching { noteWriteJournal.cancelled(it.ticket) } }
                Log.i(ROOM_LOG, "restore cancelled request=$requestId notes=${items.size}")
            }
        }

        /**
         * Called synchronously at page start, before the deletion recovery and Room reconciliation, with
         * the stored `tur_v10` text. Only the first call of this process acts: a new process has just
         * read localStorage from disk, while a later page load (e.g. rotation) may read a copy that
         * was never written. Returns [ImportResumeResult] JSON; the page adds `notes` back and stores them.
         */
        @JavascriptInterface
        fun resumeRoomImports(legacyJson: String): String {
            if (!importResumeChecked.compareAndSet(false, true)) return ImportResumeResult.NONE.toJson()
            LegacyRoomImporter.oversizedPayloadMessage(legacyJson)?.let { message ->
                Log.e(ROOM_LOG, "import resume failed error=$message")
                return ImportResumeResult.failedJson()
            }
            if (refusedInRoomFirst("resume", "")) return ImportResumeResult.NONE.toJson()
            return try {
                val result = runBlocking { ImportResume.run(importStaging, legacyJson) { notesDatabase.journal().forNote(it) } }
                Log.i(ROOM_LOG, "import resume ${result.logLine()}")
                result.toJson()
            } catch (e: Exception) {
                Log.e(ROOM_LOG, "import resume failed error=${RoomBridgeErrors.safe(e)}")
                ImportResumeResult.failedJson()
            }
        }

        /** Restores recorded by [beginRoomRestore] that the page has not finished or cancelled yet. */
        private val recordedRestores = object : LinkedHashMap<String, List<RestoreItem>>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<RestoreItem>>?) = size > 16
        }

        // Without a journal entry the write still runs; a crash would then only be reported, not repaired.
        private inline fun journalWrite(requestId: String, record: () -> Unit) {
            try {
                record()
            } catch (e: Exception) {
                Log.e(ROOM_LOG, "journal failed request=$requestId error=${RoomBridgeErrors.safe(e)}")
            }
        }

        /**
         * Startup comparison of localStorage (sent read-only by the WebView) with Room.
         * Room is repaired only where the write journal proves an interrupted write.
         */
        @JavascriptInterface
        fun reconcileRoomNotes(json: String, requestId: String, apply: Boolean) {
            if (requestId.isBlank() || requestId.length > 100) return
            LegacyRoomImporter.oversizedPayloadMessage(json)?.let { message ->
                postRoomResult(RoomBridgeScripts.reconcileResult(requestId, null, message))
                return
            }
            if (refusedInRoomFirst("reconcile", requestId)) {
                postRoomResult(RoomBridgeScripts.reconcileResult(requestId, null, ROOM_FIRST_ON))
                return
            }
            val sequencer = noteWriteSequencer
            // Writes the page makes after this call get larger tickets and are left for the next start.
            val cutoff = try {
                sequencer.nextTicket()
            } catch (e: Exception) {
                Log.e(ROOM_LOG, "open failed request=$requestId error=${RoomBridgeErrors.safe(e)}")
                postRoomResult(RoomBridgeScripts.reconcileResult(requestId, null, RoomBridgeErrors.safe(e)))
                return
            }
            RoomNoteWriteSequencer.writeScope.launch {
                try {
                    val report = notesDatabase.reconcileWithLegacy(sequencer, json, apply, cutoff)
                    Log.i(ROOM_LOG, "reconcile request=$requestId ${report.logLine()}")
                    postRoomResult(RoomBridgeScripts.reconcileResult(requestId, report.toJson(), null))
                } catch (e: Exception) {
                    Log.e(ROOM_LOG, "reconcile failed request=$requestId error=${RoomBridgeErrors.safe(e)}")
                    postRoomResult(RoomBridgeScripts.reconcileResult(requestId, null, RoomBridgeErrors.safe(e)))
                }
            }
        }

        /** Data-safety screen: one note's current difference, both versions and the safe approvals. */
        @JavascriptInterface
        fun inspectRoomNote(json: String, key: String, requestId: String) {
            if (!validSafetyRequest(requestId, key)) return
            LegacyRoomImporter.oversizedPayloadMessage(json)?.let { message ->
                postRoomResult(RoomBridgeScripts.dataSafetyResult(RoomBridgeScripts.INSPECT_RESULT, requestId, null, message))
                return
            }
            val sequencer = noteWriteSequencer
            RoomNoteWriteSequencer.writeScope.launch {
                try {
                    val plan = notesDatabase.inspectNote(sequencer, json, key)
                    Log.i(ROOM_LOG, "inspect request=$requestId kind=${plan.kind ?: "NONE"} options=${plan.options.count { it.enabled }}")
                    postRoomResult(RoomBridgeScripts.dataSafetyResult(RoomBridgeScripts.INSPECT_RESULT, requestId, plan.toJson(), null))
                } catch (e: Exception) {
                    Log.e(ROOM_LOG, "inspect failed request=$requestId error=${RoomBridgeErrors.safe(e)}")
                    postRoomResult(RoomBridgeScripts.dataSafetyResult(RoomBridgeScripts.INSPECT_RESULT, requestId, null, RoomBridgeErrors.safe(e)))
                }
            }
        }

        /** Applies one user-approved change to Room after backing up the row; localStorage is not touched. */
        @JavascriptInterface
        fun applyRoomResolution(json: String, key: String, action: String, token: String, confirmed: Boolean, requestId: String) {
            if (!validSafetyRequest(requestId, key) || token.length > 200) return
            val resolution = ResolutionAction.entries.firstOrNull { it.name == action }
            val rejected = when {
                resolution == null -> "Unknown resolution action"
                refusedInRoomFirst("resolution", requestId) -> ROOM_FIRST_ON
                else -> LegacyRoomImporter.oversizedPayloadMessage(json)
            }
            if (rejected != null) {
                Log.e(ROOM_LOG, "resolution failed request=$requestId error=$rejected")
                postRoomResult(RoomBridgeScripts.dataSafetyResult(RoomBridgeScripts.RESOLUTION_RESULT, requestId, null, rejected))
                return
            }
            val sequencer = noteWriteSequencer
            RoomNoteWriteSequencer.writeScope.launch {
                try {
                    val outcome = notesDatabase.applyResolution(sequencer, json, key, resolution!!, token, confirmed)
                    Log.i(ROOM_LOG, "resolution request=$requestId action=$action code=${outcome.code} backup=${outcome.backupId ?: "-"}")
                    postRoomResult(RoomBridgeScripts.dataSafetyResult(RoomBridgeScripts.RESOLUTION_RESULT, requestId, outcome.toJson(), null))
                } catch (e: Exception) {
                    Log.e(ROOM_LOG, "resolution failed request=$requestId error=${RoomBridgeErrors.safe(e)}")
                    postRoomResult(RoomBridgeScripts.dataSafetyResult(RoomBridgeScripts.RESOLUTION_RESULT, requestId, null, RoomBridgeErrors.safe(e)))
                }
            }
        }

        @JavascriptInterface
        fun undoRoomResolution(backupId: String, requestId: String) {
            val id = backupId.toLongOrNull()
            if (requestId.isBlank() || requestId.length > 100 || id == null) return
            if (refusedInRoomFirst("undo", requestId)) {
                postRoomResult(RoomBridgeScripts.dataSafetyResult(RoomBridgeScripts.RESOLUTION_RESULT, requestId, null, ROOM_FIRST_ON))
                return
            }
            val sequencer = noteWriteSequencer
            RoomNoteWriteSequencer.writeScope.launch {
                try {
                    val outcome = notesDatabase.undoResolution(sequencer, id)
                    Log.i(ROOM_LOG, "undo request=$requestId backup=$id code=${outcome.code}")
                    postRoomResult(RoomBridgeScripts.dataSafetyResult(RoomBridgeScripts.RESOLUTION_RESULT, requestId, outcome.toJson(), null))
                } catch (e: Exception) {
                    Log.e(ROOM_LOG, "undo failed request=$requestId error=${RoomBridgeErrors.safe(e)}")
                    postRoomResult(RoomBridgeScripts.dataSafetyResult(RoomBridgeScripts.RESOLUTION_RESULT, requestId, null, RoomBridgeErrors.safe(e)))
                }
            }
        }

        /** Repairs and lost writes from the journal plus user-resolution backups; survives restarts. */
        @JavascriptInterface
        fun requestDataSafetyStatus(requestId: String) {
            if (requestId.isBlank() || requestId.length > 100) return
            val sequencer = noteWriteSequencer
            RoomNoteWriteSequencer.writeScope.launch {
                try {
                    val status = notesDatabase.dataSafetyStatus(sequencer)
                    Log.i(ROOM_LOG, "status request=$requestId ${status.logLine()}")
                    postRoomResult(RoomBridgeScripts.dataSafetyResult(RoomBridgeScripts.SAFETY_STATUS, requestId, status.toJson(), null))
                } catch (e: Exception) {
                    Log.e(ROOM_LOG, "status failed request=$requestId error=${RoomBridgeErrors.safe(e)}")
                    postRoomResult(RoomBridgeScripts.dataSafetyResult(RoomBridgeScripts.SAFETY_STATUS, requestId, null, RoomBridgeErrors.safe(e)))
                }
            }
        }

        private fun validSafetyRequest(requestId: String, key: String) =
            requestId.isNotBlank() && requestId.length <= 100 && key.contains("::") && key.length <= 400

        /** In Room-first mode localStorage is not the source: Room writes derived from it are refused. */
        private fun refusedInRoomFirst(what: String, requestId: String): Boolean {
            val on = try {
                roomFirst.isOn()
            } catch (e: Exception) {
                Log.e(ROOM_LOG, "room-first mode unreadable error=${RoomBridgeErrors.safe(e)}")
                false
            }
            if (on) Log.w(ROOM_LOG, "$what refused request=$requestId reason=room-first")
            return on
        }

        /** Synchronous: the page decides its note source before it reads anything. Release builds: always off. */
        @JavascriptInterface
        fun roomFirstMode(): String = try {
            roomFirst.modeJson().toString()
        } catch (e: Exception) {
            Log.e(ROOM_LOG, "room-first mode failed error=${RoomBridgeErrors.safe(e)}")
            """{"available":false,"on":false,"backupId":null}"""
        }

        /** Room's committed notes for the Room-first page (debug prototype). */
        @JavascriptInterface
        fun readRoomFirst(requestId: String) {
            if (requestId.isBlank() || requestId.length > 100) return
            RoomNoteWriteSequencer.writeScope.launch {
                try {
                    if (!roomFirst.isOn()) throw RoomBridgeFailure(ROOM_FIRST_OFF)
                    val snapshot = roomFirstWriter.snapshot()
                    Log.i(ROOM_LOG, "room-first read request=$requestId ${snapshot.logLine()}")
                    postRoomResult(RoomBridgeScripts.roomFirstResult(RoomBridgeScripts.ROOM_FIRST_SNAPSHOT, requestId, snapshot.toJson(), null))
                } catch (e: Exception) {
                    Log.e(ROOM_LOG, "room-first read failed request=$requestId error=${RoomBridgeErrors.safe(e)}")
                    postRoomResult(RoomBridgeScripts.roomFirstResult(RoomBridgeScripts.ROOM_FIRST_SNAPSHOT, requestId, null, RoomBridgeErrors.safe(e)))
                }
            }
        }

        /** One Room-first change; the reply is posted only after the transaction committed or was refused. */
        @JavascriptInterface
        fun writeRoomFirst(payload: String, requestId: String) {
            if (requestId.isBlank() || requestId.length > 100) return
            RoomNoteWriteSequencer.writeScope.launch {
                try {
                    val write = RoomFirstWrite.parse(payload)
                    val result = roomFirstWriter.write(write)
                    Log.i(ROOM_LOG, "room-first write request=$requestId op=${write.op} status=${result.status} ticket=${result.ticket ?: "-"}")
                    postRoomResult(RoomBridgeScripts.roomFirstResult(RoomBridgeScripts.ROOM_FIRST_RESULT, requestId, result.toJson(), null))
                } catch (e: Exception) {
                    Log.e(ROOM_LOG, "room-first write failed request=$requestId error=${RoomBridgeErrors.safe(e)}")
                    postRoomResult(RoomBridgeScripts.roomFirstResult(RoomBridgeScripts.ROOM_FIRST_RESULT, requestId, null, RoomBridgeErrors.safe(e)))
                }
            }
        }

        /** PREFLIGHT / ENABLE / DISABLE / STATUS of the debug-only source switch; [json] is the page's localStorage notes. */
        @JavascriptInterface
        fun roomFirstSwitch(action: String, json: String, requestId: String) {
            if (requestId.isBlank() || requestId.length > 100) return
            LegacyRoomImporter.oversizedPayloadMessage(json)?.let { message ->
                postRoomResult(RoomBridgeScripts.roomFirstResult(RoomBridgeScripts.ROOM_FIRST_SWITCH, requestId, null, message))
                return
            }
            RoomNoteWriteSequencer.writeScope.launch {
                try {
                    val result = when (action) {
                        "STATUS" -> roomFirst.status()
                        "PREFLIGHT", "ENABLE", "DISABLE" -> {
                            val outcome = when (action) {
                                "PREFLIGHT" -> roomFirst.preflight(json)
                                "ENABLE" -> roomFirst.enable(json)
                                else -> roomFirst.disable(json)
                            }
                            Log.i(
                                ROOM_LOG,
                                "room-first switch request=$requestId action=$action code=${outcome.code} " +
                                    "backup=${outcome.backup?.id ?: "-"} ${outcome.preflight?.logLine() ?: ""}"
                            )
                            outcome.toJson().put("mode", roomFirst.modeJson())
                        }
                        else -> throw RoomBridgeFailure("Unknown switch action")
                    }
                    postRoomResult(RoomBridgeScripts.roomFirstResult(RoomBridgeScripts.ROOM_FIRST_SWITCH, requestId, result, null))
                } catch (e: Exception) {
                    Log.e(ROOM_LOG, "room-first switch failed request=$requestId action=${action.take(12)} error=${RoomBridgeErrors.safe(e)}")
                    postRoomResult(RoomBridgeScripts.roomFirstResult(RoomBridgeScripts.ROOM_FIRST_SWITCH, requestId, null, RoomBridgeErrors.safe(e)))
                }
            }
        }

        @JavascriptInterface
        fun requestRoomNotes() {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val result = JSONObject()
                    val active = notesDatabase.notes().allActive()
                    Log.i(ROOM_LOG, "read ok active=${active.size}")
                    active.forEach { note ->
                        val arr = result.optJSONArray(note.city) ?: JSONArray().also { result.put(note.city, it) }
                        arr.put(JSONObject().apply {
                            put("id", RoomNoteKeys.webViewId(note))
                            put("title", note.title)
                            put("content", note.content)
                            put("category", note.category)
                            put("star", note.star)
                            put("color", note.color)
                            put("fav", note.favorite)
                            put("date", note.dateLabel)
                            note.isAI?.let { put("isAI", it) }
                        })
                    }
                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript("window.onRoomNotesReady && window.onRoomNotesReady(${result});", null)
                    }
                } catch (e: Exception) {
                    Log.e(ROOM_LOG, "read failed error=${RoomBridgeErrors.safe(e)}")
                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript(
                            RoomBridgeScripts.notesError(RoomBridgeErrors.safe(e)),
                            null
                        )
                    }
                }
            }
        }


        @JavascriptInterface
        fun getCityGuide(city: String) {
            val sharedPrefs = mContext.getSharedPreferences("city_guides_cache", Context.MODE_PRIVATE)
            val cachedGuide = sharedPrefs.getString(city, null)
            if (cachedGuide != null) {
                Log.d("SehirNotlari", "Loading guide for $city from SharedPreferences cache.")
                runOnUiThread {
                    webView.evaluateJavascript("onCityGuideReady(${JSONObject.quote(cachedGuide)})", null)
                }
                return
            }
            if (!aiEnabled) {
                runOnUiThread { webView.evaluateJavascript("onCityGuideReady(${JSONObject.quote(AI_OFF_GUIDE)})", null) }
                return
            }

            Log.d("SehirNotlari", "getCityGuide called for city: $city")
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val prompt = "Sen profesyonel bir seyahat rehberisin. '$city' şehri için 'Ne yenir?', 'Gezilecek yer' ve 1 cümlelik bilgi ver. HTML (<b>, <br>) kullan. Çok kısa olsun."
                    val response = guideModel.generateContent(prompt)
                    val responseText = response.text ?: "Bilgi yok"
                    Log.d("SehirNotlari", "AI Guide Response length=${responseText.length}")
                    
                    sharedPrefs.edit().putString(city, responseText).apply()
                    
                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript("onCityGuideReady(${JSONObject.quote(responseText)})", null)
                    }
                } catch (e: Exception) {
                    Log.e("SehirNotlari", "Error in getCityGuide for city: $city, Exception: ${e.javaClass.simpleName}")
                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript("onCityGuideReady(${JSONObject.quote(AI_GUIDE_FAILED)})", null)
                    }
                }
            }
        }

        @JavascriptInterface
        fun aiAvailable(): Boolean = aiEnabled

        @JavascriptInterface
        fun startRecording() {
            if (refuseWithoutAi()) return
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
            startRecordingInternal()
        }

        @JavascriptInterface
        fun startMultiModalRecording() {
            if (refuseWithoutAi()) return
            val cameraPermission = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
            val micPermission = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
            
            if (cameraPermission != PackageManager.PERMISSION_GRANTED || micPermission != PackageManager.PERMISSION_GRANTED) {
                multimodalPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                return
            }
            takePhotoForMultiModal()
        }

        @JavascriptInterface
        fun stopRecording(city: String) {
            selectedCityForPdf = city
            try {
                mediaRecorder?.stop()
                mediaRecorder?.release()
                mediaRecorder = null

                audioFile?.let { file ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val inputContent = content {
                                text("audio_1.m4a:")
                                blob("audio/mpeg", file.readBytes())
                                text("Ekteki 'audio_1.m4a' ses kaydını metne dönüştür. BAŞLIK: ... İÇERİK: ... formatında ver.")
                            }
                            val response = generativeModel.generateContent(inputContent)
                            val aiResponse = response.text ?: ""
                            val title = if (aiResponse.contains("BAŞLIK:")) aiResponse.substringAfter("BAŞLIK:").substringBefore("İÇERİK:").trim() else "Sesli Not"
                            val summary = if (aiResponse.contains("İÇERİK:")) aiResponse.substringAfter("İÇERİK:").trim() else aiResponse
                            withContext(Dispatchers.Main) {
                                webView.evaluateJavascript("onAISummaryReady(${JSONObject.quote(title)}, ${JSONObject.quote(summary)})", null)
                            }
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        @JavascriptInterface
        fun stopMultiModalRecording(city: String) {
            selectedCityForPdf = city
            try {
                mediaRecorder?.stop()
                mediaRecorder?.release()
                mediaRecorder = null

                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val audioBytes = audioFile?.readBytes() ?: byteArrayOf()
                        val photoBytes = photoFile?.readBytes() ?: byteArrayOf()

                        // Fotoğrafı küçült (Hız için)
                        val fullBitmap = BitmapFactory.decodeByteArray(photoBytes, 0, photoBytes.size)
                        val scaledBitmap = Bitmap.createScaledBitmap(fullBitmap, 1024, 1024 * fullBitmap.height / fullBitmap.width, true)
                        val stream = ByteArrayOutputStream()
                        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                        val compressedPhotoBytes = stream.toByteArray()

                        val inputContent = content {
                            text("image_1.jpg:")
                            blob("image/jpeg", compressedPhotoBytes)
                            text("audio_1.m4a:")
                            blob("audio/mpeg", audioBytes)
                            text("""
                                Sen multimodal bir asistansın. Ekteki 'image_1.jpg' fotoğrafını incele ve 'audio_1.m4a' ses kaydındaki soruyu/isteği bu fotoğrafa göre yanıtla.
                                Eğer sesli bir soru yoksa sadece fotoğrafı özetle. 
                                Yanıtı BAŞLIK: ... ve İÇERİK: ... formatında ver.
                            """.trimIndent())
                        }

                        val response = generativeModel.generateContent(inputContent)
                        val aiResponse = response.text ?: ""
                        val title = if (aiResponse.contains("BAŞLIK:")) aiResponse.substringAfter("BAŞLIK:").substringBefore("İÇERİK:").trim() else "Görsel Analiz"
                        val summary = if (aiResponse.contains("İÇERİK:")) aiResponse.substringAfter("İÇERİK:").trim() else aiResponse

                        withContext(Dispatchers.Main) {
                            webView.evaluateJavascript("onAISummaryReady(${JSONObject.quote(title)}, ${JSONObject.quote(summary)})", null)
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        @JavascriptInterface
        fun pickPDF(city: String) {
            if (refuseWithoutAi()) return
            selectedCityForPdf = city
            runOnUiThread { pdfPickerLauncher.launch("application/pdf") }
        }

        /** Opens the system "save as" screen for [json] (the page's notes); the result goes to `onNotesFileSaved`. */
        @JavascriptInterface
        fun exportNotesFile(json: String, fileName: String) {
            startNotesExport(json, fileName)
        }

        /** Shares a real JSON backup using a temporary, read-only FileProvider URI. */
        @JavascriptInterface
        fun shareNotesBackup(json: String, fileName: String) {
            notesFileScope.launch {
                try {
                    val bytes = NotesFile.exportBytes(json)
                    val safeName = NotesFile.suggestedName(fileName)
                    val folder = File(cacheDir, "shared-backups").apply { mkdirs() }
                    val file = File(folder, safeName)
                    file.writeBytes(bytes)
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
                    runOnUiThread {
                        try {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = NotesFile.MIME
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                clipData = android.content.ClipData.newUri(contentResolver, safeName, uri)
                            }
                            startActivity(Intent.createChooser(send, "Yedeği Paylaş"))
                        } catch (e: Exception) {
                            Log.e(FILE_LOG, "share chooser failed error=${e.javaClass.simpleName}")
                            webView.evaluateJavascript("toast('Paylaşım ekranı açılamadı.')", null)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(FILE_LOG, "share failed error=${e.javaClass.simpleName}")
                    val message = (e as? NotesFile.Refused)?.message ?: "Yedek paylaşıma hazırlanamadı."
                    runOnUiThread { webView.evaluateJavascript("toast(" + JSONObject.quote(message) + ")", null) }
                }
            }
        }

        /** Opens the system file picker; the checked file text goes to `onNotesFileOpened`, nothing is merged here. */
        @JavascriptInterface
        fun pickNotesFile() {
            runOnUiThread {
                try {
                    notesImportLauncher.launch(NotesFile.OPEN_TYPES)
                } catch (e: ActivityNotFoundException) {
                    postFileResult(NotesFile.script(NotesFile.OPENED_CALLBACK, NotesFile.failed("Bu cihazda dosya seçme ekranı bulunamadı.")))
                }
            }
        }

        @JavascriptInterface
        fun shareNote(text: String) {
            runOnUiThread {
                try {
                    val sendIntent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, text)
                        type = "text/plain"
                    }
                    mContext.startActivity(Intent.createChooser(sendIntent, "Paylaş").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) { e.printStackTrace() }
            }
        }

        @JavascriptInterface
        fun scheduleReminder(city: String, message: String, timestamp: Long) {
            val intent = Intent(mContext, ReminderReceiver::class.java).apply {
                putExtra("city", city)
                putExtra("message", message)
            }
            val pendingIntent = PendingIntent.getBroadcast(mContext, System.currentTimeMillis().toInt(), intent, PendingIntent.FLAG_IMMUTABLE)
            val alarmManager = mContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timestamp, pendingIntent)
                else alarmManager.setExact(AlarmManager.RTC_WAKEUP, timestamp, pendingIntent)
            } catch (e: SecurityException) { alarmManager.set(AlarmManager.RTC_WAKEUP, timestamp, pendingIntent) }
        }

        @JavascriptInterface
        fun getGMTPlus3Time(): Long {
            val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("GMT+3"))
            return calendar.timeInMillis
        }

        // Cloud backup: signed-in Google user only, stored under that user's id (never a device model).
        // Results go to `onCloudResult`; restoring goes through the page's JSON import (applyImport).
        @JavascriptInterface
        fun cloudStatus(): String = cloud.status().toString()

        @JavascriptInterface
        fun cloudSetAuto(enabled: Boolean): String {
            cloud.setAuto(enabled)
            return cloud.status().toString()
        }

        @JavascriptInterface
        fun cloudSignIn(requestId: String) {
            if (!CloudScripts.REQUEST_ID.matches(requestId)) return
            // The Google account screen needs this activity; leaving the screen cancels the request.
            lifecycleScope.launch { runCloud("signIn", requestId) { cloud.signIn(this@MainActivity) } }
        }

        @JavascriptInterface
        fun cloudSignOut(requestId: String) {
            if (!CloudScripts.REQUEST_ID.matches(requestId)) return
            lifecycleScope.launch { runCloud("signOut", requestId) { cloud.signOut(this@MainActivity) } }
        }

        /** [notesJson] is the stored `tur_v10` text; Room deletion records are added here. */
        @JavascriptInterface
        fun cloudBackup(notesJson: String, auto: Boolean, requestId: String) {
            if (!CloudScripts.REQUEST_ID.matches(requestId)) return
            LegacyRoomImporter.oversizedPayloadMessage(notesJson)?.let {
                postCloudResult(CloudScripts.result("backup", requestId, null, "Notlar bulut yedeği için çok büyük; JSON dosyasına yedekleyin."))
                return
            }
            if (refusedInRoomFirst("cloud backup", requestId)) {
                postCloudResult(CloudScripts.result("backup", requestId, null, CLOUD_ROOM_FIRST))
                return
            }
            cloudScope.launch {
                runCloud("backup", requestId) { cloud.backup(notesJson, roomDeletionRecords(), BuildConfig.VERSION_NAME, auto) }
            }
        }

        @JavascriptInterface
        fun cloudListBackups(requestId: String) {
            if (!CloudScripts.REQUEST_ID.matches(requestId)) return
            cloudScope.launch { runCloud("list", requestId) { cloud.list() } }
        }

        /**
         * Downloads and verifies one backup for the page's import preview; nothing is stored here.
         * `localDeleted` lists the backup's notes that were deleted on this phone (Room deletion records).
         */
        @JavascriptInterface
        fun cloudFetchBackup(backupId: String, requestId: String) {
            if (!CloudScripts.REQUEST_ID.matches(requestId)) return
            if (refusedInRoomFirst("cloud restore", requestId)) {
                postCloudResult(CloudScripts.result("fetch", requestId, null, CLOUD_ROOM_FIRST))
                return
            }
            cloudScope.launch {
                runCloud("fetch", requestId) {
                    val restored = cloud.fetch(backupId)
                    val deletedHere = roomDeletionRecords().mapTo(HashSet()) { RoomNoteKeys.key(it.city, it.id) }
                    val localDeleted = JSONArray()
                    val notes = JSONObject(restored.notesJson)
                    notes.keys().forEach { city ->
                        val list = notes.optJSONArray(city) ?: return@forEach
                        for (i in 0 until list.length()) {
                            val id = RoomNoteKeys.idFromJson(list.optJSONObject(i)?.opt("id")) ?: continue
                            if (RoomNoteKeys.key(city, id) in deletedHere) localDeleted.put(JSONArray().put(city).put(id))
                        }
                    }
                    JSONObject()
                        .put("backup", restored.meta.toJson())
                        .put("text", restored.notesJson)
                        .put("deleted", restored.deleted.size)
                        .put("localDeleted", localDeleted)
                }
            }
        }

        @JavascriptInterface
        fun checkForUpdate() {
            releaseUpdater.checkForUpdate()
        }

        @JavascriptInterface
        fun downloadUpdate() {
            releaseUpdater.downloadUpdate()
        }

        @JavascriptInterface
        fun installUpdate() {
            releaseUpdater.installUpdate()
        }

        @JavascriptInterface
        fun dismissUpdate() {
            releaseUpdater.dismissUpdate()
        }

        @JavascriptInterface
        fun retryUpdate() {
            releaseUpdater.retryUpdate()
        }

        /** The two switches only; release data and files never come from the page. */
        @JavascriptInterface
        fun updaterSettings(): String = releaseUpdater.settings()

        @JavascriptInterface
        fun setAutoDownloadUpdates(on: Boolean) {
            releaseUpdater.setAutoDownload(on)
        }

        @JavascriptInterface
        fun setPrereleaseUpdates(on: Boolean) {
            releaseUpdater.setPrereleases(on)
        }
    }
}