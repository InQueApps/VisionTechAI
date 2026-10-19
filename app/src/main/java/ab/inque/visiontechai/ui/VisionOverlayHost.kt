package ab.inque.visiontechai.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import ab.inque.visiontechai.Constants
import ab.inque.visiontechai.MediaCapturePolicy
import ab.inque.visiontechai.ObjectDetectionOverlay
import ab.inque.visiontechai.R
import ab.inque.visiontechai.VisionMode

/**
 * Manages preview overlays: one shared [filterOverlay] for bitmap-based vision modes,
 * object-detection boxes, and FPS/histogram HUD.
 */
class VisionOverlayHost(
    private val parent: ConstraintLayout,
    controlsAnchorId: Int
) {
    val filterOverlay: ImageView = ImageView(parent.context).apply {
        layoutParams = ConstraintLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply {
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToTop = controlsAnchorId
        }
        scaleType = ImageView.ScaleType.CENTER_CROP
        visibility = View.GONE
        elevation = Constants.ELEVATION_EFFECT_OVERLAY
    }

    val objectDetectionOverlay: ObjectDetectionOverlay = ObjectDetectionOverlay(parent.context).apply {
        layoutParams = ConstraintLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply {
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToTop = controlsAnchorId
        }
        visibility = View.GONE
        elevation = Constants.ELEVATION_OVERLAY
    }

    val histogramView: HistogramView = HistogramView(parent.context).apply {
        layoutParams = ConstraintLayout.LayoutParams(200, 150).apply {
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            setMargins(16, 16, 0, 0)
        }
        visibility = View.VISIBLE
        elevation = Constants.ELEVATION_HUD
    }

    val fpsView: TextView = TextView(parent.context).apply {
        layoutParams = ConstraintLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            setMargins(0, 16, 16, 0)
        }
        setTextColor(Color.WHITE)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        setBackgroundResource(R.drawable.button_info_background)
        setPadding(16, 8, 16, 8)
        text = "FPS: 0"
        visibility = View.VISIBLE
        elevation = Constants.ELEVATION_HUD
    }

    init {
        parent.addView(filterOverlay)
        parent.addView(objectDetectionOverlay)
        parent.addView(histogramView)
        parent.addView(fpsView)
    }

    /** Modes that render a processed bitmap into [filterOverlay]. */
    fun usesFilterOverlay(mode: VisionMode): Boolean =
        MediaCapturePolicy.shouldCaptureOverlayBitmap(mode)

    fun hideAllEffectOverlays() {
        filterOverlay.visibility = View.GONE
        objectDetectionOverlay.visibility = View.GONE
    }

    fun showFilterOverlay(mode: VisionMode) {
        hideAllEffectOverlays()
        if (usesFilterOverlay(mode)) {
            filterOverlay.visibility = View.VISIBLE
        } else if (mode == VisionMode.OBJECT_DETECTION) {
            objectDetectionOverlay.visibility = View.VISIBLE
        }
    }

    fun setFilterBitmap(bitmap: Bitmap, bringToFront: Boolean = false) {
        filterOverlay.setImageBitmap(bitmap)
        if (bringToFront) filterOverlay.bringToFront()
    }

    fun clearFilterBitmap() {
        filterOverlay.setImageBitmap(null)
    }

    /** Returns a copy of the current filter overlay bitmap for photo capture, if any. */
    fun captureFilterBitmap(): Bitmap? {
        val drawable = filterOverlay.drawable as? BitmapDrawable ?: return null
        val bmp = drawable.bitmap ?: return null
        return bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, true)
    }

    fun bringHudToFront(controlsContainer: View) {
        controlsContainer.bringToFront()
        histogramView.bringToFront()
        fpsView.bringToFront()
    }
}
