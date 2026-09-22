package com.netanel.roadwatch.core

/** Counts suspected non-yield passages, not legal determinations. One event per tracked vehicle.
 * Requires pedestrian demand before entry, fresh real observations, and actual swept entry.
 */
class YieldEventCounter {
    private data class Observation(val point: Vec2, val time: Long)
    private val previous = mutableMapOf<Int, Observation>()
    private val counted = mutableSetOf<Int>()
    private var demandSince: Long? = null
    private val waitingSince = mutableMapOf<Int, Long>()
    fun reset() { previous.clear(); counted.clear(); demandSince = null; waitingSince.clear() }
    fun update(people: List<PersonVisual>, tracks: List<TrackSnapshot>, zone: Polygon2?, now: Long): Int {
        if (zone == null || !zone.isValid()) { reset(); return 0 }
        val freshPeople = people.filter { now - it.track.lastSeenMs <= 350 }
        val waiting = freshPeople.filter { it.state == PersonState.WAITING }.map { it.track.id }.toSet()
        waitingSince.keys.retainAll(waiting)
        waiting.forEach { waitingSince.putIfAbsent(it,now) }
        val demand = freshPeople.any { it.state == PersonState.CROSSING ||
            (it.state == PersonState.WAITING && now - (waitingSince[it.track.id] ?: now) >= 700) }
        if (!demand) demandSince = null else if (demandSince == null) demandSince = now
        var events = 0
        for (track in tracks) {
            if (track.lastSeenMs != now) continue
            val p = track.bottomCenter
            val old = previous.put(track.id, Observation(p, now)) ?: continue
            val dt = now - old.time
            if (dt !in 30..600 || track.hits < 2 || track.id in counted) continue
            val displacement = old.point.distanceTo(p)
            val moving = displacement > maxOf(.004f, track.box.width * .06f) && displacement / (dt / 1000f) > .018f
            val entry = !zone.contains(old.point) && swept(zone, old.point, p)
            if (moving && entry && demandSince != null && demandSince!! <= old.time && now - demandSince!! >= 150) {
                counted.add(track.id); events++
            }
        }
        val live = tracks.map { it.id }.toSet()
        previous.keys.retainAll(live); counted.retainAll(live)
        return events
    }
    private fun swept(zone: Polygon2, a: Vec2, b: Vec2): Boolean = zone.contains(b) ||
        zone.points.indices.any { i -> Line2(zone.points[i], zone.points[(i+1)%zone.points.size]).crossedSegment(a,b,1e-6f) }
}

fun CrosswalkEstimate.polygon(): Polygon2 = Polygon2(listOf(
    Vec2(box.left, box.top), Vec2(box.right, box.top), Vec2(box.right, box.bottom), Vec2(box.left, box.bottom)))
