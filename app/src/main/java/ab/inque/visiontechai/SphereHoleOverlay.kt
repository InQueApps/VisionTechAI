package ab.inque.visiontechai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

object SphereHoleOverlay {

    fun compositePreview(preview: Bitmap?, holeMask: Bitmap?): Bitmap? {
        if (preview == null) return null
        if (holeMask == null) return preview
        val out = preview.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        canvas.drawBitmap(holeMask, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
        return out
    }
}
