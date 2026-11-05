package dev.saeed.lasso

import android.app.UiAutomation
import android.content.ClipboardManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole flow on a real device: turn on the service, open Lasso over the settings screen,
 * check the recognized text, drag across words, tap Copy, and read the clipboard.
 *
 * Run with `./gradlew connectedDebugAndroidTest`. CI runs it on release tags.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    // By default, a test's UI automation switches off accessibility services, Lasso included.
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    private val lasso = "${context.packageName}/${LassoService::class.java.name}"
    private var servicesBefore: String? = null

    @Before
    fun turnOnLasso() {
        // Stands in for agreeing to the disclosure dialog, which Lasso needs before it opens.
        Prefs.setConsented(context, true)
        servicesBefore = shell("settings get secure enabled_accessibility_services").trim()
            .takeUnless { it.isEmpty() || it == "null" }
        // Keep any other services that were on. Remove Lasso first and add it back: the test
        // run force-stops the app, and Android only binds the service again when the setting changes.
        val others = servicesBefore?.split(':').orEmpty().filter { it.isNotEmpty() && it != lasso }
        if (others.isEmpty()) shell("settings delete secure enabled_accessibility_services")
        else shell("settings put secure enabled_accessibility_services ${others.joinToString(":")}")
        SystemClock.sleep(500)
        shell("settings put secure enabled_accessibility_services ${(others + lasso).joinToString(":")}")
        shell("settings put secure accessibility_enabled 1")
        waitFor("the service to start") { LassoService.instance }
    }

    @After
    fun restoreServices() {
        instrumentation.runOnMainSync { LassoService.instance?.dismiss() }
        val before = servicesBefore
        if (before == null) shell("settings delete secure enabled_accessibility_services")
        else shell("settings put secure enabled_accessibility_services $before")
    }

    @Test
    fun selectAndCopyTextOnScreen() {
        // The settings screen shows "Select part of this sentence to test Lasso."
        ActivityScenario.launch(MainActivity::class.java).use {
            instrumentation.waitForIdleSync()
            SystemClock.sleep(500) // Let Lasso see which app is in front.

            instrumentation.runOnMainSync { LassoService.instance!!.trigger() }
            val overlay = waitFor("the overlay", timeoutMs = 30_000) { onMain { LassoService.instance?.openOverlay } }
            // On slow emulators the window takes a moment to receive touches; earlier ones are lost.
            waitFor("the overlay to take input") { onMain { overlay.hasWindowFocus() }.takeIf { it } }

            val screen = overlay.text
            val words = screen.words.map { screen.textOf(it.index, it.index) }
            val from = words.indexOf("sentence")
            val to = words.subList(from + 1, words.size).indexOf("test") + from + 1
            check(from >= 0 && to > from) { "Expected words not recognized: $words" }

            drag(screen.words[from].box.centerX(), screen.words[from].box.centerY(),
                screen.words[to].box.centerX(), screen.words[to].box.centerY())
            val copy = onMain { overlay.findText(context.getString(R.string.action_copy)) }
            waitFor("the toolbar after selecting") { onMain { copy.isShown && copy.width > 0 }.takeIf { it } }
            tapOn(copy)

            // Copy closes Lasso.
            waitFor("Lasso to close") { onMain { LassoService.instance?.openOverlay == null }.takeIf { it } }
            // Android only lets an app read the clipboard while it has focus, which the settings
            // screen gets back a moment after the overlay closes. On slow emulators, keep trying.
            val copied = waitFor("the copied text on the clipboard") {
                onMain {
                    context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()
                }
            }
            assertEquals("sentence to test", copied)
        }
    }

    // ---- Input ----

    private fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float, steps: Int = 10) {
        val down = SystemClock.uptimeMillis()
        inject(down, MotionEvent.ACTION_DOWN, fromX, fromY)
        for (i in 1..steps) {
            SystemClock.sleep(20)
            val f = i.toFloat() / steps
            inject(down, MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * f, fromY + (toY - fromY) * f)
        }
        inject(down, MotionEvent.ACTION_UP, toX, toY)
    }

    private fun tapOn(view: View) {
        val (x, y) = onMain {
            val at = IntArray(2).also(view::getLocationOnScreen)
            (at[0] + view.width / 2f) to (at[1] + view.height / 2f)
        }
        val down = SystemClock.uptimeMillis()
        inject(down, MotionEvent.ACTION_DOWN, x, y)
        inject(down, MotionEvent.ACTION_UP, x, y)
    }

    private fun inject(downTime: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        check(automation.injectInputEvent(event, true)) { "Could not inject $event" }
        event.recycle()
    }

    // ---- Helpers ----

    private fun View.findText(text: String): TextView {
        fun search(v: View): TextView? = when {
            v is TextView && v.text.toString() == text -> v
            v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { search(v.getChildAt(it)) }
            else -> null
        }
        return requireNotNull(search(this)) { "No view with text \"$text\"" }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun <T : Any> waitFor(what: String, timeoutMs: Long = 10_000, poll: () -> T?): T {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            poll()?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {
            it.readBytes().decodeToString()
        }
}
