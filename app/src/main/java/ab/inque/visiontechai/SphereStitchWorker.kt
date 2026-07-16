package ab.inque.visiontechai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class SphereStitchResult(
    val accepted: Boolean,
    val coverage: Float,
    val solidAngleCoverage: Float,
    val frameInfo: FrameAlignmentInfo?,
    val previewBitmap: Bitmap?,
    val durationMs: Long,
    val ranOnMainThread: Boolean,
    val rejectedForOverlap: Boolean = false,
    val refined: Boolean = false
)

/** Result of final panorama generation (refine + seam + full equirect). */
data class SphereFinishResult(
    val equirect: Bitmap?,
    val durationMs: Long,
    val ranOnMainThread: Boolean
)

/**
 * Runs [SphereStitchOperations.addFrame] off the UI thread with bounded back-pressure.
 * Queues at most one pending frame while busy (replaces older queued frame).
 * Never recycles the caller's [Bitmap].
 */
class SphereStitchWorker(
    private val operations: SphereStitchOperations = SphereNativeOperations,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val previewWidth: Int = PREVIEW_W,
    private val previewHeight: Int = PREVIEW_H,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val refineExecutor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val overlapGate: Float = DEFAULT_OVERLAP_GATE,
    private val refineDelayMs: Long = DEFAULT_REFINE_DELAY_MS
) {
    private val inFlight = AtomicBoolean(false)
    private val pendingDrops = AtomicInteger(0)

    @Volatile
    private var queuedBitmap: Bitmap? = null

    @Volatile
    private var queuedQuaternion: FloatArray? = null

    var onResult: ((SphereStitchResult) -> Unit)? = null

    private val refineRunnable = Runnable { runRefine() }

    @Volatile
    private var isShutdown = false

    private val pendingIdleCallbacks = mutableListOf<() -> Unit>()

    val isBusy: Boolean get() = inFlight.get()

    /** Submit a task to an executor, ignoring rejection after shutdown. */
    private fun safeExecute(executor: ExecutorService, block: () -> Unit) {
        if (isShutdown) return
        try {
            executor.execute(block)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Executor terminated between the check and submit; drop silently.
        }
    }

    val droppedWhileBusy: Int get() = pendingDrops.get()

    fun submit(bitmap: Bitmap, quaternion: FloatArray) {
        val copy = copyToArgb8888(bitmap)
        if (!inFlight.compareAndSet(false, true)) {
            synchronized(this) {
                queuedBitmap?.recycle()
                queuedBitmap = copy
                queuedQuaternion = quaternion.copyOf()
            }
            pendingDrops.incrementAndGet()
            return
        }
        runStitch(copy, quaternion)
    }

    private fun runStitch(bitmap: Bitmap, quaternion: FloatArray) {
        safeExecute(executor) {
            val start = SystemClock.elapsedRealtime()
            val ranOnMain = Looper.myLooper() == Looper.getMainLooper()
            // Fast path: splat immediately using raw IMU orientation, gated by overlap.
            val status = operations.addFrameFast(bitmap, quaternion, overlapGate)
            val accepted = status == STITCH_ACCEPTED
            val lowOverlap = status == STITCH_LOW_OVERLAP
            val coverage = operations.getCoverage()
            val solidAngle = operations.getSolidAngleCoverage()
            val info = operations.getLastFrameInfo()
            val preview = operations.getEquirectBitmap(previewWidth, previewHeight)
            val holeMask = operations.getHoleMaskBitmap(previewWidth, previewHeight)
            val previewWithHoles = SphereHoleOverlay.compositePreview(preview, holeMask)
                ?: preview?.copy(Bitmap.Config.ARGB_8888, false)
            bitmap.recycle()
            if (previewWithHoles == null) {
                android.util.Log.e(
                    "SphereStitchWorker",
                    "No preview bitmap after stitch (accepted=$accepted, native=${operations === SphereNativeOperations})"
                )
            } else if (!accepted) {
                android.util.Log.i("SphereStitchWorker", "Frame not merged (lowOverlap=$lowOverlap); preview retained")
            }
            val duration = SystemClock.elapsedRealtime() - start
            val result = SphereStitchResult(
                accepted = accepted,
                coverage = coverage,
                solidAngleCoverage = solidAngle,
                frameInfo = info,
                previewBitmap = previewWithHoles,
                durationMs = duration,
                ranOnMainThread = ranOnMain,
                rejectedForOverlap = lowOverlap
            )
            // Settle in-flight state (and relaunch any queued frame) BEFORE
            // delivering the result, so an observer that submits a new frame in
            // its result callback isn't dropped by a stale in-flight flag.
            //
            // Refinement is scheduled ONLY on the idle debounce — never mid-burst.
            // While the user is actively capturing (frames arriving faster than
            // refineDelayMs), the debounce keeps resetting and the expensive
            // full re-align/rebuild is deferred until they pause. This is what
            // keeps per-tap latency low (measured: removes multi-second stalls).
            if (accepted) scheduleRefine()
            drainQueueOrRelease()
            mainHandler.post { onResult?.invoke(result) }
        }
    }

    /** (Re)arm the debounced background refinement timer. */
    private fun scheduleRefine() {
        mainHandler.removeCallbacks(refineRunnable)
        mainHandler.postDelayed(refineRunnable, refineDelayMs)
    }

    private fun runRefine() {
        safeExecute(refineExecutor) {
            val start = SystemClock.elapsedRealtime()
            val changed = operations.refinePending()
            if (!changed) return@safeExecute
            val coverage = operations.getCoverage()
            val solidAngle = operations.getSolidAngleCoverage()
            val info = operations.getLastFrameInfo()
            val preview = operations.getEquirectBitmap(previewWidth, previewHeight)
            val holeMask = operations.getHoleMaskBitmap(previewWidth, previewHeight)
            val previewWithHoles = SphereHoleOverlay.compositePreview(preview, holeMask)
                ?: preview?.copy(Bitmap.Config.ARGB_8888, false)
            val result = SphereStitchResult(
                accepted = false,
                coverage = coverage,
                solidAngleCoverage = solidAngle,
                frameInfo = info,
                previewBitmap = previewWithHoles,
                durationMs = SystemClock.elapsedRealtime() - start,
                ranOnMainThread = false,
                refined = true
            )
            mainHandler.post { onResult?.invoke(result) }
        }
    }

    private fun drainQueueOrRelease() {
        val nextBitmap: Bitmap?
        val nextQuat: FloatArray?
        synchronized(this) {
            nextBitmap = queuedBitmap
            nextQuat = queuedQuaternion
            queuedBitmap = null
            queuedQuaternion = null
        }
        if (nextBitmap != null && nextQuat != null) {
            runStitch(nextBitmap, nextQuat)
        } else {
            inFlight.set(false)
            dispatchStitchIdleCallbacks()
        }
    }

    private fun dispatchStitchIdleCallbacks() {
        val callbacks = synchronized(pendingIdleCallbacks) {
            if (pendingIdleCallbacks.isEmpty()) return
            pendingIdleCallbacks.toList().also { pendingIdleCallbacks.clear() }
        }
        callbacks.forEach { it() }
    }

    /**
     * Runs [block] on the stitch executor once no frame is in flight and the
     * one-slot queue is empty.
     */
    fun runWhenStitchIdle(block: () -> Unit) {
        val idleNow = !inFlight.get() && synchronized(this) { queuedBitmap == null }
        if (idleNow) {
            safeExecute(executor, block)
            return
        }
        synchronized(pendingIdleCallbacks) {
            pendingIdleCallbacks.add { safeExecute(executor, block) }
        }
    }

    /**
     * Final panorama generation: refine + seam blend + full equirect, entirely
     * off the UI thread. [onComplete] is invoked on the main thread.
     */
    fun runFinishGeneration(eqW: Int, eqH: Int, onComplete: (SphereFinishResult) -> Unit) {
        mainHandler.removeCallbacks(refineRunnable)
        runWhenStitchIdle {
            safeExecute(refineExecutor) {
                val start = SystemClock.elapsedRealtime()
                val ranOnMain = Looper.myLooper() == Looper.getMainLooper()
                operations.refinePending()
                val equirect = operations.getEquirectBitmap(eqW, eqH)
                val result = SphereFinishResult(
                    equirect = equirect,
                    durationMs = SystemClock.elapsedRealtime() - start,
                    ranOnMainThread = ranOnMain
                )
                mainHandler.post { onComplete(result) }
            }
        }
    }

    /** @deprecated Use [runWhenStitchIdle] or [runFinishGeneration]. */
    fun runWhenIdle(onComplete: () -> Unit) {
        runWhenStitchIdle { mainHandler.post(onComplete) }
    }

    fun shutdown() {
        isShutdown = true
        mainHandler.removeCallbacks(refineRunnable)
        synchronized(pendingIdleCallbacks) { pendingIdleCallbacks.clear() }
        synchronized(this) {
            queuedBitmap?.recycle()
            queuedBitmap = null
            queuedQuaternion = null
        }
        executor.shutdown()
        refineExecutor.shutdown()
    }

    companion object {
        const val PREVIEW_W = 1024
        const val PREVIEW_H = 512

        /** Minimum edge-overlap fraction required to merge a frame. */
        const val DEFAULT_OVERLAP_GATE = 0.20f

        /**
         * Idle delay before background refinement kicks in. Refine only runs
         * once frames stop arriving for this long, keeping it off the capture
         * critical path entirely.
         */
        const val DEFAULT_REFINE_DELAY_MS = 900L

        internal fun copyToArgb8888(bitmap: Bitmap): Bitmap {
            if (bitmap.config == Bitmap.Config.ARGB_8888 && !bitmap.isRecycled) {
                return bitmap.copy(Bitmap.Config.ARGB_8888, false)
            }
            val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(bitmap, 0f, 0f, null)
            return out
        }
    }
}
