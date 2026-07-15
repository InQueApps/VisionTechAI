package ab.inque.visiontechai

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityModeSwitchTest {

    companion object {
        @get:ClassRule
        @JvmField
        val grantPermissions = E2ETestRules.grantAllRequiredPermissions()
    }

    @get:Rule
    val activityRule = E2ETestRules.mainActivity()

    @Test
    fun sphereButton_showsCaptureOverlay() {
        Thread.sleep(4000)
        onView(withId(R.id.sphereButton)).perform(click())
        onView(withId(R.id.sphereCaptureOverlay)).check(matches(isDisplayed()))
    }

    @Test
    fun nightVisionButton_thenNone_exitsMode() {
        Thread.sleep(4000)
        onView(withId(R.id.nightVisionButton)).perform(click())
        onView(withId(R.id.nightVisionButton)).perform(click())
        onView(withId(R.id.sphereButton)).check(matches(isDisplayed()))
    }
}
