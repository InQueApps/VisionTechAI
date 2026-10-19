package ab.inque.visiontechai

import ab.inque.robotics.panorama.FrameAlignmentInfo


import ab.inque.visiontechai.SphereCaptureController.FrameWarning

/**
 * Maps native alignment metrics to user-facing capture warnings.
 */
object SphereAlignmentPolicy {

    fun classifyWarning(info: FrameAlignmentInfo): FrameWarning? {
        if (info.wasRejected) return FrameWarning.ALIGNMENT_FAILED
        if (info.isFirstFrame) return null
        return when {
            info.hasLowOverlap -> FrameWarning.NO_OVERLAP
            info.hasExcessiveShift -> FrameWarning.EXCESSIVE_SHIFT
            else -> null
        }
    }
}
