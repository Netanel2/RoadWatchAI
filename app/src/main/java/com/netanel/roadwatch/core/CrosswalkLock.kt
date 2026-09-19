package com.netanel.roadwatch.core

/**
 * Stabilizes noisy frame-by-frame crosswalk estimates into a persistent scene lock.
 * Once locked, the crosswalk stays available through lighting changes and short misses.
 */
class CrosswalkLock(
    private val requiredStableHits: Int = 4,
    private val candidateTimeoutMs: Long = 2500L,
    private val lockedRefreshAlpha: Float = 0.08f
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

    fun reset() {
        candidate = null
        candidateHits = 0
        candidateLastSeenMs = 0L
        locked = null
        lockedLastSeenMs = 0L
    }

    fun update(frameEstimate: CrosswalkEstimate?, nowMs: Long): State {
        val currentLocked = locked
        if (currentLocked != null) {
            if (frameEstimate != null && compatible(currentLocked.box, frameEstimate.box)) {
                val blended = currentLocked.box.blend(frameEstimate.box, lockedRefreshAlpha)
                locked = CrosswalkEstimate(
                    box = blended,
                    confidence = maxOf(currentLocked.confidence * 0.97f, frameEstimate.confidence)
                        .coerceIn(0f, 0.99f)
                )
                lockedLastSeenMs = nowMs
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
            val alpha = 0.28f
            val blended = previous.box.blend(frameEstimate.box, alpha)
            candidate = CrosswalkEstimate(
                box = blended,
                confidence = ((previous.confidence * 0.70f) + (frameEstimate.confidence * 0.30f)).coerceIn(0f, 0.99f)
            )
            candidateHits++
        }
        candidateLastSeenMs = nowMs

        if (candidateHits >= requiredStableHits && (candidate?.confidence ?: 0f) >= 0.42f) {
            locked = candidate
            lockedLastSeenMs = nowMs
            return State(locked, true, candidateHits, lockedLastSeenMs)
        }

        return State(candidate, false, candidateHits, candidateLastSeenMs)
    }

    private fun compatible(a: Box, b: Box): Boolean {
        val iou = a.iou(b)
        val centerDistance = a.center.distanceTo(b.center)
        val widthRatio = if (a.width > 0f && b.width > 0f) minOf(a.width, b.width) / maxOf(a.width, b.width) else 0f
        return iou >= 0.22f || (centerDistance <= 0.12f && widthRatio >= 0.45f)
    }
}
