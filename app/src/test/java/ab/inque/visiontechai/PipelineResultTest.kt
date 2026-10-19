package ab.inque.visiontechai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PipelineResultTest {

    @Test
    fun defaults_areZeroOrNull() {
        val result = PipelineResult(
            fps = null,
            histograms = null,
            processedBitmap = null,
            detections = null
        )
        assertEquals(0, result.imageWidth)
        assertEquals(0, result.imageHeight)
        assertEquals(0L, result.frameTimestampNanos)
        assertNull(result.rawBitmap)
    }
}
