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
    fun existingParkedVehicleCountsNowButNotParkedToday() {
        val engine = VehicleStateEngine(parkingDwellMs = 1_000L, stoppingDwellMs = 300L)
        val zones = ZoneConfig(
            road = fullRoad,
            parkingZones = listOf(fullRoad),
            countLine = Line2(Vec2(.5f, 0f), Vec2(.5f, 1f))
        )
        var now = 0L
        fun stopped() = TrackSnapshot(
            1, Box(.2f, .2f, .3f, .3f), VehicleClass.CAR, .9f,
            0f, Vec2(0f, 0f), now, now, 3
        )

        engine.update(listOf(stopped()), zones, now)
        now = 400
        assertEquals(VehicleState.STOPPING, engine.update(listOf(stopped()), zones, now).first.first().state)
        now = 1_200
        val result = engine.update(listOf(stopped()), zones, now)
        assertEquals(VehicleState.PARKED, result.first.first().state)
        assertEquals(1, result.second.parkedNow)
        assertEquals(0, result.second.parkedToday)
    }

    @Test
    fun movingVehicleThatParksCreatesOneParkingEvent() {
        val engine = VehicleStateEngine(parkingDwellMs = 800L, stoppingDwellMs = 200L)
        val zones = ZoneConfig(road = fullRoad, parkingZones = listOf(fullRoad))

        var now = 0L
        fun moving() = TrackSnapshot(
            4, Box(.1f, .2f, .2f, .3f), VehicleClass.CAR, .9f,
            .08f, Vec2(.08f, 0f), now, now, 3
        )
        fun stopped() = moving().copy(speed = 0f, velocity = Vec2(0f, 0f))

        engine.update(listOf(moving()), zones, now)
        now = 100
        engine.update(listOf(moving()), zones, now)
        now = 250
        engine.update(listOf(moving()), zones, now)
        now = 350
        engine.update(listOf(stopped()), zones, now)
        now = 1_250
        val result = engine.update(listOf(stopped()), zones, now)
        assertEquals(VehicleState.PARKED, result.first.first().state)
        assertEquals(1, result.second.parkedToday)
        assertEquals(1, result.second.parkedNow)
    }

    @Test
    fun multipleParkingZonesAreSupported() {
        val left = Polygon2(listOf(Vec2(0f,0f), Vec2(.3f,0f), Vec2(.3f,1f), Vec2(0f,1f)))
        val right = Polygon2(listOf(Vec2(.7f,0f), Vec2(1f,0f), Vec2(1f,1f), Vec2(.7f,1f)))
        val zones = ZoneConfig(road = fullRoad, parkingZones = listOf(left, right))
        assertTrue(zones.isInParking(Vec2(.1f,.5f)))
        assertTrue(zones.isInParking(Vec2(.9f,.5f)))
        assertFalse(zones.isInParking(Vec2(.5f,.5f)))
    }

    @Test
    fun lineCrossCountsOnceOnlyOnFiniteSegment() {
        val engine = VehicleStateEngine()
        val zones = ZoneConfig(
            road = fullRoad,
            parkingZones = emptyList(),
            countLine = Line2(Vec2(.5f, .25f), Vec2(.5f, .75f))
        )
        val a = TrackSnapshot(
            7, Box(.35f,.3f,.45f,.45f), VehicleClass.CAR,.9f,.2f,Vec2(.2f,0f),0,0,3
        )
        val b = TrackSnapshot(
            7, Box(.55f,.3f,.65f,.45f), VehicleClass.CAR,.9f,.2f,Vec2(.2f,0f),100,100,4
        )
        engine.update(listOf(a), zones, 0)
        val metrics = engine.update(listOf(b), zones, 100).second
        assertEquals(1, metrics.passedToday)
        val again = engine.update(listOf(b.copy(box = Box(.65f,.3f,.75f,.45f), lastSeenMs = 200)), zones, 200).second
        assertEquals(1, again.passedToday)

        val outsideEngine = VehicleStateEngine()
        val c = a.copy(id = 9, box = Box(.35f,.82f,.45f,.92f), lastSeenMs = 0)
        val d = b.copy(id = 9, box = Box(.55f,.82f,.65f,.92f), lastSeenMs = 100)
        outsideEngine.update(listOf(c), zones, 0)
        assertEquals(0, outsideEngine.update(listOf(d), zones, 100).second.passedToday)
    }

    @Test
    fun staleTrackDoesNotAccumulateParkingEvidence() {
        val engine = VehicleStateEngine(parkingDwellMs = 600L, stoppingDwellMs = 200L, evidenceFreshnessMs = 300L)
        val zones = ZoneConfig(road = fullRoad, parkingZones = listOf(fullRoad))
        val track = TrackSnapshot(
            11, Box(.2f,.2f,.3f,.3f), VehicleClass.CAR,.9f,
            0f, Vec2(0f,0f), 0, 0, 3
        )
        engine.update(listOf(track), zones, 0)
        val stale = engine.update(listOf(track), zones, 1_000)
        assertEquals(VehicleState.UNKNOWN, stale.first.first().state)
        assertEquals(0, stale.second.parkedNow)
    }
}
