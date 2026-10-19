package ab.inque.visiontechai

import ab.inque.robotics.panorama.FrameAlignmentInfo


import ab.inque.visiontechai.SphereCaptureController.FrameWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameAlignmentInfoTest {

    @Test
    fun overlapRatio_computesFraction() {
        val info = FrameAlignmentInfo(2, 10, 200, 1f, 100f, false, rollOffsetDeg = 1.5f)
        assertEquals(0.05f, info.overlapRatio, 0.001f)
        assertEquals(1.5f, info.rollOffsetDeg, 0.001f)
    }

    @Test
    fun hasLowOverlap_whenBelowFivePercent() {
        val info = FrameAlignmentInfo(2, 5, 200, 1f, 100f, false)
        assertTrue(info.hasLowOverlap)
    }

    @Test
    fun isFirstFrame_rejectedStillWarns() {
        val info = FrameAlignmentInfo(1, 0, 100, 10f, 9000f, true)
        assertEquals(FrameWarning.ALIGNMENT_FAILED, SphereAlignmentPolicy.classifyWarning(info))
    }

    @Test
    fun isFirstFrame_cleanSkipsWarnings() {
        val info = FrameAlignmentInfo(1, 0, 100, 10f, 100f, false)
        assertNull(SphereAlignmentPolicy.classifyWarning(info))
    }

    @Test
    fun classifyWarning_rejectedTakesPriority() {
        val info = FrameAlignmentInfo(3, 1, 100, 10f, 9000f, true)
        assertEquals(FrameWarning.ALIGNMENT_FAILED, SphereAlignmentPolicy.classifyWarning(info))
    }

    @Test
    fun classifyWarning_excessiveShift() {
        val info = FrameAlignmentInfo(3, 50, 100, 6f, 100f, false)
        assertEquals(FrameWarning.EXCESSIVE_SHIFT, SphereAlignmentPolicy.classifyWarning(info))
    }

    @Test
    fun classifyWarning_lowOverlap() {
        val info = FrameAlignmentInfo(3, 5, 200, 1f, 100f, false)
        assertEquals(FrameWarning.NO_OVERLAP, SphereAlignmentPolicy.classifyWarning(info))
    }

    @Test
    fun classifyWarning_noIssues_returnsNull() {
        val info = FrameAlignmentInfo(3, 50, 200, 1f, 100f, false)
        assertNull(SphereAlignmentPolicy.classifyWarning(info))
    }
}
