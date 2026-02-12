package ab.inque.visiontechai

import android.graphics.Bitmap

/**
 * Common interface for vision effect processors. Each effect (night vision, edge, etc.)
 * implements this so the pipeline can run the active processor with a single call.
 */
fun interface FrameProcessor {
    /**
     * Process the input frame and return the result bitmap, or null on failure.
     */
    fun process(input: Bitmap, params: ProcessorParams): Bitmap?
}
