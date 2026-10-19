package ab.inque.visiontechai

import ab.inque.robotics.vision.segment.SegmenterNative
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log

/**
 * Image segmentation using native C++ (no ML).
 * Uses K-means color clustering in native code; result is blended overlay.
 */
class ImageSegmentationProcessor(private val context: Context) {

    companion object {
        private const val TAG = "ImageSegmentationProcessor"
        private const val LOG_EVERY_N_FRAMES = 45
        /** Max dimension for segmentation (run at lower res for real-time). */
        private const val SEGMENT_MAX_DIM = 192
    }

    private var segmentCallCount = 0

    /**
     * Segment the image and return an RGBA overlay bitmap (same size as input).
     * Uses params.segmentationNumClusters (3–10) and params.segmentationIterations (3–15).
     */
    fun segmentImage(bitmap: Bitmap, params: ProcessorParams): Bitmap? {
        if (!SegmenterNative.isAvailable()) {
            if (segmentCallCount++ % LOG_EVERY_N_FRAMES == 0) {
                Log.w(TAG, "segmentImage skipped: native lib not loaded")
            }
            return null
        }
        var sourceCopy: Bitmap? = null
        var small: Bitmap? = null
        var smallOverlay: Bitmap? = null
        return try {
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0) {
                Log.w(TAG, "segmentImage: invalid size ${w}x$h")
                return null
            }
            val numClusters = params.segmentationNumClusters.coerceIn(3, 10)
            val iterations = params.segmentationIterations.coerceIn(3, 15)
            val source = if (bitmap.config != Bitmap.Config.ARGB_8888) {
                (bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap).also {
                    if (it !== bitmap) sourceCopy = it
                }
            } else {
                bitmap
            }
            val scale = if (w > h) SEGMENT_MAX_DIM.toFloat() / w else SEGMENT_MAX_DIM.toFloat() / h
            val sw = (w * scale).toInt().coerceAtLeast(1)
            val sh = (h * scale).toInt().coerceAtLeast(1)
            small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(small)
            val matrix = Matrix().apply { setScale(scale, scale) }
            canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            val pixelsIn = IntArray(sw * sh)
            small.getPixels(pixelsIn, 0, sw, 0, 0, sw, sh)
            val pixelsOut = IntArray(sw * sh)
            val ok = SegmenterNative.nativeSegment(pixelsIn, pixelsOut, sw, sh, numClusters, iterations)
            if (!ok) {
                if (segmentCallCount++ % LOG_EVERY_N_FRAMES == 0) {
                    Log.w(TAG, "nativeSegment returned false (size ${sw}x$sh)")
                }
                return null
            }
            smallOverlay = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            smallOverlay.setPixels(pixelsOut, 0, sw, 0, 0, sw, sh)
            val fullOverlay = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val fullCanvas = Canvas(fullOverlay)
            val upMatrix = Matrix().apply { setScale(1f / scale, 1f / scale) }
            fullCanvas.drawBitmap(smallOverlay, upMatrix, Paint(Paint.FILTER_BITMAP_FLAG))
            if (segmentCallCount++ % LOG_EVERY_N_FRAMES == 0) {
                Log.d(TAG, "segmentImage ok ${w}x$h (native ${sw}x$sh)")
            }
            fullOverlay
        } catch (e: Exception) {
            Log.e(TAG, "Segmentation failed: ${e.message}", e)
            null
        } finally {
            small?.takeUnless { it.isRecycled }?.recycle()
            smallOverlay?.takeUnless { it.isRecycled }?.recycle()
            sourceCopy?.takeUnless { it.isRecycled }?.recycle()
        }
    }

    fun clear() {
        if (SegmenterNative.isAvailable()) {
            try {
                SegmenterNative.nativeReset()
                Log.i(TAG, "Temporal segmentation state cleared")
            } catch (e: Exception) {
                Log.w(TAG, "nativeReset failed: ${e.message}")
            }
        }
    }
}
