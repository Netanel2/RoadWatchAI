package com.netanel.roadwatch.core

class PedestrianYieldEngine {
    data class Result(
        val people: List<PersonVisual>,
        val peopleNow: Int,
        val peopleInCrosswalkNow: Int,
        val yieldRiskNow: Int
    )

    fun update(
        personTracks: List<PersonTrackSnapshot>,
        vehicleVisuals: List<TrackVisual>,
        crosswalk: CrosswalkEstimate?,
        nowMs: Long,
        manualZone: Polygon2? = null
    ): Result {
        val confirmedPeople = personTracks.filter {
            val fresh = nowMs - it.lastSeenMs <= 900L
            val enoughHits = it.hits >= if (it.confidence >= 0.30f) 2 else 3
            fresh && enoughHits
        }

        val crosswalkBox = crosswalk?.box
        val peopleVisuals = confirmedPeople.map { track ->
            val state = classify(track, crosswalkBox, manualZone)
            PersonVisual(track, state)
        }

        val activePeople = peopleVisuals.count {
            it.state == PersonState.WAITING || it.state == PersonState.CROSSING
        }
        val riskVehicles = if (crosswalkBox != null && activePeople > 0) {
            val approach = crosswalkBox.expand(0.16f, 0.14f)
            vehicleVisuals.count { visual ->
                val moving = visual.state == VehicleState.MOVING ||
                    visual.state == VehicleState.LEAVING ||
                    visual.state == VehicleState.STOPPING
                val near = approach.intersects(visual.track.box) || approach.contains(visual.track.bottomCenter)
                val fresh = nowMs - visual.track.lastSeenMs <= 900L
                moving && near && fresh
            }
        } else 0

        return Result(
            people = peopleVisuals,
            peopleNow = peopleVisuals.size,
            peopleInCrosswalkNow = peopleVisuals.count { it.state == PersonState.CROSSING },
            yieldRiskNow = riskVehicles
        )
    }

    private fun classify(track: PersonTrackSnapshot, crosswalk: Box?, manualZone: Polygon2?): PersonState {
        if (crosswalk == null) return PersonState.OTHER

        val point = track.bottomCenter
        val inCrossing = manualZone?.contains(point) ?: crosswalk.contains(point)
        if (inCrossing) return PersonState.CROSSING

        val zone = manualZone ?: CrosswalkEstimate(crosswalk,1f).polygon()
        val distance = zone.points.indices.minOf { i ->
            val a=zone.points[i]; val b=zone.points[(i+1)%zone.points.size]
            val dx=b.x-a.x; val dy=b.y-a.y
            val t=(((point.x-a.x)*dx+(point.y-a.y)*dy)/(dx*dx+dy*dy).coerceAtLeast(1e-8f)).coerceIn(0f,1f)
            point.distanceTo(Vec2(a.x+t*dx,a.y+t*dy))
        }
        if (distance <= .055f) {
            return if (track.speed <= 0.025f) PersonState.WAITING else PersonState.APPROACHING
        }

        val approachZone = crosswalk.expand(0.16f, 0.14f)
        if (approachZone.contains(point)) return PersonState.APPROACHING

        return PersonState.OTHER
    }
}
