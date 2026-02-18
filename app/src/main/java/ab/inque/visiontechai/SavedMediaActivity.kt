package ab.inque.visiontechai

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
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
import android.widget.SeekBar
import android.widget.Toast
import android.media.MediaPlayer
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ab.inque.visiontechai.databinding.ActivitySavedMediaBinding
import ab.inque.visiontechai.databinding.ItemSavedMediaBinding
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.math.min

class SavedMediaActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySavedMediaBinding

    private data class MediaItem(
        val id: Long,
        val uri: Uri,
        val isVideo: Boolean,
        val dateAdded: Long
    )

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
        binding.toolbar.setTitleTextColor(android.graphics.Color.WHITE)

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
        if (items.isEmpty()) {
            showEmptyOrDenied(denied = false)
        } else {
            binding.emptyText.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            binding.recyclerView.adapter = SavedMediaAdapter(items) { item -> openMedia(item) }
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
        val fromTracker = SavedMediaTracker.getAll(this).mapIndexed { index, (isVideo, uri) ->
            MediaItem(index.toLong(), uri, isVideo, Long.MAX_VALUE)
        }
        val trackerUris = fromTracker.map { it.uri.toString() }.toSet()

        val fromMediaStore = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            loadFromMediaStoreByNamePrefix()
        } else emptyList()

        val combined = fromTracker + fromMediaStore.filter { it.uri.toString() !in trackerUris }
        return combined.sortedByDescending { it.dateAdded }
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
                list.add(MediaItem(id, uri, false, date))
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
                list.add(MediaItem(id, uri, true, date))
            }
        }

        return list
    }

    private fun openMedia(item: MediaItem) {
        showMediaViewerDialog(item)
    }

    // ── Detect 360 photos by checking filename for "SPHERE" ──

    private fun is360Photo(item: MediaItem): Boolean {
        if (item.isVideo) return false
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

    // ── Media viewer dialog ─────────────────────────────────

    private fun showMediaViewerDialog(item: MediaItem) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_media_viewer, null)
        val photoView = dialogView.findViewById<ImageView>(R.id.photoView)
        val videoTextureView = dialogView.findViewById<TextureView>(R.id.videoTextureView)
        val sphereViewer = dialogView.findViewById<SphereViewerView>(R.id.sphereViewer)
        val closeButton = dialogView.findViewById<View>(R.id.closeButton)

        val dialog = AppCompatDialog(this, R.style.MediaViewerDialogTheme).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(dialogView)
            setCancelable(true)
        }

        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
        }

        closeButton.setOnClickListener { dialog.dismiss() }

        when {
            item.isVideo -> setupVideoViewer(dialogView, videoTextureView, item, dialog)
            is360Photo(item) -> setup360Viewer(sphereViewer, item, dialog)
            else -> setupPhotoViewer(dialogView, photoView, item)
        }

        dialog.show()
    }

    // ── Photo viewer with pinch-to-zoom and pan ──

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
                override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                    if (scaleDetector.isInProgress) return false
                    displayMatrix.postTranslate(-dx, -dy)
                    photoView.imageMatrix = displayMatrix
                    return true
                }

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

    // ── Video viewer with TextureView + pinch-to-zoom ──

    private fun setupVideoViewer(
        dialogView: View,
        textureView: TextureView,
        item: MediaItem,
        dialog: AppCompatDialog
    ) {
        val videoControls = dialogView.findViewById<View>(R.id.videoControls)
        val videoSeekBar = dialogView.findViewById<SeekBar>(R.id.videoSeekBar)
        videoControls.visibility = View.VISIBLE
        textureView.visibility = View.VISIBLE

        var mediaPlayer: MediaPlayer? = null
        var creatingPlayer: MediaPlayer? = null
        var videoPfd: ParcelFileDescriptor? = null
        var isPlaying = true
        var userSeeking = false
        val handler = Handler(Looper.getMainLooper())

        val displayMatrix = Matrix()
        val baseMatrix = Matrix()
        var currentScale = 1f

        val updateSeekBar = object : Runnable {
            override fun run() {
                val mp = mediaPlayer ?: return
                if (!userSeeking && mp.duration > 0) {
                    videoSeekBar.progress = (1000 * mp.currentPosition) / mp.duration
                }
                handler.postDelayed(this, 200)
            }
        }

        fun releasePlayer() {
            (mediaPlayer ?: creatingPlayer)?.apply {
                try { release() } catch (_: Exception) { }
            }
            mediaPlayer = null
            creatingPlayer = null
            handler.removeCallbacks(updateSeekBar)
            try { videoPfd?.close() } catch (_: Exception) { }
            videoPfd = null
        }

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surfaceTexture: SurfaceTexture, width: Int, height: Int
            ) {
                val surface = Surface(surfaceTexture)
                val mp = MediaPlayer().apply {
                    setSurface(surface)
                    setOnErrorListener { _, what, extra ->
                        Toast.makeText(
                            this@SavedMediaActivity,
                            "Can't play video (err: $what, $extra)",
                            Toast.LENGTH_LONG
                        ).show()
                        true
                    }
                    setOnPreparedListener { m ->
                        creatingPlayer = null
                        mediaPlayer = m
                        videoSeekBar.max = 1000
                        videoSeekBar.progress = 0
                        m.start()
                        isPlaying = true
                        handler.post(updateSeekBar)

                        val vw = textureView.width.toFloat()
                        val vh = textureView.height.toFloat()
                        val videoW = m.videoWidth.toFloat()
                        val videoH = m.videoHeight.toFloat()
                        if (vw > 0 && vh > 0 && videoW > 0 && videoH > 0) {
                            val sx = vw / videoW
                            val sy = vh / videoH
                            val fitScale = min(sx, sy)
                            val cx = fitScale / sx
                            val cy = fitScale / sy
                            baseMatrix.setScale(cx, cy)
                            baseMatrix.postTranslate(
                                vw * (1 - cx) / 2f,
                                vh * (1 - cy) / 2f
                            )
                            displayMatrix.set(baseMatrix)
                            textureView.setTransform(displayMatrix)
                        }
                    }
                    setOnCompletionListener { m ->
                        m.seekTo(0)
                        videoSeekBar.progress = 0
                        isPlaying = false
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
                    Toast.makeText(
                        this@SavedMediaActivity,
                        "Can't open video: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    releasePlayer()
                }
            }

            override fun onSurfaceTextureSizeChanged(
                surfaceTexture: SurfaceTexture, width: Int, height: Int
            ) {}

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                releasePlayer()
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}
        }

        val scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    var sf = detector.scaleFactor
                    val newScale = (currentScale * sf).coerceIn(1f, 5f)
                    sf = newScale / currentScale
                    currentScale = newScale
                    displayMatrix.postScale(sf, sf, detector.focusX, detector.focusY)
                    textureView.setTransform(displayMatrix)
                    return true
                }
            })

        val gestureDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    val mp = mediaPlayer ?: return false
                    if (isPlaying) { mp.pause(); isPlaying = false }
                    else { mp.start(); isPlaying = true }
                    return true
                }

                override fun onScroll(
                    e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float
                ): Boolean {
                    if (scaleDetector.isInProgress) return false
                    if (currentScale > 1.01f) {
                        displayMatrix.postTranslate(-dx, -dy)
                        textureView.setTransform(displayMatrix)
                        return true
                    }
                    return false
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (currentScale > 1.5f) {
                        displayMatrix.set(baseMatrix)
                        currentScale = 1f
                    } else {
                        val sf = 2.5f / currentScale
                        currentScale = 2.5f
                        displayMatrix.postScale(sf, sf, e.x, e.y)
                    }
                    textureView.setTransform(displayMatrix)
                    return true
                }
            })

        textureView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            true
        }

        videoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { userSeeking = false }
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val mp = mediaPlayer ?: return
                if (fromUser && mp.duration > 0) {
                    val pos = (progress * mp.duration) / 1000
                    mp.seekTo(pos)
                }
            }
        })

        dialog.setOnDismissListener { releasePlayer() }
    }

    // ── 360 Sphere viewer ──

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

    // ── Adapter ─────────────────────────────────────────────

    private class SavedMediaAdapter(
        private val items: List<MediaItem>,
        private val onItemClick: (MediaItem) -> Unit
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
            }

            fun bind(item: MediaItem) {
                binding.videoIcon.visibility = if (item.isVideo) View.VISIBLE else View.GONE
                if (item.isVideo) {
                    val thumb = binding.root.context.contentResolver.loadVideoThumbnail(item.uri)
                    binding.thumbnail.setImageBitmap(thumb)
                } else {
                    binding.thumbnail.setImageURI(null)
                    binding.thumbnail.setImageURI(item.uri)
                }
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
