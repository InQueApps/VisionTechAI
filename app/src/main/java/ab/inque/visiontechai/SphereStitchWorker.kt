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
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
) {
    private val inFlight = AtomicBoolean(false)
    private val pendingDrops = AtomicInteger(0)

    @Volatile
    private var queuedBitmap: Bitmap? = null

    @Volatile
    private var queuedQuaternion: FloatArray? = null

    var onResult: ((SphereStitchResult) -> Unit)? = null

    val isBusy: Boolean get() = inFlight.get()

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
        executor.execute {
            val start = SystemClock.elapsedRealtime()
            val ranOnMain = Looper.myLooper() == Looper.getMainLooper()
            val accepted = operations.addFrame(bitmap, quaternion)
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
                android.util.Log.i("SphereStitchWorker", "Frame rejected but preview retained (${previewWidth}x$previewHeight)")
            }
            val duration = SystemClock.elapsedRealtime() - start
            val result = SphereStitchResult(
                accepted = accepted,
                coverage = coverage,
                solidAngleCoverage = solidAngle,
                frameInfo = info,
                previewBitmap = previewWithHoles,
                durationMs = duration,
                ranOnMainThread = ranOnMain
            )
            mainHandler.post { onResult?.invoke(result) }
            drainQueueOrRelease()
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
        }
    }

    fun runWhenIdle(onComplete: () -> Unit) {
        executor.execute {
            mainHandler.post(onComplete)
        }
    }

    fun shutdown() {
        synchronized(this) {
            queuedBitmap?.recycle()
            queuedBitmap = null
            queuedQuaternion = null
        }
        executor.shutdown()
    }

    companion object {
        const val PREVIEW_W = 1024
        const val PREVIEW_H = 512

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
