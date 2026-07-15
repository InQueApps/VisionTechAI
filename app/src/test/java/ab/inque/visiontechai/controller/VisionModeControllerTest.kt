package ab.inque.visiontechai.controller

import ab.inque.visiontechai.EdgeDetectionProcessor
import ab.inque.visiontechai.HDFrameProcessor
import ab.inque.visiontechai.HDImageProcessor
import ab.inque.visiontechai.ImageSegmentationProcessor
import ab.inque.visiontechai.OpticalFlowProcessor
import ab.inque.visiontechai.R
import ab.inque.visiontechai.VisionMode
import ab.inque.visiontechai.VisionState
import ab.inque.visiontechai.databinding.ActivityMainBinding
import ab.inque.visiontechai.ui.VisionOverlayHost
import android.view.LayoutInflater
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VisionModeControllerTest {

    private lateinit var binding: ActivityMainBinding
    private lateinit var visionState: VisionState
    private lateinit var controller: VisionModeController
    private var sphereCleanupCount = 0

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.setTheme(R.style.Theme_PrismAI)
        binding = ActivityMainBinding.inflate(LayoutInflater.from(context))
        visionState = VisionState()
        val parent = binding.root as androidx.constraintlayout.widget.ConstraintLayout
        val overlayHost = VisionOverlayHost(parent, binding.controlsContainer.id)
        val frameProcessors = mapOf(
            VisionMode.HD to HDFrameProcessor(HDImageProcessor(RuntimeEnvironment.getApplication()))
        )
        controller = VisionModeController(
            binding = binding,
            visionState = visionState,
            overlayHost = overlayHost,
            frameProcessors = frameProcessors,
            opticalFlowProcessor = OpticalFlowProcessor(RuntimeEnvironment.getApplication()),
            imageSegmentationProcessor = ImageSegmentationProcessor(RuntimeEnvironment.getApplication()),
            onSphereCleanup = { sphereCleanupCount++ }
        )
    }

    @Test
    fun setVisionMode_sphere_updatesState() {
        controller.setVisionMode(VisionMode.SPHERE)
        assertEquals(VisionMode.SPHERE, visionState.mode)
    }

    @Test
    fun setVisionMode_leavingSphere_triggersCleanup() {
        controller.setVisionMode(VisionMode.SPHERE)
        controller.setVisionMode(VisionMode.NONE)
        assertEquals(1, sphereCleanupCount)
    }

    @Test
    fun setVisionMode_edgeDetection_showsSliderContainer() {
        controller.setVisionMode(VisionMode.EDGE_DETECTION)
        assertTrue(binding.edgeDetectionSliderContainer.visibility == View.VISIBLE)
    }
}
