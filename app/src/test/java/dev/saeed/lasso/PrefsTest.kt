package dev.saeed.lasso

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrefsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun settingsAreOnByDefault() {
        assertTrue(Prefs.pauseMedia(context))
    }

    @Test
    fun aChangedSettingIsKept() {
        Prefs.set(context, Prefs.PAUSE_MEDIA, false)
        assertFalse(Prefs.pauseMedia(context))
        Prefs.set(context, Prefs.PAUSE_MEDIA, true)
        assertTrue(Prefs.pauseMedia(context))
    }
}
