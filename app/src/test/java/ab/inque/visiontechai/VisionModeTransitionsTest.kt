package ab.inque.visiontechai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionModeTransitionsTest {

    @Test
    fun cleanup_leavingSphere_clearsSphereOnly() {
        val flags = VisionModeTransitions.cleanupOnModeChange(VisionMode.SPHERE, VisionMode.NONE)
        assertTrue(flags.clearSphere)
        assertFalse(flags.clearFrameAccumulator)
        assertFalse(flags.clearHdCache)
    }

    @Test
    fun cleanup_leavingNightVision_clearsAccumulator() {
        val flags = VisionModeTransitions.cleanupOnModeChange(VisionMode.NIGHT_VISION, VisionMode.EDGE_DETECTION)
        assertFalse(flags.clearSphere)
        assertTrue(flags.clearFrameAccumulator)
        assertFalse(flags.clearHdCache)
    }

    @Test
    fun cleanup_leavingHd_clearsAccumulatorAndHdCache() {
        val flags = VisionModeTransitions.cleanupOnModeChange(VisionMode.HD, VisionMode.NONE)
        assertFalse(flags.clearSphere)
        assertTrue(flags.clearFrameAccumulator)
        assertTrue(flags.clearHdCache)
    }

    @Test
    fun cleanup_stayingInSameMode_noCleanup() {
        val flags = VisionModeTransitions.cleanupOnModeChange(VisionMode.SEGMENTATION, VisionMode.SEGMENTATION)
        assertFalse(flags.clearSphere)
        assertFalse(flags.clearFrameAccumulator)
        assertFalse(flags.clearHdCache)
    }
}
