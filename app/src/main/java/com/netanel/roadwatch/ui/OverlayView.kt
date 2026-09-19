package com.netanel.roadwatch.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.netanel.roadwatch.core.Box
import com.netanel.roadwatch.core.Line2
import com.netanel.roadwatch.core.Polygon2
import com.netanel.roadwatch.core.TrackVisual
import com.netanel.roadwatch.core.Vec2
import com.netanel.roadwatch.core.VehicleState
import com.netanel.roadwatch.core.ZoneConfig
import kotlin.math.min

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class EditMode { NONE, ROAD, PARKING, COUNT_LINE }

    var onZonesChanged: ((ZoneConfig) -> Unit)? = null
    var onEditProgress: ((String) -> Unit)? = null

    private var visuals: List<TrackVisual> = emptyList()
    private var zones: ZoneConfig = ZoneConfig()
    private var imageWidth = 1
    private var imageHeight = 1
    private var editMode = EditMode.NONE
    private val draft = mutableListOf<Vec2>()

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.4f)
    }
    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.4f)
    }
    private val zoneFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(215, 8, 15, 24)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(12f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dashedEffect = android.graphics.DashPathEffect(floatArrayOf(dp(8f), dp(7f)), 0f)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
    }

    fun setScene(
        trackVisuals: List<TrackVisual>,
        zoneConfig: ZoneConfig,
        rotatedImageWidth: Int,
        rotatedImageHeight: Int
    ) {
        visuals = trackVisuals
        zones = zoneConfig
        imageWidth = rotatedImageWidth.coerceAtLeast(1)
        imageHeight = rotatedImageHeight.coerceAtLeast(1)
        invalidate()
    }

    fun setZones(zoneConfig: ZoneConfig) {
        zones = zoneConfig
        invalidate()
    }

    fun getZones(): ZoneConfig = zones

    fun removeLastParkingZone() {
        if (zones.parkingZones.isEmpty()) return
        zones = zones.copy(parkingZones = zones.parkingZones.dropLast(1))
        onZonesChanged?.invoke(zones)
        invalidate()
    }

    fun clearAllZones() {
        zones = ZoneConfig()
        editMode = EditMode.NONE
        draft.clear()
        onZonesChanged?.invoke(zones)
        invalidate()
    }

    fun beginEdit(mode: EditMode) {
        editMode = mode
        draft.clear()
        val required = requiredPoints(mode)
        onEditProgress?.invoke(editHint(mode, 0, required))
        invalidate()
    }

    fun cancelEdit() {
        editMode = EditMode.NONE
        draft.clear()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode == EditMode.NONE) return false
        if (event.action != MotionEvent.ACTION_UP) return true

        val point = viewToNormalized(event.x, event.y) ?: return true
        draft += point
        val required = requiredPoints(editMode)
        onEditProgress?.invoke(editHint(editMode, draft.size, required))

        if (draft.size >= required) {
            zones = when (editMode) {
                EditMode.ROAD -> zones.copy(road = Polygon2(draft.toList()))
                EditMode.PARKING -> zones.copy(parkingZones = zones.parkingZones + Polygon2(draft.toList()))
                EditMode.COUNT_LINE -> zones.copy(countLine = Line2(draft[0], draft[1]))
                EditMode.NONE -> zones
            }
            onZonesChanged?.invoke(zones)
            onEditProgress?.invoke("הסימון נקלט ✓ אפשר לשמור או לסמן אזור נוסף")
            editMode = EditMode.NONE
            draft.clear()
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawZones(canvas)
        drawTracks(canvas)
        drawDraft(canvas)
    }

    private fun drawZones(canvas: Canvas) {
        drawPolygon(canvas, zones.road, Color.rgb(54, 201, 235), Color.argb(25, 54, 201, 235))
        zones.parkingZones.forEachIndexed { index, polygon ->
            drawPolygon(canvas, polygon, Color.rgb(66, 216, 154), Color.argb(28, 66, 216, 154))
            polygon.points.firstOrNull()?.let { point ->
                val p = normalizedToView(point)
                drawLabel(canvas, "P${index + 1}", p.x, p.y, Color.rgb(66, 216, 154))
            }
        }

        zones.countLine?.let { line ->
            zonePaint.color = Color.rgb(245, 196, 81)
            zonePaint.pathEffect = dashedEffect
            zonePaint.strokeWidth = dp(3f)
            val a = normalizedToView(line.a)
            val b = normalizedToView(line.b)
            canvas.drawLine(a.x, a.y, b.x, b.y, zonePaint)
            zonePaint.pathEffect = null
        }
    }

    private fun drawPolygon(canvas: Canvas, polygon: Polygon2, stroke: Int, fill: Int) {
        if (polygon.points.size < 3) return
        val path = Path()
        polygon.points.forEachIndexed { index, point ->
            val p = normalizedToView(point)
            if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        path.close()
        zoneFillPaint.color = fill
        canvas.drawPath(path, zoneFillPaint)
        zonePaint.color = stroke
        zonePaint.pathEffect = dashedEffect
        zonePaint.strokeWidth = dp(2.3f)
        canvas.drawPath(path, zonePaint)
        zonePaint.pathEffect = null
    }

    private fun drawTracks(canvas: Canvas) {
        val now = android.os.SystemClock.uptimeMillis()
        visuals.forEach { visual ->
            val rect = boxToView(visual.track.box)
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

            dotPaint.color = color
            val bottom = normalizedToView(visual.track.bottomCenter)
            canvas.drawCircle(bottom.x, bottom.y, dp(4f), dotPaint)

            val stateSeconds = ((now - visual.stateSinceMs).coerceAtLeast(0L) / 1000L)
            val suffix = if (visual.state == VehicleState.PARKED) " · ${stateSeconds}s" else ""
            val label = "#${visual.track.id} · ${visual.track.vehicleClass.he} · ${visual.state.he}$suffix"
            drawLabel(canvas, label, rect.left, rect.top, color)
        }
    }

    private fun drawLabel(canvas: Canvas, text: String, x: Float, y: Float, accent: Int) {
        val paddingX = dp(7f)
        val paddingY = dp(5f)
        val width = labelPaint.measureText(text) + paddingX * 2
        val height = labelPaint.textSize + paddingY * 2
        val top = (y - height).coerceAtLeast(0f)
        val bg = RectF(x, top, (x + width).coerceAtMost(this.width.toFloat()), top + height)
        canvas.drawRoundRect(bg, dp(6f), dp(6f), labelBg)
        dotPaint.color = accent
        canvas.drawRect(bg.left, bg.top, bg.left + dp(3f), bg.bottom, dotPaint)
        canvas.drawText(text, bg.left + paddingX, bg.bottom - paddingY, labelPaint)
    }

    private fun drawDraft(canvas: Canvas) {
        if (editMode == EditMode.NONE || draft.isEmpty()) return
        val color = when (editMode) {
            EditMode.ROAD -> Color.rgb(54, 201, 235)
            EditMode.PARKING -> Color.rgb(66, 216, 154)
            EditMode.COUNT_LINE -> Color.rgb(245, 196, 81)
            EditMode.NONE -> Color.WHITE
        }
        zonePaint.color = color
        zonePaint.strokeWidth = dp(2.5f)
        dotPaint.color = color

        var previous: Vec2? = null
        draft.forEachIndexed { index, point ->
            val p = normalizedToView(point)
            canvas.drawCircle(p.x, p.y, dp(7f), dotPaint)
            canvas.drawText("${index + 1}", p.x + dp(9f), p.y - dp(8f), labelPaint)
            previous?.let {
                val pp = normalizedToView(it)
                canvas.drawLine(pp.x, pp.y, p.x, p.y, zonePaint)
            }
            previous = point
        }
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

    private fun viewToNormalized(x: Float, y: Float): Vec2? {
        val rect = frameRect()
        if (!rect.contains(x, y)) return null
        return Vec2(
            ((x - rect.left) / rect.width()).coerceIn(0f, 1f),
            ((y - rect.top) / rect.height()).coerceIn(0f, 1f)
        )
    }

    private fun boxToView(box: Box): RectF {
        val tl = normalizedToView(Vec2(box.left, box.top))
        val br = normalizedToView(Vec2(box.right, box.bottom))
        return RectF(tl.x, tl.y, br.x, br.y)
    }

    private fun requiredPoints(mode: EditMode): Int = when (mode) {
        EditMode.ROAD, EditMode.PARKING -> 4
        EditMode.COUNT_LINE -> 2
        EditMode.NONE -> 0
    }

    private fun editHint(mode: EditMode, current: Int, required: Int): String {
        val name = when (mode) {
            EditMode.ROAD -> "הכביש"
            EditMode.PARKING -> "אזור החניה"
            EditMode.COUNT_LINE -> "קו הספירה"
            EditMode.NONE -> ""
        }
        return "סמן $name · נקודה ${(current + 1).coerceAtMost(required)}/$required"
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
