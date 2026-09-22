package com.netanel.roadwatch.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.MotionEvent
import com.netanel.roadwatch.core.Polygon2
import com.netanel.roadwatch.core.Box
import com.netanel.roadwatch.core.CrosswalkEstimate
import com.netanel.roadwatch.core.PersonState
import com.netanel.roadwatch.core.PersonVisual
import com.netanel.roadwatch.core.TrackVisual
import com.netanel.roadwatch.core.Vec2
import com.netanel.roadwatch.core.VehicleState
import kotlin.math.min

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var visuals: List<TrackVisual> = emptyList()
    private var people: List<PersonVisual> = emptyList()
    private var crosswalk: CrosswalkEstimate? = null
    private var crosswalkLocked = false
    private var imageWidth = 1
    private var imageHeight = 1
    private var vehicleFromBoxes: Map<Int, Box> = emptyMap()
    private var personFromBoxes: Map<Int, Box> = emptyMap()
    private var animationStartMs = 0L
    private val animationDurationMs = 1L
    var speedKmh: Map<Int, Float> = emptyMap()
    var manualPolygon: Polygon2? = null
    var calibrationPolygon: Polygon2? = null
    private var selection: ((List<Vec2>) -> Unit)? = null
    private val selected = mutableListOf<Vec2>()
    fun selectFourCorners(done: (List<Vec2>) -> Unit) {
        selected.clear(); selection = done; invalidate()
    }
    fun cancelSelection() { selection = null; selected.clear(); invalidate() }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (selection == null) return false
        if (event.action == MotionEvent.ACTION_UP) {
            val frame = frameRect()
            if (frame.contains(event.x,event.y)) {
                selected.add(Vec2((event.x-frame.left)/frame.width(),(event.y-frame.top)/frame.height()))
                if (selected.size == 4) {
                    val callback = selection; val points = selected.toList()
                    selection = null; selected.clear(); callback?.invoke(points)
                }
                performClick(); invalidate()
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.4f)
    }
    private val personPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
    }
    private val crosswalkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.4f)
        pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(6f)), 0f)
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(210, 8, 15, 24)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(11f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun setScene(
        trackVisuals: List<TrackVisual>,
        personVisuals: List<PersonVisual>,
        crosswalkEstimate: CrosswalkEstimate?,
        crosswalkLocked: Boolean,
        rotatedImageWidth: Int,
        rotatedImageHeight: Int
    ) {
        val previousVehicles = visuals.associate { it.track.id to it.track.box }
        val previousPeople = people.associate { it.track.id to it.track.box }
        vehicleFromBoxes = trackVisuals.associate { visual ->
            visual.track.id to (previousVehicles[visual.track.id] ?: visual.track.box)
        }
        personFromBoxes = personVisuals.associate { visual ->
            visual.track.id to (previousPeople[visual.track.id] ?: visual.track.box)
        }
        visuals = trackVisuals
        people = personVisuals
        crosswalk = crosswalkEstimate
        this.crosswalkLocked = crosswalkLocked
        imageWidth = rotatedImageWidth.coerceAtLeast(1)
        imageHeight = rotatedImageHeight.coerceAtLeast(1)
        animationStartMs = android.os.SystemClock.elapsedRealtime()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = android.os.SystemClock.elapsedRealtime()
        val animationT = ((now - animationStartMs).toFloat() / animationDurationMs).coerceIn(0f, 1f)

        fun drawPolygon(poly: Polygon2?, color: Int) {
            if (poly == null || poly.points.isEmpty()) return
            val path = Path()
            poly.points.forEachIndexed { i,p -> val v=normalizedToView(p); if(i==0) path.moveTo(v.x,v.y) else path.lineTo(v.x,v.y) }
            if (poly.points.size >= 3) path.close()
            crosswalkPaint.color=color; canvas.drawPath(path,crosswalkPaint)
        }
        drawPolygon(calibrationPolygon,Color.rgb(130,190,255))
        drawPolygon(manualPolygon,Color.rgb(80,231,219))
        drawPolygon(Polygon2(selected),Color.YELLOW)
        selected.forEachIndexed { i,p ->
            val v=normalizedToView(p); dotPaint.color=Color.YELLOW
            canvas.drawCircle(v.x,v.y,dp(5f),dotPaint)
            drawLabel(canvas,"${i+1}",v.x,v.y,Color.YELLOW)
        }
        crosswalk?.takeIf { manualPolygon == null }?.let { estimate ->
            val rect = boxToView(estimate.box)
            crosswalkPaint.color = if (crosswalkLocked) Color.rgb(80, 231, 219) else Color.rgb(125, 176, 210)
            val path = Path().apply { addRoundRect(rect, dp(8f), dp(8f), Path.Direction.CW) }
            canvas.drawPath(path, crosswalkPaint)
            val conf = (estimate.confidence * 100f).toInt().coerceIn(0, 100)
            val mode = if (crosswalkLocked) "LOCK ✓" else "לומד"
            drawLabel(canvas, "מעבר חציה · $mode · ${conf}%", rect.left, rect.top, crosswalkPaint.color)
        }

        people.filter { now - it.track.lastSeenMs <= 450L }.forEach { person ->
            val drawBox = interpolateBox(personFromBoxes[person.track.id] ?: person.track.box, person.track.box, animationT)
            val rect = boxToView(drawBox)
            val color = when (person.state) {
                PersonState.CROSSING -> Color.rgb(255, 91, 91)
                PersonState.WAITING -> Color.rgb(255, 191, 72)
                PersonState.APPROACHING -> Color.rgb(255, 220, 92)
                PersonState.LEAVING -> Color.rgb(184, 132, 255)
                PersonState.OTHER -> Color.rgb(255, 210, 74)
            }
            personPaint.color = color
            canvas.drawRoundRect(rect, dp(6f), dp(6f), personPaint)
            val conf = (person.track.confidence * 100f).toInt().coerceIn(0, 100)
            drawLabel(canvas, "אדם #${person.track.id} · ${person.state.he} · ${conf}%", rect.left, rect.top, color)
        }

        visuals.filter { now - it.track.lastSeenMs <= 500L }.forEach { visual ->
            // Bounded display-only prediction compensates inference age; never feeds counters/speed.
            val age = ((now - visual.track.lastSeenMs).coerceIn(0L,160L) / 1000f)
            val b = visual.track.box
            val dx = (visual.track.velocity.x * age).coerceIn(-.06f,.06f)
            val dy = (visual.track.velocity.y * age).coerceIn(-.06f,.06f)
            val drawBox = Box(b.left+dx,b.top+dy,b.right+dx,b.bottom+dy).clamp01()
            val rect = boxToView(drawBox)
            val color = when (visual.state) {
                VehicleState.PARKED -> Color.rgb(66, 216, 154)
                VehicleState.MOVING -> Color.rgb(74, 168, 255)
                VehicleState.STOPPING -> Color.rgb(245, 196, 81)
                VehicleState.LEAVING -> Color.rgb(190, 112, 255)
                VehicleState.UNKNOWN -> Color.rgb(150, 166, 184)
            }
            boxPaint.color = color
            boxPaint.strokeWidth = if (visual.state == VehicleState.PARKED) dp(3f) else dp(2.2f)
            canvas.drawRoundRect(rect, dp(7f), dp(7f), boxPaint)

            val bottom = normalizedToView(drawBox.bottomCenter)
            dotPaint.color = color
            canvas.drawCircle(bottom.x, bottom.y, dp(3.5f), dotPaint)

            val conf = (visual.track.confidence * 100f).toInt().coerceIn(0, 100)
            val parkedSeconds = ((now - visual.stateSinceMs).coerceAtLeast(0L) / 1000L)
            val suffix = speedKmh[visual.track.id]?.let { " · ≈${it.toInt()} קמ״ש" }
                ?: if (visual.state == VehicleState.PARKED) " · ${parkedSeconds}s" else ""
            val label = "#${visual.track.id} · ${visual.track.vehicleClass.he} · ${visual.state.he} · ${conf}%$suffix"
            drawLabel(canvas, label, rect.left, rect.top, color)
        }
        if (visuals.any { now - it.track.lastSeenMs < 550L } || people.any { now - it.track.lastSeenMs < 550L }) postInvalidateOnAnimation()
    }

    private fun interpolateBox(from: Box, to: Box, t: Float): Box {
        val a = t.coerceIn(0f, 1f)
        return Box(
            left = from.left + (to.left - from.left) * a,
            top = from.top + (to.top - from.top) * a,
            right = from.right + (to.right - from.right) * a,
            bottom = from.bottom + (to.bottom - from.bottom) * a
        )
    }

    private fun frameRect(): RectF {
        val viewW = width.toFloat().coerceAtLeast(1f)
        val viewH = height.toFloat().coerceAtLeast(1f)
        val scale = min(viewW / imageWidth, viewH / imageHeight)
        val drawW = imageWidth * scale
        val drawH = imageHeight * scale
        val left = (viewW - drawW) * 0.5f
        val top = (viewH - drawH) * 0.5f
        return RectF(left, top, left + drawW, top + drawH)
    }

    private fun normalizedToView(point: Vec2): Vec2 {
        val rect = frameRect()
        return Vec2(
            rect.left + point.x * rect.width(),
            rect.top + point.y * rect.height()
        )
    }

    private fun boxToView(box: Box): RectF {
        val tl = normalizedToView(Vec2(box.left, box.top))
        val br = normalizedToView(Vec2(box.right, box.bottom))
        return RectF(tl.x, tl.y, br.x, br.y)
    }

    private fun drawLabel(canvas: Canvas, text: String, x: Float, y: Float, accent: Int) {
        val paddingX = dp(6f)
        val paddingY = dp(4f)
        val width = labelPaint.measureText(text) + paddingX * 2
        val height = labelPaint.textSize + paddingY * 2
        val safeX = x.coerceIn(0f, (this.width - width).coerceAtLeast(0f))
        val top = (y - height).coerceAtLeast(0f)
        val bg = RectF(safeX, top, (safeX + width).coerceAtMost(this.width.toFloat()), top + height)
        canvas.drawRoundRect(bg, dp(6f), dp(6f), labelBg)
        dotPaint.color = accent
        canvas.drawRect(bg.left, bg.top, bg.left + dp(3f), bg.bottom, dotPaint)
        canvas.drawText(text, bg.left + paddingX, bg.bottom - paddingY, labelPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
