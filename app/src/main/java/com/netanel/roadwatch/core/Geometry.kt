package com.netanel.roadwatch.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class Vec2(val x: Float, val y: Float) {
    fun distanceTo(other: Vec2): Float = hypot(x - other.x, y - other.y)
}

data class Box(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = max(0f, right - left)
    val height: Float get() = max(0f, bottom - top)
    val area: Float get() = width * height
    val center: Vec2 get() = Vec2((left + right) * 0.5f, (top + bottom) * 0.5f)
    val bottomCenter: Vec2 get() = Vec2((left + right) * 0.5f, top + height * 0.93f)

    fun clamp01(): Box = Box(
        left.coerceIn(0f, 1f),
        top.coerceIn(0f, 1f),
        right.coerceIn(0f, 1f),
        bottom.coerceIn(0f, 1f)
    )

    fun iou(other: Box): Float {
        val x1 = max(left, other.left)
        val y1 = max(top, other.top)
        val x2 = min(right, other.right)
        val y2 = min(bottom, other.bottom)
        val intersection = max(0f, x2 - x1) * max(0f, y2 - y1)
        val union = area + other.area - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    fun blend(other: Box, alpha: Float): Box {
        val a = alpha.coerceIn(0f, 1f)
        val b = 1f - a
        return Box(
            left * b + other.left * a,
            top * b + other.top * a,
            right * b + other.right * a,
            bottom * b + other.bottom * a
        )
    }
}

data class Line2(val a: Vec2, val b: Vec2) {
    fun side(p: Vec2): Float = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)

    /** Crossing of the infinite line. Useful for direction, not enough for counting. */
    fun crossed(previous: Vec2, current: Vec2, epsilon: Float = 0.002f): Boolean {
        val s1 = side(previous)
        val s2 = side(current)
        return abs(s1) > epsilon && abs(s2) > epsilon && (s1 < 0f) != (s2 < 0f)
    }

    /**
     * True only when the motion segment intersects the finite counting segment.
     * This prevents a vehicle crossing an imaginary extension of the line elsewhere
     * in the frame from being counted.
     */
    fun crossedSegment(previous: Vec2, current: Vec2, epsilon: Float = 0.002f): Boolean {
        if (!crossed(previous, current, epsilon)) return false
        return segmentsIntersect(previous, current, a, b, epsilon)
    }

    private fun segmentsIntersect(p1: Vec2, p2: Vec2, q1: Vec2, q2: Vec2, epsilon: Float): Boolean {
        fun orient(x: Vec2, y: Vec2, z: Vec2): Float =
            (y.x - x.x) * (z.y - x.y) - (y.y - x.y) * (z.x - x.x)

        fun onSegment(x: Vec2, y: Vec2, z: Vec2): Boolean =
            y.x >= min(x.x, z.x) - epsilon && y.x <= max(x.x, z.x) + epsilon &&
                y.y >= min(x.y, z.y) - epsilon && y.y <= max(x.y, z.y) + epsilon

        val o1 = orient(p1, p2, q1)
        val o2 = orient(p1, p2, q2)
        val o3 = orient(q1, q2, p1)
        val o4 = orient(q1, q2, p2)

        if ((o1 > epsilon && o2 < -epsilon || o1 < -epsilon && o2 > epsilon) &&
            (o3 > epsilon && o4 < -epsilon || o3 < -epsilon && o4 > epsilon)) return true

        if (abs(o1) <= epsilon && onSegment(p1, q1, p2)) return true
        if (abs(o2) <= epsilon && onSegment(p1, q2, p2)) return true
        if (abs(o3) <= epsilon && onSegment(q1, p1, q2)) return true
        if (abs(o4) <= epsilon && onSegment(q1, p2, q2)) return true
        return false
    }
}

data class Polygon2(val points: List<Vec2>) {
    fun isValid(): Boolean = points.size >= 3

    fun contains(point: Vec2): Boolean {
        if (points.size < 3) return false
        var inside = false
        var j = points.lastIndex
        for (i in points.indices) {
            val pi = points[i]
            val pj = points[j]
            val intersects = ((pi.y > point.y) != (pj.y > point.y)) &&
                point.x < (pj.x - pi.x) * (point.y - pi.y) / ((pj.y - pi.y) + 1e-9f) + pi.x
            if (intersects) inside = !inside
            j = i
        }
        return inside
    }
}
