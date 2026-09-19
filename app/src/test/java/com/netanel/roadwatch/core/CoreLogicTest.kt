package com.netanel.roadwatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogicTest {

    @Test
    fun trackerKeepsSameIdAcrossMotion() {
        val tracker = VehicleTracker()
        var t = 1_000L
        val first = tracker.update(
            listOf(Detection(Box(.1f, .2f, .25f, .35f), VehicleClass.CAR, .9f)), t
        )
        t += 100
        val second = tracker.update(
            listOf(Detection(Box(.13f, .2f, .28f, .35f), VehicleClass.CAR, .88f)), t
        )
        assertEquals(first.first().id, second.first().id)
        assertTrue(second.first().speed > 0f)
    }

    @Test
    fun existingStationaryVehicleBecomesParkedButNotParkedToday() {
        val engine = VehicleStateEngine(
            parkingDwellMs = 900L,
            stoppingDwellMs = 250L,
            motionSampleMs = 200L
        )
        var now = 0L
        fun stopped() = TrackSnapshot(
            1, Box(.2f, .2f, .3f, .3f), VehicleClass.CAR, .9f,
            0f, Vec2(0f, 0f), now, now, 3
        )

        engine.update(listOf(stopped()), now)
        now = 300
        assertEquals(VehicleState.STOPPING, engine.update(listOf(stopped()), now).first.first().state)
        now = 1_100
        val result = engine.update(listOf(stopped()), now)
        assertEquals(VehicleState.PARKED, result.first.first().state)
        assertEquals(1, result.second.parkedNow)
        assertEquals(0, result.second.parkedToday)
    }

    @Test
    fun movingTrackCountsAsPassedWithoutManualLine() {
        val engine = VehicleStateEngine(
            motionSampleMs = 100L,
            movingDwellMs = 80L,
            passDisplacementMin = .05f
        )
        var now = 0L
        fun track(x: Float, hits: Int) = TrackSnapshot(
            4, Box(x, .2f, x + .1f, .3f), VehicleClass.CAR, .9f,
            .1f, Vec2(.1f, 0f), now, now, hits
        )

        engine.update(listOf(track(.10f, 3)), now)
        now = 120
        engine.update(listOf(track(.14f, 4)), now)
        now = 240
        val result = engine.update(listOf(track(.18f, 5)), now)
        assertEquals(1, result.second.passedToday)
        now = 360
        assertEquals(1, engine.update(listOf(track(.24f, 6)), now).second.passedToday)
    }

    @Test
    fun movingVehicleThatStopsCreatesParkingEvent() {
        val engine = VehicleStateEngine(
            parkingDwellMs = 500L,
            stoppingDwellMs = 150L,
            movingDwellMs = 80L,
            motionSampleMs = 100L,
            passDisplacementMin = .04f
        )
        var now = 0L
        fun snap(x: Float, id: Int = 7) = TrackSnapshot(
            id, Box(x, .3f, x + .1f, .4f), VehicleClass.CAR, .9f,
            0f, Vec2(0f, 0f), now, now, 4
        )

        engine.update(listOf(snap(.10f)), now)
        now = 120
        engine.update(listOf(snap(.16f)), now)
        now = 240
        engine.update(listOf(snap(.22f)), now)
        now = 360
        engine.update(listOf(snap(.22f)), now)
        now = 600
        engine.update(listOf(snap(.22f)), now)
        now = 1_000
        val result = engine.update(listOf(snap(.22f)), now)
        assertEquals(VehicleState.PARKED, result.first.first().state)
        assertEquals(1, result.second.parkedToday)
        assertEquals(1, result.second.parkedNow)
    }

    @Test
    fun parkedVehicleLeavingCreatesLeftEvent() {
        val engine = VehicleStateEngine(
            parkingDwellMs = 400L,
            stoppingDwellMs = 100L,
            movingDwellMs = 80L,
            motionSampleMs = 100L
        )
        var now = 0L
        fun snap(x: Float) = TrackSnapshot(
            12, Box(x, .3f, x + .1f, .4f), VehicleClass.CAR, .9f,
            0f, Vec2(0f, 0f), now, now, 4
        )

        engine.update(listOf(snap(.2f)), now)
        now = 150
        engine.update(listOf(snap(.2f)), now)
        now = 550
        assertEquals(VehicleState.PARKED, engine.update(listOf(snap(.2f)), now).first.first().state)
        now = 700
        val leaving = engine.update(listOf(snap(.25f)), now)
        assertEquals(VehicleState.LEAVING, leaving.first.first().state)
        assertEquals(1, leaving.second.leftParkingToday)
    }

    @Test
    fun staleTrackDoesNotCreateFreshMotionEvidence() {
        val engine = VehicleStateEngine(evidenceFreshnessMs = 200L, motionSampleMs = 100L)
        val track = TrackSnapshot(
            21, Box(.2f,.2f,.3f,.3f), VehicleClass.CAR,.9f,
            0f, Vec2(0f,0f), 0, 0, 3
        )
        engine.update(listOf(track), 0)
        val stale = engine.update(listOf(track), 1_000)
        assertEquals(VehicleState.UNKNOWN, stale.first.first().state)
        assertEquals(0, stale.second.passedToday)
    }
}
