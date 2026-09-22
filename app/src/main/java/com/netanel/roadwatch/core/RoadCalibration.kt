package com.netanel.roadwatch.core

import kotlin.math.abs
import kotlin.math.sqrt

/** Four ordered corners of a measured rectangle on the road plane. No guessed scale. */
class RoadCalibration(val corners: List<Vec2>, val widthMeters: Float, val lengthMeters: Float) {
    private val h: DoubleArray
    val polygon = Polygon2(corners)
    init {
        require(validQuad(corners)) { "סמן ארבע פינות של מלבן על הכביש, לפי הסדר" }
        require(widthMeters.isFinite() && lengthMeters.isFinite() && widthMeters in 1f..100f && lengthMeters in 1f..200f)
        val world = listOf(Vec2(0f, 0f), Vec2(widthMeters, 0f), Vec2(widthMeters, lengthMeters), Vec2(0f, lengthMeters))
        val a = Array(8) { DoubleArray(9) }
        corners.zip(world).forEachIndexed { i, (p, q) ->
            val x = p.x.toDouble(); val y = p.y.toDouble(); val u = q.x.toDouble(); val v = q.y.toDouble()
            a[2*i] = doubleArrayOf(x,y,1.0,0.0,0.0,0.0,-u*x,-u*y,u)
            a[2*i+1] = doubleArrayOf(0.0,0.0,0.0,x,y,1.0,-v*x,-v*y,v)
        }
        for (c in 0..7) {
            val pivot = (c..7).maxByOrNull { abs(a[it][c]) }!!
            require(abs(a[pivot][c]) > 1e-9) { "כיול לא תקין" }
            val row = a[c]; a[c] = a[pivot]; a[pivot] = row
            val d = a[c][c]; for (j in c..8) a[c][j] /= d
            for (r in 0..7) if (r != c) {
                val f = a[r][c]; for (j in c..8) a[r][j] -= f * a[c][j]
            }
        }
        h = DoubleArray(8) { a[it][8] }
    }
    fun project(p: Vec2): Vec2? {
        if (!polygon.contains(p)) return null
        val d = h[6]*p.x + h[7]*p.y + 1.0
        if (abs(d) < 1e-6) return null
        return Vec2(((h[0]*p.x+h[1]*p.y+h[2])/d).toFloat(), ((h[3]*p.x+h[4]*p.y+h[5])/d).toFloat())
    }
    companion object {
        fun validQuad(p: List<Vec2>): Boolean {
            if (p.size != 4 || p.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..1f || it.y !in 0f..1f }) return false
            val turns = p.indices.map { i ->
                val a=p[i]; val b=p[(i+1)%4]; val c=p[(i+2)%4]
                (b.x-a.x)*(c.y-b.y)-(b.y-a.y)*(c.x-b.x)
            }
            return (turns.all { it > .0002f } || turns.all { it < -.0002f }) &&
                abs(p.indices.sumOf { i -> val a=p[i]; val b=p[(i+1)%4]; (a.x*b.y-b.x*a.y).toDouble() }) > .004
        }
    }
}

class SpeedEstimator {
    private data class Sample(val t: Long, val p: Vec2)
    private val history = mutableMapOf<Int, MutableList<Sample>>()
    fun reset() = history.clear()
    fun update(tracks: List<TrackSnapshot>, calibration: RoadCalibration?, now: Long): Map<Int, Float> {
        if (calibration == null) { reset(); return emptyMap() }
        history.keys.retainAll(tracks.map { it.id }.toSet())
        val speeds = mutableMapOf<Int, Float>()
        for (track in tracks) {
            if (track.lastSeenMs != now) continue // Never measure extrapolated boxes.
            val p = calibration.project(track.bottomCenter)
            if (p == null) { history.remove(track.id); continue }
            val samples = history.getOrPut(track.id) { mutableListOf() }
            if (samples.isNotEmpty() && now - samples.last().t > 500) samples.clear()
            if (samples.lastOrNull()?.t == now) continue
            samples.add(Sample(now, p))
            samples.removeAll { now - it.t > 900 }
            if (samples.size < 4 || now - samples.first().t < 300) continue
            val times = samples.map { (it.t - now) / 1000.0 }
            val mt = times.average(); val mx = samples.map { it.p.x.toDouble() }.average(); val my = samples.map { it.p.y.toDouble() }.average()
            val variance = times.sumOf { (it-mt)*(it-mt) }
            if (variance <= 1e-8) continue
            val vx = samples.indices.sumOf { (times[it]-mt)*(samples[it].p.x-mx) } / variance
            val vy = samples.indices.sumOf { (times[it]-mt)*(samples[it].p.y-my) } / variance
            val residual = sqrt(samples.indices.sumOf {
                val dx = samples[it].p.x - (mx+vx*(times[it]-mt)); val dy = samples[it].p.y - (my+vy*(times[it]-mt))
                dx*dx+dy*dy
            } / samples.size)
            val kmh = (sqrt(vx*vx+vy*vy)*3.6).toFloat()
            // Suppress ID jumps, poor geometry and unstable samples instead of inventing speed.
            if (residual < .65 && kmh.isFinite() && kmh in 2f..180f) speeds[track.id] = kmh
        }
        return speeds
    }
}
