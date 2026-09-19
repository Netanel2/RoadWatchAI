package com.netanel.roadwatch.core

import kotlin.math.max

/**
 * Lightweight tracker for pedestrians. It deliberately allows lower-confidence
 * person detections to enter a tentative track, but only multi-frame tracks are
 * exposed to the UI / behavior engine. This helps with small, distant people.
 */
class PersonTracker(
    private val highConfidence: Float = 0.30f,
    private val newTrackConfidence: Float = 0.18f,
    private val maxMissingMs: Long = 1800L
) {
    private data class MutableTrack(
        val id: Int,
        var box: Box,
        var confidence: Float,
        var velocity: Vec2,
        var speed: Float,
        var firstSeenMs: Long,
        var lastSeenMs: Long,
        var hits: Int
    )

    private val tracks = linkedMapOf<Int, MutableTrack>()
    private var nextId = 1

    fun reset() {
        tracks.clear()
        nextId = 1
    }

    fun update(detections: List<PersonDetection>, timestampMs: Long): List<PersonTrackSnapshot> {
        expire(timestampMs)

        val ordered = detections.sortedByDescending { it.confidence }
        val unmatchedTracks = tracks.keys.toMutableSet()
        val unmatchedDetections = ordered.indices.toMutableSet()

        data class Candidate(val trackId: Int, val detIndex: Int, val cost: Float)
        val candidates = mutableListOf<Candidate>()

        for (trackId in unmatchedTracks) {
            val track = tracks[trackId] ?: continue
            for (index in unmatchedDetections) {
                val det = ordered[index]
                val dt = max(0.033f, (timestampMs - track.lastSeenMs) / 1000f)
                val predicted = Vec2(
                    track.box.center.x + track.velocity.x * dt,
                    track.box.center.y + track.velocity.y * dt
                )
                val dist = predicted.distanceTo(det.box.center)
                val iou = track.box.iou(det.box)
                val distanceNorm = (dist / 0.18f).coerceIn(0f, 1.5f)
                val confidencePenalty = if (det.confidence < highConfidence) 0.05f else 0f
                val cost = (1f - iou) * 0.55f + distanceNorm * 0.45f + confidencePenalty
                if (cost < 0.92f) candidates += Candidate(trackId, index, cost)
            }
        }

        candidates.sortBy { it.cost }
        val usedTracks = mutableSetOf<Int>()
        val usedDetections = mutableSetOf<Int>()
        for (candidate in candidates) {
            if (candidate.trackId in usedTracks || candidate.detIndex in usedDetections) continue
            val track = tracks[candidate.trackId] ?: continue
            val det = ordered[candidate.detIndex]
            updateTrack(track, det, timestampMs)
            usedTracks += candidate.trackId
            usedDetections += candidate.detIndex
        }

        unmatchedDetections.removeAll(usedDetections)
        unmatchedDetections.forEach { index ->
            val det = ordered[index]
            if (det.confidence >= newTrackConfidence) createTrack(det, timestampMs)
        }

        return tracks.values
            .filter { timestampMs - it.lastSeenMs <= maxMissingMs }
            .map { it.snapshot(timestampMs) }
    }

    private fun updateTrack(track: MutableTrack, detection: PersonDetection, timestampMs: Long) {
        val previous = track.box.center
        val dt = max(0.033f, (timestampMs - track.lastSeenMs) / 1000f)
        val rawVelocity = Vec2(
            (detection.box.center.x - previous.x) / dt,
            (detection.box.center.y - previous.y) / dt
        )
        val alpha = 0.30f
        val velocity = Vec2(
            track.velocity.x * (1f - alpha) + rawVelocity.x * alpha,
            track.velocity.y * (1f - alpha) + rawVelocity.y * alpha
        )
        track.velocity = velocity
        track.speed = velocity.distanceTo(Vec2(0f, 0f))
        track.box = track.box.blend(detection.box, 0.62f).clamp01()
        track.confidence = detection.confidence
        track.lastSeenMs = timestampMs
        track.hits += 1
    }

    private fun createTrack(detection: PersonDetection, timestampMs: Long) {
        val id = nextId++
        tracks[id] = MutableTrack(
            id = id,
            box = detection.box.clamp01(),
            confidence = detection.confidence,
            velocity = Vec2(0f, 0f),
            speed = 0f,
            firstSeenMs = timestampMs,
            lastSeenMs = timestampMs,
            hits = 1
        )
    }

    private fun expire(timestampMs: Long) {
        tracks.values
            .filter { timestampMs - it.lastSeenMs > maxMissingMs }
            .map { it.id }
            .forEach(tracks::remove)
    }

    private fun MutableTrack.snapshot(nowMs: Long) = PersonTrackSnapshot(
        id = id,
        box = box,
        confidence = confidence,
        speed = speed,
        velocity = velocity,
        ageMs = nowMs - firstSeenMs,
        lastSeenMs = lastSeenMs,
        hits = hits
    )
}
