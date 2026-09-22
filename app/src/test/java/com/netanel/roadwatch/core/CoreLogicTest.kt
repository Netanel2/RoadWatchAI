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

    @Test
    fun personTrackerConfirmsSmallPedestrianAcrossFrames() {
        val tracker = PersonTracker()
        var now = 0L
        val box1 = Box(.30f, .40f, .34f, .54f)
        val box2 = Box(.305f, .40f, .345f, .54f)
        tracker.update(listOf(PersonDetection(box1, .27f)), now)
        now += 120
        val second = tracker.update(listOf(PersonDetection(box2, .28f)), now)
        assertEquals(1, second.size)
        assertEquals(2, second.first().hits)
        now += 120
        val third = tracker.update(listOf(PersonDetection(box2, .30f)), now)
        assertEquals(3, third.first().hits)
    }

    @Test
    fun crosswalkLocksAfterStableRepeatedEstimates() {
        val lock = CrosswalkLock(requiredStableHits = 3)
        var now = 0L
        val a = CrosswalkEstimate(Box(.10f, .65f, .45f, .80f), .65f)
        val b = CrosswalkEstimate(Box(.11f, .65f, .46f, .80f), .68f)
        assertTrue(!lock.update(a, now).locked)
        now += 100
        assertTrue(!lock.update(b, now).locked)
        now += 100
        val state = lock.update(a, now)
        assertTrue(state.locked)
        now += 5_000
        assertTrue(lock.update(null, now).locked)
    }

    @Test
    fun crossingPersonCreatesYieldRiskNearMovingVehicle() {
        val engine = PedestrianYieldEngine()
        val crosswalk = CrosswalkEstimate(Box(.30f, .50f, .60f, .68f), .8f)
        val person = PersonTrackSnapshot(
            id = 1,
            box = Box(.40f, .48f, .46f, .64f),
            confidence = .8f,
            speed = .01f,
            velocity = Vec2(0f, 0f),
            ageMs = 500L,
            lastSeenMs = 1000L,
            hits = 4
        )
        val vehicleTrack = TrackSnapshot(
            id = 2,
            box = Box(.55f, .62f, .70f, .75f),
            vehicleClass = VehicleClass.CAR,
            confidence = .9f,
            speed = .08f,
            velocity = Vec2(-.05f, 0f),
            ageMs = 1000L,
            lastSeenMs = 1000L,
            hits = 6
        )
        val vehicle = TrackVisual(vehicleTrack, VehicleState.MOVING, 900L)
        val result = engine.update(listOf(person), listOf(vehicle), crosswalk, 1000L)
        assertEquals(1, result.peopleInCrosswalkNow)
        assertEquals(1, result.yieldRiskNow)
    }

    @Test
    fun tinyDetectorJitterDoesNotTurnParkedCarIntoMoving() {
        val engine = VehicleStateEngine(
            parkingDwellMs = 600L,
            stoppingDwellMs = 150L,
            movingDwellMs = 180L,
            motionSampleMs = 100L
        )
        var now = 0L
        fun snap(x: Float) = TrackSnapshot(
            31, Box(x, .2f, x + .1f, .3f), VehicleClass.CAR, .82f,
            0f, Vec2(0f, 0f), now, now, 5, motionScore = .03f
        )

        var result = engine.update(listOf(snap(.200f)), now)
        for (x in listOf(.202f, .199f, .201f, .200f, .201f, .199f, .200f)) {
            now += 120L
            result = engine.update(listOf(snap(x)), now)
        }
        assertEquals(VehicleState.PARKED, result.first.first().state)
        assertEquals(0, result.second.movingNow)
    }

    @Test
    fun fastVehicleInTinyFovCountsAfterTwoHits() {
        val engine = VehicleStateEngine()
        var now = 0L
        val first = TrackSnapshot(
            id = 77,
            box = Box(.10f, .40f, .22f, .50f),
            vehicleClass = VehicleClass.CAR,
            confidence = .88f,
            speed = 0f,
            velocity = Vec2(0f, 0f),
            ageMs = 0L,
            lastSeenMs = now,
            hits = 1,
            motionScore = 0f
        )
        engine.update(listOf(first), now)

        now = 120L
        val second = first.copy(
            box = Box(.145f, .40f, .265f, .50f),
            speed = .08f,
            velocity = Vec2(.08f, 0f),
            ageMs = 120L,
            lastSeenMs = now,
            hits = 2,
            motionScore = .18f
        )
        val result = engine.update(listOf(second), now)
        assertEquals(1, result.second.passedToday)
        assertTrue(result.first.isEmpty())
    }

    @Test
    fun twoHitJitterDoesNotCreateFastPass() {
        val engine = VehicleStateEngine()
        var now = 0L
        val first = TrackSnapshot(
            id = 78,
            box = Box(.30f, .40f, .42f, .50f),
            vehicleClass = VehicleClass.CAR,
            confidence = .90f,
            speed = 0f,
            velocity = Vec2(0f, 0f),
            ageMs = 0L,
            lastSeenMs = now,
            hits = 1,
            motionScore = 0f
        )
        engine.update(listOf(first), now)

        now = 120L
        val second = first.copy(
            box = Box(.304f, .40f, .424f, .50f),
            speed = .006f,
            velocity = Vec2(.006f, 0f),
            ageMs = 120L,
            lastSeenMs = now,
            hits = 2,
            motionScore = .03f
        )
        val result = engine.update(listOf(second), now)
        assertEquals(0, result.second.passedToday)
    }

}
