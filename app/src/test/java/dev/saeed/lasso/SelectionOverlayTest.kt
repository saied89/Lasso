package dev.saeed.lasso

import android.content.Context
import android.graphics.Region
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SelectionOverlayTest {
    private val context: Context =
        ContextThemeWrapper(ApplicationProvider.getApplicationContext(), android.R.style.Theme_Material_Light)

    private val copied = mutableListOf<String>()
    private var dismissed = 0

    // "Hello world" at (100, 100) and "Second line" at (100, 300), 10 px per character.
    private val first = lineOf("Hello world", left = 100, top = 100)
    private val second = lineOf("Second line", left = 100, top = 300)

    private fun overlay(vararg blocks: TextBlock) = SelectionOverlay(
        context = context,
        screen = ScreenText(blocks.toList()),
        frozen = null,
        frozenArea = Region(),
        onCopy = { copied += it },
        onShare = {},
        onTranslate = {},
        onLaunch = {},
        onDismiss = { dismissed++ },
    ).apply {
        measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
        layout(0, 0, 1080, 1920)
    }

    @Test
    fun draggingAcrossWordsSelectsThem() {
        val view = overlay(first, second)
        view.drag(first.charCenter(0), second.charCenter(0))
        view.tapButton(R.string.action_copy)
        assertEquals(listOf("Hello world\nSecond"), copied)
    }

    @Test
    fun tappingAWordSelectsIt() {
        val view = overlay(first)
        view.tap(first.charCenter(7))
        view.tapButton(R.string.action_copy)
        assertEquals(listOf("world"), copied)
    }

    @Test
    fun doubleTapSelectsTheWholeLine() {
        val view = overlay(first, second)
        view.tap(first.charCenter(0))
        view.tap(first.charCenter(0))
        view.tapButton(R.string.action_copy)
        assertEquals(listOf("Hello world"), copied)
    }

    @Test
    fun selectAllSelectsEverything() {
        val view = overlay(first, second)
        view.tap(first.charCenter(0))
        view.tapButton(R.string.action_select_all)
        view.tapButton(R.string.action_copy)
        assertEquals(listOf("Hello world\nSecond line"), copied)
    }

    @Test
    fun tappingOutsideWithNothingSelectedCloses() {
        val view = overlay(first)
        view.tap(500f to 1000f)
        assertEquals(1, dismissed)
    }

    @Test
    fun tappingOutsideClearsTheSelectionFirst() {
        val view = overlay(first)
        view.tap(first.charCenter(0))
        assertEquals(View.VISIBLE, view.button(R.string.action_copy).toolbarVisibility())

        view.tap(500f to 1000f)
        assertEquals(0, dismissed)
        assertNotEquals(View.VISIBLE, view.button(R.string.action_copy).toolbarVisibility())
    }

    @Test
    fun backCloses() {
        val view = overlay(first)
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        assertEquals(1, dismissed)
    }

    @Test
    fun anEmptyScreenSaysSo() {
        val view = overlay()
        val message = view.findText(context.getString(R.string.no_text_found))
        assertEquals(View.VISIBLE, message.visibility)
    }

    @Test
    fun withTextTheEmptyMessageIsHidden() {
        val view = overlay(first)
        val message = view.findText(context.getString(R.string.no_text_found))
        assertNotEquals(View.VISIBLE, message.visibility)
    }

    // ---- Helpers ----

    private fun View.touch(action: Int, at: Pair<Float, Float>) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, at.first, at.second, 0)
        dispatchTouchEvent(event)
        event.recycle()
    }

    private fun View.tap(at: Pair<Float, Float>) {
        touch(MotionEvent.ACTION_DOWN, at)
        touch(MotionEvent.ACTION_UP, at)
    }

    private fun View.drag(from: Pair<Float, Float>, to: Pair<Float, Float>) {
        touch(MotionEvent.ACTION_DOWN, from)
        touch(MotionEvent.ACTION_MOVE, to)
        touch(MotionEvent.ACTION_UP, to)
    }

    private fun View.findText(text: String): TextView {
        fun search(v: View): TextView? = when {
            v is TextView && v.text.toString() == text -> v
            v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { search(v.getChildAt(it)) }
            else -> null
        }
        return requireNotNull(search(this)) { "No view with text \"$text\"" }
    }

    private fun View.button(res: Int) = findText(context.getString(res))

    private fun View.tapButton(res: Int) {
        val button = button(res)
        assertEquals("Toolbar should be showing", View.VISIBLE, button.toolbarVisibility())
        assertTrue(button.performClick())
    }

    private fun TextView.toolbarVisibility() = (parent as View).visibility
}
