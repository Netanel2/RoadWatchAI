package com.netanel.roadwatch.core

import java.time.LocalDate

class VehicleStateEngine(
    private val parkingDwellMs: Long = 7_000L,
    private val stoppingDwellMs: Long = 1_200L,
    private val movingDwellMs: Long = 220L,
    private val parkedSpeedMax: Float = 0.013f,
    private val movingSpeedMin: Float = 0.024f,
    private val crossingSpeedMin: Float = 0.012f,
    private val evidenceFreshnessMs: Long = 450L
) {
    private data class Memory(
        var state: VehicleState = VehicleState.UNKNOWN,
        var stateSinceMs: Long = 0L,
        var lowSpeedSinceMs: Long? = null,
        var highSpeedSinceMs: Long? = null,
        var previousBottomCenter: Vec2? = null,
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
    private data class CrossingEvent(val timeMs: Long, val point: Vec2, val direction: Int)
    private val recentCrossings = mutableListOf<CrossingEvent>()


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
        recentCrossings.clear()
        memory.values.forEach {
            it.countedLine = false
            it.parkedEventSent = false
            it.leftEventSent = false
        }
    }

    fun resetTrackingState() {
        memory.clear()
    }

    fun update(
        tracks: List<TrackSnapshot>,
        zones: ZoneConfig,
        nowMs: Long
    ): Pair<List<TrackVisual>, DashboardMetrics> {
        if (LocalDate.now() != currentDate) resetDay()

        val activeIds = tracks.mapTo(mutableSetOf()) { it.id }
        memory.keys.filter { it !in activeIds }.toList().forEach(memory::remove)

        val visuals = tracks
            .filter { it.hits >= 2 }
            .map { track -> updateTrack(track, zones, nowMs) }

        // Visualize every confirmed AI track for debugging, but dashboard counts are
        // restricted to the configured road / parking areas. This prevents a vehicle
        // elsewhere in the camera view from inflating "moving now".
        val relevantVisuals = if (zones.road.isValid() || zones.parkingZones.isNotEmpty()) {
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
        nowMs: Long
    ): TrackVisual {
        val mem = memory.getOrPut(track.id) {
            Memory(stateSinceMs = nowMs)
        }

        val point = track.bottomCenter
        val inParking = zones.isInParking(point)
        val inRoad = zones.road.contains(point)
        val isFresh = nowMs - track.lastSeenMs <= evidenceFreshnessMs

        // A tracker is intentionally kept alive through short detector misses. Do not
        // turn repeated stale snapshots into false motion/parking evidence.
        if (!isFresh) {
            return TrackVisual(track, mem.state, mem.stateSinceMs)
        }

        if (track.speed <= parkedSpeedMax) {
            if (mem.lowSpeedSinceMs == null) mem.lowSpeedSinceMs = nowMs
            mem.highSpeedSinceMs = null
        } else if (track.speed >= movingSpeedMin) {
            if (mem.highSpeedSinceMs == null) mem.highSpeedSinceMs = nowMs
            mem.lowSpeedSinceMs = null
        } else {
            // Hysteresis band: neither moving nor parked evidence is accumulated.
            mem.lowSpeedSinceMs = null
            mem.highSpeedSinceMs = null
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
                // Existing cars already parked when the app starts count in PARKED NOW,
                // but not as a new "parked today" event until movement was actually observed.
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
        if (
            !mem.countedLine &&
            previous != null &&
            line != null &&
            inRoad &&
            track.speed >= crossingSpeedMin &&
            line.crossedSegment(previous, point)
        ) {
            val direction = if (line.side(previous) < line.side(point)) 1 else -1
            recentCrossings.removeAll { nowMs - it.timeMs > 400L }
            val duplicate = recentCrossings.any { event ->
                event.direction == direction && event.point.distanceTo(point) < 0.06f
            }
            if (!duplicate) {
                passedToday++
                recentCrossings += CrossingEvent(nowMs, point, direction)
            }
            mem.countedLine = true
        }
        mem.previousBottomCenter = point

        return TrackVisual(track, mem.state, mem.stateSinceMs)
    }
}
