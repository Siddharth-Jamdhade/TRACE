package com.example.trace

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * TRACE — foreground capture service (Stage 8).
 *
 * Owns every evidence-producing pipeline while a session is armed: sensor
 * listeners, microphone + amplitude polling, camera frame-differencing and
 * snapshot capture. Running as a foreground service is what makes the REC chip
 * honest — capture now survives the screen turning off or the operator leaving
 * the app, instead of dying with the activity.
 *
 * The service runs in the same process as the UI, so it shares [LiveBus] (the
 * live session log, confidence readouts and the last-confirmed marker) with
 * [MainActivity] directly — no broadcast plumbing.
 *
 * The session lifecycle stays where it was: the UI arms and ends sessions via
 * [SessionManager]; the service only starts and stops *capture*. A system kill
 * of the service therefore leaves the session ACTIVE, which the next launch
 * recovers as INTERRUPTED — an honest record of a lost recording.
 */
class CaptureService : Service(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    // ── Database + scope ──────────────────────────────────────────────────
    private lateinit var database: TraceDatabase
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Session-scoped pipeline state (moved from MainActivity) ───────────
    private lateinit var sensorManager: SensorManager
    private val baseline = RollingBaseline()
    private val lastEventTime = ConcurrentHashMap<String, Long>()

    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var lastZ = Float.NaN

    // ── Camera ────────────────────────────────────────────────────────────
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var previousFrame: ByteArray? = null
    private var imageCapture: ImageCapture? = null

    /** True while a snapshot save is running; further requests are dropped. */
    @Volatile
    private var capturePending = false

    // ── Microphone (single-owner thread, as before) ───────────────────────
    private val audioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "trace-audio").apply { isDaemon = true }
    }
    private val audioDispatcher = audioExecutor.asCoroutineDispatcher()
    private var mediaRecorder: MediaRecorder? = null
    private var recorderStarted = false

    /** Audio thread only. Set during teardown so a late clip save cannot reopen the mic. */
    @Volatile
    private var audioTornDown = false

    @Volatile
    private var micActive = false

    // ── Tuning constants (unchanged thresholds) ───────────────────────────
    private val MOTION_THRESHOLD = 3.0f
    private val MOTION_MAX = 20.0f
    private val AUDIO_THRESHOLD = 1500
    private val AUDIO_MAX = 16147
    private val CAMERA_CHANGE_THRESHOLD = 0.05f
    private val CAMERA_MAX_CHANGE = 0.30f
    private val AMPLITUDE_POLL_MS = 150L
    private val REOPEN_RETRY_POLLS = 7
    private val EVENT_COOLDOWN_MS = 1000L

    private val TEMP_AUDIO_FILE = "temp_audio.amr"
    private val EVIDENCE_EXT = ".amr"

    /** [SensorRegistry] ids the armed session captures from. */
    private var armedSensorIds: Set<String> = emptySet()

    // ── Extended sensor listeners ─────────────────────────────────────────
    private lateinit var magnetListener: SensorEventListener
    private lateinit var baroListener: SensorEventListener
    private lateinit var lightListener: SensorEventListener
    private lateinit var linearListener: SensorEventListener
    private lateinit var gyroListener: SensorEventListener
    private lateinit var stepListener: SensorEventListener
    private lateinit var sigMotionListener: SensorEventListener

    // ── Service lifecycle ─────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        database = TraceDatabase.getInstance(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        createSensorListeners()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when {
            intent?.action == ACTION_END -> {
                stopCapturePipelines()
                stopSelf()
                return START_NOT_STICKY
            }
            intent?.action == ACTION_CAPTURE_FOR_EVENT -> {
                // A manual tag was written by the UI; freeze clip + photo here,
                // where the recorder and the camera actually live.
                val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
                if (eventId > 0) captureForEvent(eventId)
                return START_NOT_STICKY
            }
            else -> {
                // Arm (or re-arm after a sticky restart, where intent is null).
                startForegroundWithNotification()
                lifecycleRegistry.currentState = Lifecycle.State.RESUMED
                armCapture()
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCapturePipelines()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        cameraExecutor.shutdown()

        // The recorder belongs to [audioDispatcher]; teardown runs there.
        kotlinx.coroutines.runBlocking {
            withContext(audioDispatcher) {
                audioTornDown = true
                stopRecorder()
            }
        }
        audioDispatcher.close()
        serviceScope.cancel()

        markRunning(false)
        super.onDestroy()
    }

    // ── Arming / stopping ─────────────────────────────────────────────────

    private fun armCapture() {
        serviceScope.launch {
            val session = SessionManager.currentSession(database.sessionDao())
            if (session == null) {
                Log.w("TRACE", "CaptureService armed with no active session — stopping")
                withContext(Dispatchers.Main) { stopSelf() }
                return@launch
            }
            withContext(Dispatchers.Main) { armCaptureFor(session) }
        }
    }

    /** Main-thread continuation of [armCapture] once the session is known. */
    private fun armCaptureFor(session: Session) {
        armedSensorIds = session.sensorIds.toSet()
        LiveBus.log.append(SessionLog.Kind.LIFECYCLE, "capture service attached · ${armedSensorIds.size} sensor(s)")

        // Stale state would fire a false event in the first second.
        previousFrame = null
        baseline.resetAll()
        lastX = Float.NaN; lastY = Float.NaN; lastZ = Float.NaN
        lastEventTime.clear()
        capturePending = false

        registerArmedSensors()
        if (SensorRegistry.AUDIO.id in armedSensorIds) startMicMonitoring() else stopMicMonitoring()
        bindCapture()

        // Name absent hardware before it can be mistaken for a quiet scene.
        val missing = armedSensorIds
            .mapNotNull { SensorRegistry.byId(it) }
            .filter { it.type > 0 && sensorManager.getDefaultSensor(it.type) == null }
            .map { "${it.icon} ${SensorRegistry.labelFor(it.id)}" }
        if (missing.isNotEmpty()) {
            LiveBus.log.append(SessionLog.Kind.INFO, "not on this device: ${missing.joinToString(", ")}")
        }

        Log.i("TRACE", "Capture armed (service): ${armedSensorIds.joinToString(", ")}")
        updateNotification()
    }

    private fun stopCapturePipelines() {
        unregisterArmedSensors()
        stopMicMonitoring()
        unbindCapture()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        Log.i("TRACE", "Capture stopped (service)")
    }

    // ── Public API for the UI ─────────────────────────────────────────────

    companion object {
        const val ACTION_END = "com.example.trace.action.END"
        const val ACTION_CAPTURE_FOR_EVENT = "com.example.trace.action.CAPTURE_FOR_EVENT"
        const val EXTRA_EVENT_ID = "eventId"

        private const val CHANNEL_ALERTS = "trace_alerts_v2"
        private const val CHANNEL_CAPTURE = "trace_capture_v1"
        private const val NOTIF_CAPTURE_ID = 42
        private const val LEGACY_NOTIF_CHANNEL_ID = "trace_confirmed"

        /** True while the foreground service owns capture. UI reads this. */
        @Volatile
        var running: Boolean = false
            private set

        private val stateLock = Any()

        internal fun markRunning(value: Boolean) {
            synchronized(stateLock) { running = value }
        }

        /**
         * Live state shared with the UI (same process). Written by the service,
         * rendered by the activity's ticker.
         */
        @Volatile
        var liveEventCount: Int = 0

        /** Starts the service for the currently armed session. */
        fun start(context: Context) {
            val intent = Intent(context, CaptureService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Graceful stop: the service tears capture down, then stops itself. */
        fun stop(context: Context) {
            context.startService(
                Intent(context, CaptureService::class.java).setAction(ACTION_END)
            )
        }

        /** Asks the service to freeze clip + photo for a just-written event. */
        fun captureForEvent(context: Context, eventId: Long) {
            if (!running) return
            context.startService(
                Intent(context, CaptureService::class.java)
                    .setAction(ACTION_CAPTURE_FOR_EVENT)
                    .putExtra(EXTRA_EVENT_ID, eventId)
            )
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────

    private fun startForegroundWithNotification() {
        createChannels()
        ServiceCompat.startForeground(
            this,
            NOTIF_CAPTURE_ID,
            captureNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else 0
        )
        markRunning(true)
    }

    private fun captureNotification(): Notification {
        val session = SessionManager.activeSessionId
        val endIntent = PendingIntent.getService(
            this, 1,
            Intent(this, CaptureService::class.java).setAction(ACTION_END),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_CAPTURE)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("TRACE recording")
            .setContentText(
                "Session #$session · ${LiveBus.eventCount} event(s) · sensors: " +
                    armedSensorIds.joinToString(", ").ifEmpty { "starting…" }
            )
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "End session", endIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIF_CAPTURE_ID, captureNotification())
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val capture = android.app.NotificationChannel(
            CHANNEL_CAPTURE, "Recording in progress", android.app.NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notice while a TRACE session is capturing"
            setSound(null, null)
            enableVibration(false)
        }
        val alerts = android.app.NotificationChannel(
            CHANNEL_ALERTS, "TRACE Confirmed Events", android.app.NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Silent alert when TRACE confirms an incident via multi-sensor fusion"
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }
        manager.createNotificationChannel(capture)
        manager.createNotificationChannel(alerts)
        manager.deleteNotificationChannel(LEGACY_NOTIF_CHANNEL_ID)
    }

    // ── Sensors (moved verbatim from MainActivity) ────────────────────────

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            if (lastX.isNaN()) { lastX = x; lastY = y; lastZ = z; return }
            val dX = x - lastX
            val dY = y - lastY
            val dZ = z - lastZ
            val jerk = kotlin.math.sqrt(dX * dX + dY * dY + dZ * dZ)
            val confidence = toConfidence(jerk, MOTION_THRESHOLD, MOTION_MAX)
            if (confidence > 0f) {
                onObservation(Observation("motion", confidence, System.currentTimeMillis()))
            }
            lastX = x; lastY = y; lastZ = z
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun createSensorListeners() {
        magnetListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val magnitude = kotlin.math.sqrt(
                    event.values[0] * event.values[0] +
                        event.values[1] * event.values[1] +
                        event.values[2] * event.values[2]
                )
                val deltaUf = baseline.feed("magnetometer", magnitude)
                val confidence = toConfidence(deltaUf, 10f, 40f)
                if (confidence > 0f) {
                    onObservation(Observation("magnetometer", confidence, System.currentTimeMillis()))
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
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
        lightListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val lux = event.values[0]
                val delta = baseline.feed("light", lux)
                if (lux < baseline.get("light") && delta > 0f) {
                    val confidence = toConfidence(delta, 100f, 400f)
                    if (confidence > 0f) {
                        onObservation(Observation("light", confidence, System.currentTimeMillis()))
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        linearListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val magnitude = kotlin.math.sqrt(
                    event.values[0] * event.values[0] +
                        event.values[1] * event.values[1] +
                        event.values[2] * event.values[2]
                )
                val confidence = if (magnitude < 2.5f) toConfidence(2.5f - magnitude, 0f, 2.5f) else 0f
                if (confidence > 0f) {
                    onObservation(Observation("linear", confidence, System.currentTimeMillis()))
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
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
        stepListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                onObservation(Observation("step", 0.5f, System.currentTimeMillis()))
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sigMotionListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                onObservation(Observation("sigmotion", 0.5f, System.currentTimeMillis()))
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
    }

    private fun registerArmedSensors() {
        val ids = armedSensorIds
        if (SensorRegistry.MOTION.id in ids) {
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        if (SensorRegistry.MAGNETOMETER.id in ids) {
            registerIfAvailable(Sensor.TYPE_MAGNETIC_FIELD, magnetListener, SensorManager.SENSOR_DELAY_GAME)
        }
        if (SensorRegistry.BAROMETER.id in ids) {
            registerIfAvailable(Sensor.TYPE_PRESSURE, baroListener, SensorManager.SENSOR_DELAY_UI)
        }
        if (SensorRegistry.LIGHT.id in ids) {
            registerIfAvailable(Sensor.TYPE_LIGHT, lightListener, SensorManager.SENSOR_DELAY_UI)
        }
        if (SensorRegistry.LINEAR.id in ids) {
            registerIfAvailable(Sensor.TYPE_LINEAR_ACCELERATION, linearListener, SensorManager.SENSOR_DELAY_GAME)
        }
        if (SensorRegistry.GYROSCOPE.id in ids) {
            registerIfAvailable(Sensor.TYPE_GYROSCOPE, gyroListener, SensorManager.SENSOR_DELAY_GAME)
        }
        if (SensorRegistry.STEP.id in ids) {
            registerIfAvailable(Sensor.TYPE_STEP_DETECTOR, stepListener, SensorManager.SENSOR_DELAY_UI)
        }
        if (SensorRegistry.SIGMOTION.id in ids) {
            registerIfAvailable(Sensor.TYPE_SIGNIFICANT_MOTION, sigMotionListener, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun unregisterArmedSensors() {
        sensorManager.unregisterListener(sensorListener)
        if (::magnetListener.isInitialized) sensorManager.unregisterListener(magnetListener)
        if (::baroListener.isInitialized) sensorManager.unregisterListener(baroListener)
        if (::lightListener.isInitialized) sensorManager.unregisterListener(lightListener)
        if (::linearListener.isInitialized) sensorManager.unregisterListener(linearListener)
        if (::gyroListener.isInitialized) sensorManager.unregisterListener(gyroListener)
        if (::stepListener.isInitialized) sensorManager.unregisterListener(stepListener)
        if (::sigMotionListener.isInitialized) sensorManager.unregisterListener(sigMotionListener)
    }

    private fun registerIfAvailable(type: Int, listener: SensorEventListener, rate: Int) {
        val sensor = sensorManager.getDefaultSensor(type) ?: return
        sensorManager.registerListener(listener, sensor, rate)
    }

    private fun toConfidence(value: Float, threshold: Float, max: Float): Float {
        if (value < threshold) return 0f
        return ((value - threshold) / (max - threshold)).coerceIn(0f, 1f)
    }

    // ── Camera: analysis + snapshot (no preview — the UI owns that) ──────

    private var serviceAnalysis: ImageAnalysis? = null

    private fun bindCapture() {
        val ids = armedSensorIds
        if (SensorRegistry.CAMERA.id !in ids) {
            imageCapture = null
            return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val analysis = buildImageAnalysis()
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageCapture = capture
                serviceAnalysis = analysis
                // Deliberately NO unbindAll(): the activity's viewfinder Preview
                // is bound by a different lifecycle owner and must survive.
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    analysis, capture
                )
            } catch (e: Exception) {
                Log.e("TRACE", "Service camera bind failed", e)
                LiveBus.log.append(SessionLog.Kind.ERROR, "camera failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun unbindCapture() {
        imageCapture = null
        capturePending = false
        val analysis = serviceAnalysis
        serviceAnalysis = null
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                // Unbind only this owner's use cases, not the activity's preview.
                if (analysis != null) {
                    runCatching { provider.unbind(analysis) }
                }
                imageCapture?.let { runCatching { provider.unbind(it) } }
            } catch (e: Exception) {
                Log.w("TRACE", "Service camera unbind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun buildImageAnalysis(): ImageAnalysis {
        @Suppress("DEPRECATION")
        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(320, 240))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(cameraExecutor) { imageProxy -> analyzeFrame(imageProxy) }
        return analysis
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            val buffer = imageProxy.planes[0].buffer
            val data = ByteArray(buffer.remaining())
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
                    i += 4
                }
                val totalSamples = data.size / 4
                val changeRatio = changedSamples.toFloat() / totalSamples
                val confidence = toConfidence(changeRatio, CAMERA_CHANGE_THRESHOLD, CAMERA_MAX_CHANGE)
                if (confidence > 0f) {
                    onObservation(Observation("camera", confidence, System.currentTimeMillis()))
                }
            }
            previousFrame = data
        } finally {
            imageProxy.close()
        }
    }

    /** Freezes one camera frame as evidence for [eventId] (drop, not queue). */
    private fun takeEventSnapshot(eventId: Long) {
        val capture = imageCapture ?: return
        if (capturePending) return
        capturePending = true

        val dest = File(EvidenceFiles.dir(this), "photo_$eventId.jpg")
        val opts = ImageCapture.OutputFileOptions.Builder(dest).build()

        capture.takePicture(
            opts,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    capturePending = false
                    val path = result.savedUri?.path ?: dest.absolutePath
                    serviceScope.launch {
                        val hash = runCatching { FileIntegrity.sha256(File(path)) }.getOrNull()
                        database.eventDao().updatePhotoPath(eventId, path)
                        if (hash != null) database.eventDao().updatePhotoHash(eventId, hash)
                        Log.d("TRACE", "Snapshot saved for event $eventId")
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    capturePending = false
                    Log.w("TRACE", "Snapshot failed for event $eventId", exc)
                    LiveBus.log.append(SessionLog.Kind.INFO, "snapshot failed: ${exc.message}")
                    dest.delete()
                }
            }
        )
    }

    // ── Microphone (moved verbatim) ───────────────────────────────────────

    private fun startMicMonitoring() {
        if (micActive) return
        micActive = true
        serviceScope.launch(audioDispatcher) {
            var pollsSinceReopen = REOPEN_RETRY_POLLS
            while (isActive && micActive) {
                if (!recorderStarted) {
                    if (pollsSinceReopen >= REOPEN_RETRY_POLLS) {
                        openRecorder()
                        pollsSinceReopen = 0
                    }
                    pollsSinceReopen++
                } else {
                    pollsSinceReopen = 0
                    val amplitude = readAmplitude()
                    val confidence = toConfidence(
                        amplitude.toFloat(), AUDIO_THRESHOLD.toFloat(), AUDIO_MAX.toFloat()
                    )
                    if (confidence > 0f) {
                        onObservation(Observation("audio", confidence, System.currentTimeMillis()))
                    }
                }
                delay(AMPLITUDE_POLL_MS)
            }
        }
    }

    private fun stopMicMonitoring() {
        micActive = false
        serviceScope.launch(audioDispatcher) {
            if (!audioTornDown) stopRecorder()
        }
    }

    private fun openRecorder(): Boolean {
        if (audioTornDown) return false
        val recorder = try {
            newRecorder()
        } catch (e: Exception) {
            Log.e("TRACE", "Mic unavailable", e)
            LiveBus.log.append(SessionLog.Kind.ERROR, "mic failed: ${e.message}")
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
            releaseQuietly(recorder)
            mediaRecorder = null
            recorderStarted = false
            LiveBus.log.append(SessionLog.Kind.ERROR, "mic failed: ${e.message}")
            false
        }
    }

    private fun readAmplitude(): Int =
        try {
            if (recorderStarted) mediaRecorder?.maxAmplitude ?: 0 else 0
        } catch (e: IllegalStateException) {
            Log.w("TRACE", "maxAmplitude unavailable", e)
            0
        }

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

    private fun releaseQuietly(recorder: MediaRecorder) {
        try {
            recorder.release()
        } catch (e: Exception) {
            Log.w("TRACE", "Recorder release failed", e)
        }
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(applicationContext)
        else @Suppress("DEPRECATION") MediaRecorder()

    /** Freezes the current mic buffer as the event's evidence clip, with hash. */
    private suspend fun saveEvidenceClip(eventId: Long): String? = withContext(audioDispatcher) {
        if (audioTornDown) return@withContext null
        val source = File(cacheDir, TEMP_AUDIO_FILE)
        if (!recorderStarted || !source.exists() || source.length() == 0L) return@withContext null

        if (!stopRecorder()) {
            openRecorder()
            return@withContext null
        }
        try {
            val dest = File(EvidenceFiles.dir(this@CaptureService), "evidence_$eventId$EVIDENCE_EXT")
            source.copyTo(dest, overwrite = true)
            // Hash the file while the audio thread still owns the moment —
            // this is the write-time integrity binding for Stage 7.
            val hash = runCatching { FileIntegrity.sha256(dest) }.getOrNull()
            if (hash != null) database.eventDao().updateClipHash(eventId, hash)
            dest.absolutePath
        } catch (e: Exception) {
            Log.e("TRACE", "Evidence clip save failed", e)
            null
        } finally {
            openRecorder()
        }
    }

    /** Clip + photo for a manually tagged event, requested by the UI. */
    private fun captureForEvent(eventId: Long) {
        serviceScope.launch {
            saveEvidenceClip(eventId)
            runOnMain { takeEventSnapshot(eventId) }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    // ── The pipeline (moved verbatim, UI calls swapped for LiveBus) ──────

    private fun onObservation(obs: Observation) {
        val sessionId = SessionManager.activeSessionId ?: return
        if (EvidenceLock.isLocked(this)) return

        val eventType = EventExtractor.extract(obs) ?: return

        val lastTime = lastEventTime[eventType] ?: 0L
        if (obs.timestamp - lastTime < EVENT_COOLDOWN_MS) return
        lastEventTime[eventType] = obs.timestamp

        val baseEvent = Event(
            type = eventType,
            timestamp = obs.timestamp,
            source = obs.source,
            confidence = obs.confidence
        )

        serviceScope.launch {
            // The session could have been ended between trigger and write.
            val session = database.sessionDao().getSessionById(sessionId) ?: return@launch
            if (session.state != Session.STATE_ACTIVE) return@launch

            val windowStart = obs.timestamp - FusionEngine.FUSION_WINDOW_MS
            val windowEnd = obs.timestamp + FusionEngine.FUSION_WINDOW_MS

            val finalEvent = ChainWriter.append(database.eventDao(), session, baseEvent) { inserted ->
                FusionEngine.buildEnrichedEvent(
                    inserted,
                    database.eventDao().getEventsInWindowForSession(session.id, windowStart, windowEnd)
                )
            }
            val newId = finalEvent.id
            Log.d("TRACE", "EVENT SAVED: id=$newId type=$eventType status=${finalEvent.status}")

            LiveBus.eventCount += 1
            LiveBus.log.append(
                SessionLog.Kind.EVENT,
                "${SensorRegistry.iconFor(obs.source)} ${eventType.replace("_", " ")}" +
                    " · ${SensorRegistry.labelFor(obs.source)} · ${finalEvent.status}",
                finalEvent.timestamp
            )
            LiveBus.setConfidence(obs.source, obs.confidence)

            if (finalEvent.status == "CONFIRMED") {
                LiveBus.lastConfirmedAt = System.currentTimeMillis()
                LiveBus.lastConfirmedText =
                    "CONFIRMED · ${eventType.replace("_", " ").replaceFirstChar { it.uppercase() }}" +
                        " · ${"%.0f".format(finalEvent.confidence * 100)}%"
                fireConfirmedNotification(eventType)
            }

            val clipPath = saveEvidenceClip(newId)
            if (clipPath != null) {
                database.eventDao().updateClipPath(newId, clipPath)
            }
            runOnMain { takeEventSnapshot(newId) }

            val updatedWindow = database.eventDao()
                .getEventsInWindowForSession(session.id, windowStart, windowEnd)
            val fusedStatus = FusionEngine.determineStatus(updatedWindow)
            updatedWindow.forEach { e ->
                if (!FusionEngine.isHumanAsserted(e) && e.status != fusedStatus) {
                    database.eventDao().updateStatus(e.id, fusedStatus)
                }
            }

            updateNotification()
        }
    }

    private fun fireConfirmedNotification(eventType: String) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return

        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("CONFIRMED: ${eventType.replace("_", " ").replaceFirstChar { it.uppercase() }}")
            .setContentText("Multi-sensor agreement detected")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify((System.currentTimeMillis() and 0xFFFF).toInt(), notification)
    }
}

/**
 * Same-process bus between the capture service and the live view.
 *
 * The log IS the session transcript — one instance for the whole process, so
 * lines written by the service appear on the dashboard exactly like lines
 * written by the UI.
 */
object LiveBus {
    val log = SessionLog()

    @Volatile
    var eventCount: Int = 0

    /** Wall-clock of the most recent CONFIRMED, and the banner text for it. */
    @Volatile
    var lastConfirmedAt: Long = 0L

    @Volatile
    var lastConfirmedText: String = ""

    @Volatile
    var confCamera: Float = 0f

    @Volatile
    var confAudio: Float = 0f

    @Volatile
    var confMotion: Float = 0f

    fun setConfidence(source: String, value: Float) {
        when (source) {
            "camera" -> confCamera = value
            "audio" -> confAudio = value
            "motion" -> confMotion = value
        }
    }
}
