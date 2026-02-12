package ab.inque.visiontechai

import android.content.ContentResolver
import android.content.ContentValues
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records video by encoding a sequence of processed bitmaps (e.g. from vision filters) to MP4.
 * No audio. Writes to a temp file first, then copies to MediaStore so saving works reliably
 * (direct MediaStore PFD can fail on some devices with status 0x80000000).
 */
class ProcessedVideoRecorder(
    private val contentResolver: ContentResolver,
    private val contentValues: ContentValues,
    private val width: Int,
    private val height: Int,
    private val targetFps: Int = 30,
    private val bitRate: Int = 4_000_000,
    private val tempFile: File
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var encoder: MediaCodec? = null
    /** When non-null, we use Surface input (draw bitmap with GL); encoder then gets same colors as display. */
    private var codecInputSurface: CodecInputSurface? = null
    private var muxer: MediaMuxer? = null
    private var muxerTrackIndex = -1
    private var muxerStarted = false
    private var startTimeNs = 0L
    private var frameCount = 0
    private var outputUri: Uri? = null
    private val stopped = AtomicBoolean(false)

    /**
     * @param onReady called when encoder and muxer are ready (call from any thread; UI should set isRecording here)
     * @param onFailed called if start fails (e.g. codec/muxer error); run UI updates on main thread
     */
    fun start(onReady: () -> Unit, onFailed: (() -> Unit)? = null) {
        executor.execute {
            try {
                tempFile.parentFile?.mkdirs()
                // Use Surface input so we draw the same bitmap with OpenGL → encoder gets exact on-screen colors.
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                        setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                    }
                }
                val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                // createInputSurface() must be called after configure() and before start()
                val inputSurface = codec.createInputSurface()
                val codecSurface = CodecInputSurface(inputSurface, width, height)
                codec.start()
                encoder = codec
                codecInputSurface = codecSurface
                muxer = MediaMuxer(tempFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                startTimeNs = System.nanoTime()
                stopped.set(false)
                Log.d(TAG, "Using Surface input for recording (colors match display)")
                onReady()
            } catch (e: Exception) {
                Log.e(TAG, "ProcessedVideoRecorder start failed: ${e.message}", e)
                releaseQuietly()
                onFailed?.invoke()
            }
        }
    }

    /**
     * Call from the thread that receives pipeline results. Bitmap is copied internally so the caller can reuse it.
     * Bitmap is scaled to [width]x[height] if dimensions differ. Drawn to encoder Surface so colors match display.
     */
    fun addFrame(bitmap: android.graphics.Bitmap) {
        if (stopped.get()) return
        val surface = codecInputSurface ?: return
        executor.execute {
            if (stopped.get()) return@execute
            try {
                val src = if (bitmap.width != width || bitmap.height != height) {
                    android.graphics.Bitmap.createScaledBitmap(bitmap, width, height, true)
                } else {
                    bitmap.copy(bitmap.config ?: android.graphics.Bitmap.Config.ARGB_8888, false)
                }
                if (src == null) return@execute
                drainEncoder(false) // drain before submitting so input doesn't fill and block
                surface.makeCurrent()
                surface.drawBitmap(src)
                if (src !== bitmap) src.recycle()
                val ptsNsec = System.nanoTime() - startTimeNs
                surface.setPresentationTime(ptsNsec)
                surface.swapBuffers()
                frameCount++
            } catch (e: Exception) {
                Log.e(TAG, "addFrame failed", e)
            }
        }
    }

    fun stop(callback: (Uri?) -> Unit) {
        stopped.set(true)
        executor.execute {
            try {
                val codec = encoder ?: run {
                    cleanupTempFile()
                    callback(null)
                    return@execute
                }
                codec.signalEndOfInputStream()
                drainEncoder(true)
                releaseQuietly()
                val uri = if (frameCount > 0 && tempFile.exists()) {
                    copyTempToMediaStore()
                } else {
                    null
                }
                cleanupTempFile()
                Log.d(TAG, "Processed video stop: frameCount=$frameCount, uri=${uri != null}")
                callback(uri)
            } catch (e: Exception) {
                Log.e(TAG, "ProcessedVideoRecorder stop failed", e)
                releaseQuietly()
                cleanupTempFile()
                callback(null)
            }
        }
    }

    private fun copyTempToMediaStore(): Uri? {
        return try {
            if (!tempFile.exists() || tempFile.length() == 0L) {
                Log.w(TAG, "Copy temp to MediaStore: temp file missing or empty (frames=$frameCount)")
                return null
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: run {
                    Log.e(TAG, "Copy temp to MediaStore: insert returned null")
                    return null
                }
            contentResolver.openOutputStream(uri)?.use { out: OutputStream ->
                FileInputStream(tempFile).use { inp ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (inp.read(buf).also { n = it } > 0) out.write(buf, 0, n)
                }
            } ?: run {
                Log.e(TAG, "Copy temp to MediaStore: openOutputStream returned null")
                return null
            }
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Copy temp to MediaStore failed: ${e.message}", e)
            null
        }
    }

    private fun cleanupTempFile() {
        try {
            if (tempFile.exists()) tempFile.delete()
        } catch (_: Exception) { }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val codec = encoder ?: return
        val mux = muxer ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) break
                    val newFormat = codec.outputFormat
                    muxerTrackIndex = mux.addTrack(newFormat)
                    mux.start()
                    muxerStarted = true
                }
                outputBufferIndex >= 0 -> {
                    if (!muxerStarted) break
                    val buffer = codec.getOutputBuffer(outputBufferIndex) ?: break
                    if (bufferInfo.size > 0) {
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        mux.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
                outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) break else { /* retry */ }
                else -> break
            }
        }
    }

    private fun releaseQuietly() {
        try {
            codecInputSurface?.release()
        } catch (_: Exception) { }
        codecInputSurface = null
        try {
            encoder?.stop()
            encoder?.release()
        } catch (_: Exception) { }
        encoder = null
        try {
            if (muxerStarted) muxer?.stop()
            muxer?.release()
        } catch (_: Exception) { }
        muxer = null
        muxerStarted = false
    }

    companion object {
        private const val TAG = "ProcessedVideoRecorder"
        private const val TIMEOUT_US = 10_000L
    }
}
