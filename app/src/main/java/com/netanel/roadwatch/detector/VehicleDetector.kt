package com.netanel.roadwatch.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import com.netanel.roadwatch.core.Box
import com.netanel.roadwatch.core.Detection
import com.netanel.roadwatch.core.VehicleClass
import java.util.concurrent.atomic.AtomicBoolean

class VehicleDetector(
    private val context: Context,
    private val listener: Listener
) : AutoCloseable {

    interface Listener {
        fun onReady(delegateName: String)
        fun onResult(result: Result)
        fun onError(message: String)
    }

    data class Result(
        val detections: List<Detection>,
        val inferenceMs: Long,
        val rotatedWidth: Int,
        val rotatedHeight: Int,
        val timestampMs: Long
    )

    private var detector: ObjectDetector? = null
    private var bitmapBuffer: Bitmap? = null
    private val inFlight = AtomicBoolean(false)
    @Volatile private var ready = false

    fun initialize() {
        close()
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .setDelegate(Delegate.CPU)
                .build()

            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setScoreThreshold(0.10f)
                .setMaxResults(30)
                .setResultListener(this::onMediaPipeResult)
                .setErrorListener { error ->
                    inFlight.set(false)
                    listener.onError(error.message ?: "Object detector error")
                }
                .build()

            detector = ObjectDetector.createFromOptions(context, options)
            ready = true
            listener.onReady("EfficientDet-Lite2 INT8 · CPU")
        } catch (t: Throwable) {
            ready = false
            listener.onError("AI init failed: ${t.message}")
        }
    }

    fun analyze(imageProxy: ImageProxy) {
        val activeDetector = detector
        if (!ready || activeDetector == null || !inFlight.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        try {
            val rotation = imageProxy.imageInfo.rotationDegrees
            val frameWidth = imageProxy.width
            val frameHeight = imageProxy.height
            val bitmap = bitmapBuffer?.takeIf {
                it.width == frameWidth && it.height == frameHeight && !it.isRecycled
            } ?: Bitmap.createBitmap(
                frameWidth,
                frameHeight,
                Bitmap.Config.ARGB_8888
            ).also { created ->
                bitmapBuffer?.takeIf { !it.isRecycled }?.recycle()
                bitmapBuffer = created
            }
            val buffer = imageProxy.planes[0].buffer
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)
            imageProxy.close()

            val mpImage = BitmapImageBuilder(bitmap).build()
            val processing = ImageProcessingOptions.builder()
                .setRotationDegrees(rotation)
                .build()
            val timestamp = SystemClock.uptimeMillis()
            FrameMetadataRegistry.put(timestamp, frameWidth, frameHeight, rotation)
            activeDetector.detectAsync(mpImage, processing, timestamp)
        } catch (t: Throwable) {
            imageProxy.close()
            inFlight.set(false)
            listener.onError("Frame processing failed: ${t.message}")
        }
    }

    private fun onMediaPipeResult(result: ObjectDetectorResult, input: MPImage) {
        val now = SystemClock.uptimeMillis()
        val timestamp = result.timestampMs()
        val metadata = FrameMetadataRegistry.take(timestamp)
        val originalWidth = metadata?.width ?: input.width
        val originalHeight = metadata?.height ?: input.height
        val rotation = metadata?.rotation ?: 0
        val rotatedWidth = if (rotation == 90 || rotation == 270) originalHeight else originalWidth
        val rotatedHeight = if (rotation == 90 || rotation == 270) originalWidth else originalHeight

        val detections = result.detections().mapNotNull { detection ->
            val category = detection.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
            val vehicleClass = VehicleClass.fromLabel(category.categoryName())
            if (vehicleClass == VehicleClass.UNKNOWN) return@mapNotNull null
            val rotated = rotateRect(
                RectF(detection.boundingBox()),
                originalWidth,
                originalHeight,
                rotation
            )
            Detection(
                box = Box(
                    rotated.left / rotatedWidth,
                    rotated.top / rotatedHeight,
                    rotated.right / rotatedWidth,
                    rotated.bottom / rotatedHeight
                ).clamp01(),
                vehicleClass = vehicleClass,
                confidence = category.score()
            )
        }

        inFlight.set(false)
        listener.onResult(
            Result(
                detections = detections,
                inferenceMs = (now - timestamp).coerceAtLeast(0L),
                rotatedWidth = rotatedWidth,
                rotatedHeight = rotatedHeight,
                timestampMs = timestamp
            )
        )
    }

    private fun rotateRect(rect: RectF, width: Int, height: Int, rotation: Int): RectF = when ((rotation % 360 + 360) % 360) {
        0 -> RectF(rect)
        90 -> RectF(
            height - rect.bottom,
            rect.left,
            height - rect.top,
            rect.right
        )
        180 -> RectF(
            width - rect.right,
            height - rect.bottom,
            width - rect.left,
            height - rect.top
        )
        270 -> RectF(
            rect.top,
            width - rect.right,
            rect.bottom,
            width - rect.left
        )
        else -> RectF(rect)
    }

    override fun close() {
        ready = false
        inFlight.set(false)
        detector?.close()
        detector = null
        bitmapBuffer?.takeIf { !it.isRecycled }?.recycle()
        bitmapBuffer = null
        FrameMetadataRegistry.clear()
    }

    private data class FrameMetadata(val width: Int, val height: Int, val rotation: Int)

    private object FrameMetadataRegistry {
        private val map = linkedMapOf<Long, FrameMetadata>()
        @Synchronized fun put(timestamp: Long, width: Int, height: Int, rotation: Int) {
            map[timestamp] = FrameMetadata(width, height, rotation)
            while (map.size > 6) map.remove(map.keys.first())
        }
        @Synchronized fun take(timestamp: Long): FrameMetadata? = map.remove(timestamp)
        @Synchronized fun clear() = map.clear()
    }

    companion object {
        const val MODEL_ASSET = "efficientdet_lite2_int8.tflite"
    }
}
