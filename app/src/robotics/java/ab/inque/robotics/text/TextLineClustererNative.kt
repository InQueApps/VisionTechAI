package ab.inque.robotics.text

/** JNI bridge for clustering recognized text words into lines by bounding box position. */
object TextLineClustererNative {

    private var loaded = false

    init {
        try {
            System.loadLibrary("vision_translator")
            loaded = true
        } catch (_: UnsatisfiedLinkError) {
            loaded = false
        }
    }

    fun isAvailable(): Boolean = loaded

    external fun clusterWordsIntoLines(
        left: IntArray,
        top: IntArray,
        right: IntArray,
        bottom: IntArray,
        count: Int,
        outGroupIds: IntArray
    )
}
