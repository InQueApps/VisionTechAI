package ab.inque.visiontechai

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import ab.inque.visiontechai.LensDistortion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SphereStitchWorkerTest {

    private open class FakeOps : SphereStitchOperations {
        var addCount = 0
        override fun init(
            eqW: Int,
            eqH: Int,
            fovH: Float,
            fovV: Float,
            distortion: LensDistortion
        ) = true
        override fun addFrame(bitmap: Bitmap, quaternion: FloatArray): Boolean {
            addCount++
            return true
        }
        override fun getCoverage(): Float = addCount * 0.1f
        override fun getSolidAngleCoverage(): Float = addCount * 0.08f
        override fun getHoleMaskBitmap(outW: Int, outH: Int): Bitmap? = null
        override fun getLastFrameInfo(): FrameAlignmentInfo? =
            FrameAlignmentInfo(addCount, 10, 100, 1f, 100f, false)
        override fun getEquirectBitmap(eqW: Int, eqH: Int): Bitmap =
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        override fun reset() { addCount = 0 }
        override fun release() {}
    }

    @Test
    fun submit_runsOffMainThread_andDeliversResult() {
        val ops = FakeOps()
        val executor = Executors.newSingleThreadExecutor()
        val worker = SphereStitchWorker(operations = ops, executor = executor)
        val latch = java.util.concurrent.CountDownLatch(1)
        var ranOnMain = false
        var result: SphereStitchResult? = null
        worker.onResult = {
            ranOnMain = Looper.myLooper() == Looper.getMainLooper()
            result = it
            latch.countDown()
        }
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.RED)
        worker.submit(bmp, floatArrayOf(0f, 0f, 0f, 1f))
        executor.shutdown()
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(ranOnMain)
        assertFalse(result!!.ranOnMainThread)
        assertEquals(1, ops.addCount)
        assertEquals(0.1f, result!!.coverage, 0.001f)
        assertEquals(0.08f, result!!.solidAngleCoverage, 0.001f)
        worker.shutdown()
    }

    @Test
    fun submit_whileBusy_queuesSecondFrame() {
        val ops = FakeOps()
        val executor = Executors.newSingleThreadExecutor()
        val worker = SphereStitchWorker(operations = ops, executor = executor)
        worker.onResult = {}
        val bmp1 = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val bmp2 = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        worker.submit(bmp1, floatArrayOf(0f, 0f, 0f, 1f))
        worker.submit(bmp2, floatArrayOf(0f, 0f, 0f, 1f))
        val deadline = System.currentTimeMillis() + 3000
        while (ops.addCount < 2 && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertEquals(2, ops.addCount)
        assertEquals(1, worker.droppedWhileBusy)
        executor.shutdown()
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        worker.shutdown()
    }

    @Test
    fun submit_compositesHoleMaskOnPreview() {
        val ops = HoleMaskOps()
        val executor = Executors.newSingleThreadExecutor()
        val worker = SphereStitchWorker(operations = ops, executor = executor)
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: SphereStitchResult? = null
        worker.onResult = {
            result = it
            latch.countDown()
        }
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.RED)
        worker.submit(bmp, floatArrayOf(0f, 0f, 0f, 1f))
        executor.shutdown()
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertNotNull(result!!.previewBitmap)
        worker.shutdown()
    }

    @Test
    fun runFinishGeneration_deliversOffMainThread() {
        val ops = FakeOps()
        val executor = Executors.newSingleThreadExecutor()
        val worker = SphereStitchWorker(operations = ops, executor = executor)
        val latch = java.util.concurrent.CountDownLatch(1)
        var finish: SphereFinishResult? = null
        worker.submit(
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) },
            floatArrayOf(0f, 0f, 0f, 1f)
        )
        val submitDeadline = System.currentTimeMillis() + 3000
        while (ops.addCount < 1 && System.currentTimeMillis() < submitDeadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        worker.runFinishGeneration(64, 32) { result ->
            finish = result
            latch.countDown()
        }
        val finishDeadline = System.currentTimeMillis() + 3000
        while (finish == null && System.currentTimeMillis() < finishDeadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertNotNull(finish)
        assertFalse(finish!!.ranOnMainThread)
        assertNotNull(finish!!.equirect)
        worker.shutdown()
        executor.shutdown()
    }

    private class HoleMaskOps : FakeOps() {
        override fun getHoleMaskBitmap(outW: Int, outH: Int): Bitmap =
            Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).apply {
                eraseColor(0x88FF0000.toInt())
            }
    }
}
