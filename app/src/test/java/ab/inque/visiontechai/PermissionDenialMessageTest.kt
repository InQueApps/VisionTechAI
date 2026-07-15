package ab.inque.visiontechai

import android.Manifest
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionDenialMessageTest {

    @Test
    fun forDenied_camera() {
        val msg = PermissionDenialMessage.forDenied(setOf(Manifest.permission.CAMERA))
        assertTrue(msg.contains("Camera"))
    }

    @Test
    fun forDenied_audio() {
        val msg = PermissionDenialMessage.forDenied(setOf(Manifest.permission.RECORD_AUDIO))
        assertTrue(msg.contains("Audio"))
    }

    @Test
    fun forDenied_media() {
        val msg = PermissionDenialMessage.forDenied(setOf("android.permission.READ_MEDIA_IMAGES"))
        assertTrue(msg.contains("Media"))
    }
}
