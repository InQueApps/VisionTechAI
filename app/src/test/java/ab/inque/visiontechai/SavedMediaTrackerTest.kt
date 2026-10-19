package ab.inque.visiontechai

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SavedMediaTrackerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.filesDir.resolve("saved_media_uris.txt").delete()
    }

    @Test
    fun addImage_andGetAllTyped_roundTrips() {
        val uri = Uri.parse("content://media/external/images/1")
        SavedMediaTracker.addImage(context, uri)
        val items = SavedMediaTracker.getAllTyped(context)
        assertEquals(1, items.size)
        assertEquals(SavedMediaTracker.MediaType.IMAGE, items[0].mediaType)
        assertEquals(uri, items[0].uri)
    }

    @Test
    fun removeUri_dropsEntry() {
        val uri = Uri.parse("content://media/external/images/2")
        SavedMediaTracker.addImage(context, uri)
        SavedMediaTracker.removeUri(context, uri)
        assertTrue(SavedMediaTracker.getAllTyped(context).isEmpty())
    }

    @Test
    fun nativeModuleAvailability_returnsStatusObject() {
        val status = NativeModuleAvailability.current()
        assertEquals(false, status.sphere && status.nightVision && status.video)
    }
}
