package com.netanel.roadwatch.core

import java.util.ArrayDeque
import kotlin.math.hypot
import kotlin.math.max

/**
 * V8 temporal vehicle tracker.
 *
 * The old tracker estimated motion from two adjacent detector boxes. On a distant
 * parked car, a one-pixel YOLO wobble could therefore look like real movement.
 * V8 keeps a short centre history (similar to the Python speed-tracking demos) and
 * derives motion from a longer baseline. This makes the overlay calmer and gives
 * the state engine a much better signal without requiring more detector FPS.
 */
class VehicleTracker(
    private val highConfidence: Float = 0.42f,
    private val newTrackConfidence: Float = 0.32f,
    private val maxMissingMs: Long = 2600L,
    private val historyWindowMs: Long = 1600L,
    private val speedBaselineMs: Long = 500L
) {
    private data class Sample(val timeMs: Long, val point: Vec2)

    private data class MutableTrack(
        val id: Int,
        var box: Box,
        var vehicleClass: VehicleClass,
        var confidence: Float,
        var velocity: Vec2,
        var speed: Float,
        var motionScore: Float,
        var firstSeenMs: Long,
        var lastSeenMs: Long,
        var hits: Int,
        val history: ArrayDeque<Sample> = ArrayDeque(),
        val classVotes: MutableMap<VehicleClass, Float> = mutableMapOf()
    )

    private val tracks = linkedMapOf<Int, MutableTrack>()
    private var nextId = 1

    fun reset() {
        tracks.clear()
        nextId = 1
    }

    fun update(detections: List<Detection>, timestampMs: Long): List<TrackSnapshot> {
        expire(timestampMs)

        val high = detections.filter { it.confidence >= highConfidence }
        val low = detections.filter { it.confidence < highConfidence }

        val unmatchedTrackIds = tracks.keys.toMutableSet()
        val unmatchedHigh = high.indices.toMutableSet()
        associate(high, unmatchedTrackIds, unmatchedHigh, timestampMs, rescueStage = false)

        val rescueTrackIds = unmatchedTrackIds.toMutableSet()
        val unmatchedLow = low.indices.toMutableSet()
        associate(low, rescueTrackIds, unmatchedLow, timestampMs, rescueStage = true)

        unmatchedHigh.forEach { index ->
            val detection = high[index]
            if (detection.confidence >= newTrackConfidence) createTrack(detection, timestampMs)
        }
        unmatchedLow.forEach { index ->
            val detection = low[index]
            if (detection.confidence >= newTrackConfidence) createTrack(detection, timestampMs)
        }

        return tracks.values
            .filter { timestampMs - it.lastSeenMs <= maxMissingMs }
            .map { it.snapshot(timestampMs) }
    }

    private fun associate(
        detections: List<Detection>,
        trackIds: MutableSet<Int>,
        detectionIndices: MutableSet<Int>,
        timestampMs: Long,
        rescueStage: Boolean
    ) {
        data class Candidate(val trackId: Int, val detectionIndex: Int, val cost: Float)
        val candidates = mutableListOf<Candidate>()

        for (trackId in trackIds) {
            val track = tracks[trackId] ?: continue
            for (detectionIndex in detectionIndices) {
                val detection = detections[detectionIndex]
                val cost = associationCost(track, detection, timestampMs, rescueStage)
                if (cost < 0.94f) candidates += Candidate(trackId, detectionIndex, cost)
            }
        }

        candidates.sortBy { it.cost }
        val usedTracks = mutableSetOf<Int>()
        val usedDetections = mutableSetOf<Int>()
        for (candidate in candidates) {
            if (candidate.trackId in usedTracks || candidate.detectionIndex in usedDetections) continue
            val track = tracks[candidate.trackId] ?: continue
            val detection = detections[candidate.detectionIndex]
            updateTrack(track, detection, timestampMs)
            usedTracks += candidate.trackId
            usedDetections += candidate.detectionIndex
        }

        trackIds.removeAll(usedTracks)
        detectionIndices.removeAll(usedDetections)
    }

    private fun associationCost(
        track: MutableTrack,
        detection: Detection,
        timestampMs: Long,
        rescueStage: Boolean
    ): Float {
        val dt = max(0.033f, (timestampMs - track.lastSeenMs) / 1000f)
        val predictedCenter = Vec2(
            track.box.center.x + track.velocity.x * dt,
            track.box.center.y + track.velocity.y * dt
        )
        val centerDistance = predictedCenter.distanceTo(detection.box.center)
        val iou = track.box.iou(detection.box)
        val classPenalty = if (
            track.vehicleClass != VehicleClass.UNKNOWN &&
            detection.vehicleClass != VehicleClass.UNKNOWN &&
            track.vehicleClass != detection.vehicleClass
        ) 0.10f else 0f

        val distanceNorm = (centerDistance / 0.28f).coerceIn(0f, 1.4f)
        val rescuePenalty = if (rescueStage) 0.06f else 0f
        return (1f - iou) * 0.58f + distanceNorm * 0.42f + classPenalty + rescuePenalty
    }

    private fun updateTrack(track: MutableTrack, detection: Detection, timestampMs: Long) {
        val measuredCenter = detection.box.center
        track.history.addLast(Sample(timestampMs, measuredCenter))
        while (track.history.size > 2 && timestampMs - track.history.first.timeMs > historyWindowMs) {
            track.history.removeFirst()
        }

        val targetTime = timestampMs - speedBaselineMs
        val reference = track.history.lastOrNull { it.timeMs <= targetTime } ?: track.history.first()
        val dt = max(0.08f, (timestampMs - reference.timeMs) / 1000f)
        val rawVelocity = Vec2(
            (measuredCenter.x - reference.point.x) / dt,
            (measuredCenter.y - reference.point.y) / dt
        )

        // Long-baseline velocity is already much less noisy; a small EMA removes
        // the remaining box wobble without causing a large lag on real cars.
        val alpha = 0.28f
        track.velocity = Vec2(
            track.velocity.x * (1f - alpha) + rawVelocity.x * alpha,
            track.velocity.y * (1f - alpha) + rawVelocity.y * alpha
        )
        track.speed = track.velocity.distanceTo(Vec2(0f, 0f))

        val diagonal = max(0.025f, hypot(detection.box.width, detection.box.height))
        val baselineDisplacement = reference.point.distanceTo(measuredCenter)
        val instantaneousScore = (baselineDisplacement / diagonal).coerceIn(0f, 4f)
        track.motionScore = track.motionScore * 0.70f + instantaneousScore * 0.30f

        // More conservative box smoothing than V7: labels stay visually stable.
        track.box = track.box.blend(detection.box, 0.48f).clamp01()
        track.confidence = detection.confidence
        track.lastSeenMs = timestampMs
        track.hits += 1
        track.classVotes[detection.vehicleClass] =
            (track.classVotes[detection.vehicleClass] ?: 0f) + detection.confidence
        track.vehicleClass = track.classVotes.maxByOrNull { it.value }?.key ?: detection.vehicleClass
    }

    private fun createTrack(detection: Detection, timestampMs: Long) {
        val id = nextId++
        val history = ArrayDeque<Sample>()
        history.addLast(Sample(timestampMs, detection.box.center))
        tracks[id] = MutableTrack(
            id = id,
            box = detection.box.clamp01(),
            vehicleClass = detection.vehicleClass,
            confidence = detection.confidence,
            velocity = Vec2(0f, 0f),
            speed = 0f,
            motionScore = 0f,
            firstSeenMs = timestampMs,
            lastSeenMs = timestampMs,
            hits = 1,
            history = history,
            classVotes = mutableMapOf(detection.vehicleClass to detection.confidence)
        )
    }

    private fun expire(timestampMs: Long) {
        tracks.values
            .filter { timestampMs - it.lastSeenMs > maxMissingMs }
            .map { it.id }
            .forEach(tracks::remove)
    }

    private fun MutableTrack.snapshot(nowMs: Long): TrackSnapshot = TrackSnapshot(
        id = id,
        box = box,
        vehicleClass = vehicleClass,
        confidence = confidence,
        speed = speed,
        velocity = velocity,
        ageMs = nowMs - firstSeenMs,
        lastSeenMs = lastSeenMs,
        hits = hits,
        motionScore = motionScore
    )
}
