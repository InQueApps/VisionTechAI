package ab.inque.visiontechai.sphere.debug

import ab.inque.robotics.math.LensDistortion
import ab.inque.robotics.panorama.SphereNative
import ab.inque.visiontechai.CameraFrameOrientation
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import kotlin.math.min

/**
 * Picks bitmap rotation + horizontal mirror so IMU poses match CameraX JPEG pixels.
 */
object SphereStitchOrientationProbe {

    private const val TAG = "SphereStitchOrient"

    data class Result(
        val imageRotationDegrees: Int,
        val mirrorCameraX: Boolean
    )

    fun resolve(
        manifest: SphereDebugSessionManifest,
        distortion: LensDistortion,
        framesDir: File,
        records: List<SphereDebugFrameRecord>,
        bitmapAlreadyMirrored: Boolean = false
    ): Result {
        val legacy = SphereDebugFrameRecord.isLegacyUncorrectedImu(records)
        if (!legacy && manifest.imageRotationDegrees != null && manifest.mirrorCameraX != null) {
            return Result(manifest.imageRotationDegrees, manifest.mirrorCameraX)
        }
        if (records.size < 2) {
            return Result(
                CameraFrameOrientation.inferRotationDegrees(
                    manifest.fovH,
                    manifest.fovV,
                    manifest.imageRotationDegrees
                ),
                false
            )
        }

        val inferred = CameraFrameOrientation.inferRotationDegrees(
            manifest.fovH,
            manifest.fovV,
            null
        )
        val probeCount = min(4, records.size)
        val rotations = intArrayOf(0, 90, 180, 270)
        val mirrors = if (bitmapAlreadyMirrored) booleanArrayOf(false) else booleanArrayOf(false, true)

        var best = Result(inferred, false)
        var bestScore = Float.MAX_VALUE

        for (mirror in mirrors) {
            for (rot in rotations) {
                val score = probeScore(
                    eqW = manifest.eqW,
                    eqH = manifest.eqH,
                    fovH = manifest.fovH,
                    fovV = manifest.fovV,
                    distortion = distortion,
                    framesDir = framesDir,
                    records = records,
                    rotationDegrees = rot,
                    mirrorCameraX = mirror,
                    probeCount = probeCount
                )
                Log.d(TAG, "Probe rot=$rot mirror=$mirror score=$score")
                if (score < bestScore) {
                    bestScore = score
                    best = Result(rot, mirror)
                }
            }
        }
        Log.i(
            TAG,
            "Selected rotation=${best.imageRotationDegrees} mirror=${best.mirrorCameraX} " +
                "(inferred=$inferred score=$bestScore)"
        )
        return best
    }

    /** Live capture: probe horizontal mirror on first two upright frames. */
    fun resolveLiveMirror(
        eqW: Int,
        eqH: Int,
        fovH: Float,
        fovV: Float,
        distortion: LensDistortion,
        frame0: Bitmap,
        pose0: FloatArray,
        frame1: Bitmap,
        pose1: FloatArray,
        bitmapAlreadyMirrored: Boolean = false
    ): Boolean {
        if (bitmapAlreadyMirrored) return false
        var bestMirror = false
        var bestScore = Float.MAX_VALUE
        for (mirror in booleanArrayOf(false, true)) {
            val score = probeScoreBitmaps(
                eqW, eqH, fovH, fovV, distortion,
                listOf(frame0 to pose0, frame1 to pose1),
                rotationDegrees = 0,
                mirrorCameraX = mirror
            )
            Log.d(TAG, "Live mirror probe mirror=$mirror score=$score")
            if (score < bestScore) {
                bestScore = score
                bestMirror = mirror
            }
        }
        Log.i(TAG, "Live mirror selected: $bestMirror (score=$bestScore)")
        return bestMirror
    }

    private fun probeScore(
        eqW: Int,
        eqH: Int,
        fovH: Float,
        fovV: Float,
        distortion: LensDistortion,
        framesDir: File,
        records: List<SphereDebugFrameRecord>,
        rotationDegrees: Int,
        mirrorCameraX: Boolean,
        probeCount: Int
    ): Float {
        val pairs = (0 until probeCount).mapNotNull { i ->
            val record = records[i]
            val file = File(framesDir, record.fileName)
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return Float.MAX_VALUE
            val pose = SphereDebugFrameRecord.replayQuaternion(record, rotationDegrees)
            bitmap to pose
        }
        return probeScoreBitmaps(
            eqW, eqH, fovH, fovV, distortion, pairs, rotationDegrees, mirrorCameraX
        )
    }

    private fun probeScoreBitmaps(
        eqW: Int,
        eqH: Int,
        fovH: Float,
        fovV: Float,
        distortion: LensDistortion,
        frames: List<Pair<Bitmap, FloatArray>>,
        rotationDegrees: Int,
        mirrorCameraX: Boolean
    ): Float {
        SphereNative.reset()
        SphereNative.release()
        if (!SphereNative.init(eqW, eqH, fovH, fovV, distortion)) {
            return Float.MAX_VALUE
        }
        SphereNative.setMirrorCameraX(mirrorCameraX)

        var score = 0f
        for ((index, pair) in frames.withIndex()) {
            val (bitmap, pose) = pair
            val accepted = SphereNative.addFrameAligned(bitmap, pose, allowReject = false)
            val info = SphereNative.getLastFrameInfo()
            if (!accepted || info == null) {
                score += 1_000_000f
                continue
            }
            if (index == 0) continue
            score += info.residualSSD
            score += (1f - info.overlapRatio.coerceIn(0f, 1f)) * 8_000f
            score += info.alignmentOffsetDeg * 25f
            if (info.wasRejected) score += 50_000f
        }
        return score
    }
}
