package ab.inque.visiontechai.sphere.debug

import ab.inque.robotics.math.LensDistortion
import ab.inque.robotics.panorama.SphereNative
import ab.inque.visiontechai.CameraFrameOrientation
import ab.inque.visiontechai.SphereProductionStitchPipeline
import ab.inque.visiontechai.SphereNativeOperations
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Replays a saved [SphereDebugSessionRecorder] session through native stitch offline
 * and writes panorama outputs + [QUALITY_REPORT_FILE].
 */
object SphereDebugSessionReplayer {

    private const val TAG = "SphereDebugReplay"

    data class ReplayResult(
        val sessionDir: File,
        val report: SphereDebugQualityReport,
        val fastEquirect: Bitmap?,
        val finishEquirect: Bitmap?
    )

    fun replay(sessionDir: File): ReplayResult {
        require(File(sessionDir, MANIFEST_FILE).exists()) {
            "Missing manifest in ${sessionDir.absolutePath}"
        }
        val startMs = System.currentTimeMillis()
        val manifest = SphereDebugSessionManifest.load(sessionDir)
        val frames = loadFrameRecords(sessionDir)
        require(frames.isNotEmpty()) { "No frames in session ${manifest.sessionId}" }

        val distortion = LensDistortion(
            k1 = manifest.distortionK1,
            k2 = manifest.distortionK2,
            p1 = manifest.distortionP1,
            p2 = manifest.distortionP2
        )
        val framesDir = File(sessionDir, FRAMES_DIR)

        resetNative()
        val orientation = SphereStitchOrientationProbe.resolve(
            manifest,
            distortion,
            framesDir,
            frames
        )
        initNative(manifest, distortion)
        SphereNative.setMirrorCameraX(orientation.mirrorCameraX)
        Log.i(
            TAG,
            "Replay ${manifest.sessionId}: rotation=${orientation.imageRotationDegrees} " +
                "mirror=${orientation.mirrorCameraX}"
        )
        val poses = frames.map {
            SphereDebugFrameRecord.replayQuaternion(it, orientation.imageRotationDegrees)
        }
        stitchFrames(framesDir, frames, poses)

        val fastCoverage = SphereNative.getCoverage()
        val fastSolid = SphereNative.getSolidAngleCoverage()
        val fastEquirect = SphereNative.getEquirectBitmap(manifest.eqW, manifest.eqH)
            ?: error("Fast equirect null after replay")

        val content = SphereStitchQualityEvaluator.requireContentRegion(fastEquirect)
        val band = SphereStitchQualityEvaluator.overlapBandRegion(content)
        val fastGhost = SphereStitchQualityEvaluator.ghostingScore(fastEquirect, band)
        val fastSharpness = SphereStitchQualityEvaluator.laplacianVariance(fastEquirect, content)

        // Same production SAVE path as live capture (pre-finish drain + export resplat + light seams).
        SphereProductionStitchPipeline.runFinishExport(
            operations = SphereNativeOperations,
            exportW = manifest.eqW,
            exportH = manifest.eqH,
            onPass = { label -> Log.i(TAG, "Finish $label frames=${frames.size}") }
        )

        val finishEquirect = SphereNative.getEquirectBitmap(manifest.eqW, manifest.eqH)
            ?: error("Finish equirect null after production export")

        val finishFields = SphereStitchQualityEvaluator.evaluateFinish(
            fastEquirect = fastEquirect,
            finishEquirect = finishEquirect,
            ssdBefore = 0f,
            ssdAfter = 0f
        )

        saveJpeg(fastEquirect, File(sessionDir, OFFLINE_FAST_FILE))
        saveJpeg(finishEquirect, File(sessionDir, OFFLINE_FINISH_FILE))

        val durationMs = System.currentTimeMillis() - startMs
        val report = SphereDebugQualityReport(
            sessionId = manifest.sessionId,
            frameCount = frames.size,
            fastCoverage = fastCoverage,
            fastSolidAngle = fastSolid,
            fastGhostScore = fastGhost,
            fastSharpness = fastSharpness,
            previewGhostDelta = 0.0,
            previewCorrelation = 0.0,
            finishGhostDelta = finishFields.finishGhostDelta,
            finishOverlapBandCorrelation = finishFields.finishOverlapBandCorrelation,
            finishSharpness = finishFields.finishSharpness,
            ssdBeforeGlobal = 0f,
            ssdAfterGlobal = 0f,
            replayDurationMs = durationMs
        )
        File(sessionDir, QUALITY_REPORT_FILE).writeText(report.toJson().toString(2))

        Log.i(
            TAG,
            "Replay ${manifest.sessionId}: ${report.summaryLine()} " +
                "ghostGate=${finishFields.passesFinishGhostGate} " +
                "bandGate=${finishFields.passesBandCorrelationGate} " +
                "in ${durationMs}ms"
        )

        resetNative()
        return ReplayResult(sessionDir, report, fastEquirect, finishEquirect)
    }

    fun replayLast(context: android.content.Context): ReplayResult? {
        val sessions = SphereDebugSessionRecorder(context).listSessions()
        val last = sessions.firstOrNull() ?: return null
        return replay(last)
    }

    private fun resetNative() {
        SphereNative.reset()
        SphereNative.release()
    }

    private fun initNative(manifest: SphereDebugSessionManifest, distortion: LensDistortion) {
        if (!SphereNative.init(manifest.eqW, manifest.eqH, manifest.fovH, manifest.fovV, distortion)) {
            error("SphereNative.init failed for debug replay")
        }
    }

    private fun stitchFrames(
        framesDir: File,
        records: List<SphereDebugFrameRecord>,
        poses: List<FloatArray>
    ) {
        require(records.size == poses.size)
        for ((record, pose) in records.zip(poses)) {
            val frameFile = File(framesDir, record.fileName)
            val bitmap = BitmapFactory.decodeFile(frameFile.absolutePath)
                ?: error("Failed to decode ${frameFile.absolutePath}")
            val accepted = SphereNative.addFrameAligned(bitmap, pose, allowReject = false)
            if (!accepted) {
                Log.w(TAG, "addFrameAligned failed for ${record.fileName}")
            }
        }
    }

    private fun loadFrameRecords(sessionDir: File): List<SphereDebugFrameRecord> {
        val imuFile = File(sessionDir, IMU_FILE)
        if (!imuFile.exists()) return emptyList()
        return imuFile.readLines()
            .filter { it.isNotBlank() }
            .map { SphereDebugFrameRecord.fromJsonLine(it) }
            .sortedBy { it.index }
    }

    private fun saveJpeg(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
    }
}
