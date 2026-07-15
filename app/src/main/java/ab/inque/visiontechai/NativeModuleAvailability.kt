package ab.inque.visiontechai

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
