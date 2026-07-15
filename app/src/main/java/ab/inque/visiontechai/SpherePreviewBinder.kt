package ab.inque.visiontechai

import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.widget.ImageView

/**
 * Forces [ImageView] to display a new equirect preview even when dimensions match the prior frame.
 */
object SpherePreviewBinder {

    fun apply(imageView: ImageView, bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        val display = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val action = Runnable {
            if (imageView.drawable != null) {
                imageView.setImageDrawable(null)
            }
            imageView.setImageBitmap(display)
            imageView.invalidate()
            (imageView.parent as? View)?.invalidate()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            imageView.post(action)
        }
    }
}
