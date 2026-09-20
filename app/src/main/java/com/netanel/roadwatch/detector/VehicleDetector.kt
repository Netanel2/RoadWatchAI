package com.netanel.roadwatch.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import com.netanel.roadwatch.core.Box
import com.netanel.roadwatch.core.CrosswalkEstimate
import com.netanel.roadwatch.core.Detection
import com.netanel.roadwatch.core.PersonDetection
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
        val personDetections: List<PersonDetection>,
        val crosswalk: CrosswalkEstimate?,
        val inferenceMs: Long,
        val generalMs: Long,
        val aerialMs: Long,
        val generalVehicles: Int,
        val aerialVehicles: Int,
        val rotatedWidth: Int,
        val rotatedHeight: Int,
        val timestampMs: Long,
        val engineLabel: String,
        val sceneChanged: Boolean = false
    )

    private enum class Source { GENERAL, AERIAL }

    private data class Candidate(
        val box: Box,
        val vehicleClass: VehicleClass,
        val confidence: Float,
        val source: Source
    )

    private data class GeneralDecoded(
        val vehicles: List<Candidate>,
        val people: List<PersonDetection>
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
    private var lastInferenceStartedMs = 0L
    private var sceneBaseline: FloatArray? = null
    private var sceneMismatchChecks = 0
    @Volatile private var crosswalkSearchEnabled = true
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

        ensureModelBuffers(reference.inputWidth, reference.inputHeight)
        ready = true
        listener.onReady(engineLabel())
    }

    fun setCrosswalkSearchEnabled(enabled: Boolean) {
        crosswalkSearchEnabled = enabled
    }

    fun analyze(imageProxy: ImageProxy) {
        if (!ready) {
            imageProxy.close()
            return
        }

        val frameTimestamp = SystemClock.uptimeMillis()
        // Keep the camera preview independent and smooth. The AI does not need to
        // process every sensor frame; tracking history fills the gaps.
        if (frameTimestamp - lastInferenceStartedMs < MIN_INFERENCE_INTERVAL_MS) {
            imageProxy.close()
            return
        }
        lastInferenceStartedMs = frameTimestamp
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
            val sceneChanged = detectSceneChange(oriented)
            if (sceneChanged) crosswalkSearchEnabled = true

            val ref = general ?: aerial ?: error("No detector available")
            val letterbox = letterbox(oriented, ref.inputWidth, ref.inputHeight)
            preparePixels(ref.inputWidth, ref.inputHeight)

            frameNumber++
            val start = SystemClock.elapsedRealtime()

            val generalRunner = general
            val generalDecoded: GeneralDecoded
            val generalMs: Long
            if (generalRunner != null) {
                val input = inputFor(generalRunner)
                val t0 = SystemClock.elapsedRealtime()
                val outputs = generalRunner.run(input)
                generalMs = SystemClock.elapsedRealtime() - t0
                generalDecoded = decodeGeneral(generalRunner, outputs, letterbox)
            } else {
                generalMs = 0L
                generalDecoded = GeneralDecoded(emptyList(), emptyList())
            }

            val generalCandidates = generalDecoded.vehicles

            val shouldRunAerial = aerial != null && (
                (generalCandidates.isEmpty() && frameNumber % 2L == 0L) ||
                    frameNumber % AERIAL_EVERY_N == 0L
                )

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

            // V7: add one magnified overlapping tile per frame for small / distant people.
            // The full-frame pass stays in place, but the tile makes pedestrians roughly
            // 1.8x larger to YOLO. Four tiles are cycled, so the tracker receives a
            // refreshed high-resolution observation of every part of the scene.
            val tiledPeople = if (
                generalRunner != null &&
                generalDecoded.people.isEmpty() &&
                frameNumber % PERSON_TILE_EVERY_N == 0L
            ) {
                detectPeopleInTile(oriented, generalRunner, frameNumber)
            } else {
                emptyList()
            }
            val personDetections = nmsPeople(generalDecoded.people + tiledPeople, 0.42f, 30)

            val fused = fuse(generalCandidates + aerialCandidates)
                .map { Detection(it.box, it.vehicleClass, it.confidence) }

            val crosswalk = if (crosswalkSearchEnabled && frameNumber % CROSSWALK_SCAN_EVERY_N == 0L) {
                estimateCrosswalk(oriented)
            } else {
                null
            }
            val totalMs = SystemClock.elapsedRealtime() - start
            listener.onResult(
                Result(
                    detections = fused,
                    personDetections = personDetections,
                    crosswalk = crosswalk,
                    inferenceMs = totalMs,
                    generalMs = generalMs,
                    aerialMs = aerialMs,
                    generalVehicles = generalCandidates.size,
                    aerialVehicles = aerialCandidates.size,
                    rotatedWidth = rotatedWidth,
                    rotatedHeight = rotatedHeight,
                    timestampMs = frameTimestamp,
                    engineLabel = engineLabel(),
                    sceneChanged = sceneChanged
                )
            )
        } catch (t: Throwable) {
            runCatching { imageProxy.close() }
            listener.onError("AI frame failed: ${t.message}")
        }
    }

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
            throw IllegalStateException("Unsupported RGBA pixel stride: $pixelStride")
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

    private fun letterboxRegion(
        oriented: Bitmap,
        region: Box,
        modelWidth: Int,
        modelHeight: Int
    ): Letterbox {
        ensureModelBuffers(modelWidth, modelHeight)
        val target = modelBitmap ?: error("Model bitmap unavailable")
        val canvas = Canvas(target)
        canvas.drawColor(Color.rgb(114, 114, 114))

        val srcLeft = (region.left * oriented.width).coerceIn(0f, oriented.width.toFloat())
        val srcTop = (region.top * oriented.height).coerceIn(0f, oriented.height.toFloat())
        val srcRight = (region.right * oriented.width).coerceIn(srcLeft + 1f, oriented.width.toFloat())
        val srcBottom = (region.bottom * oriented.height).coerceIn(srcTop + 1f, oriented.height.toFloat())
        val cropWidth = (srcRight - srcLeft).coerceAtLeast(1f)
        val cropHeight = (srcBottom - srcTop).coerceAtLeast(1f)

        val gain = min(modelWidth / cropWidth, modelHeight / cropHeight)
        val drawW = cropWidth * gain
        val drawH = cropHeight * gain
        val padX = (modelWidth - drawW) * 0.5f
        val padY = (modelHeight - drawH) * 0.5f
        canvas.drawBitmap(
            oriented,
            Rect(srcLeft.toInt(), srcTop.toInt(), srcRight.toInt(), srcBottom.toInt()),
            RectF(padX, padY, padX + drawW, padY + drawH),
            bitmapPaint
        )

        return Letterbox(
            gain = gain,
            padX = padX,
            padY = padY,
            imageWidth = cropWidth.toInt().coerceAtLeast(1),
            imageHeight = cropHeight.toInt().coerceAtLeast(1),
            modelWidth = modelWidth,
            modelHeight = modelHeight
        )
    }

    private fun detectPeopleInTile(
        oriented: Bitmap,
        runner: LiteRtRunner,
        frameIndex: Long
    ): List<PersonDetection> {
        val region = PERSON_TILES[(((frameIndex - 1L) % PERSON_TILES.size.toLong()).toInt())]
        val tileTransform = letterboxRegion(oriented, region, runner.inputWidth, runner.inputHeight)
        preparePixels(runner.inputWidth, runner.inputHeight)
        val decoded = decodeGeneral(runner, runner.run(inputFor(runner)), tileTransform).people
        return decoded.map { person ->
            val b = person.box
            val mapped = Box(
                left = region.left + b.left * region.width,
                top = region.top + b.top * region.height,
                right = region.left + b.right * region.width,
                bottom = region.top + b.bottom * region.height
            ).clamp01()
            PersonDetection(mapped, person.confidence)
        }
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
    ): GeneralDecoded {
        val flat = outputs.firstOrNull() ?: return GeneralDecoded(emptyList(), emptyList())
        val dims = runner.outputDims.firstOrNull() ?: IntArray(0)
        if (dims.size < 3) return GeneralDecoded(emptyList(), emptyList())

        val d1 = dims[1]
        val d2 = dims[2]
        val vehicles = mutableListOf<Candidate>()
        val people = mutableListOf<PersonDetection>()

        if (d2 in 6..8 && d2 < d1) {
            for (row in 0 until d1) {
                val base = row * d2
                if (base + 5 >= flat.size) break
                val score = flat[base + 4]
                if (score < minOf(GENERAL_CONF, PERSON_CONF)) continue
                val cls = flat[base + 5].toInt()
                val box = modelRectToNormalized(
                    flat[base], flat[base + 1], flat[base + 2], flat[base + 3], transform
                ) ?: continue

                if (cls == PERSON_CLASS_ID && score >= PERSON_CONF && personShapePlausible(box, score)) {
                    people += PersonDetection(box, score)
                }
                val vehicleClass = cocoVehicleClass(cls)
                if (vehicleClass != null && score >= GENERAL_CONF && vehicleShapePlausible(box, vehicleClass, score)) {
                    vehicles += Candidate(box, vehicleClass, score, Source.GENERAL)
                }
            }
            return GeneralDecoded(
                vehicles = nms(vehicles, 0.55f, 35),
                people = nmsPeople(people, 0.45f, 20)
            )
        }

        val features = d1
        val anchors = d2
        if (features < 8 || anchors <= 0) return GeneralDecoded(emptyList(), emptyList())
        val classRows = features - 4

        for (i in 0 until anchors) {
            var bestVehicleScore = 0f
            var bestVehicleClass = -1
            for (cls in COCO_VEHICLE_IDS) {
                if (cls >= classRows) continue
                val idx = (4 + cls) * anchors + i
                if (idx >= flat.size) continue
                val score = flat[idx]
                if (score > bestVehicleScore) {
                    bestVehicleScore = score
                    bestVehicleClass = cls
                }
            }

            val personScore = if (PERSON_CLASS_ID < classRows) {
                val idx = (4 + PERSON_CLASS_ID) * anchors + i
                if (idx in flat.indices) flat[idx] else 0f
            } else 0f

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

            if (personScore >= PERSON_CONF && personShapePlausible(box, personScore)) {
                people += PersonDetection(box, personScore)
            }

            if (bestVehicleScore >= GENERAL_CONF) {
                val vehicleClass = cocoVehicleClass(bestVehicleClass) ?: continue
                if (box.area < 0.00012f) continue
                if (!vehicleShapePlausible(box, vehicleClass, bestVehicleScore)) continue
                vehicles += Candidate(box, vehicleClass, bestVehicleScore, Source.GENERAL)
            }
        }
        return GeneralDecoded(
            vehicles = nms(vehicles, 0.55f, 35),
            people = nmsPeople(people, 0.45f, 20)
        )
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
                if (!vehicleShapePlausible(box, vehicleClass, bestScore)) continue
                candidates += Candidate(box, vehicleClass, bestScore * 0.96f, Source.AERIAL)
            }
            return nms(candidates, 0.50f, 35)
        }

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
                if (!vehicleShapePlausible(box, vehicleClass, score)) continue
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

    private fun nmsPeople(input: List<PersonDetection>, iouThreshold: Float, limit: Int): List<PersonDetection> {
        if (input.isEmpty()) return emptyList()
        val sorted = input.sortedByDescending { it.confidence }
        val keep = mutableListOf<PersonDetection>()
        for (candidate in sorted) {
            if (keep.any { it.box.iou(candidate.box) > iouThreshold }) continue
            keep += candidate
            if (keep.size >= limit) break
        }
        return keep
    }

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

    private fun vehicleShapePlausible(box: Box, vehicleClass: VehicleClass, confidence: Float): Boolean {
        val aspect = box.width / max(0.0001f, box.height)
        if (box.area < 0.00010f || box.area > 0.45f) return false
        if (aspect < 0.42f || aspect > 5.8f) return false
        // V6: very weak tiny detections are a common source of "electrical box = car" errors.
        // Keep 0.30-0.32 detections available only when the object occupies enough pixels;
        // the tracker separately requires >=0.32 to create a new vehicle track.
        if (confidence < 0.34f && box.area < 0.0010f) return false
        if (vehicleClass == VehicleClass.BUS || vehicleClass == VehicleClass.TRUCK) {
            if (box.area < 0.00022f) return false
        }
        return true
    }

    private fun personShapePlausible(box: Box, confidence: Float): Boolean {
        val aspect = box.width / max(0.0001f, box.height)
        // People are often tiny from Netanel's high street camera. V6 lowers the
        // detector floor and lets the temporal PersonTracker decide whether a weak
        // detection is real across multiple frames.
        if (box.area < 0.000035f || box.area > 0.20f) return false
        if (aspect < 0.12f || aspect > 1.45f) return false
        if (confidence < 0.28f && box.area < 0.000070f) return false
        return true
    }

    /**
     * Best-effort zebra-crossing estimator. It searches for repeated bright stripe
     * bands in BOTH image axes, so steep/rotated camera angles are supported better
     * than the V5 row-only detector. The result is intentionally stabilized by
     * CrosswalkLock before it is used by behavior logic.
     */
    /**
     * Detect a material camera/scene move from a tiny normalized luminance grid.
     * Global exposure changes are mostly cancelled by subtracting the frame mean,
     * while a new street / large pan changes the spatial pattern.
     */
    private fun detectSceneChange(bitmap: Bitmap): Boolean {
        if (frameNumber % SCENE_CHECK_EVERY_N != 0L) return false
        val signature = sceneSignature(bitmap)
        val baseline = sceneBaseline
        if (baseline == null || baseline.size != signature.size) {
            sceneBaseline = signature
            sceneMismatchChecks = 0
            return false
        }

        var diff = 0f
        for (i in signature.indices) diff += kotlin.math.abs(signature[i] - baseline[i])
        diff /= signature.size.coerceAtLeast(1)

        if (diff >= SCENE_CHANGE_THRESHOLD) {
            sceneMismatchChecks++
            if (sceneMismatchChecks >= SCENE_CHANGE_CONFIRMATIONS) {
                sceneBaseline = signature
                sceneMismatchChecks = 0
                return true
            }
        } else {
            sceneMismatchChecks = 0
            for (i in baseline.indices) baseline[i] = baseline[i] * 0.97f + signature[i] * 0.03f
        }
        return false
    }

    private fun sceneSignature(bitmap: Bitmap): FloatArray {
        val cols = 6
        val rows = 4
        val values = FloatArray(cols * rows)
        var mean = 0f
        var index = 0
        for (row in 0 until rows) {
            val y = (((row + 0.5f) / rows) * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
            for (col in 0 until cols) {
                val x = (((col + 0.5f) / cols) * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                val pixel = bitmap.getPixel(x, y)
                val lum = (Color.red(pixel) * 0.299f + Color.green(pixel) * 0.587f + Color.blue(pixel) * 0.114f) / 255f
                values[index++] = lum
                mean += lum
            }
        }
        mean /= values.size
        for (i in values.indices) values[i] -= mean
        return values
    }

    private fun estimateCrosswalk(bitmap: Bitmap): CrosswalkEstimate? {
        val horizontal = estimateStripeCluster(bitmap, scanRows = true)
        val vertical = estimateStripeCluster(bitmap, scanRows = false)
        return listOfNotNull(horizontal, vertical).maxByOrNull { it.confidence }
    }

    private fun estimateStripeCluster(bitmap: Bitmap, scanRows: Boolean): CrosswalkEstimate? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 120 || height < 120) return null

        val xStart = (width * 0.03f).toInt()
        val xEnd = (width * 0.97f).toInt().coerceAtLeast(xStart + 1)
        val yStart = (height * 0.28f).toInt()
        val yEnd = (height * 0.96f).toInt().coerceAtLeast(yStart + 1)
        val step = 3

        val majorCount = if (scanRows) (yEnd - yStart) / step else (xEnd - xStart) / step
        val minorCount = if (scanRows) (xEnd - xStart) / step else (yEnd - yStart) / step
        if (majorCount < 12 || minorCount < 12) return null

        data class Band(val index: Int, val start: Int, val end: Int, val length: Int)
        val bands = mutableListOf<Band>()

        // V7 deliberately looks for one CONTIGUOUS neutral-white run on each scan
        // line. V6 counted every bright pixel on a row, which merged curbs + lane
        // markings into one enormous fake crosswalk.
        for (major in 0 until majorCount) {
            var bestStart = -1
            var bestEnd = -1
            var bestLength = 0
            var runStart = -1
            var lastWhite = -1
            var gap = 0

            fun finishRun() {
                if (runStart >= 0 && lastWhite >= runStart) {
                    val len = lastWhite - runStart + 1
                    if (len > bestLength) {
                        bestLength = len
                        bestStart = runStart
                        bestEnd = lastWhite
                    }
                }
                runStart = -1
                lastWhite = -1
                gap = 0
            }

            for (minor in 0 until minorCount) {
                val x = if (scanRows) xStart + minor * step else xStart + major * step
                val y = if (scanRows) yStart + major * step else yStart + minor * step
                val white = isWhiteLike(bitmap.getPixel(x.coerceIn(0, width - 1), y.coerceIn(0, height - 1)))
                if (white) {
                    if (runStart < 0) runStart = minor
                    lastWhite = minor
                    gap = 0
                } else if (runStart >= 0) {
                    gap++
                    if (gap > 1) finishRun()
                }
            }
            finishRun()

            if (bestLength >= 5) {
                val ratio = bestLength.toFloat() / minorCount
                if (ratio in 0.055f..0.48f) {
                    bands += Band(major, bestStart, bestEnd, bestLength)
                }
            }
        }
        if (bands.size < 6) return null

        data class Stripe(
            var first: Int,
            var last: Int,
            var start: Int,
            var end: Int,
            var centerMinor: Float,
            var peak: Int
        )

        val stripes = mutableListOf<Stripe>()
        for (band in bands) {
            val center = (band.start + band.end) * 0.5f
            val last = stripes.lastOrNull()
            val similarCenter = last != null && kotlin.math.abs(center - last.centerMinor) <= max(8f, band.length * 0.60f)
            if (last != null && band.index - last.last <= 2 && similarCenter) {
                last.last = band.index
                last.start = min(last.start, band.start)
                last.end = max(last.end, band.end)
                last.centerMinor = (last.centerMinor + center) * 0.5f
                last.peak = max(last.peak, band.length)
            } else {
                stripes += Stripe(band.index, band.index, band.start, band.end, center, band.length)
            }
        }
        if (stripes.size < 4) return null

        fun median(values: List<Float>): Float {
            if (values.isEmpty()) return 0f
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) * 0.5f
        }

        var bestBox: Box? = null
        var bestScore = Float.NEGATIVE_INFINITY
        var bestStripeCount = 0
        var bestOverlap = 0f

        for (start in stripes.indices) {
            val maxEnd = min(stripes.lastIndex, start + 8)
            for (end in (start + 3)..maxEnd) {
                if (end > stripes.lastIndex) break
                val group = stripes.subList(start, end + 1)
                val majorCenters = group.map { (it.first + it.last) * 0.5f }
                val gaps = majorCenters.zipWithNext { a, b -> b - a }
                val medianGap = median(gaps)
                if (medianGap < 2f || medianGap > majorCount * 0.14f) continue
                val maxGapError = gaps.maxOfOrNull { kotlin.math.abs(it - medianGap) } ?: 0f
                if (maxGapError > max(3f, medianGap * 0.65f)) continue

                val widths = group.map { (it.end - it.start + 1).toFloat() }
                val minWidth = widths.minOrNull() ?: continue
                val maxWidth = widths.maxOrNull() ?: continue
                if (minWidth / maxWidth < 0.35f) continue

                val overlapStart = group.maxOf { it.start }
                val overlapEnd = group.minOf { it.end }
                val unionStart = group.minOf { it.start }
                val unionEnd = group.maxOf { it.end }
                val union = unionEnd - unionStart + 1
                val overlap = (overlapEnd - overlapStart + 1).coerceAtLeast(0)
                if (union < minorCount * 0.07f) continue
                val overlapRatio = overlap.toFloat() / union
                if (overlapRatio < 0.25f) continue

                val majorFirst = group.first().first
                val majorLast = group.last().last
                val box = if (scanRows) {
                    Box(
                        left = (xStart + unionStart * step).toFloat() / width,
                        top = (yStart + majorFirst * step).toFloat() / height,
                        right = (xStart + (unionEnd + 1) * step).toFloat() / width,
                        bottom = (yStart + (majorLast + 1) * step).toFloat() / height
                    )
                } else {
                    Box(
                        left = (xStart + majorFirst * step).toFloat() / width,
                        top = (yStart + unionStart * step).toFloat() / height,
                        right = (xStart + (majorLast + 1) * step).toFloat() / width,
                        bottom = (yStart + (unionEnd + 1) * step).toFloat() / height
                    )
                }.clamp01()

                // Reject the exact V6 failure mode: a curb / lane-marking band that
                // stretches across most of the road. A zebra cluster should be local.
                if (box.width < 0.08f || box.height < 0.035f) continue
                if (box.width > 0.68f || box.height > 0.50f || box.area > 0.20f) continue

                val centerY = box.center.y
                val lowerSceneBonus = ((centerY - 0.35f).coerceAtLeast(0f) * 0.25f)
                val widthConsistency = minWidth / maxWidth
                val stripeWidthRatio = (widths.average().toFloat() / minorCount).coerceIn(0f, 1f)
                val score = group.size * 0.12f +
                    overlapRatio * 0.55f +
                    widthConsistency * 0.22f +
                    stripeWidthRatio * 0.80f +
                    lowerSceneBonus

                if (score > bestScore) {
                    bestScore = score
                    bestBox = box
                    bestStripeCount = group.size
                    bestOverlap = overlapRatio
                }
            }
        }

        val box = bestBox ?: return null
        val confidence = (
            0.43f +
                (bestStripeCount.coerceAtMost(8) - 4) * 0.055f +
                bestOverlap * 0.18f +
                (bestScore - 0.70f).coerceIn(0f, 0.18f)
            ).coerceIn(0f, 0.95f)
        return CrosswalkEstimate(box, confidence)
    }

    private fun isWhiteLike(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        val maxRgb = max(r, max(g, b))
        val minRgb = min(r, min(g, b))
        val brightness = (r + g + b) / 3
        // Night mode: accept moderately bright neutral paint, but reject strongly
        // colored lamps and red/white curb paint as much as possible.
        return brightness >= 138 && maxRgb - minRgb <= 48
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
        lastInferenceStartedMs = 0L
        sceneBaseline = null
        sceneMismatchChecks = 0
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

        private const val GENERAL_CONF = 0.30f
        private const val AERIAL_CONF = 0.34f
        private const val PERSON_CONF = 0.18f
        private const val PERSON_CLASS_ID = 0
        private const val MIN_INFERENCE_INTERVAL_MS = 90L // ~11 AI FPS; preview stays native-speed
        private const val AERIAL_EVERY_N = 4L
        private const val PERSON_TILE_EVERY_N = 2L
        private const val CROSSWALK_SCAN_EVERY_N = 2L
        private const val SCENE_CHECK_EVERY_N = 8L
        private const val SCENE_CHANGE_THRESHOLD = 0.16f
        private const val SCENE_CHANGE_CONFIRMATIONS = 3

        // Overlapping 2x2 scene tiles for the extra small-person pass.
        private val PERSON_TILES = listOf(
            Box(0.00f, 0.00f, 0.58f, 0.58f),
            Box(0.42f, 0.00f, 1.00f, 0.58f),
            Box(0.00f, 0.42f, 0.58f, 1.00f),
            Box(0.42f, 0.42f, 1.00f, 1.00f)
        )

        private val COCO_VEHICLE_IDS = intArrayOf(2, 3, 5, 7)
        private const val DOTA_LARGE_VEHICLE = 9
        private const val DOTA_SMALL_VEHICLE = 10
        private val DOTA_VEHICLE_IDS = intArrayOf(DOTA_LARGE_VEHICLE, DOTA_SMALL_VEHICLE)
    }
}
