package ab.inque.visiontechai

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.util.Log
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.media.MediaPlayer
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ab.inque.visiontechai.databinding.ActivitySavedMediaBinding
import ab.inque.visiontechai.databinding.ItemSavedMediaBinding
import androidx.activity.result.contract.ActivityResultContracts
import androidx.viewpager2.widget.ViewPager2
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

class SavedMediaActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySavedMediaBinding
    private var currentItems: List<MediaItem> = emptyList()

    enum class MediaType { IMAGE, VIDEO, DOCUMENT }

    private data class MediaItem(
        val id: Long,
        val uri: Uri,
        val mediaType: MediaType,
        val dateAdded: Long,
        val isPdf: Boolean = false
    ) {
        val isVideo get() = mediaType == MediaType.VIDEO
        val isDocument get() = mediaType == MediaType.DOCUMENT
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { it }) {
            refreshMediaList()
        } else {
            showEmptyOrDenied(denied = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySavedMediaBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.toolbar.setTitleTextColor(Color.WHITE)

        binding.recyclerView.layoutManager = GridLayoutManager(this, 3)

        refreshMediaList()
    }

    private fun hasReadMediaPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestReadMediaPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            )
        } else {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    }

    private fun refreshMediaList() {
        val items = loadSavedMedia()
        currentItems = items
        if (items.isEmpty()) {
            showEmptyOrDenied(denied = false)
        } else {
            binding.emptyText.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            binding.recyclerView.adapter = SavedMediaAdapter(
                items,
                onItemClick = { item ->
                    if (item.isPdf) {
                        openPdfExternally(item)
                    } else {
                        val idx = currentItems.indexOf(item)
                        showMediaViewerDialog(if (idx >= 0) idx else 0)
                    }
                },
                formatDate = { item -> formatMediaDate(this, item) },
                onDelete = { item -> confirmDeleteMedia(item) }
            )
        }
    }

    private fun showEmptyOrDenied(denied: Boolean) {
        binding.recyclerView.visibility = View.GONE
        binding.emptyText.visibility = View.VISIBLE
        binding.emptyText.text = if (denied) {
            "Permission needed to show saved photos and videos.\nGrant storage access in app settings."
        } else {
            "No photos or videos yet.\nCapture from the camera view."
        }
    }

    private fun loadSavedMedia(): List<MediaItem> {
        val fromTracker = SavedMediaTracker.getAllTyped(this).map { tracked ->
            val mt = when (tracked.mediaType) {
                SavedMediaTracker.MediaType.VIDEO -> MediaType.VIDEO
                SavedMediaTracker.MediaType.IMAGE -> MediaType.IMAGE
                SavedMediaTracker.MediaType.DOCUMENT -> MediaType.DOCUMENT
                SavedMediaTracker.MediaType.PDF -> MediaType.DOCUMENT
            }
            val dateSeconds = if (tracked.timestampMs > 0) {
                tracked.timestampMs / 1000L
            } else {
                queryDateAddedFromUri(tracked.uri)
            }
            val pdf = tracked.mediaType == SavedMediaTracker.MediaType.PDF ||
                (mt == MediaType.DOCUMENT && resolveIsPdf(tracked.uri))
            MediaItem(0L, tracked.uri, mt, dateSeconds, pdf)
        }

        val fromMediaStore = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            loadFromMediaStoreByNamePrefix()
        } else emptyList()

        val trackerUris = fromTracker.map { it.uri.toString() }.toSet()
        val combined = fromTracker + fromMediaStore.filter { it.uri.toString() !in trackerUris }
        return combined.sortedByDescending { it.dateAdded }
    }

    private fun queryDateAddedFromUri(uri: Uri): Long {
        try {
            contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val dateAdded = cursor.getLong(0)
                    if (dateAdded > 0) return dateAdded

                    val name = cursor.getString(1)
                    if (name != null) {
                        val millis = parseDateFromFilename(name)
                        if (millis != null) return millis / 1000L
                    }
                }
            }
        } catch (_: Exception) { }
        return Long.MAX_VALUE
    }

    private fun loadFromMediaStoreByNamePrefix(): List<MediaItem> {
        val list = mutableListOf<MediaItem>()
        val imageProjection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED
        )
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            imageProjection,
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("${Constants.MEDIA_FILENAME_PREFIX}%"),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val date = cursor.getLong(dateCol)
                val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                list.add(MediaItem(id, uri, MediaType.IMAGE, date))
            }
        }

        val videoProjection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATE_ADDED
        )
        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            videoProjection,
            "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("${Constants.MEDIA_FILENAME_PREFIX}%"),
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val date = cursor.getLong(dateCol)
                val uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
                list.add(MediaItem(id, uri, MediaType.VIDEO, date))
            }
        }

        // Documents: text files from MediaStore.Files
        try {
            val filesProjection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DATE_ADDED,
                MediaStore.Files.FileColumns.MIME_TYPE
            )
            contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                filesProjection,
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.Files.FileColumns.MIME_TYPE} = ?",
                arrayOf("${Constants.MEDIA_FILENAME_PREFIX}TRANS_%", "text/plain"),
                "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val date = cursor.getLong(dateCol)
                    val uri = Uri.withAppendedPath(MediaStore.Files.getContentUri("external"), id.toString())
                    list.add(MediaItem(id, uri, MediaType.DOCUMENT, date, false))
                }
            }
        } catch (_: Exception) { }

        // Documents: PDFs from MediaStore.Downloads
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val dlProjection = arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DATE_ADDED
                )
                contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    dlProjection,
                    "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND ${MediaStore.Downloads.MIME_TYPE} = ?",
                    arrayOf("${Constants.MEDIA_FILENAME_PREFIX}PDF_%", "application/pdf"),
                    "${MediaStore.Downloads.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val date = cursor.getLong(dateCol)
                        val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                        list.add(MediaItem(id, uri, MediaType.DOCUMENT, date, true))
                    }
                }
            } catch (_: Exception) { }
        }

        return list
    }

    private fun is360Photo(item: MediaItem): Boolean {
        if (item.isVideo || item.isDocument) return false
        return try {
            contentResolver.query(
                item.uri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.contains("SPHERE") == true
                } else false
            } ?: false
        } catch (_: Exception) { false }
    }

    private fun isPdfDocument(item: MediaItem): Boolean = item.isPdf

    private fun openPdfExternally(item: MediaItem) {
        try {
            val uri = if (item.uri.scheme == "file") {
                val file = java.io.File(item.uri.path!!)
                androidx.core.content.FileProvider.getUriForFile(
                    this, "$packageName.fileprovider", file
                )
            } else {
                item.uri
            }
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "No PDF viewer found", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("SavedMedia", "Failed to open PDF: ${e.message}", e)
            Toast.makeText(this, "Cannot open PDF: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMediaViewerDialog(startIndex: Int) {
        val items = currentItems
        if (items.isEmpty()) return

        val pagerView = layoutInflater.inflate(R.layout.dialog_media_viewer_pager, null)
        val viewPager = pagerView.findViewById<ViewPager2>(R.id.mediaPager)
        val closeButton = pagerView.findViewById<View>(R.id.closeButton)

        val dialog = AppCompatDialog(this, R.style.MediaViewerDialogTheme).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(pagerView)
            setCancelable(true)
        }

        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
        }

        closeButton.setOnClickListener { dialog.dismiss() }

        val adapter = MediaPagerAdapter(items, dialog)
        viewPager.adapter = adapter
        viewPager.setCurrentItem(startIndex, false)

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                adapter.pauseAllVideos(position)
            }
        })

        dialog.setOnDismissListener { adapter.releaseAll() }
        dialog.show()
    }

    // ── ViewPager adapter for media viewer ───────────────────

    private inner class MediaPagerAdapter(
        private val items: List<MediaItem>,
        private val dialog: AppCompatDialog
    ) : RecyclerView.Adapter<MediaPagerAdapter.PageHolder>() {

        private val activeVideoHolders = mutableMapOf<Int, PageHolder>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val page = LayoutInflater.from(parent.context)
                .inflate(R.layout.dialog_media_viewer, parent, false)
            return PageHolder(page)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        override fun onViewRecycled(holder: PageHolder) {
            holder.cleanup()
            activeVideoHolders.values.remove(holder)
        }

        fun pauseAllVideos(exceptPosition: Int) {
            activeVideoHolders.forEach { (pos, holder) ->
                if (pos != exceptPosition) holder.pauseVideo()
            }
        }

        fun releaseAll() {
            activeVideoHolders.values.forEach { it.cleanup() }
            activeVideoHolders.clear()
        }

        inner class PageHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private var mediaPlayer: MediaPlayer? = null
            private var creatingPlayer: MediaPlayer? = null
            private var videoPfd: ParcelFileDescriptor? = null
            private var handler: Handler? = null
            private var seekBarUpdater: Runnable? = null

            fun bind(item: MediaItem) {
                val photoView = itemView.findViewById<ImageView>(R.id.photoView)
                val videoTextureView = itemView.findViewById<TextureView>(R.id.videoTextureView)
                val sphereViewer = itemView.findViewById<SphereViewerView>(R.id.sphereViewer)
                val textScrollView = itemView.findViewById<ScrollView>(R.id.textScrollView)
                val textContentView = itemView.findViewById<TextView>(R.id.textContentView)

                photoView.visibility = View.GONE
                videoTextureView.visibility = View.GONE
                sphereViewer.visibility = View.GONE
                textScrollView.visibility = View.GONE
                itemView.findViewById<View>(R.id.videoControls).visibility = View.GONE

                when {
                    item.isPdf -> {
                        openPdfExternally(item)
                    }
                    item.isDocument -> setupTextViewer(textScrollView, textContentView, item)
                    item.isVideo -> {
                        activeVideoHolders[bindingAdapterPosition] = this
                        setupVideoInPage(itemView, videoTextureView, item)
                    }
                    is360Photo(item) -> setup360Viewer(sphereViewer, item, dialog)
                    else -> setupPhotoViewer(itemView, photoView, item)
                }
            }

            private fun setupVideoInPage(pageView: View, textureView: TextureView, item: MediaItem) {
                val videoControls = pageView.findViewById<View>(R.id.videoControls)
                val videoSeekBar = pageView.findViewById<SeekBar>(R.id.videoSeekBar)
                videoControls.visibility = View.VISIBLE
                textureView.visibility = View.VISIBLE

                var isPlaying = true
                var userSeeking = false
                val h = Handler(Looper.getMainLooper())
                handler = h

                val updateSeekBar = object : Runnable {
                    override fun run() {
                        val mp = mediaPlayer ?: return
                        if (!userSeeking && mp.duration > 0) {
                            videoSeekBar.progress = (1000 * mp.currentPosition) / mp.duration
                        }
                        h.postDelayed(this, 200)
                    }
                }
                seekBarUpdater = updateSeekBar

                val displayMatrix = Matrix()
                val baseMatrix = Matrix()
                var currentScale = 1f

                textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h2: Int) {
                        val surface = Surface(st)
                        val mp = MediaPlayer().apply {
                            setSurface(surface)
                            setOnErrorListener { _, what, extra ->
                                Toast.makeText(this@SavedMediaActivity,
                                    "Can't play video (err: $what, $extra)", Toast.LENGTH_LONG).show()
                                true
                            }
                            setOnPreparedListener { m ->
                                creatingPlayer = null
                                mediaPlayer = m
                                videoSeekBar.max = 1000
                                videoSeekBar.progress = 0
                                m.start()
                                isPlaying = true
                                h.post(updateSeekBar)

                                val vw = textureView.width.toFloat()
                                val vh = textureView.height.toFloat()
                                val videoW = m.videoWidth.toFloat()
                                val videoH = m.videoHeight.toFloat()
                                if (vw > 0 && vh > 0 && videoW > 0 && videoH > 0) {
                                    val sx = vw / videoW; val sy = vh / videoH
                                    val fitScale = min(sx, sy)
                                    val cx = fitScale / sx; val cy = fitScale / sy
                                    baseMatrix.setScale(cx, cy)
                                    baseMatrix.postTranslate(vw * (1 - cx) / 2f, vh * (1 - cy) / 2f)
                                    displayMatrix.set(baseMatrix)
                                    textureView.setTransform(displayMatrix)
                                }
                            }
                            setOnCompletionListener { m ->
                                m.seekTo(0); videoSeekBar.progress = 0; isPlaying = false
                            }
                        }
                        creatingPlayer = mp
                        try {
                            val pfd = contentResolver.openFileDescriptor(item.uri, "r")
                                ?: throw IllegalStateException("Could not open video URI")
                            videoPfd = pfd
                            mp.setDataSource(pfd.fileDescriptor)
                            mp.prepareAsync()
                        } catch (e: Exception) {
                            Toast.makeText(this@SavedMediaActivity,
                                "Can't open video: ${e.message}", Toast.LENGTH_LONG).show()
                            cleanup()
                        }
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { cleanup(); return true }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }

                val scaleDetector = ScaleGestureDetector(this@SavedMediaActivity,
                    object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        override fun onScale(detector: ScaleGestureDetector): Boolean {
                            var sf = detector.scaleFactor
                            val newScale = (currentScale * sf).coerceIn(1f, 5f)
                            sf = newScale / currentScale; currentScale = newScale
                            displayMatrix.postScale(sf, sf, detector.focusX, detector.focusY)
                            textureView.setTransform(displayMatrix)
                            return true
                        }
                    })

                val gestureDetector = GestureDetector(this@SavedMediaActivity,
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            val mp = mediaPlayer ?: return false
                            if (isPlaying) { mp.pause(); isPlaying = false }
                            else { mp.start(); isPlaying = true }
                            return true
                        }
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            if (currentScale > 1.5f) { displayMatrix.set(baseMatrix); currentScale = 1f }
                            else { val sf = 2.5f / currentScale; currentScale = 2.5f; displayMatrix.postScale(sf, sf, e.x, e.y) }
                            textureView.setTransform(displayMatrix)
                            return true
                        }
                    })

                textureView.setOnTouchListener { _, event ->
                    scaleDetector.onTouchEvent(event); gestureDetector.onTouchEvent(event); true
                }

                videoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(sb: SeekBar?) { userSeeking = true }
                    override fun onStopTrackingTouch(sb: SeekBar?) { userSeeking = false }
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        val mp = mediaPlayer ?: return
                        if (fromUser && mp.duration > 0) mp.seekTo((progress * mp.duration) / 1000)
                    }
                })
            }

            fun pauseVideo() {
                try { mediaPlayer?.pause() } catch (_: Exception) {}
            }

            fun cleanup() {
                seekBarUpdater?.let { handler?.removeCallbacks(it) }
                (mediaPlayer ?: creatingPlayer)?.apply {
                    try { release() } catch (_: Exception) {}
                }
                mediaPlayer = null; creatingPlayer = null
                try { videoPfd?.close() } catch (_: Exception) {}
                videoPfd = null
            }
        }
    }

    private fun setupTextViewer(scrollView: ScrollView, textView: TextView, item: MediaItem) {
        scrollView.visibility = View.VISIBLE
        val content = try {
            contentResolver.openInputStream(item.uri)?.bufferedReader()?.readText() ?: "(empty file)"
        } catch (e: Exception) {
            "(Could not read file: ${e.message})"
        }
        textView.text = content
    }

    private fun setupPhotoViewer(dialogView: View, photoView: ImageView, item: MediaItem) {
        dialogView.findViewById<View>(R.id.videoControls).visibility = View.GONE
        photoView.visibility = View.VISIBLE
        photoView.setImageURI(item.uri)

        val displayMatrix = Matrix()
        val baseMatrix = Matrix()
        var currentScale = 1f

        photoView.post {
            val drawable = photoView.drawable ?: return@post
            val iw = drawable.intrinsicWidth.toFloat()
            val ih = drawable.intrinsicHeight.toFloat()
            val vw = photoView.width.toFloat()
            val vh = photoView.height.toFloat()
            if (iw == 0f || ih == 0f) return@post
            val scale = min(vw / iw, vh / ih)
            baseMatrix.setScale(scale, scale)
            baseMatrix.postTranslate((vw - iw * scale) / 2f, (vh - ih * scale) / 2f)
            displayMatrix.set(baseMatrix)
            photoView.imageMatrix = displayMatrix
        }

        val scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    var sf = detector.scaleFactor
                    val newScale = (currentScale * sf).coerceIn(1f, 8f)
                    sf = newScale / currentScale
                    currentScale = newScale
                    displayMatrix.postScale(sf, sf, detector.focusX, detector.focusY)
                    photoView.imageMatrix = displayMatrix
                    return true
                }
            })

        val gestureDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (currentScale > 1.5f) {
                        displayMatrix.set(baseMatrix)
                        currentScale = 1f
                    } else {
                        val sf = 3f / currentScale
                        currentScale = 3f
                        displayMatrix.postScale(sf, sf, e.x, e.y)
                    }
                    photoView.imageMatrix = displayMatrix
                    return true
                }
            })

        photoView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun setup360Viewer(
        sphereViewer: SphereViewerView,
        item: MediaItem,
        dialog: AppCompatDialog
    ) {
        sphereViewer.visibility = View.VISIBLE

        Thread {
            val bitmap = try {
                contentResolver.openInputStream(item.uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            } catch (_: Exception) { null }

            if (bitmap != null && SphereNative.isAvailable()) {
                SphereNative.loadEquirect(bitmap)
                bitmap.recycle()
                runOnUiThread { sphereViewer.setLookDirection(0f, 0f, 90f) }
            } else {
                runOnUiThread {
                    Toast.makeText(this, "Cannot load 360 photo", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()

        dialog.setOnDismissListener {
            sphereViewer.cleanup()
        }
    }

    private fun confirmDeleteMedia(item: MediaItem) {
        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("Delete this file?")
            .setMessage("This will permanently remove the file from your device.")
            .setPositiveButton("Delete") { _, _ -> deleteMedia(item) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteMedia(item: MediaItem) {
        try {
            contentResolver.delete(item.uri, null, null)
        } catch (_: Exception) { }
        SavedMediaTracker.removeUri(this, item.uri)
        Toast.makeText(this, "File deleted", Toast.LENGTH_SHORT).show()
        refreshMediaList()
    }

    private fun resolveMediaDate(item: MediaItem): Long? {
        if (item.dateAdded != Long.MAX_VALUE && item.dateAdded > 0) return item.dateAdded * 1000L

        // Try querying DATE_ADDED and DISPLAY_NAME directly from the content URI
        return try {
            val cursor = contentResolver.query(
                item.uri,
                arrayOf(MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DISPLAY_NAME),
                null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val dateAdded = it.getLong(0)
                    if (dateAdded > 0) return dateAdded * 1000L

                    val name = it.getString(1) ?: return@use null
                    parseDateFromFilename(name)
                } else null
            }
        } catch (_: Exception) {
            // For file:// URIs, try extracting date from the path
            try {
                val name = item.uri.lastPathSegment ?: return null
                parseDateFromFilename(name)
            } catch (_: Exception) { null }
        }
    }

    private fun parseDateFromFilename(name: String): Long? {
        val dateStr = name
            .removePrefix(Constants.MEDIA_FILENAME_PREFIX)
            .removeSuffix(".jpg").removeSuffix(".mp4").removeSuffix(".txt").removeSuffix(".pdf")
            .removePrefix("SPHERE_").removePrefix("TRANS_").removePrefix("PDF_")
        return SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).parse(dateStr)?.time
    }

    private val displayDateFormat = SimpleDateFormat("MMM dd, yyyy  HH:mm", Locale.US)

    private fun formatMediaDate(context: android.content.Context, item: MediaItem): String {
        val millis = resolveMediaDate(item) ?: return ""
        return displayDateFormat.format(Date(millis))
    }

    // ── Adapter ─────────────────────────────────────────────

    private fun resolveIsPdf(uri: Uri): Boolean {
        try {
            val mime = contentResolver.getType(uri)
            if (mime != null) return mime.equals("application/pdf", ignoreCase = true)
        } catch (_: Exception) { }
        try {
            contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0) ?: return false
                    return name.endsWith(".pdf", ignoreCase = true)
                }
            }
        } catch (_: Exception) { }
        return false
    }

    private class SavedMediaAdapter(
        private val items: List<MediaItem>,
        private val onItemClick: (MediaItem) -> Unit,
        private val formatDate: (MediaItem) -> String,
        private val onDelete: (MediaItem) -> Unit
    ) : RecyclerView.Adapter<SavedMediaAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = ItemSavedMediaBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(private val binding: ItemSavedMediaBinding) :
            RecyclerView.ViewHolder(binding.root) {

            init {
                binding.root.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onItemClick(items[pos])
                }
                binding.deleteButton.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onDelete(items[pos])
                }
            }

            fun bind(item: MediaItem) {
                binding.videoIcon.visibility = if (item.isVideo) View.VISIBLE else View.GONE
                binding.textIcon.visibility = if (item.isDocument) View.VISIBLE else View.GONE
                if (item.isDocument) {
                    binding.textIcon.text = if (item.isPdf) "PDF" else "TXT"
                }

                when {
                    item.isDocument -> {
                        binding.thumbnail.setImageResource(0)
                        binding.thumbnail.setBackgroundColor(Color.parseColor("#1A1035"))
                    }
                    item.isVideo -> {
                        binding.thumbnail.setBackgroundColor(Color.TRANSPARENT)
                        val thumb = binding.root.context.contentResolver.loadVideoThumbnail(item.uri)
                        binding.thumbnail.setImageBitmap(thumb)
                    }
                    else -> {
                        binding.thumbnail.setBackgroundColor(Color.TRANSPARENT)
                        binding.thumbnail.setImageURI(null)
                        binding.thumbnail.setImageURI(item.uri)
                    }
                }

                val dateText = formatDate(item)
                binding.dateLabel.text = dateText
                binding.dateLabel.visibility = if (dateText.isNotEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
}

private fun android.content.ContentResolver.loadVideoThumbnail(uri: Uri): Bitmap? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        loadThumbnail(uri, android.util.Size(256, 256), null)
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Video.Thumbnails.getThumbnail(
            this,
            ContentUris.parseId(uri),
            MediaStore.Video.Thumbnails.MINI_KIND,
            null
        )
    }
}
