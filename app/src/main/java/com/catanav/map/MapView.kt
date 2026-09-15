package com.catanav.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Map viewer over BitmapRegionDecoder: only the visible viewport (plus margin) is ever
 * decoded, at a sample size matched to the zoom level (hard-constraint #3 — the full
 * 7000x7000 plate is never in memory; a heavily subsampled overview is kept as the
 * fallback layer while panning).
 *
 * Gestures: one-finger pan, pinch zoom (clamped fit-to-screen..native), two-finger
 * rotation. Rotation is VIEW-ONLY — all data stays in north-up image pixel space; the
 * overlay (marker / trail / uncertainty) shares this view's transform matrix.
 */
class MapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    // ---- viewport & transform -------------------------------------------------
    var viewport: MapViewport? = null
        private set
    private val drawMatrix = Matrix()

    // ---- decode state ----------------------------------------------------------
    private var source: MapSource? = null
    private var decoder: BitmapRegionDecoder? = null
    private var overview: Bitmap? = null
    private var regionBitmap: Bitmap? = null
    private var regionRect: RectF? = null // image-space rect the regionBitmap covers
    private var scope: CoroutineScope? = null
    private val decodeRequests = Channel<Unit>(Channel.CONFLATED)
    private var decodeJob: Job? = null

    // ---- overlay data (internally image pixels; meter setters go through the
    //      CoordinateTransformer — the ONLY place meters become pixels) -----------
    data class Marker(val xPx: Double, val yPx: Double, val label: String? = null)

    /**
     * Transformer for the currently displayed map version. Required by the
     * meter-based setters below; the raw pixel API (used by calibration flows,
     * which run BEFORE a calibration exists) works without it.
     */
    var transformer: com.catanav.domain.CoordinateTransformer? = null

    var trail: List<Pair<Double, Double>> = emptyList()
        set(v) { field = v; invalidate() }
    var retraceTrail: List<Pair<Double, Double>> = emptyList()
        set(v) { field = v; invalidate() }
    var anchors: List<Marker> = emptyList()
        set(v) { field = v; invalidate() }
    var positionX: Double = Double.NaN
    var positionY: Double = Double.NaN

    /** Marker arrow angle in IMAGE space: degrees clockwise from image-up. */
    var arrowImageDeg: Double = Double.NaN
    var uncertaintyRadiusPx: Double = 0.0
    var pendingAnchor: Pair<Double, Double>? = null
        set(v) { field = v; invalidate() }
    var crosshairMode: Boolean = false
        set(v) { field = v; invalidate() }
    var redMode: Boolean = false
        set(v) { field = v; applyPalette(); invalidate() }

    /** Called with image pixel coordinates when the user taps the map. */
    var onMapTap: ((Double, Double) -> Unit)? = null

    /** Raw pixel API (calibration flows). [arrowFromImageUpDeg] clockwise from image-up. */
    fun setPosition(x: Double, y: Double, arrowFromImageUpDeg: Double, uncertaintyPx: Double) {
        positionX = x; positionY = y; arrowImageDeg = arrowFromImageUpDeg
        uncertaintyRadiusPx = uncertaintyPx
        invalidate()
    }

    // ---- meter-based API (navigation screens) -----------------------------------

    private fun toPx(p: com.catanav.domain.MapPoint): Pair<Double, Double> {
        val t = transformer ?: error("transformer not set — meter API needs a calibrated map")
        val i = t.mapToImage(p)
        return i.xPx to i.yPx
    }

    fun setTrailMeters(points: List<com.catanav.domain.MapPoint>) {
        trail = points.map(::toPx)
    }

    fun setRetraceMeters(points: List<com.catanav.domain.MapPoint>) {
        retraceTrail = points.map(::toPx)
    }

    fun setAnchorsMeters(markers: List<Pair<com.catanav.domain.MapPoint, String?>>) {
        anchors = markers.map { (p, label) ->
            val (x, y) = toPx(p)
            Marker(x, y, label)
        }
    }

    fun clearPosition() {
        positionX = Double.NaN; positionY = Double.NaN
        arrowImageDeg = Double.NaN; uncertaintyRadiusPx = 0.0
        invalidate()
    }

    /**
     * Position in meters with a TRUE-NORTH compass heading; the transformer places the
     * arrow and sizes the uncertainty circle, so any northOffset is handled here and
     * nowhere else.
     */
    fun setPositionMeters(
        p: com.catanav.domain.MapPoint,
        headingDeg: Double,
        uncertaintyMeters: Double,
    ) {
        val t = transformer ?: error("transformer not set — meter API needs a calibrated map")
        val pos = t.mapToImage(p)
        // Probe 1 m east for the local pixels-per-meter scale.
        val east = t.mapToImage(com.catanav.domain.MapPoint(p.xMeters + 1.0, p.yMeters))
        val pxPerMeter = kotlin.math.hypot(east.xPx - pos.xPx, east.yPx - pos.yPx)
        val arrow = if (headingDeg.isNaN()) {
            Double.NaN
        } else {
            // Probe 1 m along the heading; arrow angle measured from image-up.
            val rad = Math.toRadians(headingDeg)
            val ahead = t.mapToImage(
                com.catanav.domain.MapPoint(
                    p.xMeters + kotlin.math.sin(rad),
                    p.yMeters + kotlin.math.cos(rad),
                ),
            )
            Math.toDegrees(atan2(ahead.xPx - pos.xPx, -(ahead.yPx - pos.yPx)))
        }
        setPosition(pos.xPx, pos.yPx, arrow, uncertaintyMeters * pxPerMeter)
    }

    /** Convert a tap (image px) to map meters; null while no transformer is set. */
    fun imageTapToMeters(xPx: Double, yPx: Double): com.catanav.domain.MapPoint? =
        transformer?.imageToMap(com.catanav.domain.ImagePoint(xPx, yPx))

    // ---- paints -----------------------------------------------------------------
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5f; strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val retracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 8f; strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val uncertaintyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val anchorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val anchorLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val dimPaint = Paint()

    init {
        applyPalette()
    }

    private fun applyPalette() {
        if (redMode) {
            trailPaint.color = 0x66CC4444.toInt()
            retracePaint.color = 0xFFE05050.toInt()
            uncertaintyPaint.color = 0x33CC2222
            markerPaint.color = 0xFFCC2222.toInt()
            anchorPaint.color = 0xFF803030.toInt()
            anchorLabelPaint.color = 0xFFE05050.toInt()
            crosshairPaint.color = 0xFFE05050.toInt()
            dimPaint.colorFilter = android.graphics.ColorMatrixColorFilter(
                floatArrayOf(
                    0.5f, 0.3f, 0.1f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )
        } else {
            trailPaint.color = 0x66FFD54F
            retracePaint.color = 0xFFFF5252.toInt()
            uncertaintyPaint.color = 0x334DA3FF
            markerPaint.color = 0xFF4DA3FF.toInt()
            anchorPaint.color = 0xFF69F0AE.toInt()
            anchorLabelPaint.color = 0xFF69F0AE.toInt()
            crosshairPaint.color = 0xFFFFAB40.toInt()
            dimPaint.colorFilter = null
        }
    }

    // ---- lifecycle ----------------------------------------------------------------
    fun setMapSource(src: MapSource) {
        source = src
        viewport = MapViewport(src.widthPx, src.heightPx)
        if (width > 0) resetToFit()
        startDecodeLoop()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        if (source != null) startDecodeLoop()
    }

    override fun onDetachedFromWindow() {
        scope?.cancel()
        scope = null
        decoder?.recycle()
        decoder = null
        regionBitmap?.recycle(); regionBitmap = null
        overview?.recycle(); overview = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (viewport != null && oldw == 0) resetToFit()
        requestDecode()
    }

    private fun resetToFit() {
        val vp = viewport ?: return
        val fit = vp.fitScale(width, height)
        vp.set(fit, 0.0, (width - vp.imageWidth * fit) / 2.0, (height - vp.imageHeight * fit) / 2.0)
        invalidate()
    }

    /** Center the view on an image-space point at the current zoom. */
    fun centerOn(ix: Double, iy: Double) {
        val vp = viewport ?: return
        val screen = vp.imageToScreen(ix, iy)
        vp.set(
            vp.scale, vp.rotationDeg,
            vp.offsetX + width / 2.0 - screen[0],
            vp.offsetY + height / 2.0 - screen[1],
        )
        requestDecode()
        invalidate()
    }

    // ---- decoding -------------------------------------------------------------------
    private fun startDecodeLoop() {
        val sc = scope ?: return
        if (decodeJob?.isActive == true) return
        decodeJob = sc.launch {
            val src = source ?: return@launch
            val dec = withContext(Dispatchers.IO) { src.newRegionDecoder() }
            decoder = dec
            // Subsampled overview as the fallback layer (~1/8 => ~875x875 for the real plate).
            val ovSample = MapViewport.chooseSampleSize(1024.0 / maxOf(dec.width, dec.height))
            overview = withContext(Dispatchers.IO) {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = ovSample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                dec.decodeRegion(Rect(0, 0, dec.width, dec.height), opts)
            }
            invalidate()
            requestDecode()
            for (unit in decodeRequests) {
                decodeVisibleRegion(dec)
            }
        }
    }

    private fun requestDecode() {
        decodeRequests.trySend(Unit)
    }

    private suspend fun decodeVisibleRegion(dec: BitmapRegionDecoder) {
        val vp = viewport ?: return
        if (width == 0 || height == 0) return
        val visible = vp.visibleImageRect(width, height)
        if (visible.isEmpty) return
        // 25% margin so small pans don't force an immediate re-decode.
        val mx = visible.width * 0.25
        val my = visible.height * 0.25
        val rect = Rect(
            maxOf(0, (visible.left - mx).toInt()),
            maxOf(0, (visible.top - my).toInt()),
            minOf(dec.width, (visible.right + mx).toInt()),
            minOf(dec.height, (visible.bottom + my).toInt()),
        )
        val sample = MapViewport.chooseSampleSize(vp.scale)
        val cachedRect = regionRect
        if (cachedRect != null && cachedSample == sample &&
            cachedRect.contains(RectF(visible.left.toFloat(), visible.top.toFloat(), visible.right.toFloat(), visible.bottom.toFloat()))
        ) {
            return // cache still covers the viewport at the right resolution
        }
        val bmp = withContext(Dispatchers.IO) {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            try {
                dec.decodeRegion(rect, opts)
            } catch (_: Throwable) {
                null
            }
        } ?: return
        regionBitmap?.recycle()
        regionBitmap = bmp
        regionRect = RectF(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())
        cachedSample = sample
        invalidate()
    }

    private var cachedSample = 0

    // ---- drawing -----------------------------------------------------------------------
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val vp = viewport ?: return
        canvas.drawColor(if (redMode) Color.rgb(10, 0, 0) else Color.rgb(16, 18, 20))
        drawMatrix.reset()
        drawMatrix.postScale(vp.scale.toFloat(), vp.scale.toFloat())
        drawMatrix.postRotate(vp.rotationDeg.toFloat())
        drawMatrix.postTranslate(vp.offsetX.toFloat(), vp.offsetY.toFloat())

        val mapPaint = if (redMode) dimPaint.also { it.isFilterBitmap = true } else bitmapPaint
        canvas.save()
        canvas.concat(drawMatrix)
        overview?.let {
            canvas.drawBitmap(it, null, RectF(0f, 0f, vp.imageWidth.toFloat(), vp.imageHeight.toFloat()), mapPaint)
        }
        val rb = regionBitmap
        val rr = regionRect
        if (rb != null && rr != null && !rb.isRecycled) {
            canvas.drawBitmap(rb, null, rr, mapPaint)
        }
        canvas.restore()

        drawOverlay(canvas, vp)
    }

    private val scratch = FloatArray(2)
    private val trailPath = Path()

    private fun mapPoint(x: Double, y: Double): FloatArray {
        scratch[0] = x.toFloat(); scratch[1] = y.toFloat()
        drawMatrix.mapPoints(scratch)
        return scratch
    }

    private fun drawPolyline(canvas: Canvas, pts: List<Pair<Double, Double>>, paint: Paint) {
        if (pts.size < 2) return
        trailPath.reset()
        val p0 = mapPoint(pts[0].first, pts[0].second)
        trailPath.moveTo(p0[0], p0[1])
        for (i in 1 until pts.size) {
            val p = mapPoint(pts[i].first, pts[i].second)
            trailPath.lineTo(p[0], p[1])
        }
        canvas.drawPath(trailPath, paint)
    }

    private fun drawOverlay(canvas: Canvas, vp: MapViewport) {
        // Trail + retrace (drawn in screen space with constant stroke width)
        drawPolyline(canvas, trail, trailPaint)
        drawPolyline(canvas, retraceTrail, retracePaint)

        for (m in anchors) {
            val p = mapPoint(m.xPx, m.yPx)
            canvas.drawCircle(p[0], p[1], 8f, anchorPaint)
            m.label?.let { label ->
                canvas.drawText(label, p[0] + 12f, p[1] - 12f, anchorLabelPaint)
            }
        }

        if (!positionX.isNaN()) {
            val p = mapPoint(positionX, positionY)
            val px = p[0]; val py = p[1]
            if (uncertaintyRadiusPx > 0) {
                canvas.drawCircle(px, py, (uncertaintyRadiusPx * vp.scale).toFloat(), uncertaintyPaint)
            }
            canvas.drawCircle(px, py, 12f, markerPaint)
            if (!arrowImageDeg.isNaN()) {
                // Arrow angle is in IMAGE space (set via the transformer); on screen it
                // additionally rotates with the view-only rotation.
                val ang = Math.toRadians(arrowImageDeg + vp.rotationDeg)
                val tipX = px + 34f * kotlin.math.sin(ang).toFloat()
                val tipY = py - 34f * kotlin.math.cos(ang).toFloat()
                val arrow = Paint(markerPaint).apply { strokeWidth = 7f; strokeCap = Paint.Cap.ROUND }
                canvas.drawLine(px, py, tipX, tipY, arrow)
            }
        }

        pendingAnchor?.let { (ax, ay) ->
            val p = mapPoint(ax, ay)
            canvas.drawCircle(p[0], p[1], 26f, crosshairPaint)
            canvas.drawLine(p[0] - 40f, p[1], p[0] + 40f, p[1], crosshairPaint)
            canvas.drawLine(p[0], p[1] - 40f, p[0], p[1] + 40f, crosshairPaint)
        }

        if (crosshairMode && pendingAnchor == null) {
            // Passive hint crosshair at screen center until the user taps.
            val cx = width / 2f; val cy = height / 2f
            canvas.drawLine(cx - 30f, cy, cx + 30f, cy, crosshairPaint)
            canvas.drawLine(cx, cy - 30f, cx, cy + 30f, crosshairPaint)
        }
    }

    // ---- gestures --------------------------------------------------------------------------
    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val vp = viewport ?: return false
                val img = vp.screenToImage(e.x.toDouble(), e.y.toDouble())
                if (img[0] in 0.0..vp.imageWidth.toDouble() && img[1] in 0.0..vp.imageHeight.toDouble()) {
                    onMapTap?.invoke(img[0], img[1])
                }
                return true
            }
        },
    )

    private var lastX0 = 0f; private var lastY0 = 0f
    private var lastX1 = 0f; private var lastY1 = 0f
    private var twoFingers = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        tapDetector.onTouchEvent(event)
        val vp = viewport ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX0 = event.x; lastY0 = event.y; twoFingers = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    twoFingers = true
                    lastX0 = event.getX(0); lastY0 = event.getY(0)
                    lastX1 = event.getX(1); lastY1 = event.getY(1)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val x0 = event.getX(0); val y0 = event.getY(0)
                    val x1 = event.getX(1); val y1 = event.getY(1)
                    val prevDist = hypot((lastX1 - lastX0).toDouble(), (lastY1 - lastY0).toDouble())
                    val newDist = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble())
                    val prevAng = Math.toDegrees(atan2((lastY1 - lastY0).toDouble(), (lastX1 - lastX0).toDouble()))
                    val newAng = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble()))
                    val focusX = (x0 + x1) / 2.0
                    val focusY = (y0 + y1) / 2.0
                    val prevFocusX = (lastX0 + lastX1) / 2.0
                    val prevFocusY = (lastY0 + lastY1) / 2.0
                    if (prevDist > 10.0) {
                        vp.applyGesture(
                            focusX, focusY,
                            newDist / prevDist,
                            angleDelta(prevAng, newAng),
                            focusX - prevFocusX, focusY - prevFocusY,
                            width, height,
                        )
                    }
                    lastX0 = x0; lastY0 = y0; lastX1 = x1; lastY1 = y1
                    requestDecode()
                    invalidate()
                } else if (!twoFingers) {
                    val dx = event.x - lastX0
                    val dy = event.y - lastY0
                    vp.applyGesture(event.x.toDouble(), event.y.toDouble(), 1.0, 0.0, dx.toDouble(), dy.toDouble(), width, height)
                    lastX0 = event.x; lastY0 = event.y
                    requestDecode()
                    invalidate()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Re-seat remaining pointer to avoid a jump.
                val idx = if (event.actionIndex == 0) 1 else 0
                if (idx < event.pointerCount) {
                    lastX0 = event.getX(idx); lastY0 = event.getY(idx)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                twoFingers = false
            }
        }
        return true
    }

    private fun angleDelta(from: Double, to: Double): Double {
        var d = to - from
        while (d > 180) d -= 360
        while (d < -180) d += 360
        return d
    }
}
