package ab.inque.visiontechai

import ab.inque.robotics.math.LensDistortion


import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LensDistortionStoreTest {

    private lateinit var store: LensDistortionStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = LensDistortionStore(context)
        store.reset()
    }

    @Test
    fun deviceDefault_withoutOverride_returnsNoneOrProfile() {
        val d = store.deviceDefault()
        assertEquals(0f, d.p1, 0f)
        assertEquals(0f, d.p2, 0f)
    }

    @Test
    fun set_persistsCustomCoeffs() {
        store.set(LensDistortion(k1 = -0.12f, k2 = 0.01f))
        val loaded = store.get()
        assertEquals(-0.12f, loaded.k1, 0.001f)
        assertEquals(0.01f, loaded.k2, 0.001f)
    }

    @Test
    fun reset_clearsCustomOverride() {
        store.set(LensDistortion(k1 = 0.2f))
        store.reset()
        assertEquals(store.deviceDefault(), store.get())
    }

    @Test
    fun deviceProfiles_pixel6_hasBarrelK1() {
        val pixel = LensDistortionStore.DEVICE_PROFILES["Pixel 6"]
        assertNotEquals(null, pixel)
        assertEquals(-0.08f, pixel!!.k1, 0.001f)
    }
}
