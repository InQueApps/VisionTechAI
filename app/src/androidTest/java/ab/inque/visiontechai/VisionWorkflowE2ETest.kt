package ab.inque.visiontechai

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
