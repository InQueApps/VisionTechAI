package ab.inque.visiontechai

import ab.inque.robotics.math.LensDistortion
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CameraLensIntrinsicsTest {

    @Test
    fun resolveSessionDistortion_prefersStoredOverDeviceDefault() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = LensDistortionStore(context)
        store.set(LensDistortion(k1 = -0.12f))
        val resolved = CameraLensIntrinsics.resolveSessionDistortion(context, store)
        assertEquals(-0.12f, resolved.k1, 1e-5f)
        store.reset()
    }

    @Test
    fun resolveSessionDistortion_fallsBackToDeviceTableWhenNoCamera2() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = LensDistortionStore(context)
        store.reset()
        val resolved = CameraLensIntrinsics.resolveSessionDistortion(context, store)
        // Robolectric has no LENS_DISTORTION — should match deviceDefault (NONE on unknown model).
        assertEquals(store.deviceDefault().k1, resolved.k1, 1e-5f)
    }

    @Test
    fun readFocalScaleSeed_returnsOneWhenCamera2Unavailable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scale = CameraLensIntrinsics.readFocalScaleSeed(context, nominalFovHDeg = 65f)
        org.junit.Assert.assertTrue(scale in 0.92f..1.08f)
    }
}
