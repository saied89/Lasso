package dev.saeed.lasso

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
class BackTapTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun pixelWithQuickTap() {
        shadowOf(context.packageManager).setSystemFeature("com.google.android.feature.QUICK_TAP", true)
        assertEquals(BackTap.QUICK_TAP, backTap(context))
    }

    @Test
    fun samsungUsesGoodLock() {
        ShadowBuild.setManufacturer("samsung")
        assertEquals(BackTap.SAMSUNG, backTap(context))
    }

    @Test
    fun otherPhonesHaveNoBackTap() {
        ShadowBuild.setManufacturer("Google")
        assertEquals(BackTap.NONE, backTap(context))
    }
}
