package dev.saeed.lasso

import android.content.Context
import androidx.core.content.edit

/** User settings, shared by the settings screen and the service. */
object Prefs {
    const val VOLUME_TRIGGER = "volume_trigger"
    const val RESTORE_VOLUME = "restore_volume"
    const val PAUSE_MEDIA = "pause_media"
    const val OCR = "ocr"

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun volumeTrigger(context: Context) = get(context, VOLUME_TRIGGER)
    fun restoreVolume(context: Context) = get(context, RESTORE_VOLUME)
    fun pauseMedia(context: Context) = get(context, PAUSE_MEDIA)
    fun ocr(context: Context) = get(context, OCR)

    /** Every setting is on by default. */
    fun get(context: Context, key: String) = prefs(context).getBoolean(key, true)

    fun set(context: Context, key: String, value: Boolean) = prefs(context).edit { putBoolean(key, value) }
}
