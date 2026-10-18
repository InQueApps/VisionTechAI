package ab.inque.visiontechai

import ab.inque.robotics.vision.detection.DetectionResult
import ab.inque.robotics.vision.detection.Yolo11OutputParser


import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class Yolo11OutputParserTest {

    private val classLabels = List(80) { "class_$it" }
    private val threshold = 0.5f

    @Test
    fun centerCropInfo_landscapeImage_cropsCenterSquare() {
        val info = Yolo11OutputParser.centerCropInfo(1920, 1080)
        assertEquals(420, info.cropRect.left)
        assertEquals(0, info.cropRect.top)
        assertEquals(1500, info.cropRect.right)
        assertEquals(1080, info.cropRect.bottom)
        assertEquals(640f / 1080f, info.scaleFactor, 0.0001f)
    }

    @Test
    fun centerCropInfo_portraitImage_cropsCenterSquare() {
        val info = Yolo11OutputParser.centerCropInfo(1080, 1920)
        assertEquals(0, info.cropRect.left)
        assertEquals(420, info.cropRect.top)
        assertEquals(1080, info.cropRect.right)
        assertEquals(1500, info.cropRect.bottom)
    }

    @Test
    fun parse_noBatchFormat_singleDetection_mapsToAbsoluteCoords() {
        val cropInfo = Yolo11OutputParser.centerCropInfo(1920, 1080)
        val output = FloatArray(8400 * 84)
        output[0] = 0.25f
        output[1] = 0.25f
        output[2] = 0.2f
        output[3] = 0.3f
        output[4] = 3f // sigmoid(3) > threshold

        val detections = Yolo11OutputParser.parse(
            output, cropInfo, classLabels, threshold, outputShape = intArrayOf(8400, 84)
        )

        assertEquals(1, detections.size)
        assertEquals("class_0", detections[0].label)
        assertEquals(690f, detections[0].boundingBox.left, 0.5f)
        assertEquals(270f, detections[0].boundingBox.top, 0.5f)
        assertEquals(906f, detections[0].boundingBox.right, 0.5f)
        assertEquals(594f, detections[0].boundingBox.bottom, 0.5f)
        assertTrue(detections[0].confidence > threshold)
    }

    @Test
    fun parse_nanOutput_returnsEmpty() {
        val cropInfo = Yolo11OutputParser.centerCropInfo(640, 640)
        val output = FloatArray(8400 * 84) { Float.NaN }
        assertTrue(Yolo11OutputParser.parse(output, cropInfo, classLabels, threshold).isEmpty())
    }

    @Test
    fun parse_unknownSize_returnsEmpty() {
        val cropInfo = Yolo11OutputParser.centerCropInfo(640, 640)
        assertTrue(Yolo11OutputParser.parse(FloatArray(100), cropInfo, classLabels, threshold).isEmpty())
    }

    @Test
    fun applyNMS_suppressesOverlappingLowerConfidenceBox() {
        val high = DetectionResult(RectF(0f, 0f, 100f, 100f), "a", 0.9f)
        val low = DetectionResult(RectF(10f, 10f, 90f, 90f), "b", 0.7f)
        val result = Yolo11OutputParser.applyNMS(listOf(low, high))
        assertEquals(1, result.size)
        assertEquals("a", result[0].label)
    }

    @Test
    fun calculateIoU_identicalBoxes_returnsOne() {
        val box = RectF(0f, 0f, 50f, 50f)
        assertEquals(1f, Yolo11OutputParser.calculateIoU(box, box), 0.0001f)
    }

    @Test
    fun parse_channelMajorFormat_readsFirstDetectionFromChannelPlanes() {
        val cropInfo = Yolo11OutputParser.centerCropInfo(1920, 1080)
        val output = FloatArray(1 * 84 * 8400)
        output[0 * 8400] = 0.25f
        output[1 * 8400] = 0.25f
        output[2 * 8400] = 0.2f
        output[3 * 8400] = 0.3f
        output[4 * 8400] = 3f

        val detections = Yolo11OutputParser.parse(
            output, cropInfo, classLabels, threshold, outputShape = intArrayOf(1, 84, 8400)
        )

        assertEquals(1, detections.size)
        assertEquals("class_0", detections[0].label)
        assertEquals(690f, detections[0].boundingBox.left, 0.5f)
        assertEquals(270f, detections[0].boundingBox.top, 0.5f)
    }

    @Test
    fun parse_alternativeFormat_usesObjectnessMultiplier() {
        val cropInfo = Yolo11OutputParser.centerCropInfo(640, 640)
        val numClasses = classLabels.size
        val output = FloatArray(1 * 85 * 8400)
        output[0] = 0.1f
        output[1] = 0.1f
        output[2] = 0.2f
        output[3] = 0.2f
        output[4] = 0.9f // objectness
        output[5] = 0.8f // class 0 confidence

        val detections = Yolo11OutputParser.parse(output, cropInfo, classLabels, 0.5f)
        assertEquals(1, detections.size)
        assertEquals(0.72f, detections[0].confidence, 0.01f)
    }
}
