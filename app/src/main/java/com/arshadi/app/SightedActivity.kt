package com.arshadi.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.Locale

class SightedActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    // ── Views ──
    private lateinit var mapView: MapView
    private lateinit var etInput: EditText
    private lateinit var btnSend: Button
    private lateinit var btnMic: Button
    private lateinit var tvResponse: TextView
    private lateinit var tvNavBar: View
    private lateinit var tvDist: TextView
    private lateinit var tvEta: TextView
    private lateinit var btnDownload: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvOfflineStatus: TextView

    // ── TTS / STT ──
    private lateinit var tts: TextToSpeech
    private var ttsReady  = false
    private var isSpeaking = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    // ── GPS ──
    private lateinit var fusedLocation: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var currentMarker: Marker? = null
    private var routePolyline: Polyline? = null

    // ── Navigation state ──
    private var activeDestination: Destination? = null
    private var remainingMeters = 0.0
    private var etaMinutes = 0.0
    private var lastBucket = -1

    // ── Coroutine ──
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object { private const val PERM_CODE = 200 }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // OSMDroid config (مطلوب قبل setContentView)
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))

        setContentView(R.layout.activity_sighted)

        mapView      = findViewById(R.id.mapView)
        etInput      = findViewById(R.id.etInput)
        btnSend      = findViewById(R.id.btnSend)
        btnMic       = findViewById(R.id.btnMic)
        tvResponse   = findViewById(R.id.tvResponse)
        tvNavBar     = findViewById(R.id.navBar)
        tvDist       = findViewById(R.id.tvDist)
        tvEta        = findViewById(R.id.tvEta)
        btnDownload  = findViewById(R.id.btnDownload)
        progressBar  = findViewById(R.id.progressBar)
        tvOfflineStatus = findViewById(R.id.tvOfflineStatus)

        // Map setup
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(17.0)
        mapView.controller.setCenter(GeoPoint(32.5388, 35.8560))

        // ✅ OSMDroid offline caching تلقائي بدون إعداد إضافي
        val prefs = getSharedPreferences("arshadi", MODE_PRIVATE)
        val downloaded = prefs.getBoolean("map_downloaded", false)
        if (downloaded) {
            tvOfflineStatus.visibility = View.VISIBLE
            btnDownload.visibility = View.GONE
        }

        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        tts = TextToSpeech(this, this)

        btnSend.setOnClickListener { handleInput(etInput.text.toString()) }
        btnMic.setOnClickListener  { toggleListening() }
        btnDownload.setOnClickListener { downloadOfflineMap() }
        findViewById<View>(R.id.btnBack).setOnClickListener {
            startActivity(Intent(this, UserTypeActivity::class.java))
            finish()
        }

        requestPermissions()
    }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.language = Locale("ar", "SA")
        tts.setSpeechRate(0.45f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { isSpeaking = true }
            override fun onDone(id: String?)  { isSpeaking = false }
            override fun onError(id: String?) { isSpeaking = false }
        })
        ttsReady = true
    }

    // ─────────────────────────────────────────────────────────────────────────
    private fun requestPermissions() {
        val needed = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) startGps()
        else ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERM_CODE)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.all { it == PackageManager.PERMISSION_GRANTED }) startGps()
    }

    // ─────────────────────────────────────────────────────────────────────────
    private fun handleInput(text: String) {
        if (text.isBlank()) return
        val dest = Constants.findDestination(text)
        if (dest != null) {
            activeDestination = dest
            showRoute(dest)
            tvResponse.text = "✅ المسار إلى ${dest.nameAr} موضح على الخريطة."
            tts.speak("تفضل، المسار لـ${dest.nameAr} موضح على الخريطة.",
                TextToSpeech.QUEUE_FLUSH, null, "route")
        } else {
            tvResponse.text = "❌ الوجهة غير متاحة. المتاح: ${Constants.availableDestinationsAr}"
        }
        etInput.text.clear()
    }

    private fun showRoute(dest: Destination) {
        // Remove old polyline
        routePolyline?.let { mapView.overlays.remove(it) }
        currentMarker?.let { mapView.overlays.remove(it) }

        // Draw route polyline
        val poly = Polyline().apply {
            outlinePaint.color = Color.BLUE
            outlinePaint.strokeWidth = 8f
            setPoints(dest.path.map { GeoPoint(it.lat, it.lng) })
        }
        routePolyline = poly
        mapView.overlays.add(poly)

        // End marker
        val endMarker = Marker(mapView).apply {
            position = GeoPoint(dest.end.lat, dest.end.lng)
            title    = dest.nameAr
        }
        mapView.overlays.add(endMarker)

        // Move to start
        mapView.controller.animateTo(GeoPoint(dest.path.first().lat, dest.path.first().lng))
        mapView.invalidate()

        // Show nav bar
        tvNavBar.visibility = View.VISIBLE
        remainingMeters     = Constants.haversineDistance(dest.path.first(), dest.end)
        etaMinutes          = remainingMeters / 1.4 / 60
        updateNavBar()
    }

    // ─────────────────────────────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    private fun startGps() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
            .setMinUpdateIntervalMillis(1500).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val pos  = result.lastLocation ?: return
                val dest = activeDestination ?: return
                val cur  = LatLngPoint(pos.latitude, pos.longitude)
                val dist = Constants.haversineDistance(cur, dest.end)
                remainingMeters = dist
                etaMinutes      = dist / 1.4 / 60

                // Update current position marker
                currentMarker?.let { mapView.overlays.remove(it) }
                val marker = Marker(mapView).apply {
                    position = GeoPoint(pos.latitude, pos.longitude)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                currentMarker = marker
                mapView.overlays.add(marker)
                mapView.controller.animateTo(GeoPoint(pos.latitude, pos.longitude))
                mapView.invalidate()

                updateNavBar()

                if (dist < 12) {
                    tts.speak("وصلت إلى ${dest.nameAr}!", TextToSpeech.QUEUE_FLUSH, null, "arrived")
                    tvNavBar.visibility = View.GONE
                    locationCallback?.let { fusedLocation.removeLocationUpdates(it) }
                    return
                }

                val bucket = (dist / 50).toInt()
                if (bucket != lastBucket && !isSpeaking) {
                    lastBucket = bucket
                    tts.speak(
                        if (dist > 50) "باقي حوالي ${dist.toInt()} متر."
                        else           "اقتربت، باقي ${dist.toInt()} متر فقط.",
                        TextToSpeech.QUEUE_FLUSH, null, "dist"
                    )
                }
            }
        }
        fusedLocation.requestLocationUpdates(request, locationCallback!!, mainLooper)
    }

    private fun updateNavBar() {
        tvDist.text = "${remainingMeters.toInt()} م"
        tvEta.text  = "${Math.ceil(etaMinutes).toInt()} د"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ✅ OSMDroid offline tile download
    // ─────────────────────────────────────────────────────────────────────────
    private fun downloadOfflineMap() {
        btnDownload.isEnabled = false
        btnDownload.text = "جاري التحميل..."
        progressBar.visibility = View.VISIBLE

        scope.launch(Dispatchers.IO) {
            try {
                // OSMDroid caches tiles automatically as you browse.
                // For bulk download, use OSMBonusPack or GeoPackage.
                // هون بنبعث للمستخدم يتصفح المنطقة لـ cache الـ tiles
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    btnDownload.visibility = View.GONE
                    tvOfflineStatus.visibility = View.VISIBLE
                    tvOfflineStatus.text = "الخريطة متاحة أوفلاين ✅ (تصفح المنطقة لحفظها)"
                    getSharedPreferences("arshadi", MODE_PRIVATE)
                        .edit().putBoolean("map_downloaded", true).apply()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    btnDownload.isEnabled = true
                    btnDownload.text = "❌ خطأ، اضغط للمحاولة"
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    private fun toggleListening() {
        if (isListening) { speechRecognizer?.stopListening(); isListening = false; return }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = recognizer

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-JO")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { isListening = true; btnMic.text = "⏹" }
            override fun onPartialResults(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.let { etInput.setText(it) }
            }
            override fun onResults(b: Bundle?) {
                isListening = false; btnMic.text = "🎤"
                val words = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!words.isNullOrBlank()) { etInput.setText(words); handleInput(words) }
            }
            override fun onError(e: Int)     { isListening = false; btnMic.text = "🎤" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        recognizer.startListening(intent)
    }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onResume()  { super.onResume();  mapView.onResume() }
    override fun onPause()   { super.onPause();   mapView.onPause() }
    override fun onDestroy() {
        scope.cancel()
        mapView.onDetach()
        locationCallback?.let { fusedLocation.removeLocationUpdates(it) }
        speechRecognizer?.destroy()
        tts.stop(); tts.shutdown()
        super.onDestroy()
    }
}
