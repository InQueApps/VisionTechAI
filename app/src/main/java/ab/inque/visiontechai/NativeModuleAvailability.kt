package ab.inque.visiontechai

import ab.inque.robotics.panorama.SphereNative
import ab.inque.robotics.video.VideoNative
import ab.inque.robotics.vision.depth.DepthNative
import ab.inque.robotics.vision.edge.EdgeDetectionNative
import ab.inque.robotics.vision.flow.OpticalFlowNative
import ab.inque.robotics.vision.night.NightVisionNative


/**
 * Reports which optional native libraries loaded successfully.
 */
object NativeModuleAvailability {

    data class Status(
        val sphere: Boolean,
        val nightVision: Boolean,
        val edgeDetection: Boolean,
        val opticalFlow: Boolean,
        val depth: Boolean,
        val video: Boolean
    )

    fun current(): Status = Status(
        sphere = SphereNative.isAvailable(),
        nightVision = NightVisionNative.isAvailable(),
        edgeDetection = EdgeDetectionNative.isAvailable(),
        opticalFlow = OpticalFlowNative.isAvailable(),
        depth = DepthNative.isAvailable(),
        video = VideoNative.isAvailable()
    )
}
