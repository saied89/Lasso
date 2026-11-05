package dev.saeed.lasso

import android.content.Context
import androidx.core.content.edit

/** User settings, shared by the settings screen and the service. */
object Prefs {
    const val PAUSE_MEDIA = "pause_media"
    private const val CONSENT = "accessibility_consent"

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun pauseMedia(context: Context) = get(context, PAUSE_MEDIA)

    /** Whether the user agreed to the accessibility disclosure. Lasso does nothing until they do. */
    fun consented(context: Context) = prefs(context).getBoolean(CONSENT, false)

    fun setConsented(context: Context, value: Boolean) = prefs(context).edit { putBoolean(CONSENT, value) }

    /** Every setting is on by default. */
    fun get(context: Context, key: String) = prefs(context).getBoolean(key, true)

    fun set(context: Context, key: String, value: Boolean) = prefs(context).edit { putBoolean(key, value) }
}
