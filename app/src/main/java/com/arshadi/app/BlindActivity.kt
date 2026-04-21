package com.arshadi.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// ─────────────────────────────────────────────────────────────────────────────
enum class BlindState { WELCOME, ASK_DESTINATION, NAVIGATING, IDLE }

class BlindActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    // ── Views ──
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: DetectionOverlayView
    private lateinit var tvStatus: TextView
    private lateinit var tvDebug: TextView
    private lateinit var btnMic: View
    private lateinit var btnDebug: View
    private lateinit var debugPanel: View
    private lateinit var navBar: View
    private lateinit var tvNavDist: TextView

    // ── AI ──
    private lateinit var detector: YoloDetector
    private lateinit var cameraExecutor: ExecutorService
    private var isProcessing = false

    // ── TTS ──
    private lateinit var tts: TextToSpeech
    private var ttsReady  = false
    private var isSpeaking = false

    // ── STT ──
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    // ── GPS ──
    private lateinit var fusedLocation: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    // ── Vibration ──
    private lateinit var vibrator: Vibrator

    // ── State ──
    private var state = BlindState.WELCOME
    private var destinationSet  = false
    private var activeDestination: Destination? = null
    private var remainingMeters = 0.0
    private var lastBucket      = -1
    private var isNavigating    = false

    // ── Alert dedup ──
    private var lastDetKey  = ""
    private var lastDetTime = 0L

    // ── Debug ──
    private var showDebug     = false
    private val debugLines    = ArrayDeque<String>(100)
    private var frameRecv     = 0
    private var frameProc     = 0
    private var frameSkip     = 0
    private var totalDets     = 0
    private var fpsWindowStart= System.currentTimeMillis()
    private var fpsWindowCount= 0
    private var currentFps    = 0f

    // ── Coroutine ──
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val TAG = "BlindActivity"
        private const val PERM_CODE = 100
    }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blind)

        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        tvStatus    = findViewById(R.id.tvStatus)
        tvDebug     = findViewById(R.id.tvDebug)
        btnMic      = findViewById(R.id.btnMic)
        btnDebug    = findViewById(R.id.btnDebug)
        debugPanel  = findViewById(R.id.debugPanel)
        navBar      = findViewById(R.id.navBar)
        tvNavDist   = findViewById(R.id.tvNavDist)

        vibrator      = getSystemService(VIBRATOR_SERVICE) as Vibrator
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        cameraExecutor= Executors.newSingleThreadExecutor()

        btnMic.setOnClickListener { onMicTap() }
        btnDebug.setOnClickListener { toggleDebug() }
        findViewById<View>(R.id.btnBack).setOnClickListener {
            startActivity(Intent(this, UserTypeActivity::class.java))
            finish()
        }

        tts = TextToSpeech(this, this)
        detector = YoloDetector(this)
        scope.launch(Dispatchers.IO) {
            val ok = detector.initialize()
            withContext(Dispatchers.Main) {
                if (ok) requestPermissions()
                else {
                    Toast.makeText(this@BlindActivity, "فشل تحميل الموديل!", Toast.LENGTH_LONG).show()
                    addLog("❌ Detector init failed")
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.language = Locale("ar", "SA")
        tts.setSpeechRate(0.42f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { isSpeaking = true }
            override fun onDone(id: String?)  { isSpeaking = false }
            override fun onError(id: String?) { isSpeaking = false }
        })
        ttsReady = true
        addLog("🔊 TTS ready (ar-SA)")
    }

    // ─────────────────────────────────────────────────────────────────────────
    private fun requestPermissions() {
        val needed = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) onPermissionsGranted()
        else ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERM_CODE)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.all { it == PackageManager.PERMISSION_GRANTED }) onPermissionsGranted()
        else Toast.makeText(this, "الصلاحيات مطلوبة!", Toast.LENGTH_LONG).show()
    }

    private fun onPermissionsGranted() {
        addLog("🔐 Permissions OK")
        startCamera()
        scope.launch {
            delay(500)
            startWelcome()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ✅ CameraX — imageProxy.toBitmap() = native YUV→Bitmap في ~5ms
    // ─────────────────────────────────────────────────────────────────────────
    private fun startCamera() {
        val camProvider = ProcessCameraProvider.getInstance(this)
        camProvider.addListener({
            val provider = camProvider.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(320, 240))  // ✅ لو بدنا أصغر
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // ✅ لا تراكم
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processFrame(imageProxy)
            }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                addLog("📷 Camera started")
            } catch (e: Exception) {
                addLog("❌ Camera error: $e")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ─────────────────────────────────────────────────────────────────────────
    @SuppressLint("UnsafeOptInUsageError")
    private fun processFrame(imageProxy: ImageProxy) {
        frameRecv++

        if (isProcessing || state == BlindState.WELCOME) {
            frameSkip++
            imageProxy.close()
            return
        }

        isProcessing = true
        frameProc++

        try {
            // ✅ toBitmap() = native JNI YUV→ARGB في ~5ms (بدل ~150ms في Dart)
            val bitmap = imageProxy.toBitmap()
            imageProxy.close()

            val dets = detector.detect(bitmap)
            bitmap.recycle()

            // ── FPS ──
            fpsWindowCount++
            val now = System.currentTimeMillis()
            if (now - fpsWindowStart >= 2000) {
                currentFps = fpsWindowCount / ((now - fpsWindowStart) / 1000f)
                fpsWindowCount = 0
                fpsWindowStart = now
            }

            runOnUiThread {
                overlayView.update(dets, imageProxy.width, imageProxy.height)
                updateDebugBar()
                if (dets.isNotEmpty()) {
                    totalDets += dets.size
                    val closest = dets.minByOrNull { it.distance }!!
                    handleDetection(closest)
                }
            }
        } catch (e: Exception) {
            imageProxy.close()
            Log.e(TAG, "Frame error: $e")
        } finally {
            isProcessing = false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    private fun handleDetection(det: DetectionResult) {
        val key = "${det.label}-${det.position}"
        val now = System.currentTimeMillis()
        if (key == lastDetKey && now - lastDetTime < 5000) return
        lastDetKey  = key
        lastDetTime = now

        val distStr = if (det.distance < 1f) "قريب جداً"
        else "${"%.1f".format(det.distance)} متر"

        tvStatus.text = "⚠️ ${det.label} | $distStr"
        addLog("⚠️ ${det.label} | $distStr | ${det.position}")

        // Vibrate
        if (det.distance < 1f) vibrate(longArrayOf(0, 400, 100, 400, 100, 400))
        else if (det.distance < 1.5f) vibrate(longArrayOf(0, 300, 100, 300))
        else vibrate(longArrayOf(0, 150))

        if (isSpeaking && det.distance < 2f) tts.stop()
        speak(Constants.obstacleMsg(det.label, det.position))
    }

    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun startWelcome() {
        withContext(Dispatchers.Main) {
            state = BlindState.WELCOME
            tvStatus.text = "أهلاً وسهلاً"
        }
        speak("أهلاً وسهلاً، أنا أرشدي، رفيقك في كل خطوة.")
        waitTts()

        withContext(Dispatchers.Main) {
            state = BlindState.ASK_DESTINATION
            tvStatus.text = "إلى أين تريد الذهاب؟"
            btnMic.visibility = View.VISIBLE
        }
        speak("وين بدك تروح اليوم؟ الوجهات المتاحة هي: ${Constants.availableDestinationsAr}. اضغط على الشاشة وقل وجهتك.")
        waitTts()

        if (!destinationSet) onMicTap()
    }

    // ─────────────────────────────────────────────────────────────────────────
    private fun onMicTap() {
        if (destinationSet || state != BlindState.ASK_DESTINATION) return
        if (isListening) { stopListening(); return }
        if (isSpeaking) tts.stop()

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("الميكروفون غير متاح."); return
        }
        startListening()
    }

    private fun startListening() {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = recognizer

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-JO")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {
                isListening = true
                runOnUiThread { tvStatus.text = "🎤 أنا أسمعك... تكلّم الآن" }
            }
            override fun onPartialResults(b: Bundle?) {
                val words = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!words.isNullOrBlank()) {
                    runOnUiThread { tvStatus.text = "سمعت: $words" }
                }
            }
            override fun onResults(b: Bundle?) {
                isListening = false
                val words = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                addLog("🎤 STT result: \"$words\"")
                if (!words.isNullOrBlank()) processDestination(words)
                else speak("لم أسمع شيئاً، اضغط مجدداً.")
            }
            override fun onError(error: Int) {
                isListening = false
                addLog("❌ STT error: $error")
                speak("لم أتمكن من السماع، اضغط مجدداً.")
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })

        recognizer.startListening(intent)
        addLog("🎤 Listening started")
    }

    private fun stopListening() {
        speechRecognizer?.stopListening()
        isListening = false
    }

    private fun processDestination(words: String) {
        if (destinationSet) return
        val dest = Constants.findDestination(words)
        if (dest != null) {
            addLog("✅ DEST: ${dest.nameAr}")
            destinationSet  = true
            activeDestination = dest
            scope.launch { startNavigation(dest) }
        } else {
            addLog("❌ Not found: \"$words\"")
            speak("لم أجد الوجهة. اضغط مجدداً.")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun startNavigation(dest: Destination) {
        remainingMeters = Constants.haversineDistance(dest.path.first(), dest.end)
        withContext(Dispatchers.Main) {
            state = BlindState.NAVIGATING
            tvStatus.text = "🗺️ جاري التنقل إلى ${dest.nameAr}"
            btnMic.visibility = View.GONE
            navBar.visibility = View.VISIBLE
            tvNavDist.text = "باقي ${remainingMeters.toInt()} متر لـ${dest.nameAr}"
        }
        speak("تمام! سأرشدك إلى ${dest.nameAr}. المسافة حوالي ${remainingMeters.toInt()} متر. امشِ للأمام وسأحذرك من العوائق.")
        isNavigating = true
        startGpsTracking()
        startSafeTimer()
    }

    @SuppressLint("MissingPermission")
    private fun startGpsTracking() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setMinUpdateIntervalMillis(2000).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val pos  = result.lastLocation ?: return
                val cur  = LatLngPoint(pos.latitude, pos.longitude)
                val dest = activeDestination ?: return
                val dist = Constants.haversineDistance(cur, dest.end)
                remainingMeters = dist
                addLog("📍 GPS: (${pos.latitude.format(5)},${pos.longitude.format(5)}) dist=${dist.toInt()}m")

                runOnUiThread {
                    tvNavDist.text = "باقي ${dist.toInt()} متر لـ${dest.nameAr}"
                }

                if (dist < 12) { onArrived(); return }

                val bucket = (dist / 50).toInt()
                if (bucket != lastBucket && !isSpeaking) {
                    lastBucket = bucket
                    speak(if (dist > 50) "باقي حوالي ${dist.toInt()} متر."
                    else "اقتربت، باقي ${dist.toInt()} متر فقط.")
                }
            }
        }
        fusedLocation.requestLocationUpdates(request, locationCallback!!, mainLooper)
    }

    private fun startSafeTimer() {
        scope.launch {
            while (isNavigating) {
                delay(12000)
                if (isNavigating && !isSpeaking) speak("الطريق أمامك آمن، تابع بثقة.")
            }
        }
    }

    private fun onArrived() {
        isNavigating = false
        locationCallback?.let { fusedLocation.removeLocationUpdates(it) }
        vibrate(longArrayOf(0, 500, 200, 500, 200, 500))
        runOnUiThread {
            state = BlindState.IDLE
            tvStatus.text = "✅ وصلت!"
            navBar.visibility = View.GONE
        }
        speak("وصلت إلى ${activeDestination?.nameAr ?: "وجهتك"} بنجاح! أحسنت، أتمنى لك يوماً رائعاً.")
    }

    // ─────────────────────────────────────────────────────────────────────────
    private fun speak(text: String) {
        if (!ttsReady || text.isBlank()) return
        addLog("🔊 TTS: \"${text.take(60)}\"")
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_${System.currentTimeMillis()}")
    }

    private suspend fun waitTts() {
        var safety = 0
        while (isSpeaking && safety < 100) { delay(100); safety++ }
        delay(400)
    }

    private fun vibrate(pattern: LongArray) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ✅ Debug helpers
    // ─────────────────────────────────────────────────────────────────────────
    private fun toggleDebug() {
        showDebug = !showDebug
        debugPanel.visibility = if (showDebug) View.VISIBLE else View.GONE
        updateDebugLog()
    }

    private fun addLog(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date())
        debugLines.addFirst("[$time] $msg")
        if (debugLines.size > 100) debugLines.removeLast()
        if (showDebug) runOnUiThread { updateDebugLog() }
    }

    private fun updateDebugLog() {
        tvDebug.text = debugLines.take(30).joinToString("\n")
    }

    private fun updateDebugBar() {
        if (!showDebug) return
        val bar = "FPS:${"%.1f".format(currentFps)} | " +
                "Infer:${detector.lastInferenceMs}ms | " +
                "Recv:$frameRecv Proc:$frameProc Skip:$frameSkip | " +
                "Dets:$totalDets"
        addLog("📊 $bar")
    }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onDestroy() {
        scope.cancel()
        cameraExecutor.shutdown()
        locationCallback?.let { fusedLocation.removeLocationUpdates(it) }
        speechRecognizer?.destroy()
        tts.stop(); tts.shutdown()
        detector.close()
        super.onDestroy()
    }

    private fun Double.format(digits: Int) = "%.${digits}f".format(this)
}
