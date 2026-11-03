package dev.saeed.lasso

/**
 * How Lasso reacts to apps coming and going, from window events. Plain values in and out, so
 * the rules can be unit tested; [LassoService] does the Android calls.
 */
object WindowRules {
    /** How long to wait for an app we started (e.g. Translate) to appear before giving up on it. */
    const val RETURN_WAIT_MS = 3000L

    /** After this long in another app, the frozen screen is likely stale; close instead. */
    const val HIDDEN_MAX_MS = 5 * 60_000L

    private const val SYSTEM_UI = "com.android.systemui"

    /**
     * Whether a window event from [pkg] means an app came to the front. The status bar, the
     * keyboard and Lasso's own windows also send these, but the app below stays where it was.
     * Of Lasso's own windows, only the settings screen ([settingsActivity]) counts.
     */
    fun isAppWindow(
        pkg: String,
        className: String?,
        ownPackage: String,
        settingsActivity: String,
        keyboards: Set<String>,
    ): Boolean = when (pkg) {
        SYSTEM_UI -> false
        ownPackage -> className == settingsActivity
        else -> pkg !in keyboards
    }

    /**
     * Whether the overlay, shown over [under], should close because [front] came to the front
     * (e.g. an incoming call): the text boxes no longer match the screen. With [under] unknown,
     * Lasso can't tell, so it stays open.
     */
    fun closesOverlay(front: String, under: String?): Boolean = under != null && front != under

    enum class Return {
        /** The user is back in the app below: show the overlay again. */
        RESTORE,
        /** The user left (home screen) or stayed away too long: close Lasso. */
        CLOSE,
        /** The app we started (e.g. Translate) is in front. */
        OTHER_APP_SHOWN,
        /** Too early to tell. */
        WAIT,
    }

    /**
     * While the overlay is set aside for another app: what to do now that [front] is in front.
     * [under] is the app the overlay was shown over, [home] the home screen app.
     */
    fun whileHidden(front: String, under: String?, home: String?, sawOtherApp: Boolean, elapsedMs: Long): Return =
        when {
            // Right after hiding, the app is still in front until the other one opens.
            front == under -> if (sawOtherApp || elapsedMs >= RETURN_WAIT_MS) Return.RESTORE else Return.WAIT
            front == home || elapsedMs > HIDDEN_MAX_MS -> Return.CLOSE
            else -> Return.OTHER_APP_SHOWN
        }
}
