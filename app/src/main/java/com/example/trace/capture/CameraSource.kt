package com.example.trace.capture

import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.trace.DeviceProfile
import com.example.trace.EvidenceFiles
import com.example.trace.FileIntegrity
import com.example.trace.LiveBus
import com.example.trace.Observation
import com.example.trace.SessionLog
import com.example.trace.SensorRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CameraX capture source with frame-differencing analysis and snapshot capture.
 *
 * Ported from ECHO's CameraSource. Uses [DeviceProfile] to select resolution
 * appropriate to the device tier.
 *
 * @param profile device capability profile for resolution selection
 * @param enabledSensorIds sensor IDs from [SensorRegistry]
 * @param onObservation called for each frame-differencing observation
 * @param serviceScope scope for snapshot persistence
 * @param db DAO access for evidence path updates
 */
class CameraSource(
    private val profile: DeviceProfile,
    private val enabledSensorIds: Set<String>,
    private val onObservation: (Observation) -> Unit,
    private val serviceScope: CoroutineScope,
    private val updatePhotoPath: (Long, String) -> Unit,
    private val updatePhotoHash: (Long, String) -> Unit,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var appContext: android.content.Context? = null
    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var previewUseCase: Preview? = null
    private var previousFrame: ByteArray? = null
    private var capturePending = false

    private val diffW = 160
    private val diffH = 120
    private val baseline = BaselineTracker(minSamples = 50, sigmaFloor = 0.002)

    private val frameMeter = RateMeter()
    private var lastRatePublish = 0L

    /** Whether camera should be active for this session. */
    private val cameraEnabled: Boolean get() = SensorRegistry.CAMERA.id in enabledSensorIds

    fun start(context: android.content.Context, owner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider? = null) {
        if (!cameraEnabled) return
        appContext = context
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                val cp = future.get()
                provider = cp
                val resWidth = profile.visionWidth
                val resHeight = profile.visionHeight

                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(resWidth, resHeight))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor) { proxy -> analyze(proxy) } }

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageCapture = capture

                val preview = Preview.Builder().build()
                // If the surface provider is already set by the activity, wire it
                // now. Otherwise the activity will push it via setPreviewSurfaceProvider
                // once ready — the Preview use case accepts late binding.
                if (surfaceProvider != null) preview.setSurfaceProvider(surfaceProvider)
                previewUseCase = preview

                // unbindAll() prevents CAM FREEZES from mixing two lifecycle owners
                cp.unbindAll()
                val useCases = if (surfaceProvider != null) {
                    arrayOf(analysis, capture, preview)
                } else {
                    arrayOf(analysis, capture)
                }
                cp.bindToLifecycle(
                    owner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    *useCases,
                )
                LiveBus.log.append(SessionLog.Kind.LIFECYCLE, "camera bound: ${resWidth}x$resHeight")
            }.onFailure { e ->
                Log.e("TRACE", "camera bind failed", e)
                LiveBus.log.append(SessionLog.Kind.ERROR, "camera failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            val tMs = proxy.imageInfo.timestamp / 1_000_000L
            frameMeter.tick(tMs)

            val plane = proxy.planes[0]
            if (plane.pixelStride != 1) return
            val w = proxy.width; val h = proxy.height
            val buffer = plane.buffer
            val data = ByteArray(w * h)
            var offset = 0
            for (row in 0 until h) {
                buffer.position(row * plane.rowStride)
                buffer.get(data, offset, w)
                offset += w
            }

            // Downscale to 160x120 then diff
            val scaled = ByteArray(diffW * diffH)
            downscale(data, w, h, diffW, diffH, scaled)

            val prev = previousFrame
            if (prev != null) {
                var changed = 0
                for (i in scaled.indices) {
                    val diff = kotlin.math.abs(
                        (scaled[i].toInt() and 0xFF) - (prev[i].toInt() and 0xFF)
                    )
                    if (diff > 30) changed++
                }
                val ratio = changed.toFloat() / scaled.size
                baseline.feed(ratio.toDouble())
                val sigma = baseline.deviationSigma(ratio.toDouble())

                val confidence = if (sigma != null) {
                    (sigma / 8.0).coerceIn(0.0, 1.0).toFloat()
                } else {
                    toConfidence(ratio, 0.05f, 0.30f)
                }
                if (confidence > 0f) {
                    onObservation(Observation("camera", confidence, tMs))
                }
            }
            previousFrame = scaled
        } catch (t: Throwable) {
            Log.e("TRACE", "frame analysis failed", t)
        } finally {
            proxy.close()
        }
    }

    /** Simple bilinear downscale. */
    private fun downscale(src: ByteArray, sw: Int, sh: Int, dw: Int, dh: Int, dest: ByteArray) {
        val xRatio = sw.toDouble() / dw
        val yRatio = sh.toDouble() / dh
        for (dy in 0 until dh) {
            val srcY = (dy * yRatio).toInt().coerceAtMost(sh - 1)
            for (dx in 0 until dw) {
                val srcX = (dx * xRatio).toInt().coerceAtMost(sw - 1)
                dest[dy * dw + dx] = src[srcY * sw + srcX]
            }
        }
    }

    /** Captures a snapshot frame for [eventId] and persists the path + hash. */
    fun takeSnapshot(eventId: Long) {
        val capture = imageCapture ?: return
        if (capturePending) return
        capturePending = true

        val ctx = appContext ?: return
        val dest = File(EvidenceFiles.dir(ctx), "photo_$eventId.jpg")
        val opts = ImageCapture.OutputFileOptions.Builder(dest).build()
        capture.takePicture(
            opts,
            ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    capturePending = false
                    val path = result.savedUri?.path ?: dest.absolutePath
                    serviceScope.launch {
                        val hash = runCatching { FileIntegrity.sha256(File(path)) }.getOrNull()
                        updatePhotoPath(eventId, path)
                        if (hash != null) updatePhotoHash(eventId, hash)
                    }
                }
                override fun onError(exc: ImageCaptureException) {
                    capturePending = false
                    Log.w("TRACE", "snapshot failed for event $eventId", exc)
                    dest.delete()
                }
            },
        )
    }

    fun stop() {
        runCatching { provider?.unbindAll() }
        provider = null
        executor.shutdown()
        previousFrame = null
        imageCapture = null
        previewUseCase = null
    }

    /**
     * Attaches (or replaces) the preview surface provider.
     *
     * Safe to call after [start] — CameraX applies it immediately. This lets the
     * activity set its surface provider after CameraSource has already bound to
     * the lifecycle, covering timing edge cases where the provider arrives late.
     */
    fun setPreviewSurfaceProvider(surfaceProvider: Preview.SurfaceProvider) {
        previewUseCase?.setSurfaceProvider(surfaceProvider)
    }

    companion object {
        private fun toConfidence(value: Float, threshold: Float, max: Float): Float {
            if (value < threshold) return 0f
            return ((value - threshold) / (max - threshold)).coerceIn(0f, 1f)
        }
    }
}