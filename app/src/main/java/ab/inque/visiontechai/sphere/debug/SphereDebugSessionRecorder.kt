package ab.inque.visiontechai.sphere.debug

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Records sphere capture frames + IMU to disk for offline stitch replay.
 */
class SphereDebugSessionRecorder(private val context: Context) {

    private val tag = "SphereDebugRec"
    private var sessionDir: File? = null
    private var manifest: SphereDebugSessionManifest? = null
    private val frameRecords = mutableListOf<SphereDebugFrameRecord>()
    private var frameIndex = 0

    val isRecording: Boolean get() = sessionDir != null

    fun startSession(
        fovH: Float,
        fovV: Float,
        distortionK1: Float,
        distortionK2: Float,
        distortionP1: Float,
        distortionP2: Float,
        extrinsicOffset: FloatArray,
        eqW: Int,
        eqH: Int,
        imageRotationDegrees: Int? = null
    ): File {
        endSession()
        val sessionId = UUID.randomUUID().toString().take(8)
        val root = debugRoot()
        val dir = File(root, sessionId).apply { mkdirs() }
        File(dir, FRAMES_DIR).mkdirs()
        manifest = SphereDebugSessionManifest(
            sessionId = sessionId,
            startTimeMs = System.currentTimeMillis(),
            fovH = fovH,
            fovV = fovV,
            distortionK1 = distortionK1,
            distortionK2 = distortionK2,
            distortionP1 = distortionP1,
            distortionP2 = distortionP2,
            extrinsicOffset = extrinsicOffset.copyOf(),
            eqW = eqW,
            eqH = eqH,
            imageRotationDegrees = imageRotationDegrees
        )
        sessionDir = dir
        frameIndex = 0
        frameRecords.clear()
        Log.i(tag, "Started debug session $sessionId at ${dir.absolutePath}")
        return dir
    }

    fun recordFrame(
        bitmap: Bitmap,
        cameraTimestampNanos: Long,
        imuQuaternion: FloatArray,
        calibratedQuaternion: FloatArray
    ): Int {
        val dir = sessionDir ?: return -1
        frameIndex++
        val idx = frameIndex
        val fileName = "frame_%06d.jpg".format(idx)
        val frameFile = File(File(dir, FRAMES_DIR), fileName)
        FileOutputStream(frameFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        val record = SphereDebugFrameRecord(
            index = idx,
            fileName = fileName,
            cameraTimestampNanos = cameraTimestampNanos,
            imuQuaternion = imuQuaternion.copyOf(),
            calibratedQuaternion = calibratedQuaternion.copyOf()
        )
        frameRecords.add(record)
        appendImuLine(dir, record)
        persistManifest(dir)
        Log.d(tag, "Recorded frame $idx (${bitmap.width}x${bitmap.height})")
        return idx
    }

    fun updateLastFrameOutcome(
        accepted: Boolean,
        overlapRatio: Float,
        alignmentOffsetDeg: Float,
        wasRejected: Boolean,
        stitchedQuaternion: FloatArray? = null
    ) {
        val dir = sessionDir ?: return
        val record = frameRecords.lastOrNull() ?: return
        record.accepted = accepted
        record.overlapRatio = overlapRatio
        record.alignmentOffsetDeg = alignmentOffsetDeg
        record.wasRejected = wasRejected
        if (stitchedQuaternion != null && stitchedQuaternion.size >= 4) {
            record.stitchedQuaternion = stitchedQuaternion.copyOf()
        }
        rewriteImuFile(dir)
    }

    fun endSession(): File? {
        val dir = sessionDir ?: return null
        persistManifest(dir)
        rewriteImuFile(dir)
        val sessionId = manifest?.sessionId
        Log.i(tag, "Ended debug session $sessionId frames=${frameRecords.size}")
        sessionDir = null
        manifest = null
        frameRecords.clear()
        frameIndex = 0
        return dir
    }

    private fun persistManifest(dir: File) {
        val m = manifest ?: return
        File(dir, MANIFEST_FILE).writeText(
            m.copy(frameCount = frameRecords.size).toJson().toString(2)
        )
    }

    fun listSessions(): List<File> =
        debugRoot().listFiles()
            ?.filter { it.isDirectory && File(it, MANIFEST_FILE).exists() }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun debugRoot(): File =
        File(context.getExternalFilesDir(null), "sphere_debug_sessions").apply { mkdirs() }

    private fun appendImuLine(dir: File, record: SphereDebugFrameRecord) {
        File(dir, IMU_FILE).appendText(record.toJsonLine() + "\n")
    }

    private fun rewriteImuFile(dir: File) {
        val imuFile = File(dir, IMU_FILE)
        imuFile.writeText(frameRecords.joinToString("\n") { it.toJsonLine() } + "\n")
    }
}
