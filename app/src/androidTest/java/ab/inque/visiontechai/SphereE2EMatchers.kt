package ab.inque.visiontechai



import android.graphics.Bitmap

import android.graphics.drawable.BitmapDrawable

import android.view.View

import android.widget.ImageView

import android.widget.TextView

import androidx.test.espresso.Espresso.onView

import androidx.test.espresso.assertion.ViewAssertions.matches

import androidx.test.espresso.matcher.ViewMatchers.withId

import androidx.test.ext.junit.rules.ActivityScenarioRule

import ab.inque.visiontechai.controller.SphereSessionController

import org.hamcrest.Description

import org.hamcrest.Matcher

import org.hamcrest.TypeSafeMatcher



object SphereE2EMatchers {



    private val coveragePattern = Regex("""Coverage:\s*(\d+)%\s*area\s*\|\s*(\d+)%\s*sphere""")

    private const val GAP_COLOR = 0xFF1A1A2E.toInt()

    private const val BG_COLOR = 0xFF1A1A1A.toInt()



    fun coverageAreaPercentAtLeast(minPercent: Int): Matcher<View> =

        object : TypeSafeMatcher<View>() {

            override fun describeTo(description: Description) {

                description.appendText("sphere coverage area >= $minPercent%")

            }



            override fun matchesSafely(view: View): Boolean {

                if (view !is TextView) return false

                val (_, area) = parseCoverage(view.text?.toString()) ?: return false

                return area >= minPercent

            }

        }



    fun coverageSolidPercentAtLeast(minPercent: Int): Matcher<View> =

        object : TypeSafeMatcher<View>() {

            override fun describeTo(description: Description) {

                description.appendText("sphere coverage solid >= $minPercent%")

            }



            override fun matchesSafely(view: View): Boolean {

                if (view !is TextView) return false

                val (_, solid) = parseCoverage(view.text?.toString()) ?: return false

                return solid >= minPercent

            }

        }



    fun hasPreviewBitmap(): Matcher<View> =

        object : TypeSafeMatcher<View>() {

            override fun describeTo(description: Description) {

                description.appendText("sphere preview ImageView has a bitmap drawable")

            }



            override fun matchesSafely(view: View): Boolean {

                if (view !is ImageView) return false

                val drawable = view.drawable ?: return false

                return drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0

            }

        }



    fun hasPreviewWithStitchedContent(): Matcher<View> =

        object : TypeSafeMatcher<View>() {

            override fun describeTo(description: Description) {

                description.appendText("sphere preview shows stitched (non-gap) pixels")

            }



            override fun matchesSafely(view: View): Boolean {

                if (view !is ImageView) return false

                val bmp = (view.drawable as? BitmapDrawable)?.bitmap ?: return false

                return bitmapHasStitchedContent(bmp)

            }

        }



    fun bitmapHasStitchedContent(bitmap: Bitmap): Boolean {

        if (bitmap.width <= 0 || bitmap.height <= 0) return false

        val cx = bitmap.width / 2

        val cy = bitmap.height / 2

        val sampleYs = listOf(cy / 2, cy, minOf(bitmap.height - 1, cy + cy / 2))

        val sampleXs = listOf(cx / 2, cx, minOf(bitmap.width - 1, cx + cx / 2))

        for (y in sampleYs) {

            for (x in sampleXs) {

                val pixel = bitmap.getPixel(x, y)

                if (pixel != 0 && pixel != GAP_COLOR && pixel != BG_COLOR) {

                    return true

                }

            }

        }

        return false

    }



    private fun parseCoverage(text: String?): Pair<Int, Int>? {

        val match = coveragePattern.matchEntire(text ?: return null) ?: return null

        val area = match.groupValues[1].toIntOrNull() ?: return null

        val solid = match.groupValues[2].toIntOrNull() ?: return null

        return area to solid

    }



    fun waitForCoverageSolidAtLeast(minPercent: Int, timeoutMs: Long = 30_000) {

        val deadline = System.currentTimeMillis() + timeoutMs

        var lastError: AssertionError? = null

        while (System.currentTimeMillis() < deadline) {

            try {

                onView(withId(R.id.sphereCoverageText))

                    .check(matches(coverageSolidPercentAtLeast(minPercent)))

                return

            } catch (e: AssertionError) {

                lastError = e

                Thread.sleep(500)

            }

        }

        throw lastError ?: AssertionError("Coverage did not reach $minPercent% within ${timeoutMs}ms")

    }



    fun waitForPreviewWithContent(timeoutMs: Long = 30_000) {

        val deadline = System.currentTimeMillis() + timeoutMs

        var lastError: AssertionError? = null

        while (System.currentTimeMillis() < deadline) {

            try {

                onView(withId(R.id.spherePreviewImage))

                    .check(matches(hasPreviewWithStitchedContent()))

                return

            } catch (e: AssertionError) {

                lastError = e

                Thread.sleep(500)

            }

        }

        throw lastError ?: AssertionError("Sphere preview did not show stitched content within ${timeoutMs}ms")

    }



    fun waitForStitchIdle(

        activityRule: ActivityScenarioRule<MainActivity>,

        timeoutMs: Long = 30_000

    ) {

        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {

            var busy = true

            activityRule.scenario.onActivity { activity ->

                busy = sphereSession(activity)?.controller?.isStitchBusy() == true

            }

            if (!busy) return

            Thread.sleep(250)

        }

        throw AssertionError("Sphere stitch worker did not become idle within ${timeoutMs}ms")

    }



    fun readSolidCoveragePercent(activityRule: ActivityScenarioRule<MainActivity>): Int {

        var solid = 0

        activityRule.scenario.onActivity { activity ->

            val text = activity.findViewById<TextView>(R.id.sphereCoverageText).text?.toString()

            solid = parseCoverage(text)?.second ?: 0

        }

        return solid

    }



    fun readPreviewCenterPixel(activityRule: ActivityScenarioRule<MainActivity>): Int {

        var pixel = 0

        activityRule.scenario.onActivity { activity ->

            val imageView = activity.findViewById<ImageView>(R.id.spherePreviewImage)

            val bitmap = (imageView.drawable as? BitmapDrawable)?.bitmap

            if (bitmap != null && bitmap.width > 0 && bitmap.height > 0) {

                pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)

            }

        }

        return pixel

    }



    fun readFullEquirect(activityRule: ActivityScenarioRule<MainActivity>): Bitmap? {

        var equirect: Bitmap? = null

        activityRule.scenario.onActivity { activity ->

            val ctrl = sphereSession(activity)?.controller

            equirect = ctrl?.getFullEquirect() ?: ctrl?.getPreviewBitmap()

        }

        return equirect

    }



    private fun sphereSession(activity: MainActivity): SphereSessionController? {

        val field = MainActivity::class.java.getDeclaredField("sphereSession")

        field.isAccessible = true

        return field.get(activity) as? SphereSessionController

    }

}


