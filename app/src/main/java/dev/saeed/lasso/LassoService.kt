package dev.saeed.lasso

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
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
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LassoService : AccessibilityService() {

    companion object {
        private const val TAG = "Lasso"
        private const val GOOGLE_TRANSLATE = "com.google.android.apps.translate"
        private const val DOUBLE_PRESS_MS = 350L
        private const val SCROLL_QUIET_MS = 120L
        private const val SCROLL_WAIT_MAX_MS = 600L
        /** How long to wait for an app we started to appear before giving up on it. */
        private const val RETURN_WAIT_MS = 3000L
        /** After this long in another app, the frozen screen is likely stale; close instead. */
        private const val HIDDEN_MAX_MS = 5 * 60_000L
        /** When to read the screen again after showing the overlay, in ms. */
        private val REFRESH_DELAYS = longArrayOf(300, 800, 1500)

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
    private var overlayPackages = emptySet<String>()
    private var focusRequest: AudioFocusRequest? = null

    /** Text recognized in this session's screenshot, reused when re-reading the accessibility text. */
    private var ocrBlocks = emptyList<TextBlock>()
    /** Created on first use; only touched on the worker thread. */
    private var ocr: Ocr? = null

    // Volume double-press tracking.
    private var lastVolumeDownAt = 0L
    /** Key codes whose ACTION_UP we must swallow, because we swallowed their ACTION_DOWN. */
    private val swallowUp = mutableSetOf<Int>()

    private var lastScrollAt = 0L

    // While HIDDEN: the apps that were on screen, and whether the other app has appeared yet.
    private var homePackages = emptySet<String>()
    private var sawOtherApp = false
    private var hiddenAt = 0L
    private val checkReturn = Runnable { checkReturn() }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        dismiss()
        instance = null
        worker.execute { ocr?.close() }
        worker.shutdown()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
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
                // Another app came to the front (e.g. an incoming call): our boxes no longer match.
                val pkg = event.packageName?.toString() ?: return
                if (state == State.SHOWING && pkg != packageName && pkg != "com.android.systemui" && pkg !in overlayPackages) {
                    dismiss()
                }
                if (state == State.HIDDEN) scheduleReturnCheck()
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> if (state == State.HIDDEN) scheduleReturnCheck()
        }
    }

    // ---- Keys ----

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_UP && swallowUp.remove(code)) return true
        // Holding a key we swallowed: swallow its repeats too, or the volume keeps dropping.
        if (event.repeatCount > 0 && code in swallowUp) return true

        // While the overlay is opening or open, volume keys don't change the volume;
        // once it's open, they and Back close it.
        val isVolume = code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_UP
        val active = state == State.PREPARING || state == State.SHOWING
        if ((active && isVolume) || (state == State.SHOWING && code == KeyEvent.KEYCODE_BACK)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0 && state == State.SHOWING) dismiss()
                swallowUp += code
            }
            return true
        }

        if (code != KeyEvent.KEYCODE_VOLUME_DOWN || !Prefs.volumeTrigger(this)) return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return false

        val now = event.eventTime
        if (now - lastVolumeDownAt <= DOUBLE_PRESS_MS) {
            lastVolumeDownAt = 0
            swallowUp += code
            if (Prefs.restoreVolume(this)) undoFirstPress()
            trigger()
            return true
        }
        // First press: let it through right away; we can't hold it and replay it later.
        lastVolumeDownAt = now
        return false
    }

    /** The first press already lowered the volume one step; put it back. */
    private fun undoFirstPress() {
        try {
            audioManager.adjustSuggestedStreamVolume(AudioManager.ADJUST_RAISE, AudioManager.USE_DEFAULT_STREAM_TYPE, 0)
        } catch (e: SecurityException) {
            // Raising the ringer out of Do Not Disturb needs a permission we don't have.
            Log.w(TAG, "Could not restore volume", e)
        }
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

    /** Takes a screenshot, reads the accessibility text and recognizes the text in the screenshot. */
    private fun capture() {
        val started = SystemClock.uptimeMillis()
        val readSession = session
        val shot = if (Prefs.ocr(this)) requestScreenshot() else CompletableFuture.completedFuture(null)
        worker.execute {
            val nodes = extract()
            val frozen = try {
                shot.get(2, TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "No screenshot", e)
                null
            }
            val recognized = frozen?.let { (ocr ?: Ocr().also { ocr = it }).read(it) }.orEmpty()
            val screen = ScreenText(TextExtractor.merge(nodes.blocks, recognized, nodes.appArea))
            Log.d(TAG, "Read ${nodes.blocks.size} nodes + ${recognized.size} recognized lines -> " +
                "${screen.words.size} words in ${SystemClock.uptimeMillis() - started} ms")
            main.post {
                if (session != readSession) return@post
                ocrBlocks = recognized
                show(screen, nodes.packages, frozen, nodes.appArea)
                REFRESH_DELAYS.forEach { delay -> main.postDelayed(::refresh, delay) }
            }
        }
    }

    private fun requestScreenshot(): CompletableFuture<Bitmap?> {
        val result = CompletableFuture<Bitmap?>()
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val buffer = screenshot.hardwareBuffer
                // A software copy, which the text recognizer can read.
                val bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                    ?.copy(Bitmap.Config.ARGB_8888, false)
                buffer.close()
                result.complete(bitmap)
            }

            override fun onFailure(errorCode: Int) {
                // E.g. an app that blocks screenshots. The accessibility text still works.
                Log.w(TAG, "Screenshot failed: $errorCode")
                result.complete(null)
            }
        })
        return result
    }

    private fun extract() = try {
        TextExtractor.extract(this)
    } catch (e: Exception) {
        Log.e(TAG, "Reading screen text failed", e)
        TextExtractor.Result(emptyList(), emptySet(), Region())
    }

    /** Some apps (Chrome) only build their full text tree after the first request; pick up the rest. */
    private fun refresh() {
        if (state != State.SHOWING) return
        val readSession = session
        val recognized = ocrBlocks
        worker.execute {
            val nodes = extract()
            val screen = ScreenText(TextExtractor.merge(nodes.blocks, recognized, nodes.appArea))
            main.post {
                val view = overlay ?: return@post
                if (session == readSession && state == State.SHOWING &&
                    screen.words.size > view.wordCount && view.update(screen)
                ) {
                    overlayPackages = overlayPackages + nodes.packages
                }
            }
        }
    }

    private fun show(screen: ScreenText, packages: Set<String>, frozen: Bitmap?, appArea: Region) {
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
        overlayPackages = packages
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
        overlayPackages = emptySet()
        ocrBlocks = emptyList()
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
        if (state != State.SHOWING || view == null) return launch(intent)
        homePackages = appPackages().toSet()
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
        val top = appPackages().firstOrNull() ?: return
        val elapsed = SystemClock.uptimeMillis() - hiddenAt
        when {
            top in homePackages -> {
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

    /** Packages of the app windows on screen, topmost first. */
    private fun appPackages(): List<String> = windows
        .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        .sortedByDescending { it.layer }
        .mapNotNull { it.root?.packageName?.toString() }
        .filter { it != "com.android.systemui" && it != packageName }

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
