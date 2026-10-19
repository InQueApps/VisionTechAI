package ab.inque.visiontechai

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.sin

/**
 * Realistic-ish synthetic frames for sphere E2E (not stripes/checkerboard).
 */
object SphereTestFixtures {

    const val FRAME_W = 640
    const val FRAME_H = 480

    /** PRNG texture with spatial structure (wallpaper-like). */
    fun indoorTexture(w: Int = FRAME_W, h: Int = FRAME_H, seed: Int = 1): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        var s = seed
        for (y in 0 until h) {
            for (x in 0 until w) {
                s = s * 1103515245 + 12345
                val n = (s ushr 16) and 0xFF
                val wall = ((sin(x * 0.04 + seed) + sin(y * 0.03)) * 40 + 128).toInt().coerceIn(0, 255)
                val r = (wall + n / 4).coerceIn(0, 255)
                val g = (wall * 0.9 + n / 6).toInt().coerceIn(0, 255)
                val b = (wall * 0.8 + n / 3).toInt().coerceIn(0, 255)
                pixels[y * w + x] = Color.rgb(r, g, b)
            }
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    /**
     * Near foreground bar over distant gradient — parallax-sensitive overlap.
     */
    fun parallaxScene(w: Int = FRAME_W, h: Int = FRAME_H, fgShiftPx: Int = 0): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val bg = (y * 255 / h.coerceAtLeast(1)).coerceIn(0, 255)
                var rgb = Color.rgb(bg, bg / 2, 255 - bg)
                val barLeft = w / 4 + fgShiftPx
                val barRight = (3 * w) / 4 + fgShiftPx
                if (x in barLeft until barRight && y in h / 3 until 2 * h / 3) {
                    rgb = Color.rgb(220, 80, 40)
                }
                pixels[y * w + x] = rgb
            }
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    /** Simulates auto-exposure stepping between frames. */
    fun aeRampFrame(
        w: Int = FRAME_W,
        h: Int = FRAME_H,
        frameIndex: Int,
        frameCount: Int = 5,
        seed: Int = 7
    ): Bitmap {
        val base = indoorTexture(w, h, seed)
        val scale = 0.55f + 0.45f * frameIndex / (frameCount - 1).coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        base.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val p = px[i]
            val r = ((p shr 16 and 0xFF) * scale).toInt().coerceIn(0, 255)
            val g = ((p shr 8 and 0xFF) * scale).toInt().coerceIn(0, 255)
            val b = ((p and 0xFF) * scale).toInt().coerceIn(0, 255)
            px[i] = Color.rgb(r, g, b)
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    fun shiftBitmap(src: Bitmap, dx: Int, dy: Int = 0): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val srcPx = IntArray(w * h)
        val dstPx = IntArray(w * h) { Color.BLACK }
        src.getPixels(srcPx, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            val sy = y - dy
            if (sy < 0 || sy >= h) continue
            for (x in 0 until w) {
                val sx = x - dx
                if (sx < 0 || sx >= w) continue
                dstPx[y * w + x] = srcPx[sy * w + sx]
            }
        }
        out.setPixels(dstPx, 0, w, 0, 0, w, h)
        return out
    }

    fun downscaledSha256(bitmap: Bitmap, outW: Int = 64, outH: Int = 32): String {
        val small = Bitmap.createScaledBitmap(bitmap, outW, outH, true)
        val pixels = IntArray(outW * outH)
        small.getPixels(pixels, 0, outW, 0, 0, outW, outH)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        for (p in pixels) {
            digest.update((p shr 16 and 0xFF).toByte())
            digest.update((p shr 8 and 0xFF).toByte())
            digest.update((p and 0xFF).toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
