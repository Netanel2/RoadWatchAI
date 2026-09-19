package com.netanel.roadwatch.core

import java.time.LocalDate
import kotlin.math.max

/**
 * V4 automatic scene state engine.
 *
 * No user drawn road / parking / tripwire is required. A fixed camera can infer
 * the useful states from persistent tracks:
 *  - PARKED: a confirmed vehicle stays spatially stable for several seconds.
 *  - MOVING: the vehicle changes position by more than detector jitter.
 *  - PASSED TODAY: a confirmed track travels a meaningful distance once.
 *  - PARKED TODAY: a vehicle that was moving later becomes parked.
 *  - LEFT PARKING: a parked vehicle starts moving again.
 *
 * Motion is measured over a sampling window instead of frame-to-frame speed so
 * bounding-box jitter at 20-30 FPS does not turn parked cars into moving cars.
 */
class VehicleStateEngine(
    private val parkingDwellMs: Long = 4_500L,
    private val stoppingDwellMs: Long = 1_200L,
    private val movingDwellMs: Long = 250L,
    private val motionSampleMs: Long = 350L,
    private val stationaryDisplacementMax: Float = 0.009f,
    private val movingDisplacementMin: Float = 0.017f,
    private val passDisplacementMin: Float = 0.055f,
    private val evidenceFreshnessMs: Long = 700L
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

        val visuals = tracks
            .filter { it.hits >= 3 }
            .map { track -> updateTrack(track, nowMs) }

        val parkedNow = visuals.count { it.state == VehicleState.PARKED }
        val movingNow = visuals.count {
            it.state == VehicleState.MOVING || it.state == VehicleState.LEAVING
        }
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

    /** Compatibility overload for older callers/tests. V4 intentionally ignores manual zones. */
    fun update(
        tracks: List<TrackSnapshot>,
        @Suppress("UNUSED_PARAMETER") zones: ZoneConfig,
        nowMs: Long
    ): Pair<List<TrackVisual>, DashboardMetrics> = update(tracks, nowMs)

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
            when {
                displacement >= movingDisplacementMin -> {
                    if (mem.highMotionSinceMs == null) {
                        // The displacement happened throughout this sample window.
                        mem.highMotionSinceMs = nowMs - elapsed
                    }
                    mem.lowMotionSinceMs = null
                }
                displacement <= stationaryDisplacementMax -> {
                    if (mem.lowMotionSinceMs == null) {
                        mem.lowMotionSinceMs = nowMs - elapsed
                    }
                    mem.highMotionSinceMs = null
                }
                else -> {
                    // Hysteresis band: keep the previous evidence briefly instead of
                    // toggling state because of normal detector box noise.
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
            oldState == VehicleState.MOVING && mem.highMotionSinceMs != null -> VehicleState.MOVING
            oldState == VehicleState.LEAVING && mem.highMotionSinceMs != null -> VehicleState.LEAVING
            else -> VehicleState.UNKNOWN
        }

        if (nextState != oldState) {
            mem.state = nextState
            mem.stateSinceMs = nowMs

            if (nextState == VehicleState.PARKED && !mem.parkedEventSent) {
                // A car already parked when the app starts is PARKED NOW, but it does
                // not become a false PARKED TODAY event unless movement was observed.
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

        // Automatic traffic counter: count a confirmed moving track once after it
        // has travelled enough of the frame to be clearly more than box jitter.
        if (!mem.countedPass && mem.everMoving && mem.maxDisplacement >= passDisplacementMin) {
            recentPasses.removeAll { nowMs - it.timeMs > 850L }
            val duplicate = recentPasses.any {
                it.vehicleClass == track.vehicleClass && it.point.distanceTo(point) < 0.07f
            }
            if (!duplicate) {
                passedToday++
                recentPasses += PassEvent(nowMs, point, track.vehicleClass)
            }
            mem.countedPass = true
        }

        return TrackVisual(track, mem.state, mem.stateSinceMs)
    }
}
