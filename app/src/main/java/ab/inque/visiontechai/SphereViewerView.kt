package ab.inque.visiontechai

import ab.inque.robotics.panorama.SphereLookOrientation
import ab.inque.robotics.panorama.SphereViewerNative

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import kotlin.math.max
import kotlin.math.min

/**
 * Bitmap-based interactive viewer for a 360-degree equirectangular panorama.
 * Uses [SphereViewerNative] only — never loads display bitmaps into [SphereNative].
 */
class SphereViewerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var azimuth = 0f        // radians (touch-only mode)
    private var elevation = 0f      // radians (touch-only mode)
    private var fieldOfView = 90f   // degrees

    private var imuModeEnabled = false
    private var imuReferenceQuat: FloatArray? = null
    private var touchAzOffset = 0f
    private var touchElOffset = 0f
    private var orientationSupplier: (() -> FloatArray)? = null

    private var viewBitmap: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        textAlign = Paint.Align.CENTER
    }

    private val scroller = OverScroller(context)
    private val choreographer = Choreographer.getInstance()
    private var imuFramePosted = false

    private val imuFrameCallback = Choreographer.FrameCallback {
        imuFramePosted = false
        if (imuModeEnabled && orientationSupplier != null) {
            refreshView()
            scheduleImuFrame()
        }
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            applyPanDelta(dx, dy)
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

    val isImuModeEnabled: Boolean get() = imuModeEnabled

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
            applyPanDelta(-dx, dy)
            postInvalidateOnAnimation()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (imuModeEnabled) scheduleImuFrame()
    }

    override fun onDetachedFromWindow() {
        stopImuFrame()
        super.onDetachedFromWindow()
    }

    private fun applyPanDelta(dx: Float, dy: Float) {
        val w = width.coerceAtLeast(1).toFloat()
        val sensitivity = fieldOfView * (Math.PI.toFloat() / 180f) / w
        if (imuModeEnabled) {
            touchAzOffset += dx * sensitivity
            touchElOffset = (touchElOffset - dy * sensitivity).coerceIn(-MAX_ELEVATION, MAX_ELEVATION)
        } else {
            azimuth += dx * sensitivity
            elevation = (elevation - dy * sensitivity).coerceIn(-MAX_ELEVATION, MAX_ELEVATION)
        }
        refreshView()
    }

    private fun effectiveLook(): Pair<Float, Float> {
        if (!imuModeEnabled) return azimuth to elevation
        val ref = imuReferenceQuat
        val current = orientationSupplier?.invoke()
        val (imuDeltaAz, imuDeltaEl) = if (ref != null && current != null) {
            val (curAz, curEl) = SphereLookOrientation.quatToAzEl(current)
            val (refAz, refEl) = SphereLookOrientation.quatToAzEl(ref)
            (curAz - refAz) to (curEl - refEl)
        } else {
            0f to 0f
        }
        val el = (touchElOffset + imuDeltaEl).coerceIn(-MAX_ELEVATION, MAX_ELEVATION)
        return (touchAzOffset + imuDeltaAz) to el
    }

    private fun refreshView() {
        if (width == 0 || height == 0 || !SphereViewerNative.isAvailable()) return
        val (lookAz, lookEl) = effectiveLook()
        val outW = min(width, 960)
        val outH = min(height, 720)
        val bmp = SphereViewerNative.getRectilinearBitmap(outW, outH, lookAz, lookEl, fieldOfView)
        if (bmp != null && !bmp.isRecycled) {
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

    /**
     * Load a display-only equirect snapshot into the viewer. Does not touch stitch state.
     */
    fun setEquirectBitmap(bitmap: Bitmap) {
        if (bitmap.isRecycled || !SphereViewerNative.isAvailable()) return
        if (!SphereViewerNative.loadEquirect(bitmap)) return
        refreshView()
    }

    fun setLookDirection(az: Float, el: Float, fov: Float = 90f) {
        fieldOfView = fov.coerceIn(MIN_FOV, MAX_FOV)
        if (imuModeEnabled) {
            touchAzOffset = az
            touchElOffset = el.coerceIn(-MAX_ELEVATION, MAX_ELEVATION)
            imuReferenceQuat = orientationSupplier?.invoke()?.copyOf()
        } else {
            azimuth = az
            elevation = el.coerceIn(-MAX_ELEVATION, MAX_ELEVATION)
        }
        refreshView()
    }

    fun setImuOrientationSupplier(supplier: (() -> FloatArray)?) {
        orientationSupplier = supplier
    }

    fun setImuModeEnabled(enabled: Boolean) {
        if (enabled == imuModeEnabled) return
        if (enabled) {
            touchAzOffset = azimuth
            touchElOffset = elevation
            imuReferenceQuat = orientationSupplier?.invoke()?.copyOf()
            imuModeEnabled = true
            scheduleImuFrame()
            refreshView()
        } else {
            imuModeEnabled = false
            stopImuFrame()
            val (lookAz, lookEl) = effectiveLook()
            azimuth = lookAz
            elevation = lookEl
            touchAzOffset = 0f
            touchElOffset = 0f
            imuReferenceQuat = null
            refreshView()
        }
    }

    private fun scheduleImuFrame() {
        if (!imuModeEnabled || imuFramePosted) return
        imuFramePosted = true
        choreographer.postFrameCallback(imuFrameCallback)
    }

    private fun stopImuFrame() {
        if (imuFramePosted) {
            choreographer.removeFrameCallback(imuFrameCallback)
            imuFramePosted = false
        }
    }

    /** Re-sample the viewer equirect after a stitch-side refinement pass. */
    fun requestRender() {
        refreshView()
    }

    fun cleanup() {
        setImuModeEnabled(false)
        orientationSupplier = null
        viewBitmap?.recycle()
        viewBitmap = null
        SphereViewerNative.release()
    }

    companion object {
        private const val MAX_ELEVATION = (Math.PI / 2 - 0.01).toFloat()
        private const val MIN_FOV = 30f
        private const val MAX_FOV = 120f
    }
}
