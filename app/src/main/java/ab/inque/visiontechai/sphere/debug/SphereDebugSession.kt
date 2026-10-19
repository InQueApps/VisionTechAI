package ab.inque.visiontechai.sphere.debug

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** On-disk sphere debug capture session (frames + IMU for offline replay). */
data class SphereDebugSessionManifest(
    val version: Int = 1,
    val sessionId: String,
    val startTimeMs: Long,
    val fovH: Float,
    val fovV: Float,
    val distortionK1: Float,
    val distortionK2: Float,
    val distortionP1: Float,
    val distortionP2: Float,
    val extrinsicOffset: FloatArray,
    val eqW: Int,
    val eqH: Int,
    val frameCount: Int = 0,
    /** CameraX clockwise rotation applied to saved JPEGs (0/90/180/270). */
    val imageRotationDegrees: Int? = null,
    /** Native camera-frame X mirror (auto-probed for legacy sessions). */
    val mirrorCameraX: Boolean? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", version)
        put("sessionId", sessionId)
        put("startTimeMs", startTimeMs)
        put("fovH", fovH.toDouble())
        put("fovV", fovV.toDouble())
        put("distortion", JSONObject().apply {
            put("k1", distortionK1.toDouble())
            put("k2", distortionK2.toDouble())
            put("p1", distortionP1.toDouble())
            put("p2", distortionP2.toDouble())
        })
        put("extrinsicOffset", quaternionToJson(extrinsicOffset))
        put("eqW", eqW)
        put("eqH", eqH)
        put("frameCount", frameCount)
        imageRotationDegrees?.let { put("imageRotationDegrees", it) }
        mirrorCameraX?.let { put("mirrorCameraX", it) }
    }

    companion object {
        fun fromJson(obj: JSONObject): SphereDebugSessionManifest {
            val dist = obj.optJSONObject("distortion") ?: JSONObject()
            return SphereDebugSessionManifest(
                version = obj.optInt("version", 1),
                sessionId = obj.getString("sessionId"),
                startTimeMs = obj.getLong("startTimeMs"),
                fovH = obj.getDouble("fovH").toFloat(),
                fovV = obj.getDouble("fovV").toFloat(),
                distortionK1 = dist.optDouble("k1", 0.0).toFloat(),
                distortionK2 = dist.optDouble("k2", 0.0).toFloat(),
                distortionP1 = dist.optDouble("p1", 0.0).toFloat(),
                distortionP2 = dist.optDouble("p2", 0.0).toFloat(),
                extrinsicOffset = jsonToQuaternion(obj.getJSONArray("extrinsicOffset")),
                eqW = obj.getInt("eqW"),
                eqH = obj.getInt("eqH"),
                frameCount = obj.optInt("frameCount", 0),
                imageRotationDegrees = if (obj.has("imageRotationDegrees")) {
                    obj.getInt("imageRotationDegrees")
                } else {
                    null
                },
                mirrorCameraX = if (obj.has("mirrorCameraX")) {
                    obj.getBoolean("mirrorCameraX")
                } else {
                    null
                }
            )
        }

        fun load(sessionDir: File): SphereDebugSessionManifest {
            val text = File(sessionDir, MANIFEST_FILE).readText()
            return fromJson(JSONObject(text))
        }
    }
}

