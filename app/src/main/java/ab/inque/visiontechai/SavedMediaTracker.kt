package ab.inque.visiontechai

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Tracks URIs of photos and videos saved by the app so the in-app gallery
 * can show them without relying on MediaStore query (which can fail on some devices).
 *
 * Line format: type|timestampMillis|uri
 * Legacy format (still readable): type|uri
 */
object SavedMediaTracker {

    private const val FILE_NAME = "saved_media_uris.txt"
    private const val PREFIX_IMAGE = "image"
    private const val PREFIX_VIDEO = "video"
    private const val PREFIX_DOCUMENT = "document"
    private const val PREFIX_PDF = "pdf"
    private val lock = ReentrantLock()

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)

    fun addImage(context: Context, uri: Uri) {
        lock.withLock {
            file(context).appendText("$PREFIX_IMAGE|${System.currentTimeMillis()}|${uri}\n")
        }
    }

    fun addVideo(context: Context, uri: Uri) {
        lock.withLock {
            file(context).appendText("$PREFIX_VIDEO|${System.currentTimeMillis()}|${uri}\n")
        }
    }

    fun addDocument(context: Context, uri: Uri) {
        lock.withLock {
            file(context).appendText("$PREFIX_DOCUMENT|${System.currentTimeMillis()}|${uri}\n")
        }
    }

    fun addPdf(context: Context, uri: Uri) {
        lock.withLock {
            file(context).appendText("$PREFIX_PDF|${System.currentTimeMillis()}|${uri}\n")
        }
    }

    enum class MediaType { IMAGE, VIDEO, DOCUMENT, PDF }

    data class TrackedItem(val mediaType: MediaType, val uri: Uri, val timestampMs: Long)

    fun getAll(context: Context): List<Pair<Boolean, Uri>> {
        return getAllTyped(context).map { (it.mediaType == MediaType.VIDEO) to it.uri }
    }

    fun removeUri(context: Context, uri: Uri) {
        lock.withLock {
            val f = file(context)
            if (!f.exists()) return
            val uriStr = uri.toString()
            val kept = f.readLines().filter { !it.contains(uriStr) }
            f.writeText(kept.joinToString("\n") + if (kept.isNotEmpty()) "\n" else "")
        }
    }

    fun getAllTyped(context: Context): List<TrackedItem> {
        lock.withLock {
            val f = file(context)
            if (!f.exists()) return emptyList()
            return f.readLines()
                .mapNotNull { line -> parseLine(line.trim()) }
                .reversed()
        }
    }

    private fun parseLine(line: String): TrackedItem? {
        if (line.isEmpty()) return null
        val parts = line.split("|")
        if (parts.size < 2) return null

        val typeStr = parts[0]
        val mediaType = when (typeStr) {
            PREFIX_IMAGE -> MediaType.IMAGE
            PREFIX_VIDEO -> MediaType.VIDEO
            PREFIX_DOCUMENT -> MediaType.DOCUMENT
            PREFIX_PDF -> MediaType.PDF
            else -> return null
        }

        return if (parts.size >= 3) {
            val timestamp = parts[1].toLongOrNull() ?: 0L
            val uriStr = parts.subList(2, parts.size).joinToString("|")
            Uri.parse(uriStr)?.let { TrackedItem(mediaType, it, timestamp) }
        } else {
            val uriStr = parts[1]
            Uri.parse(uriStr)?.let { TrackedItem(mediaType, it, 0L) }
        }
    }
}
