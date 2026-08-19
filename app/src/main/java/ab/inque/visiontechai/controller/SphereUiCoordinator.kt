package ab.inque.visiontechai.controller

import ab.inque.visiontechai.VisionMode
import ab.inque.visiontechai.VisionState
import ab.inque.visiontechai.databinding.ActivityMainBinding

/**
 * Binds sphere capture overlay controls (enter/exit, done, save, record).
 */
class SphereUiCoordinator(
    private val binding: ActivityMainBinding,
    private val visionState: VisionState,
    private val sphereSession: SphereSessionController
) {

    fun bind() {
        binding.sphereButton.setOnClickListener {
            if (visionState.mode == VisionMode.SPHERE) {
                sphereSession.exit()
            } else {
                sphereSession.enter()
            }
        }
        binding.sphereCancelButton.setOnClickListener {
            sphereSession.cancelCapture()
        }
        binding.sphereDoneButton.setOnClickListener {
            sphereSession.finishCapture()
        }
        binding.sphereSaveButton.setOnClickListener {
            sphereSession.saveEquirectangular()
        }
        binding.sphereAddMoreButton.setOnClickListener {
            sphereSession.addMoreFrames()
        }
        binding.sphereCloseButton.setOnClickListener {
            sphereSession.closeViewerAndReset()
        }
        if (ab.inque.visiontechai.BuildConfig.DEBUG) {
            binding.sphereDebugButton.setOnClickListener {
                sphereSession.toggleDebugRecording()
            }
            binding.sphereDebugButton.setOnLongClickListener {
                sphereSession.replayLastDebugSession()
                true
            }
        }
    }
}
