package com.sehirnotlari

import com.sehirnotlari.data.CityNotesDatabase
import com.sehirnotlari.data.LegacyRoomImporter
import com.sehirnotlari.data.RoomBridgeScripts
import com.sehirnotlari.data.RoomNoteDraft
import com.sehirnotlari.data.RoomNoteImportStore
import com.sehirnotlari.data.RoomNoteKeys
import com.sehirnotlari.data.RoomNoteWriteSequencer
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
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val notesDatabase by lazy { CityNotesDatabase.get(applicationContext) }
    private val noteWriteSequencer by lazy { RoomNoteWriteSequencer.shared(notesDatabase.notes()) }
    private lateinit var releaseUpdater: GithubReleaseUpdater
    private var selectedCityForPdf: String? = null

    // Media
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var photoFile: File? = null
    private var photoUri: Uri? = null

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
        if (granted) webView.reload()
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

    private fun extractTextFromPdf(uri: Uri) {
        val keyPrefix = BuildConfig.GEMINI_API_KEY.take(4)
        Log.d("SehirNotlari", "PDF Analysis started. Key prefix: $keyPrefix")
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
                    Log.e("SehirNotlari", "PDF Analysis: AI returned null text. Full response: $response")
                    throw Exception("AI yanıt vermedi")
                }

                val title = if (aiResponse.contains("BAŞLIK:")) aiResponse.substringAfter("BAŞLIK:").substringBefore("İÇERİK:").trim() else "AI Özeti"
                val summary = if (aiResponse.contains("İÇERİK:")) aiResponse.substringAfter("İÇERİK:").trim() else aiResponse

                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript("onAISummaryReady(${JSONObject.quote(title)}, ${JSONObject.quote(summary)})", null)
                }
            } catch (e: Exception) {
                val stackTrace = Log.getStackTraceString(e)
                Log.e("SehirNotlari", "PDF Error for city $selectedCityForPdf\n$stackTrace")
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

    // Room writes run in a process scope; a recreated activity's page no longer awaits the old result.
    private fun postRoomResult(script: String) {
        runOnUiThread {
            if (!isDestroyed && ::webView.isInitialized) webView.evaluateJavascript(script, null)
        }
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
                    postRoomResult(RoomBridgeScripts.importResult(true, inserted, skipped, null))
                } catch (e: Exception) {
                    Log.e("SehirNotlari", "Room import failed", e)
                    postRoomResult(RoomBridgeScripts.importResult(false, 0, 0, e.message ?: "Room import failed"))
                }
            }
        }

        // A deletion is recorded as a tombstone for future cloud synchronization.
        @JavascriptInterface
        fun deleteRoomNote(city: String, id: String, requestId: String) {
            if (city.isBlank() || id.isBlank() || requestId.isBlank()) return
            val sequencer = noteWriteSequencer
            val ticket = try {
                sequencer.nextTicket()
            } catch (e: Exception) {
                Log.e("SehirNotlari", "Room open failed", e)
                postRoomResult(RoomBridgeScripts.deleteResult(requestId, false, e.message ?: "Room open failed"))
                return
            }
            RoomNoteWriteSequencer.writeScope.launch {
                try {
                    val ack = sequencer.delete(city, id, ticket, requestId)
                    postRoomResult(RoomBridgeScripts.deleteResult(requestId, ack.toBridgeSuccess(), ack.toBridgeError()))
                } catch (e: Exception) {
                    Log.e("SehirNotlari", "Room delete failed", e)
                    postRoomResult(RoomBridgeScripts.deleteResult(requestId, false, e.message ?: "Room delete failed"))
                }
            }
        }

        // Opt-in single-note Room write for end-to-end bridge verification.
        // Does not alter the current localStorage write path.
        @JavascriptInterface
        fun upsertRoomNote(city: String, noteJson: String, requestId: String) {
            if (city.isBlank() || city.length > 120 || noteJson.length > 100_000 || requestId.length > 100) return
            val sequencer = noteWriteSequencer
            val ticket = try {
                sequencer.nextTicket()
            } catch (e: Exception) {
                Log.e("SehirNotlari", "Room open failed", e)
                postRoomResult(RoomBridgeScripts.writeResult(requestId, false, e.message ?: "Room open failed"))
                return
            }
            RoomNoteWriteSequencer.writeScope.launch {
                try {
                    val item = JSONObject(noteJson)
                    val id = RoomNoteKeys.idFromJson(item.opt("id"))
                        ?: throw IllegalArgumentException("Missing note id")
                    val ack = sequencer.upsert(
                        city,
                        id,
                        RoomNoteDraft(
                            title = item.optString("title"),
                            content = item.optString("content"),
                            category = item.optString("category", "Genel"),
                            star = item.optInt("star"),
                            color = item.optString("color"),
                            favorite = item.optBoolean("fav"),
                            dateLabel = item.optString("date"),
                            isAI = item.optBoolean("isAI")
                        ),
                        ticket,
                        requestId.takeIf { it.isNotBlank() }
                    )
                    postRoomResult(RoomBridgeScripts.writeResult(requestId, ack.toBridgeSuccess(), ack.toBridgeError()))
                } catch (e: Exception) {
                    Log.e("SehirNotlari", "Room note upsert failed", e)
                    postRoomResult(RoomBridgeScripts.writeResult(requestId, false, e.message ?: "Room write failed"))
                }
            }
        }

        @JavascriptInterface
        fun requestRoomNotes() {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val result = JSONObject()
                    notesDatabase.notes().allActive().forEach { note ->
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
                    Log.e("SehirNotlari", "Room read failed", e)
                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript(
                            RoomBridgeScripts.notesError(e.message ?: "Room read failed"),
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

            val keyFromConfig = BuildConfig.GEMINI_API_KEY.trim()
            val keyPrefix = if (keyFromConfig.length > 4) keyFromConfig.take(4) else "SHORT"
            Log.d("SehirNotlari", "getCityGuide called for city: $city. Key prefix: $keyPrefix. API: v1")
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val prompt = "Sen profesyonel bir seyahat rehberisin. '$city' şehri için 'Ne yenir?', 'Gezilecek yer' ve 1 cümlelik bilgi ver. HTML (<b>, <br>) kullan. Çok kısa olsun."
                    Log.d("SehirNotlari", "Sending guide prompt: $prompt")
                    val response = guideModel.generateContent(prompt)
                    val responseText = response.text ?: "Bilgi yok"
                    Log.d("SehirNotlari", "AI Guide Response: $responseText")
                    
                    sharedPrefs.edit().putString(city, responseText).apply()
                    
                    withContext(Dispatchers.Main) {
                        webView.evaluateJavascript("onCityGuideReady(${JSONObject.quote(responseText)})", null)
                    }
                } catch (e: Exception) {
                    val stackTrace = Log.getStackTraceString(e)
                    Log.e("SehirNotlari", "Error in getCityGuide for city: $city, Exception: ${e.javaClass.name}, Message: ${e.message}")
                    Log.e("SehirNotlari", "Error StackTrace: $stackTrace")
                    withContext(Dispatchers.Main) {
                        val errorMsg = e.message ?: "Hata oluştu"
                        webView.evaluateJavascript("onCityGuideReady(${JSONObject.quote("<i>Rehber hazırlanamadı: $errorMsg</i>")})", null)
                    }
                }
            }
        }

        @JavascriptInterface
        fun startRecording() {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
            startRecordingInternal()
        }

        @JavascriptInterface
        fun startMultiModalRecording() {
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
            selectedCityForPdf = city
            runOnUiThread { pdfPickerLauncher.launch("application/pdf") }
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

        @JavascriptInterface
        fun backupToCloud(json: String) {
            try {
                val db = FirebaseDatabase.getInstance().reference
                val deviceId = Build.MODEL.replace(" ", "_") + "_" + Build.ID
                db.child("backups").child(deviceId).setValue(json)
                    .addOnSuccessListener { runOnUiThread { webView.evaluateJavascript("toast('✅ Buluta yedeklendi')", null) } }
                    .addOnFailureListener { e -> runOnUiThread { webView.evaluateJavascript("toast('❌ Hata: ${e.message}')", null) } }
            } catch (e: Exception) {
                runOnUiThread { webView.evaluateJavascript("toast('❌ Firebase hazır değil')", null) }
            }
        }

        @JavascriptInterface
        fun restoreFromCloud() {
            try {
                val db = FirebaseDatabase.getInstance().reference
                val deviceId = Build.MODEL.replace(" ", "_") + "_" + Build.ID
                db.child("backups").child(deviceId).get()
                    .addOnSuccessListener { snapshot ->
                        val json = snapshot.value as? String
                        if (json != null) {
                            runOnUiThread { webView.evaluateJavascript("importFromCloud(${JSONObject.quote(json)})", null) }
                        } else {
                            runOnUiThread { webView.evaluateJavascript("toast('⚠️ Yedek bulunamadı')", null) }
                        }
                    }
                    .addOnFailureListener { e -> runOnUiThread { webView.evaluateJavascript("toast('❌ Hata: ${e.message}')", null) } }
            } catch (e: Exception) {
                runOnUiThread { webView.evaluateJavascript("toast('❌ Firebase hazır değil')", null) }
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
            releaseUpdater.installDownloaded()
        }
    }
}