data class SphereDebugFrameRecord(
    val index: Int,
    val fileName: String,
    val cameraTimestampNanos: Long,
    val imuQuaternion: FloatArray,
    val calibratedQuaternion: FloatArray,
    var stitchedQuaternion: FloatArray? = null,
    var accepted: Boolean? = null,
    var overlapRatio: Float? = null,
    var alignmentOffsetDeg: Float? = null,
    var wasRejected: Boolean? = null
) {
    fun toJsonLine(): String = JSONObject().apply {
        put("index", index)
        put("file", fileName)
        put("cameraTimestampNanos", cameraTimestampNanos)
        put("imuQuaternion", quaternionToJson(imuQuaternion))
        put("calibratedQuaternion", quaternionToJson(calibratedQuaternion))
        stitchedQuaternion?.let { put("stitchedQuaternion", quaternionToJson(it)) }
        accepted?.let { put("accepted", it) }
        overlapRatio?.let { put("overlapRatio", it.toDouble()) }
        alignmentOffsetDeg?.let { put("alignmentOffsetDeg", it.toDouble()) }
        wasRejected?.let { put("wasRejected", it) }
    }.toString()

    companion object {
        fun fromJsonLine(line: String): SphereDebugFrameRecord {
            val o = JSONObject(line)
            return SphereDebugFrameRecord(
                index = o.getInt("index"),
                fileName = o.getString("file"),
                cameraTimestampNanos = o.getLong("cameraTimestampNanos"),
                imuQuaternion = jsonToQuaternion(o.getJSONArray("imuQuaternion")),
                calibratedQuaternion = jsonToQuaternion(o.getJSONArray("calibratedQuaternion")),
                stitchedQuaternion = if (o.has("stitchedQuaternion")) {
                    jsonToQuaternion(o.getJSONArray("stitchedQuaternion"))
                } else {
                    null
                },
                accepted = if (o.has("accepted")) o.getBoolean("accepted") else null,
                overlapRatio = if (o.has("overlapRatio")) o.getDouble("overlapRatio").toFloat() else null,
                alignmentOffsetDeg = if (o.has("alignmentOffsetDeg")) {
                    o.getDouble("alignmentOffsetDeg").toFloat()
                } else null,
                wasRejected = if (o.has("wasRejected")) o.getBoolean("wasRejected") else null
            )
        }

        fun isLegacyUncorrectedImu(records: List<SphereDebugFrameRecord>): Boolean =
            records.all { record ->
                record.stitchedQuaternion == null &&
                    quaternionsNear(record.calibratedQuaternion, record.imuQuaternion)
            }

        /** Best pose seed for offline replay (visual align when available). */
        fun replayQuaternion(
            record: SphereDebugFrameRecord,
            imageRotationDegrees: Int
        ): FloatArray {
            record.stitchedQuaternion?.let { return it }
            if (quaternionsNear(record.calibratedQuaternion, record.imuQuaternion)) {
                return ab.inque.visiontechai.CameraFrameOrientation.quaternionForUprightBitmap(
                    record.imuQuaternion,
                    imageRotationDegrees
                )
            }
            return record.calibratedQuaternion
        }

        private fun quaternionsNear(a: FloatArray, b: FloatArray, tol: Float = 1e-3f): Boolean {
            val dot = kotlin.math.abs(
                a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
            )
            return dot > 1f - tol
        }
    }
}

data class SphereDebugQualityReport(
    val sessionId: String,
    val frameCount: Int,
    val fastCoverage: Float,
    val fastSolidAngle: Float,
    val fastGhostScore: Double,
    val fastSharpness: Double,
    val previewGhostDelta: Double,
    val previewCorrelation: Double,
    val finishGhostDelta: Double,
    val finishOverlapBandCorrelation: Double,
    val finishSharpness: Double,
    val ssdBeforeGlobal: Float,
    val ssdAfterGlobal: Float,
    val replayDurationMs: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("sessionId", sessionId)
        put("frameCount", frameCount)
        put("fastCoverage", fastCoverage.toDouble())
        put("fastSolidAngle", fastSolidAngle.toDouble())
        put("fastGhostScore", fastGhostScore)
        put("fastSharpness", fastSharpness)
        put("previewGhostDelta", previewGhostDelta)
        put("previewCorrelation", previewCorrelation)
        put("finishGhostDelta", finishGhostDelta)
        put("finishOverlapBandCorrelation", finishOverlapBandCorrelation)
        put("finishSharpness", finishSharpness)
        put("ssdBeforeGlobal", ssdBeforeGlobal.toDouble())
        put("ssdAfterGlobal", ssdAfterGlobal.toDouble())
        put("replayDurationMs", replayDurationMs)
    }

    fun summaryLine(): String = buildString {
        append("frames=$frameCount ")
        append("finishGhostΔ=${"%.3f".format(finishGhostDelta)} ")
        append("bandCorr=${"%.3f".format(finishOverlapBandCorrelation)} ")
        append("SSD ${"%.0f".format(ssdBeforeGlobal)}→${"%.0f".format(ssdAfterGlobal)}")
    }
}

const val MANIFEST_FILE = "manifest.json"
const val FRAMES_DIR = "frames"
const val IMU_FILE = "imu.jsonl"
const val QUALITY_REPORT_FILE = "quality_report.json"
const val OFFLINE_FAST_FILE = "offline_fast.jpg"
const val OFFLINE_FINISH_FILE = "offline_finish.jpg"

private fun quaternionToJson(q: FloatArray): JSONArray =
    JSONArray().apply { for (v in q) put(v.toDouble()) }

private fun jsonToQuaternion(arr: JSONArray): FloatArray =
    FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
