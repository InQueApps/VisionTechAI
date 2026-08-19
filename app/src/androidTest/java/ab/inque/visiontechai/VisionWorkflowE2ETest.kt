package ab.inque.visiontechai

import ab.inque.robotics.panorama.SphereNative


import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.IdlingPolicies
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * End-to-end walkthrough of PRISM AI user workflows on a real emulator or device.
 * Maps 1:1 to workflows in Work/skills/android-instrumented-e2e/apps/prismai.e2e.json.
 */
@RunWith(AndroidJUnit4::class)
class VisionWorkflowE2ETest {

    companion object {
        @get:ClassRule
        @JvmField
        val grantPermissions = E2ETestRules.grantAllRequiredPermissions()

        @BeforeClass
        @JvmStatic
        fun extendEspressoTimeouts() {
            IdlingPolicies.setMasterPolicyTimeout(60, TimeUnit.SECONDS)
            IdlingPolicies.setIdlingResourceTimeout(60, TimeUnit.SECONDS)
        }
    }

    @get:Rule
    val activityRule = E2ETestRules.mainActivity()

    private fun waitForActivity() {
        Thread.sleep(4000)
    }
    @Test
    fun workflow_launch_mainControlsVisible() {
        waitForActivity()
        onView(withId(R.id.viewFinder)).check(matches(isDisplayed()))
        onView(withId(R.id.nightVisionButton)).check(matches(isDisplayed()))
        onView(withId(R.id.sphereButton)).check(matches(isDisplayed()))
        onView(withId(R.id.recordButton)).check(matches(isDisplayed()))
    }

    @Test
    fun workflow_nightVision_toggleAndSlider() {
        waitForActivity()
        onView(withId(R.id.nightVisionButton)).perform(click())
        onView(withId(R.id.nightVisionSliderContainer)).check(matches(isDisplayed()))
        onView(withId(R.id.nightVisionButton)).perform(click())
    }

    @Test
    fun workflow_img_edgeDetection() {
        waitForActivity()
        onView(withId(R.id.imgButton)).perform(click())
        onView(withId(R.id.imgEDButton)).perform(click())
        onView(withId(R.id.edgeDetectionSliderContainer)).check(matches(isDisplayed()))
    }

    @Test
    fun workflow_img_colorAdjustment() {
        waitForActivity()
        onView(withId(R.id.imgButton)).perform(click())
        onView(withId(R.id.imgCOLButton)).perform(click())
        onView(withId(R.id.colorSliderContainer)).check(matches(isDisplayed()))
    }

    @Test
    fun workflow_img_hd() {
        waitForActivity()
        onView(withId(R.id.imgButton)).perform(click())
        onView(withId(R.id.imgHDButton)).perform(click())
        onView(withId(R.id.hdSliderContainer)).check(matches(isDisplayed()))
    }

    @Test
    fun workflow_rob_opticalFlow() {
        waitForActivity()
        onView(withId(R.id.robButton)).perform(click())
        onView(withId(R.id.robOFButton)).perform(click())
        onView(withId(R.id.opticalFlowSliderContainer)).check(matches(isDisplayed()))
    }

    @Test
    fun workflow_rob_segmentation() {
        waitForActivity()
        onView(withId(R.id.robButton)).perform(click())
        onView(withId(R.id.robSEGButton)).perform(click())
        onView(withId(R.id.segmentationSliderContainer)).check(matches(isDisplayed()))
    }

    @Test
    fun workflow_rob_depthField() {
        waitForActivity()
        onView(withId(R.id.robButton)).perform(click())
        onView(withId(R.id.robDFButton)).perform(click())
        onView(withId(R.id.depthFieldSliderContainer)).check(matches(isDisplayed()))
    }

    @Test
    fun workflow_textTranslation() {
        waitForActivity()
        onView(withId(R.id.txtButton)).perform(click())
        onView(withId(R.id.translationOptionsContainer)).check(matches(isDisplayed()))
        onView(withId(R.id.txtButton)).perform(click())
    }

    @Test
    fun workflow_sphere_enterAndExit() {
        waitForActivity()
        onView(withId(R.id.sphereButton)).perform(click())
        onView(withId(R.id.sphereCaptureOverlay)).check(matches(isDisplayed()))
        onView(withId(R.id.sphereCoverageText)).check(matches(isDisplayed()))
        onView(withId(R.id.sphereButton)).perform(click())
    }

