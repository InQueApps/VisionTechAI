package ab.inque.visiontechai

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityModeSwitchTest {

    @get:Rule
    val rules: TestRule = E2ETestRules.mainActivityWithPermissions()

    @Test
    fun sphereButton_showsCaptureOverlay() {
        E2ETestRules.clickId(R.id.sphereButton)
        E2ETestRules.waitForId(R.id.sphereCaptureOverlay)
    }

    @Test
    fun nightVisionButton_thenNone_exitsMode() {
        E2ETestRules.clickId(R.id.nightVisionButton)
        E2ETestRules.clickId(R.id.nightVisionButton)
        E2ETestRules.waitForId(R.id.sphereButton)
    }
}
