package ab.inque.visiontechai

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Tracks URIs of photos and videos saved by the app so the in-app gallery
 * can show them without relying on MediaStore query (which can fail on some devices).
 */
object SavedMediaTracker {

    private const val FILE_NAME = "saved_media_uris.txt"
    private const val PREFIX_IMAGE = "image|"
    private const val PREFIX_VIDEO = "video|"
    private val lock = ReentrantLock()

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)

    fun addImage(context: Context, uri: Uri) {
        lock.withLock {
            file(context).appendText("$PREFIX_IMAGE${uri}\n")
        }
    }

    fun addVideo(context: Context, uri: Uri) {
        lock.withLock {
            file(context).appendText("$PREFIX_VIDEO${uri}\n")
        }
    }

    /**
     * Returns list of (isVideo, uri) in order (newest last in file = append order).
     */
    fun getAll(context: Context): List<Pair<Boolean, Uri>> {
        lock.withLock {
            val f = file(context)
            if (!f.exists()) return emptyList()
            return f.readLines()
                .mapNotNull { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) return@mapNotNull null
                    when {
                        trimmed.startsWith(PREFIX_VIDEO) -> {
                            val uriStr = trimmed.removePrefix(PREFIX_VIDEO).trim()
                            Uri.parse(uriStr)?.let { true to it }
                        }
                        trimmed.startsWith(PREFIX_IMAGE) -> {
                            val uriStr = trimmed.removePrefix(PREFIX_IMAGE).trim()
                            Uri.parse(uriStr)?.let { false to it }
                        }
                        else -> null
                    }
                }
                .reversed() // newest first
        }
    }
}
