package ab.inque.visiontechai

import ab.inque.robotics.vision.detection.DetectionResult


import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VisionAnalysisPipelineTest {

    private fun solidBitmap(w: Int = 64, h: Int = 48): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }

    @Test
    fun processFrame_noneMode_skipsProcessorAndDetector() {
        val state = VisionState(mode = VisionMode.NONE)
        var detectorCalled = false
        val pipeline = VisionAnalysisPipeline(
            getState = { state },
            processors = mapOf(
                VisionMode.COLOR_ADJUSTMENT to FrameProcessor { _, _ ->
                    error("should not run")
                }
            ),
            detectObjects = {
                detectorCalled = true
                emptyList()
            },
            applyFlip = { it }
        )

        val result = pipeline.processFrame(solidBitmap(), frameTimestampNanos = 42L)

        assertNull(result.processedBitmap)
        assertNull(result.detections)
        assertNull(result.rawBitmap)
        assertEquals(64, result.imageWidth)
        assertEquals(48, result.imageHeight)
        assertEquals(42L, result.frameTimestampNanos)
        assertNotNull(result.histograms)
        assertTrue(!detectorCalled)
    }

    @Test
    fun processFrame_colorMode_runsProcessorAndFlip() {
        val state = VisionState(mode = VisionMode.COLOR_ADJUSTMENT)
        val marker = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val pipeline = VisionAnalysisPipeline(
            getState = { state },
            processors = mapOf(
                VisionMode.COLOR_ADJUSTMENT to FrameProcessor { _, _ -> marker }
            ),
            detectObjects = { emptyList() },
            applyFlip = { flipped -> flipped.apply { eraseColor(Color.RED) } }
        )

        val result = pipeline.processFrame(solidBitmap(8, 8), 0L)
        assertNotNull(result.processedBitmap)
        assertEquals(Color.RED, result.processedBitmap!!.getPixel(0, 0))
    }

    @Test
    fun processFrame_objectDetectionMode_invokesDetector() {
        val state = VisionState(mode = VisionMode.OBJECT_DETECTION)
        val expected = listOf(
            DetectionResult(android.graphics.RectF(1f, 2f, 3f, 4f), "person", 0.9f)
        )
        val pipeline = VisionAnalysisPipeline(
            getState = { state },
            processors = emptyMap(),
            detectObjects = { expected },
            applyFlip = { it }
        )

        val result = pipeline.processFrame(solidBitmap(), 0L)
        assertEquals(expected, result.detections)
        assertNull(result.processedBitmap)
    }

    @Test
    fun processFrame_sphereMode_setsRawBitmap() {
        val state = VisionState(mode = VisionMode.SPHERE)
        val input = solidBitmap()
        val pipeline = VisionAnalysisPipeline(
            getState = { state },
            processors = emptyMap(),
            detectObjects = { emptyList() },
            applyFlip = { it }
        )

        val result = pipeline.processFrame(input, 99L)
        assertSame(input, result.rawBitmap)
    }

    @Test
    fun processFrame_reusesHistogramOnOddFrames() {
        val state = VisionState(mode = VisionMode.NONE)
        val pipeline = VisionAnalysisPipeline(
            getState = { state },
            processors = emptyMap(),
            detectObjects = { emptyList() },
            applyFlip = { it }
        )

        pipeline.processFrame(solidBitmap(), 0L)
        val first = pipeline.processFrame(solidBitmap(), 0L).histograms
        val second = pipeline.processFrame(solidBitmap(), 0L).histograms
        assertSame(first, second)
    }
}
