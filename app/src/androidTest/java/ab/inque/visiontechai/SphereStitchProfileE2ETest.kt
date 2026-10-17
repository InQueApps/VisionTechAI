package ab.inque.visiontechai

import ab.inque.robotics.math.QuaternionMath
import ab.inque.robotics.panorama.SphereNative
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Profiles stitch hot paths after speed optimizations. Logs under TAG=SphereProfile.
 * Not a hard gate — compare before/after on the same device.
 *
 * Capture on device: adb logcat -s SphereProfile
 */
@RunWith(AndroidJUnit4::class)
class SphereStitchProfileE2ETest {

    companion object {
        private const val TAG = "SphereProfile"
        private const val EQ_W = 4096
        private const val EQ_H = 2048
        private const val FRAME_W = 640
        private const val FRAME_H = 480
    }

    private fun stripeBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(FRAME_W, FRAME_H, Bitmap.Config.ARGB_8888)
        val px = IntArray(FRAME_W * FRAME_H)
        for (y in 0 until FRAME_H) {
            for (x in 0 until FRAME_W) {
                px[y * FRAME_W + x] = if ((x / 16) % 2 == 0) Color.WHITE else Color.BLACK
            }
        }
        bmp.setPixels(px, 0, FRAME_W, 0, 0, FRAME_W, FRAME_H)
        return bmp
    }

  /** Shifted stripe pattern for feature-matcher A/B pair. */
    private fun shiftedStripeBitmap(dx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(FRAME_W, FRAME_H, Bitmap.Config.ARGB_8888)
        val px = IntArray(FRAME_W * FRAME_H)
        for (y in 0 until FRAME_H) {
            for (x in 0 until FRAME_W) {
                val sx = (x + dx) % FRAME_W
                px[y * FRAME_W + x] = if ((sx / 16) % 2 == 0) Color.WHITE else Color.BLACK
            }
        }
        bmp.setPixels(px, 0, FRAME_W, 0, 0, FRAME_W, FRAME_H)
        return bmp
    }

    @Test
    fun profile_featureMatcher_isolated() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.init(EQ_W, EQ_H, 65f, 50f)
        val frameA = stripeBitmap()
        val frameB = shiftedStripeBitmap(24)
        Log.i(TAG, "PROFILE featureMatcher isolated (cold + warm x3)")
        SphereNative.profileFeatureMatcher(frameA, frameB)
        SphereNative.release()
    }

    @Test
    fun profile_livePairwise_and_featureGraph() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.init(EQ_W, EQ_H, 65f, 50f)
        val frame = stripeBitmap()

        for (i in 0 until 4) {
            val q = if (i == 0) {
                floatArrayOf(0f, 0f, 0f, 1f)
            } else {
                QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(i * 15.0))
            }
            SphereNative.addFrameFast(frame, q, 0.10f)
        }

        val qNext = QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(60.0))
        Log.i(TAG, "PROFILE livePairwise (4 stored frames)")
        SphereNative.profileLivePairwise(frame, qNext)

        Log.i(TAG, "PROFILE featureGraph (${SphereNative.getStoredFrameQuaternions()?.size ?: 0} frames)")
        SphereNative.profileFeatureGraph()

        SphereNative.release()
    }

    @Test
    fun profile_fastPathAndFinishExport() {
        assumeTrue(SphereNative.isAvailable())
        SphereNative.init(EQ_W, EQ_H, 65f, 50f)
        val frame = stripeBitmap()

        val addMs = LongArray(8)
        for (i in addMs.indices) {
            val q = if (i == 0) {
                floatArrayOf(0f, 0f, 0f, 1f)
            } else {
                QuaternionMath.fromAxisAngle(0.0, 0.0, 1.0, Math.toRadians(i * 12.0))
            }
            val t0 = SystemClock.elapsedRealtime()
            SphereNative.addFrameFast(frame, q, 0.15f)
            addMs[i] = SystemClock.elapsedRealtime() - t0
        }
        val addP95 = addMs.sorted()[(addMs.size * 0.95).toInt().coerceAtMost(addMs.lastIndex)]
        Log.i(TAG, "addFrameFast p95=${addP95}ms samples=${addMs.contentToString()}")

        val tGlobal0 = SystemClock.elapsedRealtime()
        SphereNative.refinePending(applyGlobalPose = true)
        val globalMs = SystemClock.elapsedRealtime() - tGlobal0
        Log.i(TAG, "refineGlobalPose=${globalMs}ms")

        val tRebuild0 = SystemClock.elapsedRealtime()
        SphereNative.rebuildFullAlign()
        val rebuildMs = SystemClock.elapsedRealtime() - tRebuild0
        Log.i(TAG, "rebuildFullAlign=${rebuildMs}ms")

        val tPrep0 = SystemClock.elapsedRealtime()
        SphereNative.prepareExportEquirect(EQ_W, EQ_H)
        val prepMs = SystemClock.elapsedRealtime() - tPrep0
        Log.i(TAG, "prepareExportEquirect (2nd call, expect skip)=${prepMs}ms")

        val executor = Executors.newSingleThreadExecutor()
        val worker = SphereStitchWorker(
            operations = SphereNativeOperations,
            executor = executor,
            refineDelayMs = 60_000L
        )
        val finishLatch = CountDownLatch(1)
        var finishMs = 0L
        val finishStart = SystemClock.elapsedRealtime()
        worker.runFinishGeneration(EQ_W, EQ_H) { _ ->
            finishMs = SystemClock.elapsedRealtime() - finishStart
            finishLatch.countDown()
        }
        assertTrue(finishLatch.await(180, TimeUnit.SECONDS))
        Log.i(TAG, "runFinishGeneration total=${finishMs}ms (bg global=${SphereRefinePolicy.runBackgroundGlobalPose()})")

        worker.shutdown()
        executor.shutdown()
        SphereNative.release()

        assertTrue("prepareExport skip should be < 50ms (was ${prepMs}ms)", prepMs < 50L)
        Log.i(TAG, "PROFILE SUMMARY addP95=${addP95}ms global=${globalMs}ms rebuild=${rebuildMs}ms prepSkip=${prepMs}ms finish=${finishMs}ms")
    }
}
