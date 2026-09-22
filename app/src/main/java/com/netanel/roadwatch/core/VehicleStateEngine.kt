package com.netanel.roadwatch.core

import java.time.LocalDate
import kotlin.math.hypot
import kotlin.math.max

/**
 * V8 history-based motion state engine.
 *
 * State changes are intentionally slower than detector updates. A vehicle must
 * demonstrate sustained relative displacement (normalised by its own box size)
 * before it becomes MOVING. This is the same principle used by smooth speed
 * tracking demos: track history first, classify motion second.
 */
class VehicleStateEngine(
    private val parkingDwellMs: Long = 3_500L,
    private val stoppingDwellMs: Long = 900L,
    private val movingDwellMs: Long = 500L,
    private val motionSampleMs: Long = 420L,
    private val stationaryDisplacementMax: Float = 0.009f,
    private val movingDisplacementMin: Float = 0.017f,
    private val passDisplacementMin: Float = 0.055f,
    private val evidenceFreshnessMs: Long = 900L,
    private val stationaryRelativeMax: Float = 0.10f,
    private val movingRelativeMin: Float = 0.20f,
    private val fastPassConfidenceMin: Float = 0.40f,
    private val fastPassAbsoluteMin: Float = 0.018f,
    private val fastPassRelativeMin: Float = 0.18f,
    private val fastPassSpeedMin: Float = 0.020f
) {
    private data class Memory(
        var state: VehicleState = VehicleState.UNKNOWN,
        var stateSinceMs: Long = 0L,
        var firstPoint: Vec2? = null,
        var samplePoint: Vec2? = null,
        var sampleTimeMs: Long = 0L,
        var lowMotionSinceMs: Long? = null,
        var highMotionSinceMs: Long? = null,
        var maxDisplacement: Float = 0f,
        var countedPass: Boolean = false,
        var parkedEventSent: Boolean = false,
        var leftEventSent: Boolean = false,
        var everMoving: Boolean = false
    )

    private data class PassEvent(
        val timeMs: Long,
        val point: Vec2,
        val vehicleClass: VehicleClass
    )

    private val memory = mutableMapOf<Int, Memory>()
    private val recentPasses = mutableListOf<PassEvent>()
    private var currentDate: LocalDate = LocalDate.now()
    private var passedToday = 0
    private var parkedToday = 0
    private var leftParkingToday = 0

    fun restoreDaily(counts: DailyCounts) {
        val today = LocalDate.now()
        if (counts.dateIso == today.toString()) {
            currentDate = today
            passedToday = counts.passedToday.coerceAtLeast(0)
            parkedToday = counts.parkedToday.coerceAtLeast(0)
            leftParkingToday = counts.leftParkingToday.coerceAtLeast(0)
        } else {
            resetDay()
        }
    }

    fun dailyCounts(): DailyCounts = DailyCounts(
        dateIso = currentDate.toString(),
        passedToday = passedToday,
        parkedToday = parkedToday,
        leftParkingToday = leftParkingToday
    )

    fun resetDay() {
        passedToday = 0
        parkedToday = 0
        leftParkingToday = 0
        currentDate = LocalDate.now()
        recentPasses.clear()
        memory.values.forEach {
            it.countedPass = false
            it.parkedEventSent = false
            it.leftEventSent = false
        }
    }

    fun resetTrackingState() {
        memory.clear()
        recentPasses.clear()
    }

    fun update(
        tracks: List<TrackSnapshot>,
        nowMs: Long
    ): Pair<List<TrackVisual>, DashboardMetrics> {
        if (LocalDate.now() != currentDate) resetDay()

        val activeIds = tracks.mapTo(mutableSetOf()) { it.id }
        memory.keys.filter { it !in activeIds }.toList().forEach(memory::remove)

        // FAST-PASS path: remember the very first observation of every track.
        // A vehicle that only spends 2 frames inside a tiny field of view can still
        // be counted if its displacement is clearly larger than detector jitter.
        tracks.forEach { track -> observeFastPass(track, nowMs) }

        // Show well-supported fast tracks after two observations; weak tracks need three.
        val visuals = tracks
            .filter { it.hits >= 3 || (it.hits >= 2 && it.confidence >= .40f) }
            .map { track -> updateTrack(track, nowMs) }

        val parkedNow = visuals.count { it.state == VehicleState.PARKED }
        val movingNow = visuals.count { it.state == VehicleState.MOVING || it.state == VehicleState.LEAVING }
        val current = visuals.map { it.track }

        val metrics = DashboardMetrics(
            parkedNow = parkedNow,
            movingNow = movingNow,
            passedToday = passedToday,
            parkedToday = parkedToday,
            leftParkingToday = leftParkingToday,
            activeTracks = visuals.size,
            carsNow = current.count { it.vehicleClass == VehicleClass.CAR },
            trucksNow = current.count { it.vehicleClass == VehicleClass.TRUCK },
            busesNow = current.count { it.vehicleClass == VehicleClass.BUS },
            motorcyclesNow = current.count { it.vehicleClass == VehicleClass.MOTORCYCLE }
        )

        return visuals to metrics
    }

    fun update(
        tracks: List<TrackSnapshot>,
        @Suppress("UNUSED_PARAMETER") zones: ZoneConfig,
        nowMs: Long
    ): Pair<List<TrackVisual>, DashboardMetrics> = update(tracks, nowMs)


    private fun observeFastPass(track: TrackSnapshot, nowMs: Long) {
        val point = track.bottomCenter
        val mem = memory.getOrPut(track.id) {
            Memory(
                stateSinceMs = nowMs,
                firstPoint = point,
                samplePoint = point,
                sampleTimeMs = nowMs
            )
        }

        if (nowMs - track.lastSeenMs > evidenceFreshnessMs) return

        val first = mem.firstPoint ?: point.also { mem.firstPoint = it }
        mem.maxDisplacement = max(mem.maxDisplacement, first.distanceTo(point))

        // One frame is never enough to prove motion safely. With two or more hits,
        // use an adaptive threshold tied to the vehicle's own on-screen size.
        if (track.hits < 2 || mem.countedPass) return

        val objectScale = max(0.035f, hypot(track.box.width, track.box.height))
        val adaptiveDistance = max(fastPassAbsoluteMin, objectScale * fastPassRelativeMin)
        val clearMotion = track.speed >= fastPassSpeedMin || track.motionScore >= 0.10f
        val reliableDetection = track.confidence >= fastPassConfidenceMin

        if (reliableDetection && clearMotion && mem.maxDisplacement >= adaptiveDistance) {
            mem.everMoving = true
            countPassIfUnique(track, point, nowMs, mem)
        }
    }

    private fun countPassIfUnique(
        track: TrackSnapshot,
        point: Vec2,
        nowMs: Long,
        mem: Memory
    ) {
        if (mem.countedPass) return
        recentPasses.removeAll { nowMs - it.timeMs > 900L }
        val duplicate = recentPasses.any {
            it.vehicleClass == track.vehicleClass && it.point.distanceTo(point) < 0.07f
        }
        if (!duplicate) {
            passedToday++
            recentPasses += PassEvent(nowMs, point, track.vehicleClass)
        }
        mem.countedPass = true
    }

    private fun updateTrack(track: TrackSnapshot, nowMs: Long): TrackVisual {
        val point = track.bottomCenter
        val mem = memory.getOrPut(track.id) {
            Memory(
                stateSinceMs = nowMs,
                firstPoint = point,
                samplePoint = point,
                sampleTimeMs = nowMs
            )
        }

        val isFresh = nowMs - track.lastSeenMs <= evidenceFreshnessMs
        if (!isFresh) return TrackVisual(track, mem.state, mem.stateSinceMs)

        val first = mem.firstPoint ?: point.also { mem.firstPoint = it }
        mem.maxDisplacement = max(mem.maxDisplacement, first.distanceTo(point))

        val samplePoint = mem.samplePoint ?: point.also { mem.samplePoint = it }
        val elapsed = nowMs - mem.sampleTimeMs
        if (elapsed >= motionSampleMs) {
            val displacement = samplePoint.distanceTo(point)
            val objectScale = max(0.035f, hypot(track.box.width, track.box.height))
            val relativeDisplacement = displacement / objectScale

            // V8 uses both the long-history tracker score and a fresh sample.
            // A parked box must remain clearly quiet; ambiguous motion sits in a
            // hysteresis band and does not immediately flip the state.
            val motionEvidence = max(relativeDisplacement, track.motionScore)
            when {
                motionEvidence >= movingRelativeMin || displacement >= movingDisplacementMin * 1.6f -> {
                    if (mem.highMotionSinceMs == null) mem.highMotionSinceMs = nowMs - elapsed
                    mem.lowMotionSinceMs = null
                }
                motionEvidence <= stationaryRelativeMax && displacement <= stationaryDisplacementMax * 1.5f -> {
                    if (mem.lowMotionSinceMs == null) mem.lowMotionSinceMs = nowMs - elapsed
                    mem.highMotionSinceMs = null
                }
                else -> {
                    // Do not reset both clocks for one ambiguous sample. Instead let
                    // existing evidence decay naturally on the next clear sample.
                }
            }
            mem.samplePoint = point
            mem.sampleTimeMs = nowMs
        }

        val lowForMs = mem.lowMotionSinceMs?.let { nowMs - it } ?: 0L
        val highForMs = mem.highMotionSinceMs?.let { nowMs - it } ?: 0L
        if (highForMs >= movingDwellMs || mem.maxDisplacement >= passDisplacementMin) {
            mem.everMoving = true
        }

        val oldState = mem.state
        val nextState = when {
            oldState == VehicleState.PARKED && highForMs >= movingDwellMs -> VehicleState.LEAVING
            highForMs >= movingDwellMs -> VehicleState.MOVING
            lowForMs >= parkingDwellMs -> VehicleState.PARKED
            lowForMs >= stoppingDwellMs -> VehicleState.STOPPING
            oldState == VehicleState.MOVING && mem.highMotionSinceMs != null && highForMs < movingDwellMs * 3 -> VehicleState.MOVING
            oldState == VehicleState.LEAVING && mem.highMotionSinceMs != null && highForMs < movingDwellMs * 3 -> VehicleState.LEAVING
            else -> VehicleState.UNKNOWN
        }

        if (nextState != oldState) {
            mem.state = nextState
            mem.stateSinceMs = nowMs

            if (nextState == VehicleState.PARKED && !mem.parkedEventSent) {
                if (mem.everMoving) parkedToday++
                mem.parkedEventSent = true
                mem.leftEventSent = false
            }

            if (
                oldState == VehicleState.PARKED &&
                (nextState == VehicleState.LEAVING || nextState == VehicleState.MOVING) &&
                !mem.leftEventSent
            ) {
                leftParkingToday++
                mem.leftEventSent = true
                mem.parkedEventSent = false
            }
        }

        if (!mem.countedPass && mem.everMoving && mem.maxDisplacement >= passDisplacementMin) {
            countPassIfUnique(track, point, nowMs, mem)
        }

        return TrackVisual(track, mem.state, mem.stateSinceMs)
    }
}
