package com.netanel.roadwatch.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class SafetyV10Test {
    private val rect = listOf(Vec2(.1f,.1f),Vec2(.9f,.1f),Vec2(.9f,.9f),Vec2(.1f,.9f))
    private val zone = Polygon2(listOf(Vec2(.4f,.3f),Vec2(.6f,.3f),Vec2(.6f,.8f),Vec2(.4f,.8f)))
    private fun car(x: Float, t: Long, id: Int = 1, y: Float = .5f) = TrackSnapshot(id,Box(x-.025f,y-.093f,x+.025f,y+.007f),VehicleClass.CAR,.9f,.2f,Vec2(.2f,0f),t,t,5)
    private fun person(t: Long) = PersonVisual(PersonTrackSnapshot(2,Box(.48f,.35f,.52f,.55f),.9f,0f,Vec2(0f,0f),1000,t,5),PersonState.CROSSING)

    @Test fun calibrationMapsRectangleAndPerspective() {
        val c=RoadCalibration(rect,8f,16f)
        val p=c.project(Vec2(.5f,.5f))!!
        assertEquals(4f,p.x,.001f); assertEquals(8f,p.y,.001f)
        assertNull(c.project(Vec2(.95f,.5f)))
        val perspective=RoadCalibration(listOf(Vec2(.2f,.8f),Vec2(.8f,.8f),Vec2(.6f,.2f),Vec2(.4f,.2f)),6f,12f)
        val middle=perspective.project(Vec2(.5f,.35f))!!
        assertEquals(3f,middle.x,.01f); assertEquals(6f,middle.y,.01f)
    }
    @Test fun rejectsCrossedOrCollinearCorners() {
        assertFalse(RoadCalibration.validQuad(listOf(rect[0],rect[2],rect[1],rect[3])))
        assertFalse(RoadCalibration.validQuad(List(4){Vec2(.2f+it*.1f,.5f)}))
    }
    @Test fun measuresKnownSpeedAndRequiresCalibration() {
        val engine=SpeedEstimator(); val c=RoadCalibration(rect,8f,16f)
        var speeds=emptyMap<Int,Float>()
        // 0.04 normalized/100ms -> 4m/s -> 14.4 km/h.
        for(i in 0..7) speeds=engine.update(listOf(car(.2f+i*.04f,1000L+i*100)),c,1000L+i*100)
        assertEquals(14.4f,speeds[1]!!,.03f)
        assertTrue(engine.update(listOf(car(.6f,1900)),null,1900).isEmpty())
    }
    @Test fun speedRejectsStaleAndOutsideSamples() {
        val c=RoadCalibration(rect,8f,16f); val e=SpeedEstimator()
        for(i in 0..4) e.update(listOf(car(.2f+i*.03f,1000L+i*100)),c,1000L+i*100)
        assertTrue(e.update(listOf(car(.32f,1400)),c,1500).isEmpty())
        assertTrue(e.update(listOf(car(.95f,1600)),c,1600).isEmpty())
    }
    @Test fun oneEventForMovingEntryNotPerFrameOrPerson() {
        val e=YieldEventCounter()
        assertEquals(0,e.update(listOf(person(1000)),listOf(car(.3f,1000)),zone,1000))
        assertEquals(1,e.update(listOf(person(1200),person(1200)),listOf(car(.5f,1200)),zone,1200))
        assertEquals(0,e.update(listOf(person(1400)),listOf(car(.55f,1400)),zone,1400))
        assertEquals(0,e.update(listOf(person(1600)),listOf(car(.7f,1600)),zone,1600))
    }
    @Test fun fastVehicleCrossesWholeNarrowZoneBetweenFrames() {
        val e=YieldEventCounter()
        e.update(listOf(person(1000)),listOf(car(.3f,1000)),zone,1000)
        assertEquals(1,e.update(listOf(person(1200)),listOf(car(.7f,1200)),zone,1200))
    }
    @Test fun doesNotCountStationaryNoPedestrianLatePedestrianOrLongGap() {
        val e=YieldEventCounter()
        e.update(emptyList(),listOf(car(.3f,1000)),zone,1000)
        assertEquals(0,e.update(emptyList(),listOf(car(.5f,1200)),zone,1200))
        e.reset(); e.update(listOf(person(1000)),listOf(car(.3f,1000)),zone,1000)
        assertEquals(0,e.update(listOf(person(1200)),listOf(car(.3f,1200)),zone,1200))
        e.reset(); e.update(emptyList(),listOf(car(.3f,1000)),zone,1000)
        assertEquals(0,e.update(listOf(person(1200)),listOf(car(.5f,1200)),zone,1200))
        e.reset(); e.update(listOf(person(1000)),listOf(car(.3f,1000)),zone,1000)
        assertEquals(0,e.update(listOf(person(2000)),listOf(car(.5f,2000)),zone,2000))
    }
    @Test fun noCountOnLineExtensionOrWithoutConfirmedZone() {
        val e=YieldEventCounter()
        e.update(listOf(person(1000)),listOf(car(.3f,1000,y=.9f)),zone,1000)
        assertEquals(0,e.update(listOf(person(1200)),listOf(car(.7f,1200,y=.9f)),zone,1200))
        assertEquals(0,e.update(listOf(person(1400)),listOf(car(.5f,1400)),null,1400))
    }
    @Test fun footPointNotBodyOverlapDeterminesCrossing() {
        val p=PersonTrackSnapshot(1,Box(.45f,.4f,.55f,.9f),.9f,0f,Vec2(0f,0f),1000,1000,5)
        val r=PedestrianYieldEngine().update(listOf(p),emptyList(),CrosswalkEstimate(Box(.4f,.3f,.6f,.8f),.9f),1000)
        assertEquals(0,r.peopleInCrosswalkNow)
    }
    @Test fun staleCrosswalkLockExpires() {
        val lock=CrosswalkLock(requiredStableHits=2)
        val c=CrosswalkEstimate(Box(.2f,.4f,.6f,.7f),.85f)
        lock.update(c,1000); assertTrue(lock.update(c,1400).locked)
        assertFalse(lock.update(null,17000).locked)
    }
    @Test fun distantUnrelatedCarDoesNotStealIdAndFastCarKeepsId() {
        val t=VehicleTracker()
        val first=t.update(listOf(Detection(Box(.1f,.3f,.17f,.4f),VehicleClass.CAR,.9f)),1000).first()
        val second=t.update(listOf(Detection(Box(.16f,.3f,.23f,.4f),VehicleClass.CAR,.9f)),1150)
        assertEquals(first.id,second.single().id)
        val third=t.update(listOf(Detection(Box(.8f,.3f,.87f,.4f),VehicleClass.CAR,.9f)),1300)
        assertEquals(2,third.size)
        assertNotEquals(first.id,third.first{it.lastSeenMs==1300L}.id)
    }
    @Test fun findsSyntheticZebraAtSeveralAnglesAndRejectsBlankOrSingleStripe() {
        val w=256; val h=256
        for(angle in listOf(0.0,30.0,60.0,90.0)) {
            val rad=angle*PI/180; val pixels=IntArray(w*h){i ->
                val x=i%w-128; val y=i/w-128
                val u=x*cos(rad)+y*sin(rad); val v=-x*sin(rad)+y*cos(rad)
                val white=abs(u)<36 && (-2..2).any { abs(v-it*17)<4 }
                if(white) 0xffdddddd.toInt() else 0xff333333.toInt()
            }
            assertNotNull("angle $angle",ZebraDetector().detect(pixels,w,h))
        }
        assertNull(ZebraDetector().detect(IntArray(w*h){0xffcccccc.toInt()},w,h))
        assertNull(ZebraDetector().detect(IntArray(w*h){i->if(i%w in 70..180 && i/w in 120..128) 0xffeeeeee.toInt() else 0xff333333.toInt()},w,h))
    }
}
