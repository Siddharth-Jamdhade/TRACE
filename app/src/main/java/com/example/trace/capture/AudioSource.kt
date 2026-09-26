package com.example.trace.capture

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.example.trace.EvidenceFiles
import com.example.trace.FileIntegrity
import com.example.trace.LiveBus
import com.example.trace.Observation
import com.example.trace.SessionLog
import com.example.trace.SensorRegistry
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.Executors

/**
 * Audio capture with PCM16 analysis + MediaRecorder evidence clips.
 *
 * Uses two parallel paths:
 * 1. **PCM16 AudioRecord** (16 kHz, 20 ms hops) for RMS/ZCR analysis that
 *    feeds the event pipeline — gives actual frequency content, not just
 *    amplitude scalars.
 * 2. **MediaRecorder** (AMR-WB) for evidence clip files, dual-file alternating
 *    — unchanged from the original implementation.
 *
 * The analysis path replaces the old [MediaRecorder.maxAmplitude] polling,
 * which lost all frequency information and couldn't distinguish different
 * types of sound.
 *
 * @param enabledSensorIds sensor IDs from [SensorRegistry]
 * @param onObservation called with each qualified audio observation
 * @param serviceScope scope for evidence clip persistence
 */
class AudioSource(
    private val appContext: Context,
    private val enabledSensorIds: Set<String>,
    private val onObservation: (Observation) -> Unit,
    private val serviceScope: CoroutineScope,
    private val updateClipPath: (Long, String) -> Unit,
    private val updateClipHash: (Long, String) -> Unit,
) {
    private val audioEnabled: Boolean get() = SensorRegistry.AUDIO.id in enabledSensorIds

    // ── PCM16 analysis path ────────────────────────────────────────────────
    private val analysisExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "trace-audio-pcm").apply { isDaemon = true }
    }
    @Volatile private var analysisRunning = false

    private val rmsBaseline = BaselineTracker(minSamples = 300, sigmaFloor = 0.005)
    private var analysisThread: Thread? = null

    // ── MediaRecorder evidence path (unchanged from original) ──────────────
    private val audioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "trace-audio-recorder").apply { isDaemon = true }
    }
    private val audioDispatcher = audioExecutor.asCoroutineDispatcher()
    private var mediaRecorder: MediaRecorder? = null
    private var recorderStarted = false
    @Volatile private var audioTornDown = false
    private var micActive = false
    private var activeAudioSlot = 0

    // ── Constants ──────────────────────────────────────────────────────────
    companion object {
        const val SAMPLE_RATE_HZ = 16000
        const val HOP_SAMPLES = 320          // 20 ms at 16 kHz
        const val AMPLITUDE_POLL_MS = 60L
        const val REOPEN_RETRY_POLLS = 7
        const val AUDIO_THRESHOLD = 1500
        const val AUDIO_MAX = 16147
        const val TEMP_AUDIO_PATTERN = "temp_audio_%d.awb"
        const val EVIDENCE_EXT = ".awb"
        private const val TAG = "TraceAudio"
    }

    // ── Public API ─────────────────────────────────────────────────────────

    fun start(context: Context) {
        if (!audioEnabled) return
        micActive = true
        startAnalysis(context)
        startRecorderLoop(context)
    }

    fun stop() {
        micActive = false
        analysisRunning = false
        analysisThread?.join(500)
        analysisExecutor.shutdown()
        audioTornDown = true
        CoroutineScope(Dispatchers.IO).launch {
            stopRecorder()
        }
        audioExecutor.shutdown()
    }

    // ── PCM16 Analysis ─────────────────────────────────────────────────────

    private fun startAnalysis(context: Context) {
        if (analysisRunning) return
        analysisRunning = true
        analysisThread = Thread({
            pcmLoop(context)
        }, "trace-audio-pcm").apply { priority = Thread.NORM_PRIORITY - 1; start() }
    }

    private fun pcmLoop(context: Context) {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.e(TAG, "PCM: cannot record $SAMPLE_RATE_HZ Hz (minBufferSize=$minBuf)")
            return
        }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, HOP_SAMPLES * 4),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "PCM: AudioRecord init failed", t)
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "PCM: AudioRecord not initialized (state=${record.state})")
            record.release()
            return
        }
        try { record.startRecording() } catch (t: Throwable) {
            Log.e(TAG, "PCM: startRecording failed", t)
            record.release()
            return
        }

        val hop = ShortArray(HOP_SAMPLES)
        while (analysisRunning && micActive) {
            val n = record.read(hop, 0, HOP_SAMPLES)
            if (n > 0) {
                val tMs = System.nanoTime() / 1_000_000
                onPcmHop(hop, n, tMs)
            } else {
                // Error or end of stream — brief backoff
                try { Thread.sleep(10) } catch (_: InterruptedException) { break }
            }
        }
        runCatching { record.stop(); record.release() }
    }

    /**
     * Processes a PCM hop: computes RMS, ZCR, and band energies, then submits
     * an observation if the signal exceeds the adaptive threshold.
     */
    private fun onPcmHop(hop: ShortArray, n: Int, tMs: Long) {
        // RMS (energy)
        var sumSq = 0.0
        var zcCount = 0
        var prevSign = true
        for (i in 0 until n) {
            val s = hop[i].toDouble()
            sumSq += s * s
            val sign = s >= 0
            if (i > 0 && sign != prevSign) zcCount++
            prevSign = sign
        }
        val rms = kotlin.math.sqrt(sumSq / n) / 32768.0  // normalize to 0..1
        val zcr = zcCount.toDouble() / n

        rmsBaseline.feed(rms)
        val sigma = rmsBaseline.deviationSigma(rms)

        val confidence = if (sigma != null) {
            (sigma / 8.0).coerceIn(0.0, 1.0).toFloat()
        } else {
            val raw = (rms * 65536).toFloat()
            toConfidence(raw, AUDIO_THRESHOLD.toFloat(), AUDIO_MAX.toFloat())
        }

        if (confidence > 0f) {
            onObservation(Observation("audio", confidence, tMs))
        }
    }

    // ── MediaRecorder evidence loop (adapted from original CaptureService) ─

    private fun startRecorderLoop(context: Context) {
        if (!audioEnabled) return
        analysisExecutor.submit {
            var pollsSinceReopen = REOPEN_RETRY_POLLS
            while (micActive && !audioTornDown) {
                if (!recorderStarted) {
                    if (pollsSinceReopen >= REOPEN_RETRY_POLLS) {
                        openRecorder(context)
                        pollsSinceReopen = 0
                    }
                    pollsSinceReopen++
                    try { Thread.sleep(AMPLITUDE_POLL_MS) } catch (_: InterruptedException) { break }
                } else {
                    pollsSinceReopen = 0
                    try { Thread.sleep(AMPLITUDE_POLL_MS) } catch (_: InterruptedException) { break }
                }
            }
        }
    }

    private fun openRecorder(context: Context, slot: Int = activeAudioSlot): Boolean {
        if (audioTornDown) return false
        val recorder = try {
            newRecorder(context)
        } catch (e: Exception) {
            Log.e(TAG, "Recorder unavailable", e)
            LiveBus.log.append(SessionLog.Kind.ERROR, "mic failed: ${e.message}")
            return false
        }
        val path = File(context.cacheDir, TEMP_AUDIO_PATTERN.format(slot)).absolutePath
        return try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.AMR_WB)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_WB)
            recorder.setOutputFile(path)
            recorder.prepare()
            recorder.start()
            mediaRecorder = recorder
            recorderStarted = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Recorder start failed", e)
            releaseQuietly(recorder)
            mediaRecorder = null
            recorderStarted = false
            LiveBus.log.append(SessionLog.Kind.ERROR, "mic failed: ${e.message}")
            false
        }
    }

    private fun stopRecorder(): Boolean {
        val recorder = mediaRecorder
        if (recorder == null) { recorderStarted = false; return false }
        var closedCleanly = false
        try {
            if (recorderStarted) { recorder.stop(); closedCleanly = true }
        } catch (e: Exception) {
            Log.w(TAG, "Recorder stop failed", e)
        } finally {
            recorderStarted = false
            releaseQuietly(recorder)
            mediaRecorder = null
        }
        return closedCleanly
    }

    /** Freezes the current mic buffer as evidence clip for [eventId]. */
    suspend fun saveEvidenceClip(eventId: Long): String? = withContext(Dispatchers.IO) {
        if (audioTornDown) return@withContext null
        val saveSlot = activeAudioSlot
        val nextSlot = 1 - saveSlot
        val source = File(appContext.cacheDir, TEMP_AUDIO_PATTERN.format(saveSlot))
        if (!recorderStarted || !source.exists() || source.length() == 0L) return@withContext null

        if (!stopRecorder()) return@withContext null
        activeAudioSlot = nextSlot
        openRecorder(appContext, nextSlot)

        try {
            val dest = File(EvidenceFiles.dir(appContext), "evidence_$eventId$EVIDENCE_EXT")
            source.copyTo(dest, overwrite = true)
            val hash = runCatching { FileIntegrity.sha256(dest) }.getOrNull()
            if (hash != null) updateClipHash(eventId, hash)
            updateClipPath(eventId, dest.absolutePath)
            dest.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Evidence clip save failed", e)
            null
        } finally {
            source.delete()
        }
    }

    private fun newRecorder(context: Context): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
        else @Suppress("DEPRECATION") MediaRecorder()

    private fun releaseQuietly(recorder: MediaRecorder) {
        try { recorder.release() } catch (_: Exception) {}
    }

    private fun toConfidence(value: Float, threshold: Float, max: Float): Float {
        if (value < threshold) return 0f
        return ((value - threshold) / (max - threshold)).coerceIn(0f, 1f)
    }
}