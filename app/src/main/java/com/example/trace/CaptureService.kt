package com.example.trace

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.camera.core.Preview
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.trace.capture.AudioSource
import com.example.trace.capture.CameraSource
import com.example.trace.capture.EnvironmentSensorSource
import com.example.trace.capture.MotionSensorSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * TRACE — foreground capture service (Stage 8, refactored).
 *
 * Owns every evidence-producing pipeline while a session is armed.
 * Sensor capture has been extracted into dedicated source classes under
 * the [capture] package, each responsible for one modality domain:
 *
 * - [MotionSensorSource] — accel, gyro, linear, step, sigmotion
 * - [EnvironmentSensorSource] — magnetometer, barometer, light
 * - [CameraSource] — CameraX frame-differencing + snapshots
 * - [AudioSource] — PCM16 analysis + MediaRecorder evidence clips
 *
 * The service orchestrates startup/teardown, the event pipeline (extract →
 * chain → fuse → persist → notify), and the public API the frontend calls.
 */
class CaptureService : Service(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    // ── Database + scope ──────────────────────────────────────────────────
    private lateinit var database: TraceDatabase
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Session-scoped pipeline state ─────────────────────────────────────
    private lateinit var sensorManager: SensorManager
    private val profile = DeviceProfile.detect(this)
    private val lastEventTime = ConcurrentHashMap<String, Long>()

    // ── Extracted capture sources (nullable = not armed) ──────────────────
    private var motionSource: MotionSensorSource? = null
    private var environmentSource: EnvironmentSensorSource? = null
    private var cameraSource: CameraSource? = null
    private var audioSource: AudioSource? = null

    // ── Sensor thread (shared by motion + environment sources) ────────────
    private val sensorThread = HandlerThread(
        "trace-sensors",
        android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE,
    ).apply { start() }
    private val sensorHandler = Handler(sensorThread.looper)

    // ── Wall-clock offset (computed once at service creation) ─────────────
    private val wallClockOffsetMs = System.currentTimeMillis() - System.nanoTime() / 1_000_000
    private fun monoTimeMs(): Long = System.nanoTime() / 1_000_000

    /** Sensor IDs the armed session is capturing from. */
    private var armedSensorIds: Set<String> = emptySet()

    // ── Tuning constants ──────────────────────────────────────────────────
    private val EVENT_COOLDOWN_MS = 1000L

    // ── Service lifecycle ─────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        database = TraceDatabase.getInstance(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when {
            intent?.action == ACTION_END -> {
                stopCapturePipelines()
                TimestampDisplay.reset()
                stopSelf()
                return START_NOT_STICKY
            }
            intent?.action == ACTION_CAPTURE_FOR_EVENT -> {
                val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
                if (eventId > 0) captureForEvent(eventId)
                return START_NOT_STICKY
            }
            else -> {
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
        sensorThread.quitSafely()
        audioSource = null
        cameraSource = null
        motionSource = null
        environmentSource = null
        serviceScope.cancel()
        TimestampDisplay.reset()
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

    private fun armCaptureFor(session: Session) {
        armedSensorIds = session.sensorIds.toSet()
        LiveBus.log.append(
            SessionLog.Kind.LIFECYCLE,
            "capture service attached · ${armedSensorIds.size} sensor(s)",
        )

        // Set up timestamp display offset for this session
        TimestampDisplay.wallClockOffsetMs = wallClockOffsetMs
        TimestampDisplay.sessionStartedAtWallClock = session.startedAt

        // Reset stale state
        lastEventTime.clear()

        // Start extracted sources
        startMotionSensors()
        startEnvironmentSensors()
        startAudio()
        startCamera()

        Log.i("TRACE", "Capture armed: ${armedSensorIds.joinToString(", ")}")
        updateNotification()
    }

    private fun startMotionSensors() {
        val ids = armedSensorIds
        val hasMotion = SensorRegistry.MOTION.id in ids ||
            SensorRegistry.GYROSCOPE.id in ids ||
            SensorRegistry.LINEAR.id in ids ||
            SensorRegistry.STEP.id in ids ||
            SensorRegistry.SIGMOTION.id in ids
        if (!hasMotion) return

        val source = MotionSensorSource(
            enabledSensorIds = ids,
            sensorHandler = sensorHandler,
            onObservation = { obs -> onObservation(obs) },
        ).also { motionSource = it }
        source.start(sensorManager)
    }

    private fun startEnvironmentSensors() {
        val ids = armedSensorIds
        val hasEnv = SensorRegistry.MAGNETOMETER.id in ids ||
            SensorRegistry.BAROMETER.id in ids ||
            SensorRegistry.LIGHT.id in ids
        if (!hasEnv) return

        val source = EnvironmentSensorSource(
            enabledSensorIds = ids,
            sensorHandler = sensorHandler,
            onObservation = { obs -> onObservation(obs) },
        ).also { environmentSource = it }
        source.start(sensorManager)
    }

    private fun startAudio() {
        if (SensorRegistry.AUDIO.id !in armedSensorIds) return
        val source = AudioSource(
            appContext = this,
            enabledSensorIds = armedSensorIds,
            onObservation = { obs -> onObservation(obs) },
            serviceScope = serviceScope,
            updateClipPath = { id, path ->
                serviceScope.launch { database.eventDao().updateClipPath(id, path) }
            },
            updateClipHash = { id, hash ->
                serviceScope.launch { database.eventDao().updateClipHash(id, hash) }
            },
        ).also { audioSource = it }
        source.start(this)
    }

    private fun startCamera() {
        if (SensorRegistry.CAMERA.id !in armedSensorIds) return
        val source = CameraSource(
            profile = profile,
            enabledSensorIds = armedSensorIds,
            onObservation = { obs -> onObservation(obs) },
            serviceScope = serviceScope,
            updatePhotoPath = { id, path ->
                serviceScope.launch { database.eventDao().updatePhotoPath(id, path) }
            },
            updatePhotoHash = { id, hash ->
                serviceScope.launch { database.eventDao().updatePhotoHash(id, hash) }
            },
        ).also { cameraSource = it }
        source.start(this, this, previewSurfaceProvider)
    }

    private fun stopCapturePipelines() {
        motionSource?.stop()
        environmentSource?.stop()
        audioSource?.stop()
        cameraSource?.stop()
        motionSource = null
        environmentSource = null
        audioSource = null
        cameraSource = null
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

        /**
         * Surface provider for the live viewfinder.
         *
         * Set by [MainActivity] when the service owns the camera, nulled when
         * the service unbinds. The service reads this during camera binding
         * to include a [Preview] use case alongside analysis and capture.
         */
        @Volatile
        var previewSurfaceProvider: Preview.SurfaceProvider? = null

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

        /** Graceful stop. */
        fun stop(context: Context) {
            context.startService(
                Intent(context, CaptureService::class.java).setAction(ACTION_END),
            )
        }

        /** Asks the service to freeze clip + photo for a just-written event. */
        fun captureForEvent(context: Context, eventId: Long) {
            if (!running) return
            context.startService(
                Intent(context, CaptureService::class.java)
                    .setAction(ACTION_CAPTURE_FOR_EVENT)
                    .putExtra(EXTRA_EVENT_ID, eventId),
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
            } else 0,
        )
        markRunning(true)
    }

    private fun captureNotification(): Notification {
        val session = SessionManager.activeSessionId
        val endIntent = PendingIntent.getService(
            this, 1,
            Intent(this, CaptureService::class.java).setAction(ACTION_END),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_CAPTURE)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("TRACE recording")
            .setContentText(
                "Session #$session · ${LiveBus.eventCount} event(s) · sensors: " +
                    armedSensorIds.joinToString(", ").ifEmpty { "starting…" },
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
            CHANNEL_CAPTURE, "Recording in progress",
            android.app.NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Persistent notice while a TRACE session is capturing"
            setSound(null, null)
            enableVibration(false)
        }
        val alerts = android.app.NotificationChannel(
            CHANNEL_ALERTS, "TRACE Confirmed Events",
            android.app.NotificationManager.IMPORTANCE_LOW,
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

    // ── The pipeline ──────────────────────────────────────────────────────

    /**
     * Central observation handler. Receives observations from all capture
     * sources, extracts event types, applies cooldown, serializes through
     * [ChainWriter], fuses via [FusionEngine], persists evidence, and
     * broadcasts state to [LiveBus].
     */
    private fun onObservation(obs: Observation) {
        val sessionId = SessionManager.activeSessionId ?: return
        if (EvidenceLock.isLocked(this)) return

        val eventType = EventExtractor.extract(obs) ?: return

        // Cooldown in the monotonic clock domain (no clock-jump risk).
        val lastTime = lastEventTime[eventType] ?: 0L
        if (obs.timestamp - lastTime < EVENT_COOLDOWN_MS) return
        lastEventTime[eventType] = obs.timestamp

        val baseEvent = Event(
            type = eventType,
            timestamp = obs.timestamp,  // monotonic — stored as-is
            source = obs.source,
            confidence = obs.confidence,
        )

        serviceScope.launch {
            val session = database.sessionDao().getSessionById(sessionId) ?: return@launch
            if (session.state != Session.STATE_ACTIVE) return@launch

            // Fusion window in monotonic domain
            val windowStart = obs.timestamp - FusionEngine.FUSION_WINDOW_MS
            val windowEnd = obs.timestamp + FusionEngine.FUSION_WINDOW_MS
            val windowEvents = database.eventDao()
                .getEventsInWindowForSession(session.id, windowStart, windowEnd)

            val finalEvent = ChainWriter.append(database.eventDao(), session, baseEvent) { inserted ->
                FusionEngine.buildEnrichedEvent(inserted, windowEvents)
            }
            val newId = finalEvent.id
            Log.d("TRACE", "EVENT SAVED: id=$newId type=$eventType status=${finalEvent.status}")

            LiveBus.eventCount += 1
            LiveBus.log.append(
                SessionLog.Kind.EVENT,
                "${SensorRegistry.iconFor(obs.source)} ${eventType.replace("_", " ")}" +
                    " · ${SensorRegistry.labelFor(obs.source)} · ${finalEvent.status}",
                finalEvent.timestamp,
            )
            LiveBus.setConfidence(obs.source, obs.confidence)

            if (finalEvent.status == "CONFIRMED") {
                LiveBus.lastConfirmedAt = System.currentTimeMillis()
                LiveBus.lastConfirmedText =
                    "CONFIRMED · ${eventType.replace("_", " ").replaceFirstChar { it.uppercase() }}" +
                        " · ${"%.0f".format(finalEvent.confidence * 100)}%"
                fireConfirmedNotification(eventType)
            }

            // Evidence clip + snapshot
            val clipPath = audioSource?.saveEvidenceClip(newId)
            if (clipPath != null) {
                database.eventDao().updateClipPath(newId, clipPath)
            }
            cameraSource?.takeSnapshot(newId)

            // Re-fuse window now that this event is persisted
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

    /** Handles manual-tag event clip + photo capture. */
    private fun captureForEvent(eventId: Long) {
        serviceScope.launch {
            audioSource?.saveEvidenceClip(eventId)
            cameraSource?.takeSnapshot(eventId)
        }
    }

    private fun fireConfirmedNotification(eventType: String) {
        if (ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return

        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("CONFIRMED: ${eventType.replace("_", " ").replaceFirstChar { it.uppercase() }}")
            .setContentText("Multi-sensor agreement detected")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify((System.currentTimeMillis() and 0xFFFF).toInt(), notification)
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

    /** Only updates if [value] differs from the current, to avoid redundant UI redraws. */
    fun setConfidence(source: String, value: Float) {
        when (source) {
            "camera" -> { if (value != confCamera) confCamera = value }
            "audio"  -> { if (value != confAudio) confAudio = value }
            "motion" -> { if (value != confMotion) confMotion = value }
        }
    }
}