package ab.inque.visiontechai

import org.junit.Assert.assertTrue
import org.junit.Test

class SphereXmpMetadataTest {

    @Test
    fun buildGpanoXmp_containsRequiredTags() {
        val xmp = SphereXmpMetadata.buildGpanoXmp(4096, 2048)
        assertTrue(xmp.contains("GPano:ProjectionType=\"equirectangular\""))
        assertTrue(xmp.contains("GPano:FullPanoWidthPixels=\"4096\""))
        assertTrue(xmp.contains("GPano:FullPanoHeightPixels=\"2048\""))
        assertTrue(xmp.contains("GPano:UsePanoramaViewer=\"True\""))
    }
}
