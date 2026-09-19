package com.netanel.roadwatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogicTest {
    private val fullRoad = Polygon2(
        listOf(Vec2(0f, 0f), Vec2(1f, 0f), Vec2(1f, 1f), Vec2(0f, 1f))
    )

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
    fun automaticModeParksExistingCarWithoutManualZone() {
        val engine = VehicleStateEngine(parkingDwellMs = 900L, stoppingDwellMs = 250L)
        var now = 0L
        fun stopped() = TrackSnapshot(
            1, Box(.2f, .2f, .3f, .3f), VehicleClass.CAR, .9f,
            0f, Vec2(0f, 0f), now, now, 3
        )

        engine.update(listOf(stopped()), ZoneConfig(), now, automaticMode = true)
        now = 300
        assertEquals(VehicleState.STOPPING, engine.update(listOf(stopped()), ZoneConfig(), now, true).first.first().state)
        now = 1_000
        val result = engine.update(listOf(stopped()), ZoneConfig(), now, true)
        assertEquals(VehicleState.PARKED, result.first.first().state)
        assertEquals(1, result.second.parkedNow)
        assertEquals(0, result.second.parkedToday)
    }

    @Test
    fun automaticModeCountsMovingVehicleWithoutTripwire() {
        val engine = VehicleStateEngine(
            movingDwellMs = 100L,
            movingSpeedMin = .05f,
            automaticPassDistance = .025f
        )
        val zones = ZoneConfig()
        var now = 0L
        fun track(x: Float, hits: Int) = TrackSnapshot(
            7, Box(x, .3f, x + .1f, .45f), VehicleClass.CAR, .9f,
            .12f, Vec2(.12f, 0f), now, now, hits
        )

        engine.update(listOf(track(.10f, 3)), zones, now, true)
        now = 120
        engine.update(listOf(track(.12f, 4)), zones, now, true)
        now = 240
        val metrics = engine.update(listOf(track(.15f, 5)), zones, now, true).second
        assertEquals(1, metrics.passedToday)
        now = 360
        assertEquals(1, engine.update(listOf(track(.20f, 6)), zones, now, true).second.passedToday)
    }

    @Test
    fun movingVehicleThatParksCreatesOneParkingEventAutomaticMode() {
        val engine = VehicleStateEngine(
            parkingDwellMs = 700L,
            stoppingDwellMs = 200L,
            movingDwellMs = 100L,
            movingSpeedMin = .05f
        )
        val zones = ZoneConfig()
        var now = 0L
        fun moving() = TrackSnapshot(
            4, Box(.1f, .2f, .2f, .3f), VehicleClass.CAR, .9f,
            .10f, Vec2(.10f, 0f), now, now, 4
        )
        fun stopped() = moving().copy(speed = 0f, velocity = Vec2(0f, 0f))

        engine.update(listOf(moving()), zones, now, true)
        now = 120
        engine.update(listOf(moving()), zones, now, true)
        now = 250
        engine.update(listOf(stopped()), zones, now, true)
        now = 1_000
        val result = engine.update(listOf(stopped()), zones, now, true)
        assertEquals(VehicleState.PARKED, result.first.first().state)
        assertEquals(1, result.second.parkedToday)
        assertEquals(1, result.second.parkedNow)
    }

    @Test
    fun multipleParkingZonesAreStillSupportedForManualCompatibility() {
        val left = Polygon2(listOf(Vec2(0f,0f), Vec2(.3f,0f), Vec2(.3f,1f), Vec2(0f,1f)))
        val right = Polygon2(listOf(Vec2(.7f,0f), Vec2(1f,0f), Vec2(1f,1f), Vec2(.7f,1f)))
        val zones = ZoneConfig(road = fullRoad, parkingZones = listOf(left, right))
        assertTrue(zones.isInParking(Vec2(.1f,.5f)))
        assertTrue(zones.isInParking(Vec2(.9f,.5f)))
        assertFalse(zones.isInParking(Vec2(.5f,.5f)))
    }

    @Test
    fun manualFiniteTripwireStillWorks() {
        val engine = VehicleStateEngine()
        val zones = ZoneConfig(
            road = fullRoad,
            countLine = Line2(Vec2(.5f, .25f), Vec2(.5f, .75f))
        )
        val a = TrackSnapshot(
            9, Box(.35f,.3f,.45f,.45f), VehicleClass.CAR,.9f,.2f,Vec2(.2f,0f),0,0,3
        )
        val b = a.copy(box = Box(.55f,.3f,.65f,.45f), lastSeenMs = 100, hits = 4)
        engine.update(listOf(a), zones, 0)
        assertEquals(1, engine.update(listOf(b), zones, 100).second.passedToday)
    }
}
