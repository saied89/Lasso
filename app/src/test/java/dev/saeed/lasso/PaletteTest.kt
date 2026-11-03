package dev.saeed.lasso

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class PaletteTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun lightThemeHasDarkTextOnALightCard() {
        val palette = Palette(context)
        assertFalse(palette.dark)
        assertTrue(Color.luminance(palette.surface) > Color.luminance(palette.onSurface))
        assertTrue(Color.luminance(palette.surface) > Color.luminance(palette.onSurfaceVariant))
    }

    @Test
    @Config(qualifiers = "night")
    fun darkThemeHasLightTextOnADarkCard() {
        val palette = Palette(context)
        assertTrue(palette.dark)
        assertTrue(Color.luminance(palette.surface) < Color.luminance(palette.onSurface))
        assertTrue(Color.luminance(palette.surface) < Color.luminance(palette.onSurfaceVariant))
    }
}

