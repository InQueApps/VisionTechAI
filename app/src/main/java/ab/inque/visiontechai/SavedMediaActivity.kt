package ab.inque.visiontechai

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ab.inque.visiontechai.databinding.ActivitySavedMediaBinding
import ab.inque.visiontechai.databinding.ItemSavedMediaBinding
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Displays photos and videos saved by the app (Pictures/VisionTechAI and Movies/VisionTechAI).
 * Back button returns to the main camera view.
 */
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

        // Always load and show content (tracker doesn't need permission); avoids black screen
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
        // 1) Tracker: URIs saved this install (survives until app uninstall)
        val fromTracker = SavedMediaTracker.getAll(this).mapIndexed { index, (isVideo, uri) ->
            MediaItem(index.toLong(), uri, isVideo, Long.MAX_VALUE) // high date so they sort first
        }
        val trackerUris = fromTracker.map { it.uri.toString() }.toSet()

        // 2) MediaStore search by filename prefix: finds our media in device gallery (works after reinstall)
        val fromMediaStore = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            loadFromMediaStoreByNamePrefix()
        } else emptyList()

        // Merge: tracker first, then MediaStore items not already in tracker (dedupe), sort by date desc
        val combined = fromTracker + fromMediaStore.filter { it.uri.toString() !in trackerUris }
        return combined.sortedByDescending { it.dateAdded }
    }

    /** Query MediaStore for images/videos whose DISPLAY_NAME starts with VisionTechAI_ (findable after reinstall). */
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

    private fun showMediaViewerDialog(item: MediaItem) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_media_viewer, null)
        val photoView = dialogView.findViewById<ImageView>(R.id.photoView)
        val videoSurface = dialogView.findViewById<SurfaceView>(R.id.videoSurface)
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

        if (item.isVideo) {
            photoView.visibility = View.GONE
            val videoControls = dialogView.findViewById<View>(R.id.videoControls)
            val videoSeekBar = dialogView.findViewById<SeekBar>(R.id.videoSeekBar)
            videoControls.visibility = View.VISIBLE
            videoSurface.visibility = View.VISIBLE

            var mediaPlayer: MediaPlayer? = null
            var creatingPlayer: MediaPlayer? = null
            var videoPfd: ParcelFileDescriptor? = null
            var isPlaying = true
            var userSeeking = false
            val handler = Handler(Looper.getMainLooper())

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
            }

            videoSurface.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    val mp = MediaPlayer().apply {
                        setDisplay(holder)
                        setOnErrorListener { _, what, extra ->
                            Toast.makeText(this@SavedMediaActivity, "Can't play video (err: $what, $extra)", Toast.LENGTH_LONG).show()
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
                        Toast.makeText(this@SavedMediaActivity, "Can't open video: ${e.message}", Toast.LENGTH_LONG).show()
                        releasePlayer()
                    }
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    releasePlayer()
                }
            })

            videoSurface.setOnClickListener {
                val mp = mediaPlayer ?: return@setOnClickListener
                if (isPlaying) {
                    mp.pause()
                    isPlaying = false
                } else {
                    mp.start()
                    isPlaying = true
                }
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
        } else {
            dialogView.findViewById<View>(R.id.videoControls).visibility = View.GONE
            photoView.visibility = View.VISIBLE
            videoSurface.visibility = View.GONE
            photoView.setImageURI(item.uri)
        }

        dialog.show()
    }

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
