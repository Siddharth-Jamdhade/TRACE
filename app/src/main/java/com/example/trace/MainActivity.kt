package com.example.trace

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.trace.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TRACE — Main Activity
 *
 * The sensor hub.  On launch it requests Camera + Mic permissions, then
 * starts three independent sensor pipelines:
 *
 *   1. CameraX Preview  — live viewfinder
 *   2. CameraX ImageAnalysis — grayscale frame differencing -> camera Observations
 *   3. MediaRecorder amplitude polling (every 150 ms) -> audio Observations
 *   4. SensorManager accelerometer -> motion Observations
 *
 * Every Observation that crosses a threshold flows through:
 *   EventExtractor.extract()  ->  cooldown gate  ->  DB insert  ->
 *   FusionEngine.buildEnrichedEvent()  ->  HashChain.computeHash()  ->
 *   DB update  ->  evidence clip save
 *
 * ---------- TUNING GUIDE ----------
 * Run the app in a quiet room and watch Logcat for "RAW jerk" and
 * "RAW amplitude" messages.  Adjust the four constants below until:
 *   - Idle jerk stays < MOTION_THRESHOLD
 *   - A hard tap/drop crosses MOTION_THRESHOLD cleanly
 *   - Background noise stays < AUDIO_THRESHOLD
 *   - A clap/buzzer crosses AUDIO_THRESHOLD cleanly
 * Camera thresholds (CAMERA_CHANGE_*) are tuned by waving in front of
 * the lens and watching "RAW frame change" logs.
 * ----------------------------------
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var database: TraceDatabase

    // One coroutine scope for all background DB/IO work, cancelled in onDestroy.
    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Permissions ───────────────────────────────────────────────────────
    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )
    private val PERMISSION_REQUEST_CODE = 100

    // ── Microphone ────────────────────────────────────────────────────────
    private var mediaRecorder: MediaRecorder? = null
    private var isRecorderStarted = false
    private val amplitudeHandler = Handler(Looper.getMainLooper())

    // ── Motion ────────────────────────────────────────────────────────────
    private lateinit var sensorManager: SensorManager
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var lastZ = Float.NaN
    private var lastMotionJerk = 0f

    // ── Camera analysis ───────────────────────────────────────────────────
    private var previousFrame: ByteArray? = null
    // Dedicated single-thread executor so ImageAnalysis never blocks the main thread.
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // ── Feature C: Evidence Lock ──────────────────────────────────────────
    // Shared with TimelineActivity via SharedPreferences key "evidence_locked"
    private val evidenceLocked = AtomicBoolean(false)
    private val PREFS_NAME     = "trace_prefs"
    private val KEY_LOCKED     = "evidence_locked"

    // ── Feature B: Notifications ──────────────────────────────────────────
    private val NOTIF_CHANNEL_ID = "trace_confirmed"
    private var notifId          = 1000

    // ── Tuning constants ─────────────────────────────────────────────
    // Motion: idle jerk < 1. A firm tap peaks ~5, a hard shake ~15-25.
    // Old values (16.25 / 65) required an extreme shake to fire — now lowered.
    private val MOTION_THRESHOLD = 3.0f
    private val MOTION_MAX       = 20.0f

    // Audio: retune in a quiet room using RAW amplitude Logcat output.
    private val AUDIO_THRESHOLD  = 1500
    private val AUDIO_MAX        = 16147

    // Camera: fraction of sampled pixels that changed by more than 30 grey levels.
    private val CAMERA_CHANGE_THRESHOLD = 0.05f   // 5%  -> first event label
    private val CAMERA_MAX_CHANGE       = 0.30f   // 30% -> max confidence

    // Set to false for demo — silences the Logcat amplitude/jerk flood.
    // Set to true only when tuning thresholds in Android Studio.
    private val DEBUG_RAW_VALUES = false

    // ── Event cooldown ────────────────────────────────────────────────────
    // ConcurrentHashMap because camera (cameraExecutor thread), audio (main), and
    // motion (main) all write to this map concurrently.
    private val lastEventTime = ConcurrentHashMap<String, Long>()
    private val EVENT_COOLDOWN_MS = 1000L

    // Running event count shown live on screen (updated on main thread only)
    private var eventCount = 0

    // ── Accelerometer listener ────────────────────────────────────────────
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            // Skip the first reading — lastX is NaN on startup and would produce
            // a massive fake jerk from 0 → ~9.8 m/s² (gravity) that blocks real events.
            if (lastX.isNaN()) { lastX = x; lastY = y; lastZ = z; return }
            val dX = x - lastX
            val dY = y - lastY
            val dZ = z - lastZ
            val jerk = kotlin.math.sqrt(dX * dX + dY * dY + dZ * dZ)
            lastMotionJerk = jerk
            if (DEBUG_RAW_VALUES) Log.d("TRACE", "RAW jerk = $jerk")
            val confidence = toConfidence(jerk, MOTION_THRESHOLD, MOTION_MAX)
            if (confidence > 0f) {
                onObservation(Observation("motion", confidence, System.currentTimeMillis()))
            }
            lastX = x; lastY = y; lastZ = z
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // KEEP SCREEN ON during demo so the OS never kills TRACE mid-recording.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        database = TraceDatabase.getInstance(this)

        // Feature C: restore lock state from SharedPreferences (survives app restart)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        evidenceLocked.set(prefs.getBoolean(KEY_LOCKED, false))

        // Feature B: Create notification channel (required on Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "TRACE Confirmed Events",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Fires when TRACE confirms an incident via multi-sensor fusion"
                enableVibration(true)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        // Feature B: Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }

        // Load existing event count from DB
        appScope.launch {
            val count = database.eventDao().getAllEvents().size
            runOnUiThread {
                eventCount = count
                updateStatusBar()
            }
        }

        binding.btnTimeline.setOnClickListener {
            startActivity(Intent(this, TimelineActivity::class.java))
        }

        binding.btnAnalyseVideo.setOnClickListener {
            startActivity(Intent(this, VideoImportActivity::class.java))
        }

        if (allPermissionsGranted()) startTRACE()
        else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (allPermissionsGranted()) startTRACE()
            else binding.statusText.text = "Permissions denied. TRACE needs Camera + Mic to operate."
        }
    }

    private fun startTRACE() {
        updateStatusBar()
        startCamera()
        startMicMonitoring()
        startMotionMonitoring()
    }

    /** Updates the on-screen status bar — this is what judges see on the phone. */
    private fun updateStatusBar(lastEventType: String? = null, lastStatus: String? = null) {
        val line1 = if (lastEventType != null)
            "LAST EVENT: $lastEventType  [$lastStatus]"
        else
            "TRACE ACTIVE — watching all sensors"
        val line2 = "Events recorded: $eventCount   |   Tap  View Timeline  to review"
        binding.statusText.text = "$line1\n$line2"
    }

    override fun onPause() {
        super.onPause()
        // Unregister while screen is off to save battery.
        if (::sensorManager.isInitialized) {
            sensorManager.unregisterListener(sensorListener)
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-register when app comes back to foreground.
        if (::sensorManager.isInitialized) {
            val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (accel != null) {
                sensorManager.registerListener(sensorListener, accel, SensorManager.SENSOR_DELAY_GAME)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        amplitudeHandler.removeCallbacksAndMessages(null)
        if (::sensorManager.isInitialized) sensorManager.unregisterListener(sensorListener)
        try {
            if (isRecorderStarted) mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.w("TRACE", "Recorder stop failed", e)
        }
        mediaRecorder?.release()
        mediaRecorder = null
        cameraExecutor.shutdown()
        appScope.cancel()
    }

    // ── Camera ────────────────────────────────────────────────────────────

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                // Use-case 1: visible preview
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
                }

                // Use-case 2: frame analysis for visual change detection
                @Suppress("DEPRECATION")
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(320, 240))   // low-res is enough for diff
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    analyzeFrame(imageProxy)
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e("TRACE", "Camera failed", e)
                binding.statusText.text = "Camera failed: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Grayscale frame differencing.
     * Samples every 4th pixel of the Y-plane for speed, computes the fraction
     * of pixels that changed by more than 30 grey levels, and emits a camera
     * Observation if it exceeds the threshold.
     */
    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            val buffer = imageProxy.planes[0].buffer   // Y plane = grayscale
            val data   = ByteArray(buffer.remaining())
            buffer.get(data)

            val prev = previousFrame
            if (prev != null && prev.size == data.size) {
                var changedSamples = 0
                var i = 0
                while (i < data.size) {
                    val diff = kotlin.math.abs(
                        (data[i].toInt() and 0xFF) - (prev[i].toInt() and 0xFF)
                    )
                    if (diff > 30) changedSamples++
                    i += 4   // sample every 4th pixel
                }
                val totalSamples = data.size / 4
                val changeRatio  = changedSamples.toFloat() / totalSamples
                if (DEBUG_RAW_VALUES) Log.d("TRACE", "RAW frame change = ${"%.3f".format(changeRatio)}")

                val confidence = toConfidence(changeRatio, CAMERA_CHANGE_THRESHOLD, CAMERA_MAX_CHANGE)
                if (confidence > 0f) {
                    onObservation(Observation("camera", confidence, System.currentTimeMillis()))
                }
            }
            previousFrame = data
        } finally {
            imageProxy.close()   // MUST always close, even on exception
        }
    }

    // ── Microphone ────────────────────────────────────────────────────────

    private fun startMicMonitoring() {
        val outputFile = File(cacheDir, "temp_audio.amr")

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }

        mediaRecorder = recorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            setOutputFile(outputFile.absolutePath)
            try {
                prepare()
                start()
                isRecorderStarted = true
            } catch (e: Exception) {
                Log.e("TRACE", "Mic failed", e)
                binding.statusText.text = "Mic failed: ${e.message}"
            }
        }
        pollAmplitude()
    }

    private fun pollAmplitude() {
        amplitudeHandler.postDelayed({
            if (isRecorderStarted) {
                val amplitude = mediaRecorder?.maxAmplitude ?: 0
                if (DEBUG_RAW_VALUES) Log.d("TRACE", "RAW amplitude = $amplitude")

                val confidence = toConfidence(amplitude.toFloat(), AUDIO_THRESHOLD.toFloat(), AUDIO_MAX.toFloat())
                if (confidence > 0f) {
                    onObservation(Observation("audio", confidence, System.currentTimeMillis()))
                }
                // Status bar is updated in updateStatusBar() only when real events fire.
                // We don't overwrite it every 150ms with raw numbers during the demo.
            }
            pollAmplitude()
        }, 150)
    }

    // ── Motion ────────────────────────────────────────────────────────────

    private fun startMotionMonitoring() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer == null) {
            Log.e("TRACE", "No accelerometer on this device")
            return
        }
        sensorManager.registerListener(sensorListener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
    }

    // ── Confidence mapping ────────────────────────────────────────────────

    /** Maps a raw [value] to 0.0-1.0, returning 0 if below [threshold]. */
    private fun toConfidence(value: Float, threshold: Float, max: Float): Float {
        if (value < threshold) return 0f
        return ((value - threshold) / (max - threshold)).coerceIn(0f, 1f)
    }

    // ── Observation -> Event -> Storage pipeline ──────────────────────────

    private fun onObservation(obs: Observation) {
        // Feature C: if evidence is locked, silently drop new observations
        if (evidenceLocked.get()) return

        val eventType = EventExtractor.extract(obs) ?: return

        // Cooldown gate: same event type fires at most once per second.
        val lastTime = lastEventTime[eventType] ?: 0L
        if (obs.timestamp - lastTime < EVENT_COOLDOWN_MS) return
        lastEventTime[eventType] = obs.timestamp

        val baseEvent = Event(
            type      = eventType,
            timestamp = obs.timestamp,
            source    = obs.source,
            confidence = obs.confidence
        )

        appScope.launch {

            // Step 0: capture previous chain tip BEFORE inserting new event
            val prevLastEvent = database.eventDao().getLastEvent()
            val prevHash      = prevLastEvent?.hash ?: HashChain.GENESIS_HASH

            // Step 1: insert base event to obtain an auto-generated ID
            val newId = database.eventDao().insert(baseEvent)

            // Step 2: fetch all events in the 2-second fusion window around this event
            val windowStart  = obs.timestamp - FusionEngine.FUSION_WINDOW_MS
            val windowEnd    = obs.timestamp + FusionEngine.FUSION_WINDOW_MS
            val windowEvents = database.eventDao().getEventsInWindow(windowStart, windowEnd)

            // Step 3: build enriched event — fills per-sensor conf values + fused status
            val enriched = FusionEngine.buildEnrichedEvent(baseEvent.copy(id = newId), windowEvents)

            // Step 4: compute SHA-256 chain hash
            val hash       = HashChain.computeHash(enriched, prevHash)
            val finalEvent = enriched.copy(hash = hash, previousHash = prevHash)

            // Step 5: persist enriched + hashed event
            database.eventDao().update(finalEvent)

            Log.d("TRACE", "EVENT SAVED: id=$newId type=$eventType status=${finalEvent.status}")

            // Update the phone screen counter + last event banner immediately
            runOnUiThread {
                eventCount++
                updateStatusBar(
                    lastEventType = eventType.replace("_", " "),
                    lastStatus    = finalEvent.status
                )
                // Feature A: update live sensor confidence HUD
                updateLiveHud(obs.source, obs.confidence)
            }

            // Feature B: fire a push notification when multi-sensor fusion CONFIRMS an incident
            if (finalEvent.status == "CONFIRMED") {
                fireConfirmedNotification(eventType, finalEvent.cameraConfidence,
                    finalEvent.audioConfidence, finalEvent.motionConfidence)
            }

            // Step 6: save a copy of the current audio clip as evidence (best-effort)
            val clipPath = saveEvidenceClip(newId)
            if (clipPath != null) {
                database.eventDao().update(finalEvent.copy(evidenceClipPath = clipPath))
            }

            // Step 7: retroactively update the status of all events in the window
            // so that a motion event recorded 1 s ago gets promoted to CONFIRMED when
            // an audio event fires now.
            val updatedWindow = database.eventDao().getEventsInWindow(windowStart, windowEnd)
            val fusedStatus   = FusionEngine.determineStatus(updatedWindow)
            updatedWindow.forEach { e ->
                if (e.status != fusedStatus) {
                    database.eventDao().updateStatus(e.id, fusedStatus)
                }
            }
        }
    }

    /**
     * Saves a playable audio evidence clip for the given event.
     *
     * Strategy: stop the live recorder → copy the fully-terminated file →
     * restart the recorder to a fresh temp file. This guarantees MediaPlayer
     * can decode the copy without a missing/truncated AMR header.
     */
    private fun saveEvidenceClip(eventId: Long): String? {
        return try {
            val source = File(cacheDir, "temp_audio.amr")
            if (!source.exists() || source.length() == 0L) return null

            // ── Stop recorder so the file header is properly finalised ──────
            try {
                if (isRecorderStarted) {
                    mediaRecorder?.stop()
                    isRecorderStarted = false
                }
            } catch (stopEx: Exception) {
                Log.w("TRACE", "Recorder stop before copy failed", stopEx)
            }

            // ── Copy the now-closed file as evidence ─────────────────────────
            val destDir = File(getExternalFilesDir(null), "TRACE").also { it.mkdirs() }
            val dest    = File(destDir, "evidence_$eventId.amr")
            source.copyTo(dest, overwrite = true)

            // ── Restart recording to a fresh temp file ───────────────────────
            restartRecorder()

            dest.absolutePath
        } catch (e: Exception) {
            Log.e("TRACE", "Evidence clip save failed", e)
            restartRecorder()   // always try to keep recording
            null
        }
    }

    /** Releases the old MediaRecorder and starts a fresh one. */
    private fun restartRecorder() {
        try {
            mediaRecorder?.release()
            mediaRecorder = null
            isRecorderStarted = false
            val newFile = File(cacheDir, "temp_audio.amr")
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(newFile.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            isRecorderStarted = true
        } catch (e: Exception) {
            Log.e("TRACE", "Recorder restart failed", e)
        }
    }

    // ── Feature A: Live Sensor Confidence HUD ────────────────────────────

    /** Updates the coloured confidence readouts on the main HUD. Must be called on UI thread. */
    private fun updateLiveHud(source: String, confidence: Float) {
        val pct = "${"%.0f".format(confidence * 100)}%"
        when (source) {
            "camera" -> binding.tvCameraLive.text = "📷 $pct"
            "audio"  -> binding.tvAudioLive.text  = "🔊 $pct"
            "motion" -> binding.tvMotionLive.text  = "📳 $pct"
        }
    }

    // ── Feature B: CONFIRMED Push Notification ────────────────────────────

    /** Fires a high-priority system notification when multi-sensor fusion confirms an incident. */
    private fun fireConfirmedNotification(
        eventType: String,
        camConf: Float?,
        audioConf: Float?,
        motionConf: Float?
    ) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return

        val title = "CONFIRMED: ${eventType.replace("_", " ").replaceFirstChar { it.uppercase() }}"
        val body  = buildString {
            if (camConf    != null) append("📷 ${"%.0f".format(camConf    * 100)}%  ")
            if (audioConf  != null) append("🔊 ${"%.0f".format(audioConf  * 100)}%  ")
            if (motionConf != null) append("📳 ${"%.0f".format(motionConf * 100)}%")
        }.trim().ifEmpty { "Multi-sensor agreement detected" }

        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        getSystemService(NotificationManager::class.java).notify(notifId++, notification)
    }
}