    /**
     * Validates that taking a photo in 360 sphere mode:
     * 1. Stitches the frame into the equirectangular buffer (coverage > 0%)
     * 2. Generates a valid preview bitmap shown in spherePreviewImage
     * 3. The accumulated sphere (getFullEquirect) contains stitched pixel data
     * 4. A second capture does not break the pipeline (coverage holds or grows)
     *
     * Note: On the emulator, the rotation vector sensor is a fusion sensor
     * (accel+gyro+mag) that does NOT respond to "sensor set orientation".
     * Multi-frame coverage growth requires real device rotation or the direct
     * JNI path tested in SphereStitchE2ETest.fullPipeline_multiFrameStitch_producesValidSphere.
     */
    @Test
    fun workflow_sphere_captureUpdatesPreview() {
        assumeTrue("Sphere JNI required for capture E2E", SphereNative.isAvailable())
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        waitForActivity()

        onView(withId(R.id.sphereButton)).perform(click())
        onView(withId(R.id.sphereCaptureOverlay)).check(matches(isDisplayed()))
        onView(withId(R.id.sphereCoverageText))
            .check(matches(withText("Coverage: 0% area | 0% sphere")))

        // Wait for CameraX analysis frames and rotation sensor to stabilize.
        Thread.sleep(8000)

        // --- Frame 1: capture photo in sphere mode ---
        onView(withId(R.id.recordButton)).perform(click())

        SphereE2EMatchers.waitForCoverageSolidAtLeast(1)
        SphereE2EMatchers.waitForPreviewWithContent()
        SphereE2EMatchers.waitForStitchIdle(activityRule)

        val solidAfterFirst = SphereE2EMatchers.readSolidCoveragePercent(activityRule)
        assertTrue("First frame must produce >0% solid coverage", solidAfterFirst > 0)

        val previewPixelAfterFirst = SphereE2EMatchers.readPreviewCenterPixel(activityRule)
        assertTrue("Preview should show stitched pixels after first photo", previewPixelAfterFirst != 0)

        onView(withId(R.id.spherePreviewImage))
            .check(matches(SphereE2EMatchers.hasPreviewWithStitchedContent()))

        val equirectAfterFirst = SphereE2EMatchers.readFullEquirect(activityRule)
        assertNotNull("Accumulated sphere should exist after first capture", equirectAfterFirst)
        assertTrue(
            "Final sphere buffer should contain stitched pixels",
            SphereE2EMatchers.bitmapHasStitchedContent(equirectAfterFirst!!)
        )
        val regionFirst = SphereImageMetrics.findContentRegion(equirectAfterFirst)
        val lapFirst = if (regionFirst != null) {
            SphereImageMetrics.laplacianVariance(equirectAfterFirst, regionFirst)
        } else {
            0.0
        }

        // --- Frame 2: second capture should not break pipeline ---
        // On a real device the user would physically rotate; on emulator the
        // rotation vector sensor stays static, so coverage may not grow.
        // We inject gyroscope impulse to attempt triggering rotation vector change.
        device.executeShellCommand("emu sensor set gyroscope 0:0:3")
        Thread.sleep(1000)
        device.executeShellCommand("emu sensor set gyroscope 0:0:0")
        Thread.sleep(2000)

        onView(withId(R.id.recordButton)).perform(click())
        SphereE2EMatchers.waitForStitchIdle(activityRule)

        val solidAfterSecond = SphereE2EMatchers.readSolidCoveragePercent(activityRule)
        assertTrue(
            "Sphere coverage must not regress after second capture " +
                "(was $solidAfterFirst%, now $solidAfterSecond%)",
            solidAfterSecond >= solidAfterFirst
        )

        // Preview should still show content after second stitch attempt.
        onView(withId(R.id.spherePreviewImage))
            .check(matches(SphereE2EMatchers.hasPreviewWithStitchedContent()))

        val equirectAfterSecond = SphereE2EMatchers.readFullEquirect(activityRule)
        assertNotNull("Final sphere should still exist after second capture", equirectAfterSecond)
        assertTrue(
            "Final sphere should still contain stitched pixels",
            SphereE2EMatchers.bitmapHasStitchedContent(equirectAfterSecond!!)
        )

        val regionSecond = SphereImageMetrics.findContentRegion(equirectAfterSecond)
        if (regionFirst != null && regionSecond != null && lapFirst > 1.0) {
            val lapSecond = SphereImageMetrics.laplacianVariance(equirectAfterSecond, regionSecond)
            val retention = SphereImageMetrics.sharpnessRetentionRatio(lapFirst, lapSecond)
            assertTrue(
                "Workflow sharpness retention must be ≥ " +
                    "${SphereRefinePolicy.minWorkflowSharpnessRetention()} (was $retention)",
                retention >= SphereRefinePolicy.minWorkflowSharpnessRetention()
            )
        }

        // Reset gyroscope and exit sphere mode.
        device.executeShellCommand("emu sensor set gyroscope 0:0:0")
        onView(withId(R.id.sphereButton)).perform(click())
    }

    @Test
    fun workflow_infoPanel_toggle() {
        waitForActivity()
        onView(withId(R.id.infoButton)).perform(click())
        onView(withId(R.id.infoPanel)).check(matches(isDisplayed()))
        onView(withId(R.id.infoButton)).perform(click())
    }

    @Test
    fun workflow_gallery_opensSavedMedia() {
        waitForActivity()
        onView(withId(R.id.galleryButton)).perform(click())
        Thread.sleep(1000)
        pressBack()
        onView(withId(R.id.viewFinder)).check(matches(isDisplayed()))
    }
}
