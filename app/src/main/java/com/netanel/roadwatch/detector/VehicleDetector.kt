package com.netanel.roadwatch.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import com.netanel.roadwatch.core.Box
import com.netanel.roadwatch.core.Detection
import com.netanel.roadwatch.core.VehicleClass
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * RoadWatch V2 detector.
 *
 * Two complementary on-device YOLO models are fused:
 *  1) YOLO26n COCO detector for normal street / oblique views.
 *  2) YOLO26n-OBB DOTA detector for steep / aerial / rotated vehicle views.
 *
 * The second model is adaptive: it runs every second analyzed frame when the
 * street detector already sees vehicles, and every frame when the street model
 * sees none. CameraX keeps only the newest frame, so inference can never build
 * up a latency queue.
 */
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
        val generalMs: Long,
        val aerialMs: Long,
        val generalVehicles: Int,
        val aerialVehicles: Int,
        val rotatedWidth: Int,
        val rotatedHeight: Int,
        val timestampMs: Long,
        val engineLabel: String
    )

    private enum class Source { GENERAL, AERIAL }

    private data class Candidate(
        val box: Box,
        val vehicleClass: VehicleClass,
        val confidence: Float,
        val source: Source
    )

    private data class Letterbox(
        val gain: Float,
        val padX: Float,
        val padY: Float,
        val imageWidth: Int,
        val imageHeight: Int,
        val modelWidth: Int,
        val modelHeight: Int
    )

    private var general: LiteRtRunner? = null
    private var aerial: LiteRtRunner? = null

    private var sourceBitmap: Bitmap? = null
    private var rotatedBitmap: Bitmap? = null
    private var modelBitmap: Bitmap? = null
    private var modelPixels = IntArray(0)
    private var nchwInput = FloatArray(0)
    private var nhwcInput = FloatArray(0)
    private var packedBuffer: ByteBuffer? = null
    private var rowBytes = ByteArray(0)

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rotationMatrix = Matrix()
    private var frameNumber = 0L
    @Volatile private var ready = false

    fun initialize() {
        close()

        var generalError: Throwable? = null
        var aerialError: Throwable? = null

        try {
            general = LiteRtRunner(context, GENERAL_MODEL, preferGpu = true)
        } catch (t: Throwable) {
            generalError = t
        }

        try {
            aerial = LiteRtRunner(context, AERIAL_MODEL, preferGpu = true)
        } catch (t: Throwable) {
            aerialError = t
        }

        if (general == null && aerial == null) {
            ready = false
            listener.onError(
                "YOLO init failed · street=${generalError?.message ?: "?"} · aerial=${aerialError?.message ?: "?"}"
            )
            return
        }

        val reference = general ?: aerial!!
        require(reference.inputWidth == reference.inputHeight) {
            "RoadWatch expects a square YOLO input"
        }

        // Official mobile assets are 640x640. Keep the code tolerant of another
        // square size so a custom fine-tuned model can replace them later.
        ensureModelBuffers(reference.inputWidth, reference.inputHeight)

        ready = true
        listener.onReady(engineLabel())
    }

    fun analyze(imageProxy: ImageProxy) {
        if (!ready) {
            imageProxy.close()
            return
        }

        val frameTimestamp = SystemClock.uptimeMillis()
        try {
            val frameWidth = imageProxy.width
            val frameHeight = imageProxy.height
            val rotation = normalizeRotation(imageProxy.imageInfo.rotationDegrees)

            val bitmap = ensureSourceBitmap(frameWidth, frameHeight)
            copyRgbaFrame(imageProxy, bitmap)
            imageProxy.close()

            val oriented = orientFrame(bitmap, rotation)
            val rotatedWidth = oriented.width
            val rotatedHeight = oriented.height

            val ref = general ?: aerial ?: error("No detector available")
            val letterbox = letterbox(oriented, ref.inputWidth, ref.inputHeight)
            preparePixels(ref.inputWidth, ref.inputHeight)

            frameNumber++
            val start = SystemClock.elapsedRealtime()

            val generalRunner = general
            val generalCandidates: List<Candidate>
            val generalMs: Long
            if (generalRunner != null) {
                val input = inputFor(generalRunner)
                val t0 = SystemClock.elapsedRealtime()
                val outputs = generalRunner.run(input)
                generalMs = SystemClock.elapsedRealtime() - t0
                generalCandidates = decodeGeneral(generalRunner, outputs, letterbox)
            } else {
                generalMs = 0L
                generalCandidates = emptyList()
            }

            // A steep-angle model is the safety net. When the normal detector sees
            // nothing, it is run every frame. Otherwise every second frame is enough
            // for parking / occupancy while keeping the preview fluid.
            val shouldRunAerial = aerial != null &&
                (generalCandidates.isEmpty() || frameNumber % 2L == 0L)

            val aerialCandidates: List<Candidate>
            val aerialMs: Long
            val aerialRunner = aerial
            if (shouldRunAerial && aerialRunner != null) {
                val input = inputFor(aerialRunner)
                val t0 = SystemClock.elapsedRealtime()
                val outputs = aerialRunner.run(input)
                aerialMs = SystemClock.elapsedRealtime() - t0
                aerialCandidates = decodeAerial(aerialRunner, outputs, letterbox)
            } else {
                aerialMs = 0L
                aerialCandidates = emptyList()
            }

            val fused = fuse(generalCandidates + aerialCandidates)
                .map { Detection(it.box, it.vehicleClass, it.confidence) }

            val totalMs = SystemClock.elapsedRealtime() - start
            listener.onResult(
                Result(
                    detections = fused,
                    inferenceMs = totalMs,
                    generalMs = generalMs,
                    aerialMs = aerialMs,
                    generalVehicles = generalCandidates.size,
                    aerialVehicles = aerialCandidates.size,
                    rotatedWidth = rotatedWidth,
                    rotatedHeight = rotatedHeight,
                    timestampMs = frameTimestamp,
                    engineLabel = engineLabel()
                )
            )
        } catch (t: Throwable) {
            runCatching { imageProxy.close() }
            listener.onError("AI frame failed: ${t.message}")
        }
    }

    /** Copy CameraX RGBA_8888 safely even on devices that return padded rows. */
    private fun copyRgbaFrame(imageProxy: ImageProxy, target: Bitmap) {
        val plane = imageProxy.planes.firstOrNull() ?: error("Camera frame has no RGBA plane")
        val buffer = plane.buffer.duplicate()
        buffer.rewind()
        val width = imageProxy.width
        val height = imageProxy.height
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        if (pixelStride == 4 && rowStride == width * 4) {
            target.copyPixelsFromBuffer(buffer)
            return
        }

        if (pixelStride != 4) {
            // CameraX RGBA_8888 is expected to use 4 bytes per pixel. If a vendor
            // implementation violates that, use CameraX's own safe conversion.
            val converted = imageProxy.toBitmap()
            Canvas(target).drawBitmap(converted, 0f, 0f, bitmapPaint)
            if (converted !== target && !converted.isRecycled) converted.recycle()
            return
        }

        val packedSize = width * height * 4
        val packed = packedBuffer
            ?.takeIf { it.capacity() >= packedSize }
            ?: ByteBuffer.allocateDirect(packedSize)
                .order(ByteOrder.nativeOrder())
                .also { packedBuffer = it }
        if (rowBytes.size < width * 4) rowBytes = ByteArray(width * 4)

        packed.clear()
        for (y in 0 until height) {
            buffer.position(y * rowStride)
            buffer.get(rowBytes, 0, width * 4)
            packed.put(rowBytes, 0, width * 4)
        }
        packed.flip()
        target.copyPixelsFromBuffer(packed)
    }

    private fun ensureSourceBitmap(width: Int, height: Int): Bitmap {
        val current = sourceBitmap
        if (current != null && current.width == width && current.height == height && !current.isRecycled) {
            return current
        }
        current?.takeIf { !it.isRecycled }?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { sourceBitmap = it }
    }

    private fun orientFrame(source: Bitmap, rotation: Int): Bitmap {
        val outWidth = if (rotation == 90 || rotation == 270) source.height else source.width
        val outHeight = if (rotation == 90 || rotation == 270) source.width else source.height

        val current = rotatedBitmap
        val target = if (
            current != null && current.width == outWidth && current.height == outHeight && !current.isRecycled
        ) {
            current
        } else {
            current?.takeIf { !it.isRecycled }?.recycle()
            Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
                .also { rotatedBitmap = it }
        }

        val canvas = Canvas(target)
        canvas.drawColor(Color.BLACK)
        rotationMatrix.reset()
        rotationMatrix.setRotate(rotation.toFloat())
        val bounds = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
        rotationMatrix.mapRect(bounds)
        rotationMatrix.postTranslate(-bounds.left, -bounds.top)
        canvas.drawBitmap(source, rotationMatrix, bitmapPaint)
        return target
    }

    private fun ensureModelBuffers(width: Int, height: Int) {
        val current = modelBitmap
        if (current == null || current.width != width || current.height != height || current.isRecycled) {
            current?.takeIf { !it.isRecycled }?.recycle()
            modelBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        val pixels = width * height
        if (modelPixels.size != pixels) modelPixels = IntArray(pixels)
        if (nchwInput.size != pixels * 3) nchwInput = FloatArray(pixels * 3)
        if (nhwcInput.size != pixels * 3) nhwcInput = FloatArray(pixels * 3)
    }

    private fun letterbox(oriented: Bitmap, modelWidth: Int, modelHeight: Int): Letterbox {
        ensureModelBuffers(modelWidth, modelHeight)
        val target = modelBitmap ?: error("Model bitmap unavailable")
        val canvas = Canvas(target)
        canvas.drawColor(Color.rgb(114, 114, 114))

        val gain = min(
            modelWidth.toFloat() / oriented.width.toFloat(),
            modelHeight.toFloat() / oriented.height.toFloat()
        )
        val drawW = oriented.width * gain
        val drawH = oriented.height * gain
        val padX = (modelWidth - drawW) * 0.5f
        val padY = (modelHeight - drawH) * 0.5f
        val dst = RectF(padX, padY, padX + drawW, padY + drawH)
        canvas.drawBitmap(oriented, null, dst, bitmapPaint)

        return Letterbox(
            gain = gain,
            padX = padX,
            padY = padY,
            imageWidth = oriented.width,
            imageHeight = oriented.height,
            modelWidth = modelWidth,
            modelHeight = modelHeight
        )
    }

    private fun preparePixels(width: Int, height: Int) {
        val bitmap = modelBitmap ?: error("Model bitmap unavailable")
        bitmap.getPixels(modelPixels, 0, width, 0, 0, width, height)

        val plane = width * height
        for (i in 0 until plane) {
            val p = modelPixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f

            val base = i * 3
            nhwcInput[base] = r
            nhwcInput[base + 1] = g
            nhwcInput[base + 2] = b

            nchwInput[i] = r
            nchwInput[plane + i] = g
            nchwInput[plane * 2 + i] = b
        }
    }

    private fun inputFor(runner: LiteRtRunner): FloatArray =
        if (runner.inputUsesNchw) nchwInput else nhwcInput

    private fun decodeGeneral(
        runner: LiteRtRunner,
        outputs: List<FloatArray>,
        transform: Letterbox
    ): List<Candidate> {
        val flat = outputs.firstOrNull() ?: return emptyList()
        val dims = runner.outputDims.firstOrNull() ?: IntArray(0)
        if (dims.size < 3) return emptyList()

        val d1 = dims[1]
        val d2 = dims[2]
        val candidates = mutableListOf<Candidate>()

        // End-to-end [1, N, 6]: x1,y1,x2,y2,score,class
        if (d2 in 6..8 && d2 < d1) {
            for (row in 0 until d1) {
                val base = row * d2
                if (base + 5 >= flat.size) break
                val score = flat[base + 4]
                if (score < GENERAL_CONF) continue
                val cls = flat[base + 5].toInt()
                val vehicleClass = cocoVehicleClass(cls) ?: continue
                val box = modelRectToNormalized(
                    flat[base], flat[base + 1], flat[base + 2], flat[base + 3], transform
                ) ?: continue
                candidates += Candidate(box, vehicleClass, score, Source.GENERAL)
            }
            return nms(candidates, 0.55f, 35)
        }

        // Raw Ultralytics detect head [1, 4+classes, anchors]
        val features = d1
        val anchors = d2
        if (features < 8 || anchors <= 0) return emptyList()
        val classRows = features - 4

        for (i in 0 until anchors) {
            var bestScore = 0f
            var bestClass = -1
            for (cls in COCO_VEHICLE_IDS) {
                if (cls >= classRows) continue
                val idx = (4 + cls) * anchors + i
                if (idx >= flat.size) continue
                val score = flat[idx]
                if (score > bestScore) {
                    bestScore = score
                    bestClass = cls
                }
            }
            if (bestScore < GENERAL_CONF) continue
            val vehicleClass = cocoVehicleClass(bestClass) ?: continue

            var cx = flat[i]
            var cy = flat[anchors + i]
            var w = flat[2 * anchors + i]
            var h = flat[3 * anchors + i]
            val normalizedOutput = max(max(abs(cx), abs(cy)), max(abs(w), abs(h))) <= 2f
            if (normalizedOutput) {
                cx *= transform.modelWidth
                cy *= transform.modelHeight
                w *= transform.modelWidth
                h *= transform.modelHeight
            }

            val box = modelRectToNormalized(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, transform)
                ?: continue
            if (box.area < 0.00012f) continue
            candidates += Candidate(box, vehicleClass, bestScore, Source.GENERAL)
        }
        return nms(candidates, 0.55f, 35)
    }

    private fun decodeAerial(
        runner: LiteRtRunner,
        outputs: List<FloatArray>,
        transform: Letterbox
    ): List<Candidate> {
        val flat = outputs.firstOrNull() ?: return emptyList()
        val dims = runner.outputDims.firstOrNull() ?: IntArray(0)
        if (dims.size < 3) return emptyList()

        val channels = dims[1]
        val anchors = dims[2]
        val candidates = mutableListOf<Candidate>()

        // Current OBB raw head: 4 box + DOTA classes + 1 angle.
        if (channels >= 7 && anchors > channels) {
            val numClasses = channels - 5
            val angleRow = (4 + numClasses) * anchors
            for (i in 0 until anchors) {
                var bestScore = 0f
                var bestClass = -1
                for (cls in DOTA_VEHICLE_IDS) {
                    if (cls >= numClasses) continue
                    val idx = (4 + cls) * anchors + i
                    if (idx >= flat.size) continue
                    val score = flat[idx]
                    if (score > bestScore) {
                        bestScore = score
                        bestClass = cls
                    }
                }
                if (bestScore < AERIAL_CONF) continue

                var cx = flat[i]
                var cy = flat[anchors + i]
                var w = flat[2 * anchors + i]
                var h = flat[3 * anchors + i]
                val normalizedOutput = max(max(abs(cx), abs(cy)), max(abs(w), abs(h))) <= 2f
                if (normalizedOutput) {
                    cx *= transform.modelWidth
                    cy *= transform.modelHeight
                    w *= transform.modelWidth
                    h *= transform.modelHeight
                }
                val angle = if (angleRow + i < flat.size) flat[angleRow + i] else 0f
                val aabb = rotatedBoxAabb(cx, cy, w, h, angle)
                val box = modelRectToNormalized(aabb.left, aabb.top, aabb.right, aabb.bottom, transform)
                    ?: continue
                if (box.area < 0.00010f) continue
                val vehicleClass = if (bestClass == DOTA_LARGE_VEHICLE) VehicleClass.TRUCK else VehicleClass.CAR
                candidates += Candidate(box, vehicleClass, bestScore * 0.96f, Source.AERIAL)
            }
            return nms(candidates, 0.50f, 35)
        }

        // Defensive support for an end-to-end OBB [1,N,7] export.
        if (anchors in 7..9 && anchors < channels) {
            for (row in 0 until channels) {
                val base = row * anchors
                if (base + 6 >= flat.size) break
                val score = flat[base + 4]
                if (score < AERIAL_CONF) continue
                val cls = flat[base + 5].toInt()
                if (cls !in DOTA_VEHICLE_IDS) continue
                val box = modelRectToNormalized(
                    flat[base], flat[base + 1], flat[base + 2], flat[base + 3], transform
                ) ?: continue
                val vehicleClass = if (cls == DOTA_LARGE_VEHICLE) VehicleClass.TRUCK else VehicleClass.CAR
                candidates += Candidate(box, vehicleClass, score * 0.96f, Source.AERIAL)
            }
        }
        return nms(candidates, 0.50f, 35)
    }

    private fun rotatedBoxAabb(cx: Float, cy: Float, w: Float, h: Float, angle: Float): RectF {
        val c = cos(angle)
        val s = sin(angle)
        val hw = w / 2f
        val hh = h / 2f
        val xs = floatArrayOf(-hw, hw, hw, -hw)
        val ys = floatArrayOf(-hh, -hh, hh, hh)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in 0..3) {
            val x = cx + xs[i] * c - ys[i] * s
            val y = cy + xs[i] * s + ys[i] * c
            minX = min(minX, x)
            minY = min(minY, y)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
        }
        return RectF(minX, minY, maxX, maxY)
    }

    private fun modelRectToNormalized(
        rawLeft: Float,
        rawTop: Float,
        rawRight: Float,
        rawBottom: Float,
        t: Letterbox
    ): Box? {
        var left = rawLeft
        var top = rawTop
        var right = rawRight
        var bottom = rawBottom
        val normalizedOutput = max(max(abs(left), abs(top)), max(abs(right), abs(bottom))) <= 2f
        if (normalizedOutput) {
            left *= t.modelWidth
            right *= t.modelWidth
            top *= t.modelHeight
            bottom *= t.modelHeight
        }

        val x1 = (min(left, right) - t.padX) / t.gain
        val y1 = (min(top, bottom) - t.padY) / t.gain
        val x2 = (max(left, right) - t.padX) / t.gain
        val y2 = (max(top, bottom) - t.padY) / t.gain

        if (x2 <= 0f || y2 <= 0f || x1 >= t.imageWidth || y1 >= t.imageHeight) return null
        val box = Box(
            x1 / t.imageWidth,
            y1 / t.imageHeight,
            x2 / t.imageWidth,
            y2 / t.imageHeight
        ).clamp01()
        return box.takeIf { it.width > 0.004f && it.height > 0.004f }
    }

    private fun nms(input: List<Candidate>, iouThreshold: Float, limit: Int): List<Candidate> {
        if (input.isEmpty()) return emptyList()
        val sorted = input.sortedByDescending { it.confidence }
        val keep = mutableListOf<Candidate>()
        for (candidate in sorted) {
            if (keep.any { it.box.iou(candidate.box) > iouThreshold }) continue
            keep += candidate
            if (keep.size >= limit) break
        }
        return keep
    }

    /**
     * Cross-model fusion. General YOLO wins the class label when both models see
     * the same vehicle; aerial OBB remains able to create a detection on its own.
     */
    private fun fuse(input: List<Candidate>): List<Candidate> {
        if (input.isEmpty()) return emptyList()
        val sorted = input.sortedByDescending {
            it.confidence + if (it.source == Source.GENERAL) 0.015f else 0f
        }
        val out = mutableListOf<Candidate>()

        for (candidate in sorted) {
            val index = out.indexOfFirst { it.box.iou(candidate.box) >= 0.48f }
            if (index < 0) {
                out += candidate
                continue
            }
            val existing = out[index]
            val preferred = when {
                existing.source == Source.GENERAL -> existing
                candidate.source == Source.GENERAL -> candidate
                candidate.confidence > existing.confidence -> candidate
                else -> existing
            }
            out[index] = preferred.copy(confidence = max(existing.confidence, candidate.confidence))
        }
        return out.sortedByDescending { it.confidence }.take(40)
    }

    private fun cocoVehicleClass(classId: Int): VehicleClass? = when (classId) {
        2 -> VehicleClass.CAR
        3 -> VehicleClass.MOTORCYCLE
        5 -> VehicleClass.BUS
        7 -> VehicleClass.TRUCK
        else -> null
    }

    private fun engineLabel(): String {
        val g = general?.accelerator ?: "OFF"
        val a = aerial?.accelerator ?: "OFF"
        return "YOLO26 DUAL · street $g · aerial $a"
    }

    private fun normalizeRotation(value: Int): Int = ((value % 360) + 360) % 360

    override fun close() {
        ready = false
        runCatching { general?.close() }
        runCatching { aerial?.close() }
        general = null
        aerial = null
        sourceBitmap?.takeIf { !it.isRecycled }?.recycle()
        rotatedBitmap?.takeIf { !it.isRecycled }?.recycle()
        modelBitmap?.takeIf { !it.isRecycled }?.recycle()
        sourceBitmap = null
        rotatedBitmap = null
        modelBitmap = null
        packedBuffer = null
        rowBytes = ByteArray(0)
    }

    private class LiteRtRunner(
        private val context: Context,
        private val assetName: String,
        preferGpu: Boolean
    ) : AutoCloseable {

        private data class Prepared(
            val model: CompiledModel,
            val inputs: List<TensorBuffer>,
            val outputs: List<TensorBuffer>,
            val nativeDims: IntArray,
            val outputDims: List<IntArray>
        )

        private val modelFile: File = materializeAsset(assetName)
        private val model: CompiledModel
        private val inputs: List<TensorBuffer>
        private val outputs: List<TensorBuffer>
        val inputUsesNchw: Boolean
        val inputWidth: Int
        val inputHeight: Int
        val outputDims: List<IntArray>
        val accelerator: String

        init {
            var prepared: Prepared? = null
            var acceleratorName = "CPU"

            if (preferGpu) {
                try {
                    prepared = prepare(Accelerator.GPU)
                    acceleratorName = "GPU"
                } catch (_: Throwable) {
                    prepared = null
                }
            }
            if (prepared == null) {
                prepared = prepare(Accelerator.CPU)
                acceleratorName = "CPU"
            }

            model = prepared.model
            inputs = prepared.inputs
            outputs = prepared.outputs
            outputDims = prepared.outputDims
            accelerator = acceleratorName

            val native = prepared.nativeDims
            require(native.size >= 4) { "Unsupported YOLO input shape for $assetName: ${native.toList()}" }
            inputUsesNchw = native[1] == 3 && native.last() != 3
            if (inputUsesNchw) {
                inputHeight = native[2]
                inputWidth = native[3]
            } else {
                inputHeight = native[1]
                inputWidth = native[2]
            }
            require(inputWidth > 0 && inputHeight > 0) { "Invalid input dimensions for $assetName" }
        }

        private fun prepare(accelerator: Accelerator): Prepared {
            val options = CompiledModel.Options(accelerator)
            if (accelerator == Accelerator.CPU) {
                options.cpuOptions = CompiledModel.CpuOptions(
                    numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                )
            }

            val compiled = CompiledModel.create(modelFile.absolutePath, options)
            val inBuffers = compiled.createInputBuffers()
            val outBuffers = compiled.createOutputBuffers()

            try {
                val nativeDims = sequenceOf("args_0", "images", "input", "input_1")
                    .firstNotNullOfOrNull { name ->
                        runCatching {
                            compiled.getInputTensorType(inputName = name)
                                .layout?.dimensions?.toIntArray()
                        }.getOrNull()?.takeIf { it.isNotEmpty() }
                    }
                    ?: inferSquareInput(inBuffers.first().readFloat().size)

                val inputCount = nativeDims.fold(1) { acc, n -> acc * n }
                inBuffers.first().writeFloat(FloatArray(inputCount))
                compiled.run(inBuffers, outBuffers)

                val shapes = outBuffers.indices.map { index ->
                    val legacy = if (index == 0) "Identity" else "Identity_$index"
                    sequenceOf("output_$index", "output$index", legacy)
                        .firstNotNullOfOrNull { name ->
                            runCatching {
                                compiled.getOutputTensorType(outputName = name)
                                    .layout?.dimensions?.toIntArray()
                            }.getOrNull()?.takeIf { it.isNotEmpty() }
                        }
                        ?: inferOutputShape(outBuffers[index].readFloat().size)
                }

                return Prepared(compiled, inBuffers, outBuffers, nativeDims, shapes)
            } catch (t: Throwable) {
                inBuffers.forEach { runCatching { it.close() } }
                outBuffers.forEach { runCatching { it.close() } }
                runCatching { compiled.close() }
                throw t
            }
        }

        private fun inferSquareInput(count: Int): IntArray {
            val side = sqrt((count / 3.0)).toInt()
            require(side > 0 && side * side * 3 == count) { "Cannot infer YOLO input shape" }
            return intArrayOf(1, 3, side, side)
        }

        private fun inferOutputShape(count: Int): IntArray {
            // Current official models expose output_0, so this is only a defensive
            // fallback. Use the known task first to avoid ambiguous factors.
            val featureOrder = if (assetName.contains("obb", ignoreCase = true)) {
                intArrayOf(20, 19, 7, 84, 6)
            } else {
                intArrayOf(84, 85, 6, 7, 20)
            }
            for (features in featureOrder) {
                if (count % features == 0) return intArrayOf(1, features, count / features)
            }
            return intArrayOf(1, count, 1)
        }

        fun run(input: FloatArray): List<FloatArray> {
            inputs.first().writeFloat(input)
            model.run(inputs, outputs)
            return outputs.map { it.readFloat() }
        }

        private fun materializeAsset(name: String): File {
            val target = File(context.noBackupFilesDir, name)
            if (target.exists() && target.length() > 1_000_000L) return target
            target.parentFile?.mkdirs()
            context.assets.open(name).use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            require(target.length() > 1_000_000L) { "Model asset $name is missing or incomplete" }
            return target
        }

        override fun close() {
            inputs.forEach { runCatching { it.close() } }
            outputs.forEach { runCatching { it.close() } }
            runCatching { model.close() }
        }
    }

    companion object {
        const val GENERAL_MODEL = "yolo26n_w8a32.tflite"
        const val AERIAL_MODEL = "yolo26n_obb_w8a32.tflite"

        private const val GENERAL_CONF = 0.12f
        private const val AERIAL_CONF = 0.10f

        private val COCO_VEHICLE_IDS = intArrayOf(2, 3, 5, 7)
        private const val DOTA_LARGE_VEHICLE = 9
        private const val DOTA_SMALL_VEHICLE = 10
        private val DOTA_VEHICLE_IDS = intArrayOf(DOTA_LARGE_VEHICLE, DOTA_SMALL_VEHICLE)
    }
}
