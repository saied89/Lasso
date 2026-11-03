package dev.saeed.lasso

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Region
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LassoService : AccessibilityService() {

    companion object {
        private const val TAG = "Lasso"
        private const val GOOGLE_TRANSLATE = "com.google.android.apps.translate"
        private const val SCROLL_QUIET_MS = 120L
        private const val SCROLL_WAIT_MAX_MS = 600L
        /** How long to wait for an app we started to appear before giving up on it. */
        private const val RETURN_WAIT_MS = 3000L
        /** After this long in another app, the frozen screen is likely stale; close instead. */
        private const val HIDDEN_MAX_MS = 5 * 60_000L

        @Volatile
        var instance: LassoService? = null
            private set
    }

    /** HIDDEN: the overlay is set aside while another app (e.g. Translate) shows on top. */
    private enum class State { IDLE, PREPARING, SHOWING, HIDDEN }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }

    private var state = State.IDLE
    /** Bumped on every dismiss, so late screen reads from an earlier overlay are ignored. */
    private var session = 0
    private var overlay: SelectionOverlay? = null
    /** The app under the overlay, or null if we haven't seen which one it is. */
    private var overlayPackage: String? = null
    /** The app on screen, from window events. Null until the first one arrives. */
    private var foreground: String? = null
    private var focusRequest: AudioFocusRequest? = null

    /** Created on first use; only touched on the worker thread. */
    private var ocr: Ocr? = null

    /** Back closed the overlay; swallow the rest of that press so the app below doesn't go back too. */
    private var swallowingBack = false

    private var lastScrollAt = 0L

    // While HIDDEN: whether the other app (e.g. Translate) has appeared yet.
    private var sawOtherApp = false
    private var hiddenAt = 0L
    private val checkReturn = Runnable { checkReturn() }

    /** The accessibility button or gesture: opens Lasso, or closes it when it's open. */
    private val buttonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) {
            when (state) {
                State.IDLE -> trigger()
                State.SHOWING -> dismiss()
                else -> Unit
            }
        }
    }

    override fun onServiceConnected() {
        instance = this
        accessibilityButtonController.registerAccessibilityButtonCallback(buttonCallback, main)
    }

    override fun onDestroy() {
        dismiss()
        instance = null
        worker.execute { ocr?.close() }
        worker.shutdown()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        accessibilityButtonController.unregisterAccessibilityButtonCallback(buttonCallback)
        dismiss()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        dismiss() // Text positions are stale after rotation.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> lastScrollAt = SystemClock.uptimeMillis()
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if (!isAppWindow(pkg, event.className?.toString())) return
                foreground = pkg
                // Another app came to the front (e.g. an incoming call): our boxes no longer match.
                val under = overlayPackage
                if (state == State.SHOWING && under != null && pkg != under) dismiss()
                if (state == State.HIDDEN) scheduleReturnCheck()
            }
        }
    }

    /**
     * Whether a window event means an app came to the front. The status bar, the keyboard and
     * Lasso's own windows also send these, but the app below stays where it was.
     */
    private fun isAppWindow(pkg: String, className: String?): Boolean {
        if (pkg == "com.android.systemui") return false
        // Ours: only the settings screen counts. The overlay and the Quick Tap entry don't.
        if (pkg == packageName) return className == MainActivity::class.java.name
        val keyboards = getSystemService(InputMethodManager::class.java).enabledInputMethodList
        return keyboards.none { it.packageName == pkg }
    }

    // ---- Keys ----

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return false
        if (event.action == KeyEvent.ACTION_DOWN && state == State.SHOWING) {
            if (event.repeatCount == 0) dismiss()
            swallowingBack = true
            return true
        }
        if (swallowingBack) {
            if (event.action == KeyEvent.ACTION_UP) swallowingBack = false
            return true
        }
        return false
    }

    // ---- Trigger ----

    /** Reads the screen and shows the selection overlay. Safe to call repeatedly. */
    fun trigger(delayMs: Long = 0) {
        main.postDelayed({
            if (state != State.IDLE) return@postDelayed
            val power = getSystemService(PowerManager::class.java)
            val keyguard = getSystemService(KeyguardManager::class.java)
            if (!power.isInteractive || keyguard.isKeyguardLocked) return@postDelayed
            state = State.PREPARING
            waitForScrollToSettle(SystemClock.uptimeMillis())
        }, delayMs)
    }

    /** A fling keeps moving after the trigger; wait until it stops so the boxes line up. */
    private fun waitForScrollToSettle(startedAt: Long) {
        val now = SystemClock.uptimeMillis()
        if (now - lastScrollAt < SCROLL_QUIET_MS && now - startedAt < SCROLL_WAIT_MAX_MS) {
            main.postDelayed({ waitForScrollToSettle(startedAt) }, 30)
        } else {
            capture()
        }
    }

    /** Takes a screenshot and recognizes the text in it. */
    private fun capture() {
        val started = SystemClock.uptimeMillis()
        val readSession = session
        val shot = requestScreenshot()
        val area = appArea()
        val under = foreground
        worker.execute {
            val frozen = try {
                shot.get(2, TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "No screenshot", e)
                null
            }
            val recognized = frozen?.let { (ocr ?: Ocr().also { ocr = it }).read(it) }.orEmpty()
                // Skip the clock, notification icons and the navigation bar.
                .filter { area.contains(it.bounds.centerX(), it.bounds.centerY()) }
                .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            val screen = ScreenText(recognized)
            Log.d(TAG, "Recognized ${recognized.size} lines -> ${screen.words.size} words " +
                "in ${SystemClock.uptimeMillis() - started} ms")
            main.post {
                if (session != readSession) return@post
                if (frozen == null) {
                    // E.g. an app that blocks screenshots, like banking apps or private tabs.
                    Toast.makeText(this, R.string.no_screenshot, Toast.LENGTH_SHORT).show()
                    state = State.IDLE
                    return@post
                }
                show(screen, under, frozen, area)
            }
        }
    }

    /** The screen without the status and navigation bars, when they're showing. */
    private fun appArea(): Region {
        val metrics = windowManager.currentWindowMetrics
        val bars = metrics.windowInsets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars() or WindowInsets.Type.ime())
        val bounds = Rect(metrics.bounds)
        bounds.set(bounds.left + bars.left, bounds.top + bars.top, bounds.right - bars.right, bounds.bottom - bars.bottom)
        return Region(bounds)
    }

    private fun requestScreenshot(): CompletableFuture<Bitmap?> {
        val result = CompletableFuture<Bitmap?>()
        // Run the callback on the thread that delivers it, not the main thread: copying the
        // screenshot takes a while. Not on [worker] either, which is busy waiting for this.
        takeScreenshot(Display.DEFAULT_DISPLAY, Runnable::run, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val started = SystemClock.uptimeMillis()
                val buffer = screenshot.hardwareBuffer
                // A software copy, which the text recognizer can read.
                val bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                    ?.copy(Bitmap.Config.ARGB_8888, false)
                buffer.close()
                Log.d(TAG, "Copied screenshot in ${SystemClock.uptimeMillis() - started} ms")
                result.complete(bitmap)
            }

            override fun onFailure(errorCode: Int) {
                // E.g. an app that blocks screenshots.
                Log.w(TAG, "Screenshot failed: $errorCode")
                result.complete(null)
            }
        })
        return result
    }

    private fun show(screen: ScreenText, under: String?, frozen: Bitmap, appArea: Region) {
        if (state != State.PREPARING) return
        val view = SelectionOverlay(
            context = this,
            screen = screen,
            frozen = frozen,
            frozenArea = appArea,
            onCopy = ::copy,
            onShare = ::share,
            onTranslate = ::translate,
            onLaunch = ::launchOnTop,
            onDismiss = ::dismiss,
        )
        try {
            windowManager.addView(view, overlayParams())
        } catch (e: Exception) {
            Log.e(TAG, "Could not show overlay", e)
            state = State.IDLE
            return
        }
        overlay = view
        overlayPackage = under
        state = State.SHOWING
        if (Prefs.pauseMedia(this)) pauseMedia()
    }

    private fun overlayParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            // Focusable (so Back reaches us) but leave the keyboard alone.
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
        PixelFormat.TRANSLUCENT,
    ).apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        title = "Lasso"
    }

    fun dismiss() {
        session++
        main.removeCallbacksAndMessages(null)
        overlay?.takeIf { it.isAttachedToWindow }?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "Overlay already gone", e)
            }
        }
        overlay = null
        overlayPackage = null
        resumeMedia()
        state = State.IDLE
    }

    // ---- Actions ----

    private fun copy(text: String) {
        if (text.isEmpty()) return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Lasso", text))
        // Android 13+ shows its own confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        dismiss()
    }

    private fun share(text: String) {
        if (text.isEmpty()) return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        launch(Intent.createChooser(send, null))
    }

    /** Sends the text to Google Translate, or lets the user pick an app that handles selected text. */
    private fun translate(text: String) {
        if (text.isEmpty()) return
        val base = Intent(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        val handlers = packageManager.queryIntentActivities(base, 0)
        val google = handlers.firstOrNull { it.activityInfo.packageName == GOOGLE_TRANSLATE }?.activityInfo
        when {
            google != null -> launchOnTop(Intent(base).setClassName(google.packageName, google.name))
            handlers.isNotEmpty() -> launchOnTop(Intent.createChooser(base, getString(R.string.action_translate)))
            else -> Toast.makeText(this, R.string.no_translator, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Starts [intent] (e.g. Translate's popup) and sets the overlay aside, since it would
     * cover the other app. It comes back, selection and all, when the user returns.
     */
    private fun launchOnTop(intent: Intent) {
        val view = overlay
        // Without knowing the app below, we can't tell when the user is back in it.
        if (state != State.SHOWING || view == null || overlayPackage == null) return launch(intent)
        try {
            windowManager.removeView(view)
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "Could not start $intent", e)
            dismiss()
            return
        }
        state = State.HIDDEN
        sawOtherApp = false
        hiddenAt = SystemClock.uptimeMillis()
        // In case the other app never shows up.
        main.postDelayed(checkReturn, RETURN_WAIT_MS)
    }

    private fun scheduleReturnCheck() {
        main.removeCallbacks(checkReturn)
        main.postDelayed(checkReturn, 150) // Let the window list settle.
    }

    /**
     * While hidden: bring the overlay back once the user is back in the app, close it if they
     * went to the home screen, and keep waiting while another app (Translate) is on top.
     */
    private fun checkReturn() {
        if (state != State.HIDDEN) return
        val top = foreground ?: return
        val elapsed = SystemClock.uptimeMillis() - hiddenAt
        when {
            top == overlayPackage -> {
                // Right after hiding, the app is still on top until the other one opens.
                if (sawOtherApp || elapsed >= RETURN_WAIT_MS) restore()
            }
            top == homeScreenPackage() || elapsed > HIDDEN_MAX_MS -> dismiss()
            else -> sawOtherApp = true
        }
    }

    private fun homeScreenPackage(): String? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(home, 0)?.activityInfo?.packageName
    }

    private fun restore() {
        val view = overlay ?: return dismiss()
        try {
            windowManager.addView(view, overlayParams())
            state = State.SHOWING
        } catch (e: Exception) {
            Log.w(TAG, "Could not bring the overlay back", e)
            dismiss()
        }
    }

    /** Closes Lasso and starts [intent] on top of the current app. */
    private fun launch(intent: Intent) {
        dismiss()
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No app for $intent", e)
        }
    }

    // ---- Media ----

    /** Taking transient audio focus makes well-behaved players pause until we give it back. */
    private fun pauseMedia() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                    .build()
            )
            .setOnAudioFocusChangeListener { }
            .build()
        if (audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focusRequest = request
    }

    private fun resumeMedia() {
        focusRequest?.let(audioManager::abandonAudioFocusRequest)
        focusRequest = null
    }
}
