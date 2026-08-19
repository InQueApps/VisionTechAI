package ab.inque.visiontechai

import ab.inque.robotics.text.TextLineClustererNative
import org.junit.Assert.assertArrayEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Golden vectors for native word-line clustering (translation pipeline).
 */
class TextLineClustererGoldenTest {

    @Test
    fun clusterWordsIntoLines_twoLines_groupsByY() {
        assumeTrue(TextLineClustererNative.isAvailable())
        val left = intArrayOf(10, 10, 120, 120)
        val top = intArrayOf(20, 80, 20, 80)
        val right = intArrayOf(90, 200, 90, 200)
        val bottom = intArrayOf(40, 100, 120, 160)
        val out = IntArray(4)
        TextLineClustererNative.clusterWordsIntoLines(left, top, right, bottom, 4, out)
        assertArrayEquals(intArrayOf(0, 0, 1, 1), out)
    }
}
