package dev.saeed.lasso

import android.content.Context
import android.content.res.Configuration
import android.os.Build

/**
 * Colors for the overlay and the word card. On Android 12+ they come from the system's
 * wallpaper-based colors (Material You), so Lasso matches the rest of the phone.
 */
class Palette(context: Context) {
    val dark = context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** Card and toolbar background. */
    val surface: Int
    /** Main text on [surface]. */
    val onSurface: Int
    /** Secondary text on [surface], like hints. */
    val onSurfaceVariant: Int
    /** Selection highlight, handles and card buttons. */
    val accent: Int

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            fun c(id: Int) = context.getColor(id)
            surface = c(if (dark) android.R.color.system_neutral1_800 else android.R.color.system_neutral1_50)
            onSurface = c(if (dark) android.R.color.system_neutral1_100 else android.R.color.system_neutral1_900)
            onSurfaceVariant = c(if (dark) android.R.color.system_neutral2_200 else android.R.color.system_neutral2_700)
            accent = c(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
        } else {
            surface = if (dark) 0xFF303134.toInt() else 0xFFFFFFFF.toInt()
            onSurface = if (dark) 0xFFE8EAED.toInt() else 0xFF202124.toInt()
            onSurfaceVariant = if (dark) 0xFFBDC1C6.toInt() else 0xFF5F6368.toInt()
            accent = if (dark) 0xFF8AB4F8.toInt() else 0xFF1A73E8.toInt()
        }
    }
}
