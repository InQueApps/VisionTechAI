package ab.inque.visiontechai

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SphereExportPersistenceTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clear() {
        SphereExportPersistence.clear(context)
    }

    @Test
    fun consumeInterruptedExport_returnsTrueOnce() {
        SphereExportPersistence.markPending(context)
        assertTrue(SphereExportPersistence.consumeInterruptedExport(context))
        assertFalse(SphereExportPersistence.consumeInterruptedExport(context))
    }
}
