package ab.inque.robotics.vision.segment

import android.util.Log

/** JNI bridge for K-means color segmentation (vision_segmenter). */
object SegmenterNative {

    private const val TAG = "SegmenterNative"
    private var loaded = false

    init {
        try {
            System.loadLibrary("vision_segmenter")
            loaded = true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load vision_segmenter: ${e.message}")
        }
    }

    fun isAvailable(): Boolean = loaded

    external fun nativeSegment(
        pixelsIn: IntArray,
        pixelsOut: IntArray,
        width: Int,
        height: Int,
        numClusters: Int,
        iterations: Int
    ): Boolean

    external fun nativeReset()
}
