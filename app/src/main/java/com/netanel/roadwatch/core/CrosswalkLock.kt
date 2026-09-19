package com.netanel.roadwatch.core

/**
 * V7 crosswalk stabilizer.
 *
 * A lock is earned only after repeated, geometrically similar stripe-cluster
 * observations. Unlike V6, a bad lock can recover when several strong incompatible
 * observations consistently point somewhere else.
 */
class CrosswalkLock(
    private val requiredStableHits: Int = 6,
    private val candidateTimeoutMs: Long = 2200L,
    private val lockedRefreshAlpha: Float = 0.06f
) {
    data class State(
        val estimate: CrosswalkEstimate?,
        val locked: Boolean,
        val stableHits: Int,
        val lastSeenMs: Long
    )

    private var candidate: CrosswalkEstimate? = null
    private var candidateHits = 0
    private var candidateLastSeenMs = 0L
    private var locked: CrosswalkEstimate? = null
    private var lockedLastSeenMs = 0L
    private var incompatibleCandidate: CrosswalkEstimate? = null
    private var incompatibleHits = 0

    fun reset() {
        candidate = null
        candidateHits = 0
        candidateLastSeenMs = 0L
        locked = null
        lockedLastSeenMs = 0L
        incompatibleCandidate = null
        incompatibleHits = 0
    }

    fun update(rawEstimate: CrosswalkEstimate?, nowMs: Long): State {
        val frameEstimate = rawEstimate?.takeIf { plausible(it.box) }
        val currentLocked = locked

        if (currentLocked != null) {
            if (frameEstimate != null && compatible(currentLocked.box, frameEstimate.box)) {
                val blended = currentLocked.box.blend(frameEstimate.box, lockedRefreshAlpha)
                locked = CrosswalkEstimate(
                    box = blended,
                    confidence = maxOf(currentLocked.confidence * 0.985f, frameEstimate.confidence)
                        .coerceIn(0f, 0.99f)
                )
                lockedLastSeenMs = nowMs
                incompatibleCandidate = null
                incompatibleHits = 0
            } else if (frameEstimate != null && frameEstimate.confidence >= 0.56f) {
                val previous = incompatibleCandidate
                if (previous != null && compatible(previous.box, frameEstimate.box)) {
                    incompatibleCandidate = CrosswalkEstimate(
                        previous.box.blend(frameEstimate.box, 0.25f),
                        ((previous.confidence + frameEstimate.confidence) * 0.5f).coerceIn(0f, 0.99f)
                    )
                    incompatibleHits++
                } else {
                    incompatibleCandidate = frameEstimate
                    incompatibleHits = 1
                }

                // Do not let one wrong historical lock live forever. A replacement
                // must be repeatedly observed and the old lock must have stopped
                // receiving compatible evidence for several seconds.
                if (incompatibleHits >= 7 && nowMs - lockedLastSeenMs > 3500L) {
                    locked = incompatibleCandidate
                    lockedLastSeenMs = nowMs
                    candidate = null
                    candidateHits = 0
                    incompatibleCandidate = null
                    incompatibleHits = 0
                }
            }
            return State(locked, true, requiredStableHits, lockedLastSeenMs)
        }

        if (frameEstimate == null) {
            if (nowMs - candidateLastSeenMs > candidateTimeoutMs) {
                candidate = null
                candidateHits = 0
            }
            return State(candidate, false, candidateHits, candidateLastSeenMs)
        }

        val previous = candidate
        if (previous == null || !compatible(previous.box, frameEstimate.box) || nowMs - candidateLastSeenMs > candidateTimeoutMs) {
            candidate = frameEstimate
            candidateHits = 1
        } else {
            candidate = CrosswalkEstimate(
                box = previous.box.blend(frameEstimate.box, 0.22f),
                confidence = (previous.confidence * 0.72f + frameEstimate.confidence * 0.28f).coerceIn(0f, 0.99f)
            )
            candidateHits++
        }
        candidateLastSeenMs = nowMs

        if (candidateHits >= requiredStableHits && (candidate?.confidence ?: 0f) >= 0.52f) {
            locked = candidate
            lockedLastSeenMs = nowMs
            return State(locked, true, candidateHits, lockedLastSeenMs)
        }

        return State(candidate, false, candidateHits, candidateLastSeenMs)
    }

    private fun plausible(box: Box): Boolean =
        box.width in 0.08f..0.68f &&
            box.height in 0.035f..0.50f &&
            box.area in 0.006f..0.20f

    private fun compatible(a: Box, b: Box): Boolean {
        val iou = a.iou(b)
        val centerDistance = a.center.distanceTo(b.center)
        val widthRatio = if (a.width > 0f && b.width > 0f) minOf(a.width, b.width) / maxOf(a.width, b.width) else 0f
        val heightRatio = if (a.height > 0f && b.height > 0f) minOf(a.height, b.height) / maxOf(a.height, b.height) else 0f
        return iou >= 0.34f ||
            (centerDistance <= 0.075f && widthRatio >= 0.58f && heightRatio >= 0.45f)
    }
}
