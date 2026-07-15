package ab.inque.visiontechai

import android.Manifest
import android.os.Build
import androidx.test.espresso.Espresso
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import ab.inque.visiontechai.controller.PermissionCoordinator
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

object E2ETestRules {

    fun grantAllRequiredPermissions(): GrantPermissionRule {
        val perms = PermissionCoordinator.REQUIRED_PERMISSIONS.toMutableList()
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT in 23..32) {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return GrantPermissionRule.grant(*perms.toTypedArray())
    }

    fun mainActivity(): ActivityScenarioRule<MainActivity> {
        return ActivityScenarioRule(MainActivity::class.java)
    }

    /**
     * Dismisses any system dialogs (permission popups, ANR, etc.) before the test.
     */
    fun dismissDialogsRule(): TestRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val device = UiDevice.getInstance(
                    InstrumentationRegistry.getInstrumentation()
                )
                device.pressHome()
                Thread.sleep(500)
                base.evaluate()
            }
        }
    }
}
