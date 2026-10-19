package ab.inque.visiontechai

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ContactEmailLauncherTest {

    @Test
    fun createIntent_prefillsInqueAppsRecipientAndSubject() {
        val context = RuntimeEnvironment.getApplication()
        val intent = ContactEmailLauncher.createIntent(context)

        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals(Uri.parse("mailto:inqueapps@gmail.com"), intent.data)
        assertArrayEquals(arrayOf("inqueapps@gmail.com"), intent.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertEquals("PRISM AI feedback", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertTrue(intent.dataString.orEmpty().startsWith("mailto:inqueapps@gmail.com"))
    }
}
