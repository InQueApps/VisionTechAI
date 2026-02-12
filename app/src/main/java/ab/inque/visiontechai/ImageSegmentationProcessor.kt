package ab.inque.visiontechai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log

/**
 * Image segmentation using native C++ (no ML).
 * Uses K-means color clustering in native code; result is blended overlay.
 * To use OpenCV in C++: implement segmenter_jni.cpp with cv::Mat / cv::kmeans or cv::grabCut
 * and link OpenCV in app/src/main/cpp/CMakeLists.txt.
 */
class ImageSegmentationProcessor(private val context: Context) {

    companion object {
        private const val TAG = "ImageSegmentationProcessor"
        private const val OVERLAY_ALPHA = 0.65f
        private const val LOG_EVERY_N_FRAMES = 45
        /** Max dimension for segmentation (run at lower res for real-time). */
        private const val SEGMENT_MAX_DIM = 192
    }

    @Volatile
    private var nativeLoaded = false

    private var segmentCallCount = 0

    init {
        try {
            System.loadLibrary("vision_segmenter")
            nativeLoaded = true
            Log.i(TAG, "Native segmentation library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            nativeLoaded = false
            Log.e(TAG, "Failed to load vision_segmenter - segmentation will be disabled: ${e.message}")
        }
    }

    /**
     * Segment the image and return an RGBA overlay bitmap (same size as input).
     * Uses params.segmentationNumClusters (3–10) and params.segmentationIterations (3–15).
     */
    fun segmentImage(bitmap: Bitmap, params: ProcessorParams): Bitmap? {
        if (!nativeLoaded) {
            if (segmentCallCount++ % LOG_EVERY_N_FRAMES == 0) {
                Log.w(TAG, "segmentImage skipped: native lib not loaded")
            }
            return null
        }
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
                bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
            } else {
                bitmap
            }
            // Downscale for real-time: run native on small image
            val scale = if (w > h) SEGMENT_MAX_DIM.toFloat() / w else SEGMENT_MAX_DIM.toFloat() / h
            val sw = (w * scale).toInt().coerceAtLeast(1)
            val sh = (h * scale).toInt().coerceAtLeast(1)
            val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(small)
            val matrix = Matrix().apply { setScale(scale, scale) }
            canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            val pixelsIn = IntArray(sw * sh)
            small.getPixels(pixelsIn, 0, sw, 0, 0, sw, sh)
            val pixelsOut = IntArray(sw * sh)
            val ok = nativeSegment(pixelsIn, pixelsOut, sw, sh, numClusters, iterations)
            if (!ok) {
                if (segmentCallCount++ % LOG_EVERY_N_FRAMES == 0) {
                    Log.w(TAG, "nativeSegment returned false (size ${sw}x$sh)")
                }
                return null
            }
            val smallOverlay = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            smallOverlay.setPixels(pixelsOut, 0, sw, 0, 0, sw, sh)
            // Scale overlay to full size; show it directly on ImageView (drawn over preview) to avoid full-size blend
            val fullOverlay = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val fullCanvas = Canvas(fullOverlay)
            val upMatrix = Matrix().apply { setScale(1f / scale, 1f / scale) }
            fullCanvas.drawBitmap(smallOverlay, upMatrix, Paint(Paint.FILTER_BITMAP_FLAG))
            val result = fullOverlay
            if (segmentCallCount++ % LOG_EVERY_N_FRAMES == 0) {
                Log.d(TAG, "segmentImage ok ${w}x$h (native ${sw}x$sh)")
            }
            result
        } catch (e: UnsatisfiedLinkError) {
            nativeLoaded = false
            Log.e(TAG, "Native lib not loaded (late): ${e.message}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Segmentation failed: ${e.message}", e)
            null
        }
    }

    private fun blendOverlay(original: Bitmap, overlay: Bitmap): Bitmap {
        val w = original.width
        val h = original.height
        val result = original.copy(original.config ?: Bitmap.Config.ARGB_8888, true)
        val origPx = IntArray(w * h)
        val overPx = IntArray(w * h)
        original.getPixels(origPx, 0, w, 0, 0, w, h)
        overlay.getPixels(overPx, 0, w, 0, 0, w, h)
        for (i in origPx.indices) {
            val oa = (overPx[i] shr 24) and 0xFF
            if (oa == 0) continue
            val a = (oa / 255f * OVERLAY_ALPHA).toInt().coerceIn(0, 255)
            val or = (origPx[i] shr 16) and 0xFF
            val og = (origPx[i] shr 8) and 0xFF
            val ob = origPx[i] and 0xFF
            val sr = (overPx[i] shr 16) and 0xFF
            val sg = (overPx[i] shr 8) and 0xFF
            val sb = overPx[i] and 0xFF
            val r = (or * (255 - a) + sr * a) / 255
            val g = (og * (255 - a) + sg * a) / 255
            val b = (ob * (255 - a) + sb * a) / 255
            origPx[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        result.setPixels(origPx, 0, w, 0, 0, w, h)
        return result
    }

    private external fun nativeSegment(
        pixelsIn: IntArray,
        pixelsOut: IntArray,
        width: Int,
        height: Int,
        numClusters: Int,
        iterations: Int
    ): Boolean
}
