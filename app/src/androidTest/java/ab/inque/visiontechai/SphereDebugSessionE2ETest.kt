package ab.inque.visiontechai

import ab.inque.robotics.math.QuaternionMath
import ab.inque.robotics.panorama.SphereNative
import ab.inque.visiontechai.sphere.debug.FRAMES_DIR
import ab.inque.visiontechai.sphere.debug.IMU_FILE
import ab.inque.visiontechai.sphere.debug.MANIFEST_FILE
import ab.inque.visiontechai.sphere.debug.OFFLINE_FINISH_FILE
import ab.inque.visiontechai.sphere.debug.QUALITY_REPORT_FILE
import ab.inque.visiontechai.sphere.debug.SphereDebugFrameRecord
import ab.inque.visiontechai.sphere.debug.SphereDebugSessionManifest
import ab.inque.visiontechai.sphere.debug.SphereDebugSessionReplayer
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Offline debug session: save synthetic frames + IMU, replay, write quality report.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SphereDebugSessionE2ETest {

    companion object {
        private const val EQ_W = 1024
        private const val EQ_H = 512
        private const val FRAME_W = 480
        private const val FRAME_H = 360
    }

    @Before
    fun setup() {
        assumeTrue(SphereNative.isAvailable())
    }

    @After
    fun teardown() {
        SphereNative.reset()
        SphereNative.release()
    }

    @Test
    fun manifest_roundTripsThroughJson() {
        val manifest = SphereDebugSessionManifest(
            sessionId = "abc12345",
            startTimeMs = 1_700_000_000_000L,
            fovH = 65f,
            fovV = 50f,
            distortionK1 = -0.01f,
            distortionK2 = 0.002f,
            distortionP1 = 0f,
            distortionP2 = 0f,
            extrinsicOffset = floatArrayOf(0.01f, 0.02f, 0.03f, 0.99f),
            eqW = 4096,
            eqH = 2048,
            frameCount = 4
        )
        val restored = SphereDebugSessionManifest.fromJson(manifest.toJson())
        assertEquals(manifest.sessionId, restored.sessionId)
        assertEquals(manifest.frameCount, restored.frameCount)
        assertTrue(manifest.extrinsicOffset.contentEquals(restored.extrinsicOffset))
    }

    @Test
    fun replayAf77be51Session() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sessionDir = File(ctx.getExternalFilesDir(null), "sphere_debug_sessions/af77be51")
        assumeTrue("af77be51 session on device", File(sessionDir, MANIFEST_FILE).exists())
        Log.i("SphereDebugReplay", "Replaying ${sessionDir.absolutePath}")
        val result = SphereDebugSessionReplayer.replay(sessionDir)
        assertNotNull(result.finishEquirect)
        assertTrue(
            "af77be51 solid angle should exceed 45% (was ${result.report.fastSolidAngle})",
            result.report.fastSolidAngle >= 0.45f
        )
        assertTrue(
            "af77be51 ghost score should be ≤ 0.48 (was ${result.report.fastGhostScore})",
            result.report.fastGhostScore <= 0.48
        )
        Log.i("SphereDebugReplay", "af77be51: ${result.report.summaryLine()} ghost=${result.report.fastGhostScore}")
    }

    @Test
    fun replayLatestDeviceSession() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(ctx.getExternalFilesDir(null), "sphere_debug_sessions")
        val sessionDir = root.listFiles()
            ?.filter { it.isDirectory && File(it, MANIFEST_FILE).exists() }
            ?.maxByOrNull { it.lastModified() }
        assumeTrue("No debug session with manifest on device", sessionDir != null)
        Log.i("SphereDebugReplay", "Replaying ${sessionDir!!.absolutePath}")
        val result = SphereDebugSessionReplayer.replay(sessionDir)
        assertNotNull(result.finishEquirect)
        assertTrue(File(sessionDir, QUALITY_REPORT_FILE).exists())
        Log.i("SphereDebugReplay", "Quality: ${result.report.summaryLine()}")
    }

    @Test
    fun offlineReplay_writesQualityReport() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sessionDir = File(ctx.getExternalFilesDir(null), "sphere_debug_sessions/test_${System.currentTimeMillis()}")
        sessionDir.mkdirs()
        File(sessionDir, FRAMES_DIR).mkdirs()

        val manifest = SphereDebugSessionManifest(
            sessionId = sessionDir.name,
            startTimeMs = System.currentTimeMillis(),
            fovH = 65f,
            fovV = 50f,
            distortionK1 = 0f,
            distortionK2 = 0f,
            distortionP1 = 0f,
            distortionP2 = 0f,
            extrinsicOffset = floatArrayOf(0f, 0f, 0f, 1f),
            eqW = EQ_W,
            eqH = EQ_H,
            frameCount = 3
        )
        File(sessionDir, MANIFEST_FILE).writeText(manifest.toJson().toString(2))

        val base = stripeBitmap()
        val records = mutableListOf<SphereDebugFrameRecord>()
        val quats = listOf(
            floatArrayOf(0f, 0f, 0f, 1f),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(14.0)),
            QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(28.0))
        )
        quats.forEachIndexed { i, q ->
            val name = "frame_%06d.jpg".format(i + 1)
            val bmp = if (i == 0) base else shiftBitmap(base, 5 + i)
            FileOutputStream(File(sessionDir, "$FRAMES_DIR/$name")).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            records.add(
                SphereDebugFrameRecord(
                    index = i + 1,
                    fileName = name,
                    cameraTimestampNanos = i * 33_000_000L,
                    imuQuaternion = q,
                    calibratedQuaternion = q
                )
            )
        }
        File(sessionDir, IMU_FILE).writeText(records.joinToString("\n") { it.toJsonLine() } + "\n")

        val result = SphereDebugSessionReplayer.replay(sessionDir)
        assertNotNull(result.fastEquirect)
        assertNotNull(result.finishEquirect)
        assertTrue(File(sessionDir, QUALITY_REPORT_FILE).exists())
        assertTrue(File(sessionDir, OFFLINE_FINISH_FILE).exists())
        assertTrue(result.report.frameCount == 3)
        assertTrue(result.report.replayDurationMs > 0)
    }

    private fun stripeBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(FRAME_W, FRAME_H, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(FRAME_W * FRAME_H)
        for (y in 0 until FRAME_H) {
            for (x in 0 until FRAME_W) {
                pixels[y * FRAME_W + x] = if ((x / 32) % 2 == 0) Color.WHITE else Color.BLACK
            }
        }
        bmp.setPixels(pixels, 0, FRAME_W, 0, 0, FRAME_W, FRAME_H)
        return bmp
    }

    private fun shiftBitmap(src: Bitmap, dx: Int): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val srcPx = IntArray(w * h)
        val dstPx = IntArray(w * h) { Color.BLACK }
        src.getPixels(srcPx, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val sx = x - dx
                if (sx in 0 until w) dstPx[y * w + x] = srcPx[y * w + sx]
            }
        }
        out.setPixels(dstPx, 0, w, 0, 0, w, h)
        return out
    }
}
