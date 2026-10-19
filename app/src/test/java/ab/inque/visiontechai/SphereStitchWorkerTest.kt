package ab.inque.visiontechai

import ab.inque.robotics.image.SolidAngleCoverage
import ab.inque.robotics.math.LensDistortion
import ab.inque.robotics.panorama.FrameAlignmentInfo

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
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

    @Test
    fun submit_burstCapture_doesNotPeriodicRefine() {
        val ops = object : FakeOps() {
            var refineCount = 0
            override fun addFrameFast(bitmap: Bitmap, quaternion: FloatArray, overlapGate: Float): Int {
                addCount++
                return STITCH_ACCEPTED
            }
            override fun refineStoredPoses(): Boolean {
                refineCount++
                return true
            }
            override fun refineGlobalPose(): Boolean {
                refineCount++
                return true
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        val refineExecutor = Executors.newSingleThreadExecutor()
        val worker = SphereStitchWorker(
            operations = ops,
            executor = executor,
            refineExecutor = refineExecutor,
            refineDelayMs = 60_000L,
            periodicRefineEvery = 5
        )
        val refineLatch = java.util.concurrent.CountDownLatch(1)
        worker.onResult = { result ->
            if (result.refined) refineLatch.countDown()
        }
        val quat = floatArrayOf(0f, 0f, 0f, 1f)
        repeat(4) { i ->
            val stitchLatch = java.util.concurrent.CountDownLatch(1)
            worker.onResult = { result ->
                if (!result.refined) stitchLatch.countDown()
                if (result.refined) refineLatch.countDown()
            }
            val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            worker.submit(bmp, quat)
            val stitchDeadline = System.currentTimeMillis() + 5000
            while (!stitchLatch.await(25, TimeUnit.MILLISECONDS)) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                if (System.currentTimeMillis() > stitchDeadline) break
            }
            assertTrue("stitch $i should complete", stitchLatch.count == 0L)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        val refineDeadline = System.currentTimeMillis() + 500
        while (!refineLatch.await(25, TimeUnit.MILLISECONDS)) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > refineDeadline) break
        }
        assertEquals(4, ops.addCount)
        assertEquals(
            "Periodic refine must not fire before five accepted frames",
            0,
            ops.refineCount
        )
        executor.shutdown()
        refineExecutor.shutdown()
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        assertTrue(refineExecutor.awaitTermination(3, TimeUnit.SECONDS))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        worker.shutdown()
    }

    @Test
    fun r5_pauseDebounceRefine_firesAfterCaptureIdle() {
        val ops = object : FakeOps() {
            var refineCount = 0
            override fun refineStoredPoses(): Boolean {
                refineCount++
                return true
            }
            override fun refineGlobalPose(): Boolean {
                refineCount++
                return true
            }
            override fun rebuildFullAlign(): Boolean = true
        }
        val executor = Executors.newSingleThreadExecutor()
        val refineExecutor = Executors.newSingleThreadExecutor()
        val worker = SphereStitchWorker(
            operations = ops,
            executor = executor,
            refineExecutor = refineExecutor,
            refineDelayMs = 50L,
            periodicRefineEvery = 6
        )
        val refineLatch = java.util.concurrent.CountDownLatch(1)
        worker.onResult = { result ->
            if (result.refined) refineLatch.countDown()
        }
        val quat = floatArrayOf(0f, 0f, 0f, 1f)
        repeat(6) { i ->
            val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            worker.submit(bmp, quat)
            val stitchDeadline = System.currentTimeMillis() + 3000
            while (ops.addCount <= i && System.currentTimeMillis() < stitchDeadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
            }
            assertEquals("frame ${i + 1} should be stitched", i + 1, ops.addCount)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(2_000, TimeUnit.MILLISECONDS)
        val deadline = System.currentTimeMillis() + 5000
        while (refineLatch.count > 0 && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue("Pause debounce refine should deliver refined preview", refineLatch.count == 0L)
        assertTrue(ops.refineCount > 0)
        executor.shutdown()
        refineExecutor.shutdown()
        worker.shutdown()
    }

    @Test
    fun shutdown_afterSubmit_doesNotThrow() {
        val ops = FakeOps()
        val executor = Executors.newSingleThreadExecutor()
        val worker = SphereStitchWorker(operations = ops, executor = executor, refineDelayMs = 100L)
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        worker.submit(bmp, floatArrayOf(0f, 0f, 0f, 1f))
        worker.shutdown()
        executor.shutdown()
    }

    @Test
    fun submit_secondFrame_schedulesDebouncedCaptureRefine() {
        val ops = object : FakeOps() {
            var storedCount = 0
            var globalCount = 0
            override fun refineStoredPoses(): Boolean {
                storedCount++
                return true
            }
            override fun refineGlobalPose(): Boolean {
                globalCount++
                return true
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        val refineExecutor = Executors.newSingleThreadExecutor()
        val worker = SphereStitchWorker(
            operations = ops,
            executor = executor,
            refineExecutor = refineExecutor,
            refineDelayMs = 50L
        )
        val refineLatch = java.util.concurrent.CountDownLatch(1)
        worker.onResult = { result ->
            if (result.refined) refineLatch.countDown()
        }
        val quat = floatArrayOf(0f, 0f, 0f, 1f)
        repeat(2) { i ->
            val stitchLatch = java.util.concurrent.CountDownLatch(1)
            worker.onResult = { result ->
                if (!result.refined) stitchLatch.countDown()
                if (result.refined) refineLatch.countDown()
            }
            worker.submit(
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) },
                quat
            )
            val deadline = System.currentTimeMillis() + 5000
            while (!stitchLatch.await(25, TimeUnit.MILLISECONDS)) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                if (System.currentTimeMillis() > deadline) break
            }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        val refineDeadline = System.currentTimeMillis() + 3000
        while (!refineLatch.await(25, TimeUnit.MILLISECONDS)) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            if (System.currentTimeMillis() > refineDeadline) break
        }
        refineExecutor.shutdown()
        assertTrue(refineExecutor.awaitTermination(3, TimeUnit.SECONDS))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Debounced refine should run after frame 2", refineLatch.count == 0L)
        assertTrue("Capture refine should run global at least once", ops.globalCount >= 1)
        executor.shutdown()
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        worker.shutdown()
    }

    @Test
    fun runFinishGeneration_runsMinimalPassesThenExportPrep() {
        val ops = object : FakeOps() {
            val steps = mutableListOf<String>()
            override fun refineStoredPoses(): Boolean {
                steps.add("refineStored")
                return true
            }
            override fun refineConditionalParallax(): Int {
                steps.add("conditionalParallax")
                return 1
            }
            override fun refineTargetedPhotometric(): Int {
                steps.add("targetedPhotometric")
                return 1
            }
            override fun refineFeatureGraphReconcile(): Boolean {
                steps.add("graphReconcile")
                return true
            }
            override fun prepareExportEquirect(exportW: Int, exportH: Int): Boolean {
                steps.add("prepareExport")
                return true
            }
            override fun refineSeams(light: Boolean): Boolean {
                steps.add(if (light) "seamLight" else "seamFull")
                return true
            }
            override fun setFastFinishRefine(enabled: Boolean) {
                steps.add(if (enabled) "fastFinishOn" else "fastFinishOff")
            }
            override fun setGlobalRefinePasses(passes: Int) {
                steps.add("globalPasses=$passes")
            }
            override fun setPoseGraphOnlyGlobal(enabled: Boolean) {
                steps.add(if (enabled) "poseGraphOnlyOn" else "poseGraphOnlyOff")
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        val refineExecutor = Executors.newSingleThreadExecutor()
        val intrinsicExecutor = Executors.newSingleThreadExecutor()
        val worker = SphereStitchWorker(
            operations = ops,
            executor = executor,
            refineExecutor = refineExecutor,
            intrinsicExecutor = intrinsicExecutor
        )
        worker.submit(
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) },
            floatArrayOf(0f, 0f, 0f, 1f)
        )
        val submitDeadline = System.currentTimeMillis() + 3000
        while (ops.addCount < 1 && System.currentTimeMillis() < submitDeadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        val finishLatch = java.util.concurrent.CountDownLatch(1)
        worker.runFinishGeneration(64, 32) { finishLatch.countDown() }
        val finishDeadline = System.currentTimeMillis() + 5000
        while (finishLatch.count > 0 && System.currentTimeMillis() < finishDeadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        val bgDeadline = System.currentTimeMillis() + 5000
        while (finishLatch.count > 0 && System.currentTimeMillis() < bgDeadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        worker.shutdown()
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        val expectedForeground = listOf(
            "graphReconcile",
            "targetedPhotometric",
            "graphReconcile",
            "conditionalParallax",
            "prepareExport",
            "seamFull"
        )
        val tracked = setOf(
            "graphReconcile",
            "targetedPhotometric",
            "conditionalParallax",
            "prepareExport",
            "seamFull",
            "seamLight"
        )
        assertEquals(expectedForeground, ops.steps.filter { it in tracked })
        assertFalse(ops.steps.contains("global"))
        assertFalse(ops.steps.contains("rebuildFullAlign"))
        executor.shutdown()
        refineExecutor.shutdown()
        intrinsicExecutor.shutdown()
    }

    @Test
    fun runFinishGeneration_usesFullSeamsOnExport() {
        val ops = object : FakeOps() {
            var lightSeamCount = 0
            var fullSeamCount = 0
            override fun refineStoredPoses(): Boolean = true
            override fun rebuildFullAlign(): Boolean = true
            override fun refineGlobalPose(): Boolean = true
            override fun refineIntrinsics(): Boolean = true
            override fun refineSeams(light: Boolean): Boolean {
                if (light) lightSeamCount++ else fullSeamCount++
                return true
            }
            override fun prepareExportEquirect(exportW: Int, exportH: Int): Boolean = true
        }
        val executor = Executors.newSingleThreadExecutor()
        val refineExecutor = Executors.newSingleThreadExecutor()
        val intrinsicExecutor = Executors.newSingleThreadExecutor()
        val worker = SphereStitchWorker(
            operations = ops,
            executor = executor,
            refineExecutor = refineExecutor,
            intrinsicExecutor = intrinsicExecutor
        )
        worker.submit(
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) },
            floatArrayOf(0f, 0f, 0f, 1f)
        )
        val submitDeadline = System.currentTimeMillis() + 3000
        while (ops.addCount < 1 && System.currentTimeMillis() < submitDeadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        val finishLatch = java.util.concurrent.CountDownLatch(1)
        worker.runFinishGeneration(64, 32) { finishLatch.countDown() }
        val finishDeadline = System.currentTimeMillis() + 5000
        while (finishLatch.count > 0 && System.currentTimeMillis() < finishDeadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        worker.shutdown()
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        assertTrue("DONE export must use full seams", ops.fullSeamCount >= 1)
        executor.shutdown()
        refineExecutor.shutdown()
        intrinsicExecutor.shutdown()
    }

    @Test
    fun invalidatePendingRefinement_suppressesStaleRefinePreview() {
        val ops = object : FakeOps() {
            override fun addFrameFast(bitmap: Bitmap, quaternion: FloatArray, overlapGate: Float): Int {
                addCount++
                return STITCH_ACCEPTED
            }
            override fun getLastFrameInfo(): FrameAlignmentInfo? =
                FrameAlignmentInfo(addCount, 10, 100, 1f, 100f, false)
            override fun refineGlobalPose(): Boolean = true
        }
        val executor = Executors.newSingleThreadExecutor()
        val refineExecutor = Executors.newSingleThreadExecutor()
        val worker = SphereStitchWorker(
            operations = ops,
            executor = executor,
            refineExecutor = refineExecutor,
            refineDelayMs = 50L
        )
        var refinedDelivered = false
        worker.onResult = { if (it.refined) refinedDelivered = true }
        val quat = floatArrayOf(0f, 0f, 0f, 1f)
        repeat(2) {
            worker.submit(
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) },
                quat
            )
            val deadline = System.currentTimeMillis() + 3000
            while (ops.addCount < it + 1 && System.currentTimeMillis() < deadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
        }
        worker.invalidatePendingRefinement()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
        refineExecutor.shutdown()
        assertTrue(refineExecutor.awaitTermination(3, TimeUnit.SECONDS))
        assertFalse("Stale refine preview must not fire after invalidate", refinedDelivered)
        executor.shutdown()
        worker.shutdown()
    }

    @Test
    fun preFinish_idleHighCoverage_runsBeforeDone() {
        val ops = object : FakeOps() {
            val steps = mutableListOf<String>()
            override fun getSolidAngleCoverage(): Float = 0.90f
            override fun buildFeatureGraphCache(): Boolean {
                steps.add("graphCache")
                return true
            }
            override fun prepareExportEquirect(exportW: Int, exportH: Int): Boolean {
                steps.add("prepareExport")
                return true
            }
            override fun refineGlobalPose(): Boolean {
                steps.add("global")
                return true
            }
            override fun rebuildFullAlign(): Boolean {
                steps.add("rebuildFullAlign")
                return true
            }
            override fun refineSeams(light: Boolean): Boolean {
                steps.add(if (light) "seamLight" else "seamFull")
                return true
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        val refineExecutor = Executors.newSingleThreadExecutor()
        val preFinishExecutor = Executors.newSingleThreadExecutor()
        val intrinsicExecutor = Executors.newSingleThreadExecutor()
        val worker = SphereStitchWorker(
            operations = ops,
            executor = executor,
            refineExecutor = refineExecutor,
            preFinishExecutor = preFinishExecutor,
            intrinsicExecutor = intrinsicExecutor,
            refineDelayMs = 20L
        )
        worker.onResult = {}
        repeat(3) {
            worker.submit(
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) },
                floatArrayOf(0f, 0f, 0f, 1f)
            )
            val deadline = System.currentTimeMillis() + 3000
            while (ops.addCount < it + 1 && System.currentTimeMillis() < deadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
        }
        val preFinishDeadline = System.currentTimeMillis() + 15000
        while (!ops.steps.contains("graphCache") &&
            System.currentTimeMillis() < preFinishDeadline
        ) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            Thread.sleep(10)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
        Thread.sleep(200)
        preFinishExecutor.shutdown()
        assertTrue(preFinishExecutor.awaitTermination(10, TimeUnit.SECONDS))
        assertTrue(
            "Pre-finish idle should cache feature graph edges",
            ops.steps.contains("graphCache")
        )
        assertFalse(
            "Pre-finish must not run full align rebuilds",
            ops.steps.contains("rebuildFullAlign")
        )
        val rebuildsBeforeFinish = ops.steps.count { it == "rebuildFullAlign" }
        val finishLatch = java.util.concurrent.CountDownLatch(1)
        worker.runFinishGeneration(64, 32) { finishLatch.countDown() }
        val finishDeadline = System.currentTimeMillis() + 5000
        while (finishLatch.count > 0 && System.currentTimeMillis() < finishDeadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        refineExecutor.shutdown()
        intrinsicExecutor.shutdown()
        executor.shutdown()
        worker.shutdown()
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        val rebuildsAfterFinish = ops.steps.count { it == "rebuildFullAlign" }
        assertTrue(
            "DONE should not repeat full align when pre-finish already ran " +
                "(before=$rebuildsBeforeFinish after=$rebuildsAfterFinish)",
            rebuildsAfterFinish == rebuildsBeforeFinish ||
                rebuildsAfterFinish <= rebuildsBeforeFinish + 1
        )
        assertTrue(
            "DONE should prepare export resolution",
            ops.steps.contains("prepareExport")
        )
    }

    @Test
    fun invalidatePendingRefinement_stillDeliversFinishOnComplete() {
        val ops = FakeOps()
        val executor = Executors.newSingleThreadExecutor()
        val refineExecutor = Executors.newSingleThreadExecutor()
        val worker = SphereStitchWorker(
            operations = ops,
            executor = executor,
            refineExecutor = refineExecutor
        )
        worker.submit(
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) },
            floatArrayOf(0f, 0f, 0f, 1f)
        )
        val submitDeadline = System.currentTimeMillis() + 3000
        while (ops.addCount < 1 && System.currentTimeMillis() < submitDeadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        var finish: SphereFinishResult? = null
        worker.runFinishGeneration(64, 32) { result ->
            finish = result
            latch.countDown()
        }
        worker.invalidatePendingRefinement()
        val deadline = System.currentTimeMillis() + 5000
        while (finish == null && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertNotNull("Finish onComplete must fire even when invalidated", finish)
        refineExecutor.shutdown()
        executor.shutdown()
        worker.shutdown()
    }

    @Test
    fun runStitch_failureStillReleasesInFlight() {
        val ops = object : FakeOps() {
            override fun addFrameFast(bitmap: Bitmap, quaternion: FloatArray, overlapGate: Float): Int {
                throw RuntimeException("stitch boom")
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        val worker = SphereStitchWorker(operations = ops, executor = executor)
        val latch = java.util.concurrent.CountDownLatch(1)
        worker.onResult = { latch.countDown() }
        worker.submit(
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) },
            floatArrayOf(0f, 0f, 0f, 1f)
        )
        val deadline = System.currentTimeMillis() + 3000
        while (!worker.awaitStitchIdle(100) && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("Worker must not stay busy after stitch failure", worker.awaitStitchIdle(500))
        executor.shutdown()
        worker.shutdown()
    }

    @Test
    fun runWhenStitchIdle_runsAfterInFlightFrame() {
        val ops = FakeOps()
        val executor = Executors.newSingleThreadExecutor()
        val worker = SphereStitchWorker(operations = ops, executor = executor)
        val idleLatch = java.util.concurrent.CountDownLatch(1)
        worker.submit(
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) },
            floatArrayOf(0f, 0f, 0f, 1f)
        )
        worker.runWhenStitchIdle { idleLatch.countDown() }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue("DONE path must run after the in-flight stitch", idleLatch.await(3, TimeUnit.SECONDS))
        executor.shutdown()
        worker.shutdown()
    }
}
