package com.example.trace

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

object VideoAnalyzer {

    private const val TAG = "TRACE_VIDEO"
    private const val FRAME_INTERVAL_MS = 5_000L
    private const val MIN_LABEL_CONFIDENCE = 0.65f

    suspend fun analyzeVideo(
        context: Context,
        uri: Uri,
        videoStartMs: Long,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<Event> = withContext(Dispatchers.IO) {

        val events = mutableListOf<Event>()
        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(context, uri)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: return@withContext emptyList()
            if (durationMs == 0L) return@withContext emptyList()

            val totalFrames = (durationMs / FRAME_INTERVAL_MS).toInt().coerceAtLeast(1)
            Log.d(TAG, "Analysing video: duration=ms, frames=")

            val labeler = ImageLabeling.getClient(
                ImageLabelerOptions.Builder()
                    .setConfidenceThreshold(MIN_LABEL_CONFIDENCE)
                    .build()
            )

            var frameIndex = 0
            var offsetMs = 0L

            while (offsetMs <= durationMs) {
                onProgress(frameIndex, totalFrames)
                val bitmap: Bitmap? = try {
                    retriever.getFrameAtTime(offsetMs * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } catch (e: Exception) { null }

                if (bitmap != null) {
                    val labels = labelBitmap(labeler, bitmap)
                    bitmap.recycle()
                    if (labels.isNotEmpty()) {
                        val topLabel = labels.first()
                        val eventType = topLabel.text.lowercase().replace(" ", "_").take(40)
                        events.add(Event(
                            type       = eventType,
                            timestamp  = videoStartMs + offsetMs,
                            source     = "video",
                            confidence = topLabel.confidence
                        ))
                    }
                }
                frameIndex++
                offsetMs += FRAME_INTERVAL_MS
            }
            labeler.close()
        } catch (e: Exception) {
            Log.e(TAG, "Video analysis failed", e)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        events
    }

    private suspend fun labelBitmap(
        labeler: com.google.mlkit.vision.label.ImageLabeler,
        bitmap: Bitmap
    ) = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        labeler.process(image)
            .addOnSuccessListener { labels -> cont.resume(labels) }
            .addOnFailureListener { cont.resume(emptyList()) }
    }

    fun getVideoCreationTime(context: Context, uri: Uri): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val dateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            retriever.release()
            if (dateStr != null) parseVideoDate(dateStr) ?: System.currentTimeMillis()
            else System.currentTimeMillis()
        } catch (e: Exception) { System.currentTimeMillis() }
    }

    private fun parseVideoDate(dateStr: String): Long? = try {
        val fmt = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val clean = dateStr.substringBefore('.').substringBefore('Z')
        fmt.parse(clean)?.time
    } catch (e: Exception) { null }
}