package ab.inque.visiontechai

import ab.inque.robotics.panorama.SphereNative


import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Bitmap-based interactive viewer for a 360-degree equirectangular panorama.
 * Supports touch-drag to pan (azimuth/elevation) and pinch to zoom (FOV).
 * Renders a rectilinear (perspective) projection from the equirect via C++.
 */
class SphereViewerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var azimuth = 0f        // radians
    private var elevation = 0f      // radians
    private var fieldOfView = 90f   // degrees

    private var viewBitmap: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        textAlign = Paint.Align.CENTER
    }

    private val scroller = OverScroller(context)

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val w = width.coerceAtLeast(1).toFloat()
            val sensitivity = fieldOfView * (Math.PI.toFloat() / 180f) / w
            azimuth += dx * sensitivity
            elevation = (elevation - dy * sensitivity).coerceIn(-MAX_ELEVATION, MAX_ELEVATION)
            refreshView()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            scroller.fling(0, 0, velocityX.toInt(), velocityY.toInt(),
                Int.MIN_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE)
            lastFlingX = 0
            lastFlingY = 0
            postInvalidateOnAnimation()
            return true
        }
    })

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            fieldOfView = (fieldOfView / detector.scaleFactor).coerceIn(MIN_FOV, MAX_FOV)
            refreshView()
            return true
        }
    })

    private var lastFlingX = 0
    private var lastFlingY = 0

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val cx = scroller.currX
            val cy = scroller.currY
            val dx = (cx - lastFlingX).toFloat()
            val dy = (cy - lastFlingY).toFloat()
            lastFlingX = cx
            lastFlingY = cy

            val w = width.coerceAtLeast(1).toFloat()
            val sensitivity = fieldOfView * (Math.PI.toFloat() / 180f) / w
            azimuth -= dx * sensitivity
            elevation = (elevation + dy * sensitivity).coerceIn(-MAX_ELEVATION, MAX_ELEVATION)
            refreshView()
            postInvalidateOnAnimation()
        }
    }

    private fun refreshView() {
        if (width == 0 || height == 0) return
        val outW = min(width, 960)
        val outH = min(height, 720)
        val bmp = SphereNative.getRectilinearBitmap(outW, outH, azimuth, elevation, fieldOfView)
        if (bmp != null) {
            viewBitmap?.recycle()
            viewBitmap = bmp
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) refreshView()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val bmp = viewBitmap
        if (bmp != null) {
            val scaleX = width.toFloat() / bmp.width
            val scaleY = height.toFloat() / bmp.height
            val scale = max(scaleX, scaleY)
            val dw = bmp.width * scale
            val dh = bmp.height * scale
            val left = (width - dw) / 2f
            val top = (height - dh) / 2f
            canvas.save()
            canvas.translate(left, top)
            canvas.scale(scale, scale)
            canvas.drawBitmap(bmp, 0f, 0f, paint)
            canvas.restore()
        } else {
            canvas.drawText("Loading sphere...", width / 2f, height / 2f, textPaint)
        }
    }

    fun setLookDirection(az: Float, el: Float, fov: Float = 90f) {
        azimuth = az
        elevation = el
        fieldOfView = fov.coerceIn(MIN_FOV, MAX_FOV)
        refreshView()
    }

    fun cleanup() {
        viewBitmap?.recycle()
        viewBitmap = null
    }

    companion object {
        private const val MAX_ELEVATION = (Math.PI / 2 - 0.01).toFloat()
        private const val MIN_FOV = 30f
        private const val MAX_FOV = 120f
    }
}
