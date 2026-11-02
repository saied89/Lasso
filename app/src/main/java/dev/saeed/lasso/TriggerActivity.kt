package dev.saeed.lasso

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * The app's launcher entry, with no UI of its own.
 *
 * Pixel's Quick Tap (double-tap the back of the phone) can only "open an app", so it opens this:
 * we start text selection and close right away. Tapping the Lasso icon on the home screen
 * opens the settings screen instead.
 */
class TriggerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = LassoService.instance
        if (service == null || launchedFromHomeScreen()) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            // Give the app underneath a moment to settle after our launch.
            service.trigger(delayMs = 150)
        }
        // Theme.NoDisplay requires finishing before onResume.
        finish()
    }

    private fun launchedFromHomeScreen(): Boolean {
        val from = referrer?.host ?: return false
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homePackage = packageManager.resolveActivity(home, 0)?.activityInfo?.packageName
        return from == homePackage
    }
}
