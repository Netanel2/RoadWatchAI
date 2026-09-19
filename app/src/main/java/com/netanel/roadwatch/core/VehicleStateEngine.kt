package com.netanel.roadwatch.core

import java.time.LocalDate

class VehicleStateEngine(
    private val parkingDwellMs: Long = 3_500L,
    private val stoppingDwellMs: Long = 700L,
    private val movingDwellMs: Long = 260L,
    private val parkedSpeedMax: Float = 0.045f,
    private val movingSpeedMin: Float = 0.075f,
    private val crossingSpeedMin: Float = 0.012f,
    private val evidenceFreshnessMs: Long = 550L,
    private val automaticPassDistance: Float = 0.035f
) {
    private data class Memory(
        var state: VehicleState = VehicleState.UNKNOWN,
        var stateSinceMs: Long = 0L,
        var lowSpeedSinceMs: Long? = null,
        var highSpeedSinceMs: Long? = null,
        var previousBottomCenter: Vec2? = null,
        var firstBottomCenter: Vec2? = null,
        var maxDisplacement: Float = 0f,
        var countedLine: Boolean = false,
        var parkedEventSent: Boolean = false,
        var leftEventSent: Boolean = false,
        var everMoving: Boolean = false
    )

    private val memory = mutableMapOf<Int, Memory>()
    private var currentDate: LocalDate = LocalDate.now()
    private var passedToday = 0
    private var parkedToday = 0
    private var leftParkingToday = 0

    private data class PassingEvent(val timeMs: Long, val point: Vec2, val direction: Int)
    private val recentPasses = mutableListOf<PassingEvent>()

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
            it.countedLine = false
            it.parkedEventSent = false
            it.leftEventSent = false
        }
    }

    fun resetTrackingState() {
        memory.clear()
        recentPasses.clear()
    }

    /**
     * automaticMode=true means the whole frame is the analysis area.
     * No road polygon, parking polygon, or counting line is required.
     * A confirmed moving track is counted once after it has travelled far enough
     * to prove that it is actually a passing vehicle rather than detector jitter.
     */
    fun update(
        tracks: List<TrackSnapshot>,
        zones: ZoneConfig,
        nowMs: Long,
        automaticMode: Boolean = false
    ): Pair<List<TrackVisual>, DashboardMetrics> {
        if (LocalDate.now() != currentDate) resetDay()

        val activeIds = tracks.mapTo(mutableSetOf()) { it.id }
        memory.keys.filter { it !in activeIds }.toList().forEach(memory::remove)

        val visuals = tracks
            .filter { it.hits >= 3 }
            .map { track -> updateTrack(track, zones, nowMs, automaticMode) }

        val relevantVisuals = if (automaticMode) {
            visuals
        } else if (zones.road.isValid() || zones.parkingZones.isNotEmpty()) {
            visuals.filter { visual ->
                val point = visual.track.bottomCenter
                zones.road.contains(point) || zones.isInParking(point)
            }
        } else {
            visuals
        }

        val parkedNow = relevantVisuals.count { it.state == VehicleState.PARKED }
        val movingNow = relevantVisuals.count {
            it.state == VehicleState.MOVING || it.state == VehicleState.LEAVING
        }

        val current = relevantVisuals.map { it.track }
        val metrics = DashboardMetrics(
            parkedNow = parkedNow,
            movingNow = movingNow,
            passedToday = passedToday,
            parkedToday = parkedToday,
            leftParkingToday = leftParkingToday,
            activeTracks = relevantVisuals.size,
            carsNow = current.count { it.vehicleClass == VehicleClass.CAR },
            trucksNow = current.count { it.vehicleClass == VehicleClass.TRUCK },
            busesNow = current.count { it.vehicleClass == VehicleClass.BUS },
            motorcyclesNow = current.count { it.vehicleClass == VehicleClass.MOTORCYCLE }
        )

        return visuals to metrics
    }

    private fun updateTrack(
        track: TrackSnapshot,
        zones: ZoneConfig,
        nowMs: Long,
        automaticMode: Boolean
    ): TrackVisual {
        val mem = memory.getOrPut(track.id) {
            Memory(stateSinceMs = nowMs)
        }

        val point = track.bottomCenter
        if (mem.firstBottomCenter == null) mem.firstBottomCenter = point
        mem.firstBottomCenter?.let { first ->
            mem.maxDisplacement = maxOf(mem.maxDisplacement, first.distanceTo(point))
        }

        val inParking = automaticMode || zones.isInParking(point)
        val inRoad = automaticMode || zones.road.contains(point)
        val isFresh = nowMs - track.lastSeenMs <= evidenceFreshnessMs

        if (!isFresh) {
            return TrackVisual(track, mem.state, mem.stateSinceMs)
        }

        // Strong movement immediately cancels parking evidence. Mild box jitter does
        // not: this is important for distant parked cars at night, where the detector
        // box naturally moves by a pixel or two between frames.
        when {
            track.speed <= parkedSpeedMax -> {
                if (mem.lowSpeedSinceMs == null) mem.lowSpeedSinceMs = nowMs
                mem.highSpeedSinceMs = null
            }
            track.speed >= movingSpeedMin -> {
                if (mem.highSpeedSinceMs == null) mem.highSpeedSinceMs = nowMs
                mem.lowSpeedSinceMs = null
            }
            else -> {
                // Hysteresis band: keep existing low-speed evidence, but do not add
                // high-speed evidence until motion is clear.
                mem.highSpeedSinceMs = null
            }
        }

        val lowForMs = mem.lowSpeedSinceMs?.let { nowMs - it } ?: 0L
        val highForMs = mem.highSpeedSinceMs?.let { nowMs - it } ?: 0L
        if (highForMs >= movingDwellMs) mem.everMoving = true

        val nextState = when {
            inParking && lowForMs >= parkingDwellMs -> VehicleState.PARKED
            inParking && lowForMs >= stoppingDwellMs -> VehicleState.STOPPING
            mem.state == VehicleState.PARKED && highForMs >= 450L -> VehicleState.LEAVING
            highForMs >= movingDwellMs -> VehicleState.MOVING
            mem.state == VehicleState.MOVING && track.speed > parkedSpeedMax -> VehicleState.MOVING
            mem.state == VehicleState.LEAVING && track.speed > parkedSpeedMax -> VehicleState.LEAVING
            else -> VehicleState.UNKNOWN
        }

        if (nextState != mem.state) {
            val old = mem.state
            mem.state = nextState
            mem.stateSinceMs = nowMs

            if (nextState == VehicleState.PARKED && !mem.parkedEventSent) {
                // Cars that were already parked when the app opened count in
                // PARKED NOW, not as a new parking event for today.
                if (mem.everMoving) parkedToday++
                mem.parkedEventSent = true
                mem.leftEventSent = false
            }

            if (
                old == VehicleState.PARKED &&
                (nextState == VehicleState.LEAVING || nextState == VehicleState.MOVING) &&
                !mem.leftEventSent
            ) {
                leftParkingToday++
                mem.leftEventSent = true
                mem.parkedEventSent = false
            }
        }

        val previous = mem.previousBottomCenter
        val line = zones.countLine

        if (!mem.countedLine && automaticMode) {
            // Automatic pass counting: count a unique confirmed moving track only
            // after it has moved a meaningful distance in the frame. This removes
            // the old requirement for a manually drawn tripwire.
            if (
                mem.everMoving &&
                mem.maxDisplacement >= automaticPassDistance &&
                track.hits >= 4
            ) {
                recentPasses.removeAll { nowMs - it.timeMs > 1_200L }
                val duplicate = recentPasses.any { event ->
                    event.direction == 0 && event.point.distanceTo(point) < 0.075f
                }
                if (!duplicate) {
                    passedToday++
                    recentPasses += PassingEvent(nowMs, point, 0)
                }
                mem.countedLine = true
            }
        } else if (
            !mem.countedLine &&
            previous != null &&
            line != null &&
            inRoad &&
            track.speed >= crossingSpeedMin &&
            line.crossedSegment(previous, point)
        ) {
            val direction = if (line.side(previous) < line.side(point)) 1 else -1
            recentPasses.removeAll { nowMs - it.timeMs > 400L }
            val duplicate = recentPasses.any { event ->
                event.direction == direction && event.point.distanceTo(point) < 0.06f
            }
            if (!duplicate) {
                passedToday++
                recentPasses += PassingEvent(nowMs, point, direction)
            }
            mem.countedLine = true
        }

        mem.previousBottomCenter = point
        return TrackVisual(track, mem.state, mem.stateSinceMs)
    }
}
