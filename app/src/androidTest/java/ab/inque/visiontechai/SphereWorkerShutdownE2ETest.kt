package ab.inque.visiontechai

import ab.inque.robotics.panorama.SphereNative
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * S49: Worker [shutdown] while refine is scheduled must not crash (safeExecute guard).
 */
@RunWith(AndroidJUnit4::class)
class SphereWorkerShutdownE2ETest {

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
    fun shutdownDuringScheduledRefine_doesNotThrow() {
        val ops = SphereNativeOperations
        val executor = Executors.newSingleThreadExecutor()
        val refineExecutor = Executors.newSingleThreadExecutor()
        val worker = SphereStitchWorker(
            operations = ops,
            executor = executor,
            refineExecutor = refineExecutor,
            refineDelayMs = 500L,
            periodicRefineEvery = 6
        )
        ops.init(256, 128, 65f, 50f)
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.CYAN)
        }
        val q = floatArrayOf(0f, 0f, 0f, 1f)
        repeat(3) {
            val latch = CountDownLatch(1)
            worker.onResult = { if (!it.refined) latch.countDown() }
            worker.submit(bmp, q, photoMode = true)
            assertTrue(latch.await(5, TimeUnit.SECONDS))
        }
        worker.shutdown()
        executor.shutdown()
        refineExecutor.shutdown()
        Log.i("SphereWorkerShutdownE2E", "shutdown completed without exception")
    }
}
