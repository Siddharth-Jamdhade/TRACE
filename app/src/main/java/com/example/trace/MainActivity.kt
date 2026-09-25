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
import android.util.Log
import android.util.Size
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
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
 *   EventExtractor.extract()  ->  cooldown gate  ->
 *   ChainWriter.append(session, ...)  ->  DB insert  ->
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

    /**
     * Session this screen is recording into.
     *
     * Written from [appScope] and read from the UI thread by the status chip, so
     * it is volatile. Only ever holds a fully-hashed session.
     */
    @Volatile
    private var activeSession: Session? = null

    /** Ticks the app bar timer; only alive while this screen is resumed. */
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var statusTicker: Job? = null

    /** Palette this activity was created with, to spot a change made in Settings. */
    private var wallpaperPaletteAtCreate: Boolean? = null

    // One coroutine scope for all background DB/IO work, cancelled in onDestroy.
    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Permissions ───────────────────────────────────────────────────────
    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )
    private val PERMISSION_REQUEST_CODE = 100

    // ── Microphone ────────────────────────────────────────────────────────
    //
    // The recorder is owned by ONE thread, [audioDispatcher], because
    // MediaRecorder is not thread-safe: maxAmplitude() is only legal between
    // start() and stop(), and this pipeline stops and recreates the recorder to
    // freeze each evidence clip.
    //
    // Previously the DB/IO coroutine stopped and recreated the recorder while the
    // main thread polled maxAmplitude() on that same instance (an uncaught
    // IllegalStateException on the demo phone), and two events arriving together
    // could both restart it — leaking a recorder, copying a half-written clip, and
    // leaving audio capture silently dead for the rest of the session.
    //
    // Everything that touches [mediaRecorder] or [recorderStarted] runs on
    // [audioDispatcher]. Do not read or write them from anywhere else.
    private val audioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "trace-audio").apply { isDaemon = true }
    }
    private val audioDispatcher = audioExecutor.asCoroutineDispatcher()

    private var mediaRecorder: MediaRecorder? = null
    private var recorderStarted = false

    /** Audio thread only. Set during teardown so a late clip save cannot reopen the mic. */
    private var audioTornDown = false

    private val TEMP_AUDIO_FILE = "temp_audio.amr"
    private val EVIDENCE_DIR = "TRACE"
    private val AMPLITUDE_POLL_MS = 150L

    /** Number of polls (~1 s at [AMPLITUDE_POLL_MS]) between mic reopen attempts. */
    private val REOPEN_RETRY_POLLS = 7

    // ── Motion ────────────────────────────────────────────────────────────
    private lateinit var sensorManager: SensorManager
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var lastZ = Float.NaN
    private var lastMotionJerk = 0f

    // ── Extended sensor array (magnetometer, barometer, light, linear, gyro,
    //    step, sigmotion). All feed the same Observation pipeline.
    private val baseline = RollingBaseline()
    /** Magnetometer listener — steel doors and large metal objects near the phone. */
    private lateinit var magnetListener: SensorEventListener
    private lateinit var baroListener: SensorEventListener
    private lateinit var linearListener: SensorEventListener
    private lateinit var gyroListener: SensorEventListener
    private lateinit var stepListener: SensorEventListener
    private var sigMotionTriggeredAt = 0L

    // ── Camera analysis ───────────────────────────────────────────────────
    private var previousFrame: ByteArray? = null
    // Dedicated single-thread executor so ImageAnalysis never blocks the main thread.
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // ── Feature C: Evidence Lock ──────────────────────────────────────────
    // Shared with TimelineActivity + VideoImportActivity via [EvidenceLock].
    private val evidenceLocked = AtomicBoolean(false)

    // ── Feature B: Notifications ──────────────────────────────────────────
    // Silent channel — see onCreate. The ID changed from "trace_confirmed"
    // because a channel's sound/vibration settings are immutable after
    // creation, so a fresh ID is the only way to make an existing install quiet.
    private val NOTIF_CHANNEL_ID        = "trace_alerts_v2"
    private val LEGACY_NOTIF_CHANNEL_ID = "trace_confirmed"
    private var notifId                 = 1000

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

    /** App bar status refresh interval. */
    private val STATUS_TICK_MS = 1_000L

    // ── Event cooldown ────────────────────────────────────────────────────
    // ConcurrentHashMap because camera (cameraExecutor thread), audio (main), and
    // motion (main) all write to this map concurrently.
    private val lastEventTime = ConcurrentHashMap<String, Long>()
    private val EVENT_COOLDOWN_MS = 1000L

    // Running event count shown live on screen (updated on main thread only)
    private var eventCount = 0

    /** The open manual-tag chooser, if any. Stops a double tap stacking two dialogs. */
    private var tagDialog: AlertDialog? = null

    /**
     * What an operator can assert they saw.
     *
     * These reuse the extractor's event-type vocabulary on purpose, so a tagged
     * fall lands in the same "list all falls" answer as a camera or linear-
     * acceleration fall, and cross-checks against nearby sensor evidence work.
     * The final entry is an escape hatch: a person can see things none of the
     * ten sensors in this app model.
     */
    private val MANUAL_TAG_LABELS = listOf(
        "object_falls"   to "Object fell",
        "impact"         to "Impact / collision",
        "door_slam"      to "Door opened or shut",
        "alarm"          to "Alarm / raised voice",
        "object_moves"   to "Object moved",
        "person_present" to "Person present",
        "lights_off"     to "Lights off",
        "other"          to "Something else"
    )

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

        // The app bar is this screen's only navigation surface. The menu is wired
        // straight to the toolbar instead of through setSupportActionBar: this is a
        // custom top row with its own brand label, and an ActionBar would also push
        // the activity label into the toolbar, duplicating "TRACE".
        binding.toolbar.inflateMenu(R.menu.menu_dashboard)
        binding.toolbar.setOnMenuItemClickListener { item -> onAppBarAction(item.itemId) }

        // Remembered so a palette change made in Settings can be picked up on the
        // way back, since dynamic colour is applied at activity creation.
        wallpaperPaletteAtCreate = TraceAppearance.useWallpaperPalette(this)

        // KEEP SCREEN ON during demo so the OS never kills TRACE mid-recording.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        database = TraceDatabase.getInstance(this)

        // Feature C: restore lock state (survives app restart)
        evidenceLocked.set(EvidenceLock.isLocked(this))

        // Feature B: notification channel (required on Android 8+).
        //
        // TRACE must never make noise or vibrate during a live session, so this
        // channel is silent: no sound, no vibration, no lights, and IMPORTANCE_LOW
        // so it never takes over the screen. The channel stays in place because a
        // foreground-service notification will need it once background capture
        // exists (Stage 3+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "TRACE Confirmed Events",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Silent alert when TRACE confirms an incident via multi-sensor fusion"
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
            notificationManager.createNotificationChannel(channel)
            // Retire the old noisy channel — its settings cannot be edited.
            notificationManager.deleteNotificationChannel(LEGACY_NOTIF_CHANNEL_ID)
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

        binding.btnTagIncident.setOnClickListener {
            showTagDialog()
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
        startExtendedSensors()
        Log.i("TRACE", "Sensor availability:\n${SensorRegistry.availabilityReport(sensorManager)}")

        // Stage 1: recording still starts with the app (the arming sheet lands in
        // Stage 4), but every event now belongs to a session. Any session left
        // ACTIVE by a previous run is closed as INTERRUPTED here.
        appScope.launch {
            val recovered = SessionManager.recoverInterruptedSessions(
                database.sessionDao(),
                database.eventDao()
            )
            if (recovered > 0) {
                Log.i("TRACE", "Recovered $recovered interrupted session(s) from a previous run")
            }

            // Open this run's session straight away: the app bar needs a real state
            // to show, and no observation should ever arrive without a chain to join.
            activeSession = SessionManager.ensureActiveSession(database.sessionDao())
            runOnUiThread { updateSessionStatus() }
        }
    }

    /** Updates the on-screen status bar — this is what judges see on the phone. */
    private fun updateStatusBar(lastEventType: String? = null, lastStatus: String? = null) {
        val line1 = when {
            evidenceLocked.get() -> "🔒 EVIDENCE LOCKED — new observations are dropped"
            lastEventType != null -> "LAST EVENT: $lastEventType  [$lastStatus]"
            else -> "TRACE ACTIVE — watching all sensors"
        }
        val line2 = "Events recorded: $eventCount   |   TAG marks an incident you saw"
        binding.statusText.text = "$line1\n$line2"
    }

    // ── App bar: session status chip ──────────────────────────────────────

    /** (Re)starts the once-a-second status tick. Main thread only. */
    private fun startStatusTicker() {
        statusTicker?.cancel()
        statusTicker = uiScope.launch {
            while (isActive) {
                updateSessionStatus()
                delay(STATUS_TICK_MS)
            }
        }
    }

    /**
     * Reflects capture state on the app bar chip.
     *
     * This is the one place Evidence Lock is visible while the camera keeps
     * running: a locked timeline drops observations silently, so stating it is the
     * difference between "nothing is happening" and "nothing is being recorded".
     */
    private fun updateSessionStatus() {
        val chip = binding.chipSessionStatus
        val session = activeSession

        when {
            evidenceLocked.get() -> {
                chip.text = "🔒 LOCKED"
                chip.setChipBackgroundColorResource(R.color.trace_error_container)
                chip.setTextColor(ContextCompat.getColor(this, R.color.trace_on_error_container))
            }
            session != null -> {
                val elapsed = System.currentTimeMillis() - session.startedAt
                chip.text = "● REC ${formatElapsed(elapsed)}"
                chip.setChipBackgroundColorResource(R.color.trace_primary_container)
                chip.setTextColor(ContextCompat.getColor(this, R.color.trace_on_primary_container))
            }
            else -> {
                chip.text = "IDLE"
                chip.setChipBackgroundColorResource(R.color.trace_surface_container_high)
                chip.setTextColor(ContextCompat.getColor(this, R.color.trace_on_surface_variant))
            }
        }
    }

    /** mm:ss, or h:mm:ss past the hour. */
    private fun formatElapsed(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%02d:%02d".format(minutes, seconds)
    }

    // ── App bar actions ───────────────────────────────────────────────────

    /** Handles the app bar's actions. */
    private fun onAppBarAction(itemId: Int): Boolean = when (itemId) {
        // Previous Sessions. Timeline is today's event browser; Stage 5 replaces it
        // with the session-grouped list.
        R.id.action_sessions -> {
            startActivity(Intent(this, TimelineActivity::class.java))
            true
        }
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        else -> false
    }

    override fun onPause() {
        super.onPause()
        statusTicker?.cancel()
        // Unregister while screen is off to save battery.
        if (::sensorManager.isInitialized) {
            sensorManager.unregisterListener(sensorListener)
            unregisterExtendedListeners()
        }
    }

    override fun onResume() {
        super.onResume()

        // Evidence Lock can be armed from the Timeline while this screen is paused,
        // so the cached value must not be trusted across a resume.
        evidenceLocked.set(EvidenceLock.isLocked(this))

        if (wallpaperPaletteAtCreate != null &&
            wallpaperPaletteAtCreate != TraceAppearance.useWallpaperPalette(this)
        ) {
            // Posted rather than called inline: re-creating during onResume can race
            // the lifecycle callback we are inside.
            binding.root.post { recreate() }
            return
        }

        startStatusTicker()

        // Re-register when app comes back to foreground.
        if (::sensorManager.isInitialized && extendedSensorsStarted) {
            val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (accel != null) {
                sensorManager.registerListener(sensorListener, accel, SensorManager.SENSOR_DELAY_GAME)
            }
            if (::magnetListener.isInitialized) {
                registerIfAvailable(Sensor.TYPE_MAGNETIC_FIELD, magnetListener, SensorManager.SENSOR_DELAY_GAME)
            }
            if (::linearListener.isInitialized) {
                registerIfAvailable(Sensor.TYPE_LINEAR_ACCELERATION, linearListener, SensorManager.SENSOR_DELAY_GAME)
            }
            if (::gyroListener.isInitialized) {
                registerIfAvailable(Sensor.TYPE_GYROSCOPE, gyroListener, SensorManager.SENSOR_DELAY_GAME)
            }
        }
    }

    private var extendedSensorsStarted = false

    private fun unregisterExtendedListeners() {
        if (!extendedSensorsStarted) return
        if (::magnetListener.isInitialized) sensorManager.unregisterListener(magnetListener)
        if (::baroListener.isInitialized) sensorManager.unregisterListener(baroListener)
        if (::linearListener.isInitialized) sensorManager.unregisterListener(linearListener)
        if (::gyroListener.isInitialized) sensorManager.unregisterListener(gyroListener)
        if (::stepListener.isInitialized) sensorManager.unregisterListener(stepListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::sensorManager.isInitialized) sensorManager.unregisterListener(sensorListener)

        // Stop producing new work before tearing anything down.
        cameraExecutor.shutdown()
        appScope.cancel()

        // The recorder belongs to [audioDispatcher], so its teardown has to run
        // there. runBlocking guarantees the mic is released before the process moves
        // on — appScope is already cancelled, and the closing session aggregates
        // must be written too.
        runBlocking {
            withContext(audioDispatcher) {
                audioTornDown = true
                stopRecorder()
            }

            // Close the session only when the user actually leaves the app. A
            // process kill or a configuration change leaves it ACTIVE, and the next
            // launch recovers it as INTERRUPTED.
            if (isFinishing) {
                activeSession?.let { session ->
                    SessionManager.endSession(
                        database.sessionDao(),
                        database.eventDao(),
                        session.id
                    )
                    activeSession = null
                }
            }
        }
        audioDispatcher.close()
        uiScope.cancel()
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

    /**
     * Starts the audio capture loop on [audioDispatcher].
     *
     * Suspending between polls is what lets a clip save run on the same thread: the
     * loop yields the thread while it waits, and the recorder is never touched from
     * two places at once.
     */
    private fun startMicMonitoring() {
        appScope.launch(audioDispatcher) {
            var pollsSinceReopen = REOPEN_RETRY_POLLS   // try immediately

            while (isActive) {
                if (!recorderStarted) {
                    // Reopen after a failed clip freeze, retried at most once a
                    // second so an unavailable mic cannot spin.
                    if (pollsSinceReopen >= REOPEN_RETRY_POLLS) {
                        openRecorder()
                        pollsSinceReopen = 0
                    }
                    pollsSinceReopen++
                } else {
                    pollsSinceReopen = 0
                    val amplitude = readAmplitude()
                    if (DEBUG_RAW_VALUES) Log.d("TRACE", "RAW amplitude = $amplitude")

                    val confidence = toConfidence(
                        amplitude.toFloat(),
                        AUDIO_THRESHOLD.toFloat(),
                        AUDIO_MAX.toFloat()
                    )
                    if (confidence > 0f) {
                        onObservation(Observation("audio", confidence, System.currentTimeMillis()))
                    }
                    // The status bar is updated in updateStatusBar() only when real
                    // events fire — not with raw numbers every 150 ms.
                }
                delay(AMPLITUDE_POLL_MS)
            }
        }
    }

    /**
     * Creates and starts the recorder on a fresh temp file.
     * Audio thread only. Returns true when capture is running.
     */
    private fun openRecorder(): Boolean {
        if (audioTornDown) return false

        val recorder = try {
            newRecorder()
        } catch (e: Exception) {
            Log.e("TRACE", "Mic unavailable", e)
            runOnUiThread { binding.statusText.text = "Mic failed: ${e.message}" }
            return false
        }

        return try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            recorder.setOutputFile(File(cacheDir, TEMP_AUDIO_FILE).absolutePath)
            recorder.prepare()
            recorder.start()
            mediaRecorder = recorder
            recorderStarted = true
            true
        } catch (e: Exception) {
            Log.e("TRACE", "Mic failed", e)
            // Release the half-configured instance: a leaked audio source would make
            // every later attempt fail too.
            releaseQuietly(recorder)
            mediaRecorder = null
            recorderStarted = false
            runOnUiThread { binding.statusText.text = "Mic failed: ${e.message}" }
            false
        }
    }

    /** Audio thread only. Returns 0 when the recorder is not in a readable state. */
    private fun readAmplitude(): Int =
        try {
            if (recorderStarted) mediaRecorder?.maxAmplitude ?: 0 else 0
        } catch (e: IllegalStateException) {
            // maxAmplitude() is only valid between start() and stop(); a transient
            // state must never kill the capture loop.
            Log.w("TRACE", "maxAmplitude unavailable", e)
            0
        }

    /**
     * Stops and releases the recorder. Audio thread only.
     *
     * Returns false when the clip could not be closed cleanly — stop() throws if no
     * frames were captured, in which case the file must not be published as evidence.
     */
    private fun stopRecorder(): Boolean {
        val recorder = mediaRecorder
        if (recorder == null) {
            recorderStarted = false
            return false
        }

        var closedCleanly = false
        try {
            if (recorderStarted) {
                recorder.stop()
                closedCleanly = true
            }
        } catch (e: Exception) {
            Log.w("TRACE", "Recorder stop failed", e)
        } finally {
            recorderStarted = false
            releaseQuietly(recorder)
            mediaRecorder = null
        }
        return closedCleanly
    }

    /** Releases [recorder], logging instead of throwing. */
    private fun releaseQuietly(recorder: MediaRecorder) {
        try {
            recorder.release()
        } catch (e: Exception) {
            Log.w("TRACE", "Recorder release failed", e)
        }
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(applicationContext)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
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

    // ── Extended sensor array ───────────────────────────────────────────────
    //
    // Every sensor below feeds the same Observation -> EventExtractor ->
    // FusionEngine -> HashChain pipeline as the original three. Each entry
    // documents WHAT it contributes to incident reconstruction.
    //

    /**
     * MAGNETOMETER — detects steel doors swinging/slamming and large metal
     * objects (hand trucks, racks, forklift tines) moving near the phone.
     * Evidence toward: door_swing, door_slam, metal_moves.
     *
     * Uses a rolling EWMA baseline (~30 s) because the absolute Earth-field
     * magnitude varies by hemisphere and building steelwork.
     */
    private fun startExtendedSensors() {
        extendedSensorsStarted = true
        magnetListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val magnitude = kotlin.math.sqrt(
                    event.values[0] * event.values[0] +
                    event.values[1] * event.values[1] +
                    event.values[2] * event.values[2]
                )
                val deltaUf = baseline.feed("magnetometer", magnitude) // in µT
                // Normalize: 15 µT delta ≈ door swing nearby, 40+ ≈ big steel moving fast.
                val confidence = toConfidence(deltaUf, 10f, 40f)
                if (confidence > 0f) {
                    onObservation(Observation("magnetometer", confidence, System.currentTimeMillis()))
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        /**
         * BAROMETER — a door opening/closing couples a fast pressure pulse
         * into the room. Evidence toward: pressure_shift (context signal).
         */
        baroListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val hPa = event.values[0]
                val delta = baseline.feed("barometer", hPa)
                val confidence = toConfidence(delta, 0.3f, 1.2f)
                if (confidence > 0f) {
                    onObservation(Observation("barometer", confidence, System.currentTimeMillis()))
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        /**
         * AMBIENT LIGHT — a light source being switched off is a fast, large
         * NEGATIVE lux step. Evidence toward: lights_off (context signal —
         * never confirms alone; fusion only counts it if another sensor fired).
         */
        val lightListenerOnly = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val lux = event.values[0]
                val delta = baseline.feed("light", lux)
                // Negative step only: lights going ON is not an incident.
                if (lux < baseline.get("light") && delta > 0f) {
                    val confidence = toConfidence(delta, 100f, 400f)
                    if (confidence > 0f) {
                        onObservation(Observation("light", confidence, System.currentTimeMillis()))
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        /**
         * LINEAR ACCELERATION — gravity-compensated motion. In true free-fall
         * the vector collapses toward 0 m/s²; a phone or object dropping near
         * the device produces exactly this signature. Evidence toward:
         * object_falls (free-fall), device_falls.
         */
        linearListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val magnitude = kotlin.math.sqrt(
                    event.values[0] * event.values[0] +
                    event.values[1] * event.values[1] +
                    event.values[2] * event.values[2]
                )
                // Free-fall: magnitude → 0. Confidence rises as it collapses below 1 m/s².
                val confidence = if (magnitude < 2.5f) toConfidence(2.5f - magnitude, 0f, 2.5f) else 0f
                if (confidence > 0f) {
                    onObservation(Observation("linear", confidence, System.currentTimeMillis()))
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        /**
         * GYROSCOPE — angular velocity. A device tumbling off a shelf spins
         * fast on multiple axes; a nudge produces a brief single-axis spike.
         * Evidence toward: device_falls, device_motion.
         */
        gyroListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val magnitude = kotlin.math.sqrt(
                    event.values[0] * event.values[0] +
                    event.values[1] * event.values[1] +
                    event.values[2] * event.values[2]
                )
                val confidence = toConfidence(magnitude, 1.5f, 8f)
                if (confidence > 0f) {
                    onObservation(Observation("gyroscope", confidence, System.currentTimeMillis()))
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        /**
         * STEP DETECTOR — fires once per footstep. Evidence toward:
         * person_present (weak context signal; useful in reconstruction
         * queries like "what happened before the alarm" — footsteps
         * immediately before an event imply a person was involved).
         */
        stepListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                onObservation(Observation("step", 0.5f, System.currentTimeMillis()))
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        // Register whatever hardware actually exists. Absent sensors simply
        // never emit Observations; fusion treats them like silent sensors.
        registerIfAvailable(Sensor.TYPE_MAGNETIC_FIELD, magnetListener, SensorManager.SENSOR_DELAY_GAME)

        val barometer = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (barometer != null && lightSensor == null) {
            // Device has a barometer but no light sensor: reuse the listener slot.
            sensorManager.registerListener(baroListener, barometer, SensorManager.SENSOR_DELAY_UI)
        } else {
            if (barometer != null) {
                sensorManager.registerListener(baroListener, barometer, SensorManager.SENSOR_DELAY_UI)
            }
            if (lightSensor != null) {
                sensorManager.registerListener(lightListenerOnly, lightSensor, SensorManager.SENSOR_DELAY_UI)
            }
        }

        registerIfAvailable(Sensor.TYPE_LINEAR_ACCELERATION, linearListener, SensorManager.SENSOR_DELAY_GAME)
        registerIfAvailable(Sensor.TYPE_GYROSCOPE, gyroListener, SensorManager.SENSOR_DELAY_GAME)

        val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        if (stepDetector != null) {
            sensorManager.registerListener(stepListener, stepDetector, SensorManager.SENSOR_DELAY_UI)
        }

        // SIGNIFICANT MOTION — hardware batched trigger for "device was moved
        // in a notable way". Evidence toward: person_present. One-shot per
        // trigger; re-armed after each firing.
        val sigMotion = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        if (sigMotion != null) {
            val sigListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    sigMotionTriggeredAt = System.currentTimeMillis()
                    onObservation(Observation("sigmotion", 0.5f, sigMotionTriggeredAt))
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sensorManager.registerListener(sigListener, sigMotion, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun registerIfAvailable(type: Int, listener: SensorEventListener, rate: Int) {
        val sensor = sensorManager.getDefaultSensor(type) ?: return
        sensorManager.registerListener(listener, sensor, rate)
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

            // Every event needs a session to anchor on. Normally one already
            // exists; this lazy call only covers observations that fire while the
            // session write is still in flight.
            val session = activeSession ?: SessionManager
                .ensureActiveSession(database.sessionDao())
                .also { activeSession = it }

            val windowStart = obs.timestamp - FusionEngine.FUSION_WINDOW_MS
            val windowEnd   = obs.timestamp + FusionEngine.FUSION_WINDOW_MS

            // Steps 0-5: insert, fuse and hash. ChainWriter serialises the whole
            // read-tip → insert → hash → update sequence, so two sensors firing in
            // the same millisecond queue up instead of forking the chain.
            val finalEvent = ChainWriter.append(database.eventDao(), session, baseEvent) { inserted ->
                FusionEngine.buildEnrichedEvent(
                    inserted,
                    database.eventDao().getEventsInWindowForSession(session.id, windowStart, windowEnd)
                )
            }
            val newId = finalEvent.id

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
            val updatedWindow = database.eventDao()
                .getEventsInWindowForSession(session.id, windowStart, windowEnd)
            val fusedStatus   = FusionEngine.determineStatus(updatedWindow)
            updatedWindow.forEach { e ->
                // A human assertion is not a fusion verdict: leave MANUAL alone.
                if (!FusionEngine.isHumanAsserted(e) && e.status != fusedStatus) {
                    database.eventDao().updateStatus(e.id, fusedStatus)
                }
            }
        }
    }

    /**
     * Freezes the audio currently in the recorder's buffer as an evidence clip.
     *
     * Strategy: stop the live recorder → copy the finalised file → start a fresh
     * recorder. This guarantees MediaPlayer can decode the copy without a
     * missing/truncated AMR header.
     *
     * Runs on [audioDispatcher], so it can never interleave with the amplitude loop
     * or with another clip save — the stop-and-copy step that used to race is now
     * serialised by thread ownership rather than by hope.
     *
     * @return the clip path, or null when no usable clip could be produced.
     */
    private suspend fun saveEvidenceClip(eventId: Long): String? = withContext(audioDispatcher) {
        // A teardown may have run while this save was queued.
        if (audioTornDown) return@withContext null

        val source = File(cacheDir, TEMP_AUDIO_FILE)
        if (!recorderStarted || !source.exists() || source.length() == 0L) return@withContext null

        if (!stopRecorder()) {
            // The clip could not be closed cleanly (stop() throws when almost no
            // frames were captured). Do not publish a truncated file as evidence.
            openRecorder()
            return@withContext null
        }

        try {
            val destDir = File(getExternalFilesDir(null), EVIDENCE_DIR).also { it.mkdirs() }
            val dest = File(destDir, "evidence_$eventId.amr")
            source.copyTo(dest, overwrite = true)
            dest.absolutePath
        } catch (e: Exception) {
            Log.e("TRACE", "Evidence clip save failed", e)
            null
        } finally {
            // Always get back to recording: a missing clip must never stop capture.
            openRecorder()
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

    // ── Manual incident tagging ───────────────────────────────────────────

    /**
     * Asks the operator what happened, then records their answer as evidence.
     *
     * This is the only input to the timeline that comes from a person rather than
     * a sensor, and it exists because the sensors can infer *that* something
     * happened but never what it was or whether it mattered. The operator on
     * scene is the only observer who knows that, so their statement is stored as
     * first-class evidence — see [recordManualTag].
     */
    private fun showTagDialog() {
        if (tagDialog?.isShowing == true) return

        val titles = MANUAL_TAG_LABELS.map { it.second }.toTypedArray()

        tagDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Tag an incident")
            .setSingleChoiceItems(titles, -1) { dialog, which ->
                dialog.dismiss()
                recordManualTag(MANUAL_TAG_LABELS[which].first, MANUAL_TAG_LABELS[which].second)
            }
            .setNegativeButton("Cancel", null)
            .create()

        tagDialog?.show()
    }

    /**
     * Writes a human-asserted incident into the active session's chain.
     *
     * Deliberately not fused: [FusionEngine] leaves human assertions out of its
     * source count, so a tag can never become a CONFIRMED incident on the
     * strength of its own presence — one person tapping once is not two
     * independent sensors agreeing. It is stored as [SensorRegistry.MANUAL] with
     * status MANUAL, which is what keeps the distinction visible in the timeline,
     * the detail view and [QueryEngine].
     *
     * Everything else matches a sensor event: the same single chain writer (so
     * the tag joins the session in custody order no matter what fires beside it),
     * the same session anchor, and the same audio evidence clip.
     */
    private fun recordManualTag(type: String, title: String) {
        // A human assertion is evidence, so a locked timeline refuses it for the
        // same reason it refuses an observation.
        if (evidenceLocked.get()) {
            Toast.makeText(this, "Evidence Lock is on — unlock to add evidence", Toast.LENGTH_SHORT)
                .show()
            return
        }

        val tag = Event(
            type       = type,
            timestamp  = System.currentTimeMillis(),
            source     = SensorRegistry.MANUAL.id,
            confidence = 1f,
            status     = "MANUAL"
        )

        appScope.launch {
            val session = activeSession ?: SessionManager
                .ensureActiveSession(database.sessionDao())
                .also { activeSession = it }

            val stored = ChainWriter.append(database.eventDao(), session, tag)
            Log.i("TRACE", "MANUAL TAG: id=${stored.id} type=$type session=${session.id}")

            // An operator tagging *now* is the strongest hint there is that
            // something is happening right now, so keep the audio around this
            // moment as an evidence clip (serialised on the audio thread).
            val clipPath = saveEvidenceClip(stored.id)
            if (clipPath != null) {
                database.eventDao().update(stored.copy(evidenceClipPath = clipPath))
            }

            runOnUiThread {
                eventCount++
                updateStatusBar(lastEventType = title, lastStatus = "MANUAL")
                Toast.makeText(this@MainActivity, "Tagged: $title", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Fires a silent notification when multi-sensor fusion confirms an incident.
     * No sound and no vibration by design — the on-screen status line is the
     * in-app cue at this stage (a confirm banner lands in Stage 3).
     */
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
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()

        getSystemService(NotificationManager::class.java).notify(notifId++, notification)
    }
}

