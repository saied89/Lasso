package dev.saeed.lasso

import dev.saeed.lasso.WindowRules.HIDDEN_MAX_MS
import dev.saeed.lasso.WindowRules.RETURN_WAIT_MS
import dev.saeed.lasso.WindowRules.Return
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowRulesTest {
    private val own = "dev.saeed.lasso"
    private val settings = "dev.saeed.lasso.MainActivity"
    private val keyboards = setOf("com.google.android.inputmethod.latin")

    private fun isAppWindow(pkg: String, className: String? = null) =
        WindowRules.isAppWindow(pkg, className, own, settings, keyboards)

    @Test
    fun otherAppsCount() {
        assertTrue(isAppWindow("com.android.chrome", "org.chromium.chrome.browser.ChromeTabbedActivity"))
        assertTrue(isAppWindow("com.android.chrome", null))
    }

    @Test
    fun statusBarAndKeyboardDontCount() {
        assertFalse(isAppWindow("com.android.systemui"))
        assertFalse(isAppWindow("com.google.android.inputmethod.latin"))
    }

    @Test
    fun ofLassosOwnWindowsOnlyTheSettingsScreenCounts() {
        assertTrue(isAppWindow(own, settings))
        assertFalse(isAppWindow(own, "dev.saeed.lasso.TriggerActivity"))
        assertFalse(isAppWindow(own, "android.widget.FrameLayout")) // The overlay.
        assertFalse(isAppWindow(own, null))
    }

    @Test
    fun overlayClosesWhenAnotherAppComesToTheFront() {
        assertTrue(WindowRules.closesOverlay(front = "com.android.dialer", under = "com.android.chrome"))
        assertFalse(WindowRules.closesOverlay(front = "com.android.chrome", under = "com.android.chrome"))
    }

    @Test
    fun overlayStaysOpenWhenTheAppBelowIsUnknown() {
        assertFalse(WindowRules.closesOverlay(front = "com.android.dialer", under = null))
    }

    private fun whileHidden(front: String, sawOtherApp: Boolean = false, elapsedMs: Long = 0) =
        WindowRules.whileHidden(front, under = "app", home = "launcher", sawOtherApp, elapsedMs)

    @Test
    fun appBelowStillInFrontRightAfterHiding_waits() {
        assertEquals(Return.WAIT, whileHidden("app", sawOtherApp = false, elapsedMs = 100))
    }

    @Test
    fun backInTheAppAfterTranslate_restores() {
        assertEquals(Return.RESTORE, whileHidden("app", sawOtherApp = true, elapsedMs = 100))
    }

    @Test
    fun otherAppNeverShowedUp_restoresAfterTheWait() {
        assertEquals(Return.RESTORE, whileHidden("app", sawOtherApp = false, elapsedMs = RETURN_WAIT_MS))
    }

    @Test
    fun anotherAppInFront_isNoted() {
        assertEquals(Return.OTHER_APP_SHOWN, whileHidden("com.google.android.apps.translate"))
    }

    @Test
    fun homeScreen_closes() {
        assertEquals(Return.CLOSE, whileHidden("launcher", sawOtherApp = true, elapsedMs = 100))
    }

    @Test
    fun awayTooLong_closes() {
        assertEquals(Return.CLOSE, whileHidden("com.google.android.apps.translate", elapsedMs = HIDDEN_MAX_MS + 1))
    }

    @Test
    fun backInTheAppAfterAWhile_stillRestores() {
        assertEquals(Return.RESTORE, whileHidden("app", sawOtherApp = true, elapsedMs = HIDDEN_MAX_MS + 1))
    }
}
