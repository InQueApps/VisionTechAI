package ab.inque.visiontechai

import android.Manifest
import android.os.Build
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject
import androidx.test.uiautomator.UiSelector
import ab.inque.visiontechai.controller.PermissionCoordinator
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

object E2ETestRules {

    fun grantAllRequiredPermissions(): GrantPermissionRule {
        val perms = PermissionCoordinator.REQUIRED_PERMISSIONS.toMutableList()
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return GrantPermissionRule.grant(*perms.toTypedArray())
    }

    fun mainActivity(): ActivityScenarioRule<MainActivity> {
        return ActivityScenarioRule(MainActivity::class.java)
    }

    /** Grant runtime permissions before MainActivity launches. */
    fun mainActivityWithPermissions(): TestRule = RuleChain
        .outerRule(grantAllRequiredPermissions())
        .around(mainActivity())

    /**
     * Dismisses any system dialogs (permission popups, ANR, etc.) before the test.
     */
    fun dismissDialogsRule(): TestRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val device = device()
                device.pressHome()
                Thread.sleep(500)
                base.evaluate()
            }
        }
    }

    fun device(): UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    fun resourceId(id: Int): String {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return "${ctx.packageName}:id/${ctx.resources.getResourceEntryName(id)}"
    }

    fun dismissSystemDialogs() {
        val device = device()
        val labels = listOf(
            "While using the app",
            "Grant Permissions",
            "Allow",
            "ALLOW",
            "OK",
            "Got it"
        )
        repeat(3) {
            var clicked = false
            for (label in labels) {
                val obj = device.findObject(UiSelector().text(label))
                if (obj.exists()) {
                    obj.click()
                    clicked = true
                    break
                }
            }
            if (!clicked) return
            device.waitForIdle()
        }
    }

    fun waitForId(id: Int, timeoutMs: Long = 15_000): UiObject {
        dismissSystemDialogs()
        val obj = device().findObject(UiSelector().resourceId(resourceId(id)))
        if (!obj.waitForExists(timeoutMs)) {
            throw AssertionError("View ${resourceId(id)} not on screen after ${timeoutMs}ms")
        }
        return obj
    }

    fun clickId(id: Int, timeoutMs: Long = 15_000) {
        waitForId(id, timeoutMs).click()
        device().waitForIdle()
    }
}
