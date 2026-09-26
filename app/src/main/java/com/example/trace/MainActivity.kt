package com.example.trace

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
// Preview.SurfaceProvider type used by CaptureService.previewSurfaceProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.trace.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    /**
     * The session currently being recorded into, or null when idle.
     *
     * [Volatile] because whether an observation is kept or dropped is decided on
     * whichever thread produced it — camera analysis, the audio poll loop or the
     * UI thread — while the session is armed and ended on the main thread. Only
     * ever holds a fully-hashed session.
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
    private val REQUIRED_PERMISSIONS: Array<String>
        get() {
            val base = mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
            // Step detector and significant motion sensors require this on API 29+.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                base.add(Manifest.permission.ACTIVITY_RECOGNITION)
            }
            return base.toTypedArray()
        }
    private val PERMISSION_REQUEST_CODE = 100

    // ── Evidence Lock ─────────────────────────────────────────────────────
    // Shared with TimelineActivity via [EvidenceLock].
    private val evidenceLocked = AtomicBoolean(false)

    // Running event count shown live on screen (mirrored from [LiveBus])
    private var eventCount = 0

    /**
     * Live transcript for the dashboard's log tail — shared with
     * [CaptureService] through [LiveBus]: the service writes the lines produced
     * by capture, the UI writes lifecycle lines. One transcript per process.
     */
    private val sessionLog: SessionLog get() = LiveBus.log
    private val logAdapter = SessionLogAdapter()

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

    /** How long the CONFIRMED banner stays up before auto-hiding. */
    private val BANNER_VISIBLE_MS = 4_000L

    /** Cancels the pending banner hide when a newer confirm replaces it. */
    private var bannerHide: Job? = null

    /** Last CONFIRMED timestamp this screen has rendered (from [LiveBus]). */
    private var lastRenderedConfirmAt = 0L

    /** This screen's own preview use case, unbound without touching the service's. */
    private var previewUseCase: Preview? = null

    /**
     * Binds the viewfinder preview — this screen's only camera role.
     *
     * When a session is capturing, the service owns ALL camera use cases
     * (preview + analysis + snapshot) under the service lifecycle, with the
     * preview surface provided through [CaptureService.previewSurfaceProvider].
     * This avoids the CAM FREEZES bug caused by mixing two lifecycle owners
     * on the same camera device.
     *
     * When idle, this screen binds its own Preview use case to the activity
     * lifecycle so the operator can aim before arming.
     */
    private fun bindPreviewOnly() {
        // While a session is armed, the service owns the camera exclusively.
        if (activeSession != null) {
            CaptureService.previewSurfaceProvider = binding.cameraPreview.surfaceProvider
            // Unbind any leftover preview from the activity lifecycle so CameraX
            // sees only one lifecycle owner for this camera.
            if (previewUseCase != null) unbindCamera()
            return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                previewUseCase?.let { runCatching { provider.unbind(it) } }
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
                }
                previewUseCase = preview
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            } catch (e: Exception) {
                Log.e("TRACE", "Camera failed", e)
                binding.statusText.text = "Camera failed: ${e.message}"
                appendLog(SessionLog.Kind.ERROR, "camera failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Releases this screen's preview only. */
    private fun unbindCamera() {
        val preview = previewUseCase
        previewUseCase = null
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                preview?.let { future.get().unbind(it) }
            } catch (e: Exception) {
                Log.w("TRACE", "Preview unbind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** The open manual-tag chooser, if any. Stops a double tap stacking two dialogs. */
    private var tagDialog: AlertDialog? = null

    /**
     * [SensorRegistry] ids the armed session captures from.
     *
     * Fixed for the life of the session, because the set is part of the hashed
     * session header (see [Session.sensorSet]) — changing it mid-session would
     * invalidate the chain that already anchors on it.
     */
    private var armedSensorIds: Set<String> = emptySet()

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

        // Notification channels live in [CaptureService] now (it owns both the
        // recording notice and the silent CONFIRMED alerts). The permission
        // request stays here: without POST_NOTIFICATIONS the recording notice
        // is invisible, which reads as "the app is doing something in secret".
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

        binding.btnTagIncident.setOnClickListener {
            showTagDialog()
        }

        binding.btnStartSession.setOnClickListener { showArmingSheet() }
        binding.btnEndSession.setOnClickListener { confirmEndSession() }

        // Live log tail: one adapter, newest line always scrolled into view.
        binding.logRecycler.layoutManager = LinearLayoutManager(this)
        binding.logRecycler.adapter = logAdapter
        binding.logRecycler.itemAnimator = null

        // Render the un-armed state before anything asynchronous happens: the app
        // starts idle and must say so, not claim to be watching sensors.
        updateSessionUi()
        refreshLog()

        if (allPermissionsGranted()) {
            onCaptureReady()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE)
        }
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
            if (allPermissionsGranted()) {
                onCaptureReady()
            } else {
                binding.statusText.text = "Permissions denied. TRACE needs Camera + Mic to operate."
                appendLog(SessionLog.Kind.ERROR, "permissions denied — capture unavailable")
            }
        }
    }

    /**
     * Everything that needs permissions, run once they exist.
     *
     * Note what does NOT happen here: no session is created and no capture starts.
     * A session begins only when the operator asks for one, which is what makes the
     * app bar's REC state truthful.
     */
    private fun onCaptureReady() {
        // Capture hardware is owned by [CaptureService]; this screen only shows
        // the preview and drives the lifecycle.

        // Preview only, so the operator can point the phone before arming.
        refreshCaptureLayout()

        appScope.launch {
            // A session left ACTIVE by a process that died is closed as INTERRUPTED.
            val recovered = SessionManager.recoverInterruptedSessions(
                database.sessionDao(),
                database.eventDao()
            )
            if (recovered > 0) {
                Log.i("TRACE", "Recovered $recovered interrupted session(s) from a previous run")
            }

            // Re-arm the session this process is already recording into, if any.
            // This is the ordinary case for an activity re-creation (a palette
            // change), where the recording must survive the new instance.
            val existing = SessionManager.currentSession(database.sessionDao())
            val count = existing?.let { database.eventDao().countEventsForSession(it.id) } ?: 0

            runOnUiThread {
                activeSession = existing
                armedSensorIds = existing?.sensorIds?.toSet() ?: emptySet()
                eventCount = LiveBus.eventCount
                if (existing != null) {
                    Log.i("TRACE", "Resumed session ${existing.id} with ${armedSensorIds.size} sensors")
                    // Hand the preview over to the service before starting it,
                    // so CameraSource binds with the surface provider ready.
                    refreshCaptureLayout()
                    // Re-attach capture if the service is not already running
                    // (first launch of a session armed before a re-creation).
                    if (!CaptureService.running) CaptureService.start(this@MainActivity)
                    // The buffer may have died with the previous instance;
                    // rebuild the tail from the chain.
                    seedLogFromDb(existing.id, count)
                    appendLog(
                        SessionLog.Kind.LIFECYCLE,
                        "session resumed · ${armedSensorIds.size} sensor(s) · $count event(s)"
                    )
                }
                updateSessionUi()
            }
        }
    }

    // ── Session lifecycle ─────────────────────────────────────────────────

    /** Opens the arming sheet. The sheet collects the description and sensor set. */
    private fun showArmingSheet() {
        if (activeSession != null) return   // already recording
        ArmingSheet().apply {
            onArm = { natureOfWork, sensorIds -> startSession(natureOfWork, sensorIds) }
        }.show(supportFragmentManager, "arming")
    }

    /**
     * Opens the session and arms capture with the operator's sensor selection.
     *
     * [SessionManager.startSession] returns the existing session unchanged when one
     * is already open, so a double tap on *Start session* cannot produce two
     * recordings of one walk around the site.
     */
    private fun startSession(natureOfWork: String, sensorIds: List<String>) {
        if (sensorIds.isEmpty()) return

        appScope.launch {
            val session = SessionManager.startSession(
                database.sessionDao(),
                natureOfWork = natureOfWork,
                sensorSet = SensorRegistry.sensorSetOf(sensorIds)
            )

            runOnUiThread {
                activeSession = session
                armedSensorIds = session.sensorIds.toSet()
                LiveBus.eventCount = 0
                LiveBus.confCamera = 0f
                LiveBus.confAudio = 0f
                LiveBus.confMotion = 0f
                eventCount = 0
                // A new session is a new transcript: leftovers from the last
                // recording must not read as part of this one.
                sessionLog.clear()
                refreshLog()
                // Switch camera from activity-bound preview to service-bound
                // preview BEFORE starting the service, so CameraSource receives
                // the surface provider during bindToLifecycle.
                refreshCaptureLayout()
                CaptureService.start(this@MainActivity)
                appendLog(
                    SessionLog.Kind.LIFECYCLE,
                    "session started · ${armedSensorIds.size} sensor(s)" +
                        if (natureOfWork.isNotBlank()) " · \"$natureOfWork\"" else ""
                )
                updateSessionUi()
            }
        }
    }

    /** End of session, behind a confirmation: one tap must not seal a recording. */
    private fun confirmEndSession() {
        val session = activeSession ?: return
        val elapsed = formatElapsed(System.currentTimeMillis() - session.startedAt)

        MaterialAlertDialogBuilder(this)
            .setTitle("End this session?")
            .setMessage(
                "Recording stops and the session closes as COMPLETED after $elapsed, " +
                    "with $eventCount event(s).\n\nNothing is deleted — it stays reviewable " +
                    "in Previous Sessions, and its chain stays verifiable."
            )
            .setNegativeButton("Keep recording", null)
            .setPositiveButton("End session") { _, _ -> endSession() }
            .show()
    }

    private fun endSession() {
        val session = activeSession ?: return
        // Stop capture first: no evidence may be produced after the operator
        // has decided to end. The service tears down and stops itself.
        CaptureService.stop(this)

        appScope.launch {
            val closed = SessionManager.endSession(
                database.sessionDao(),
                database.eventDao(),
                session.id
            )

            runOnUiThread {
                activeSession = null
                armedSensorIds = emptySet()
                LiveBus.eventCount = 0
                LiveBus.confCamera = 0f
                LiveBus.confAudio = 0f
                LiveBus.confMotion = 0f
                eventCount = 0
                bannerHide?.cancel()
                binding.confirmedBanner.visibility = View.GONE
                refreshCaptureLayout()

                appendLog(
                    SessionLog.Kind.LIFECYCLE,
                    if (closed != null) {
                        "session ended · ${closed.eventCount} event(s) · " +
                            "${closed.confirmedCount} confirmed"
                    } else {
                        "session ended"
                    }
                )
                updateSessionUi()

                // Written last: updateSessionUi() refreshes the idle status line.
                binding.statusText.text = if (closed != null) {
                    "Session ended · ${closed.eventCount} events · ${closed.confirmedCount} confirmed · " +
                        formatElapsed(closed.durationMs ?: 0L)
                } else {
                    "Session ended."
                }
            }
        }
    }

    // ── Capture layout ────────────────────────────────────────────────────

    /**
     * Decides what fills the space above the HUD.
     *
     *  - idle                    → live preview, so the operator can aim
     *  - armed with the camera    → preview + frame analysis
     *  - armed without the camera → the active-sensor panel
     *
     * The last case matters: a black viewfinder in a camera-free session looks like
     * a broken app at the exact moment it is working as configured.
     */
    private fun refreshCaptureLayout() {
        val capturing = activeSession != null
        val cameraSelected = SensorRegistry.CAMERA.id in armedSensorIds
        val showPreview = !capturing || cameraSelected

        binding.cameraPreview.visibility = if (showPreview) View.VISIBLE else View.GONE
        binding.sensorPanel.visibility = if (showPreview) View.GONE else View.VISIBLE

        if (!showPreview) binding.tvSensorPanelList.text = sensorPanelText()

        if (showPreview && allPermissionsGranted()) {
            bindPreviewOnly()
        } else {
            unbindCamera()
        }
    }

    /** "📷 Camera", one per armed sensor, in registry order. */
    private fun sensorPanelText(): String =
        SensorRegistry.ALL
            .filter { it.id in armedSensorIds }
            .joinToString("\n") { "${it.icon}  ${SensorRegistry.labelFor(it.id)}" }
            .ifEmpty { "No sensors selected" }

    /** Updates the on-screen status bar — this is what judges see on the phone. */
    private fun updateStatusBar(lastEventType: String? = null, lastStatus: String? = null) {
        val armed = activeSession != null
        val line1 = when {
            evidenceLocked.get() -> "🔒 EVIDENCE LOCKED — new observations are dropped"
            !armed -> "TRACE IDLE — no session armed, nothing is being recorded"
            lastEventType != null -> "LAST EVENT: $lastEventType  [$lastStatus]"
            else -> "TRACE ACTIVE — capturing from ${armedSensorIds.size} sensor(s)"
        }
        val line2 = if (armed) {
            "Events recorded: $eventCount   |   TAG marks an incident you saw"
        } else {
            "Tap START SESSION to arm a recording"
        }
        binding.statusText.text = "$line1\n$line2"
    }

    /**
     * Reflects the session lifecycle on the live view: which of Start / End is
     * offered, and whether the human-assertion control can do anything.
     */
    private fun updateSessionUi() {
        val armed = activeSession != null

        binding.btnStartSession.visibility = if (armed) View.GONE else View.VISIBLE
        binding.btnEndSession.visibility = if (armed) View.VISIBLE else View.GONE

        // A tag is evidence, so it needs a session to anchor on. The explicit
        // backgroundTint in the layout does not respond to the disabled state, so
        // alpha is what makes "cannot be used" visible.
        binding.btnTagIncident.isEnabled = armed
        binding.btnTagIncident.alpha = if (armed) 1f else 0.4f

        updateStatusBar()
        updateSessionStatus()
    }

    // ── App bar: session status chip ──────────────────────────────────────

    /** (Re)starts the once-a-second status tick. Main thread only. */
    private fun startStatusTicker() {
        statusTicker?.cancel()
        statusTicker = uiScope.launch {
            while (isActive) {
                updateSessionStatus()
                if (activeSession != null) {
                    // The service produces evidence while this screen may be
                    // paused; while resumed, mirror its live state here.
                    eventCount = LiveBus.eventCount
                    binding.tvCameraLive.text = "📷 ${"%.0f".format(LiveBus.confCamera * 100)}%"
                    binding.tvAudioLive.text = "🔊 ${"%.0f".format(LiveBus.confAudio * 100)}%"
                    binding.tvMotionLive.text = "📳 ${"%.0f".format(LiveBus.confMotion * 100)}%"
                    binding.tvLogCount.text = "$eventCount event(s)"
                    if (LiveBus.lastConfirmedAt > lastRenderedConfirmAt) {
                        lastRenderedConfirmAt = LiveBus.lastConfirmedAt
                        binding.confirmedBanner.visibility = View.VISIBLE
                        binding.tvConfirmedText.text = LiveBus.lastConfirmedText
                        bannerHide?.cancel()
                        bannerHide = uiScope.launch {
                            delay(BANNER_VISIBLE_MS)
                            binding.confirmedBanner.visibility = View.GONE
                        }
                    }
                    refreshLog()
                }
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

    // ── Live session log tail ─────────────────────────────────────────────

    /** Adds one line to the tail and re-renders. Any thread; rendered on the main thread. */
    private fun appendLog(kind: SessionLog.Kind, text: String, timestamp: Long =
                          System.currentTimeMillis()) {
        sessionLog.append(kind, text, timestamp)
        runOnUiThread { refreshLog() }
    }

    /** Re-renders the tail and pins the newest line at the bottom. Main thread only. */
    private fun refreshLog() {
        val entries = sessionLog.all()
        logAdapter.submit(entries)
        binding.tvLogCount.text = "$eventCount event(s)"
        if (entries.isNotEmpty()) {
            binding.logRecycler.scrollToPosition(logAdapter.itemCount - 1)
        }
    }

    /**
     * Rebuilds the tail from the session's most recent stored events.
     *
     * The buffer lives only for this process, so an activity re-creation or a
     * return from background would otherwise show an empty tail beside a chip
     * that says recording never stopped. Seeding from the database keeps the
     * screen truthful: it shows the chain's actual last events, one line each.
     */
    private fun seedLogFromDb(sessionId: Long, knownCount: Int) {
        sessionLog.clear()
        appScope.launch {
            val recent = database.eventDao().getRecentEventsForSession(
                sessionId, SessionLog.DEFAULT_CAPACITY
            )
            runOnUiThread {
                recent.forEach { e ->
                    if (FusionEngine.isHumanAsserted(e)) {
                        sessionLog.append(SessionLog.Kind.TAG, "✋ tagged: ${e.type.replace("_", " ")}", e.timestamp)
                    } else {
                        sessionLog.append(
                            SessionLog.Kind.EVENT,
                            "${SensorRegistry.iconFor(e.source)} ${e.type.replace("_", " ")}" +
                                " · ${SensorRegistry.labelFor(e.source)} · ${e.status}",
                            e.timestamp
                        )
                    }
                }
                refreshLog()
                if (recent.size < knownCount) {
                    appendLog(
                        SessionLog.Kind.INFO,
                        "… ${knownCount - recent.size} earlier entr(y|ies) in Previous Sessions"
                    )
                }
            }
        }
    }

    // ── App bar actions ───────────────────────────────────────────────────

    /** Handles the app bar's actions. */
    private fun onAppBarAction(itemId: Int): Boolean = when (itemId) {
        // Previous Sessions: the session-grouped entry point. The flat
        // all-events timeline stays reachable from the sessions list's menu.
        R.id.action_sessions -> {
            startActivity(Intent(this, SessionsActivity::class.java))
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

        // Nothing to detach: capture belongs to [CaptureService], which keeps
        // running when the screen turns off — that is the whole point of the
        // foreground service. The preview below only freezes with the screen.
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

        // Re-attach the armed set when the app comes back to the foreground. The
        // set cannot have changed while paused — it is fixed for the session — so
        // this is the same call that arming uses.
        if (evidenceLocked.get()) {
            appendLog(SessionLog.Kind.ERROR, "Evidence Lock is on — new observations are dropped")
        }

        if (activeSession != null && !CaptureService.running) {
            // A session armed before this screen existed (or a service the
            // system killed) — re-attach capture.
            CaptureService.start(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        appScope.cancel()

        // The session record stays open when this screen dies: the service owns
        // capture, and a process kill leaves the session ACTIVE for the next
        // launch to recover as INTERRUPTED. Only an explicit user exit ends it.
        if (isFinishing) {
            activeSession?.let { session ->
                CaptureService.stop(this)
                kotlinx.coroutines.runBlocking {
                    SessionManager.endSession(
                        database.sessionDao(),
                        database.eventDao(),
                        session.id
                    )
                }
                activeSession = null
            }
        }
        uiScope.cancel()
    }

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
        // A tag anchors on a session's chain, so there has to be one. The button is
        // disabled while idle; this covers the race where the session ends between
        // the tap and the write.
        val session = activeSession
        if (session == null) {
            Toast.makeText(this, "No session armed", Toast.LENGTH_SHORT).show()
            return
        }

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
            val stored = ChainWriter.append(database.eventDao(), session, tag)
            Log.i("TRACE", "MANUAL TAG: id=${stored.id} type=$type session=${session.id}")

            // The service owns the recorder and the camera — ask it to freeze
            // clip + photo around this moment.
            CaptureService.captureForEvent(this@MainActivity, stored.id)

            runOnUiThread {
                LiveBus.eventCount += 1
                eventCount = LiveBus.eventCount
                appendLog(SessionLog.Kind.TAG, "✋ tagged: $title", stored.timestamp)
                updateStatusBar(lastEventType = title, lastStatus = "MANUAL")
                Toast.makeText(this@MainActivity, "Tagged: $title", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

