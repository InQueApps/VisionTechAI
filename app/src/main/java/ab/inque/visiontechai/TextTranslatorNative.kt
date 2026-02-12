package ab.inque.visiontechai

/**
 * JNI bridge for text translation: cluster words into lines/sentences by location.
 * Used by [TextTranslationFrameProcessor] after ML Kit text recognition.
 */
object TextTranslatorNative {

    init {
        try {
            System.loadLibrary("vision_translator")
        } catch (e: UnsatisfiedLinkError) {
            // Native lib not built; processor will use Kotlin fallback clustering
        }
    }

    /**
     * Cluster words into lines by bounding box position. Same group id = same line/sentence.
     * @param left top right bottom arrays of length [count]
     * @param outGroupIds output array of length [count]; filled with group ids (0, 1, 2, ...)
     */
    external fun clusterWordsIntoLines(
        left: IntArray,
        top: IntArray,
        right: IntArray,
        bottom: IntArray,
        count: Int,
        outGroupIds: IntArray
    )
}
