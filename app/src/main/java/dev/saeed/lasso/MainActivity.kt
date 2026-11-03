package dev.saeed.lasso

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.net.toUri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.saeed.lasso.ui.theme.LassoTheme

class MainActivity : ComponentActivity() {
    private var serviceEnabled by mutableStateOf(false)
    private var restricted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LassoTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    SettingsScreen(serviceEnabled, restricted, Modifier.padding(padding))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        serviceEnabled = isServiceEnabled(this)
        restricted = isRestricted(this)
    }
}

private fun isServiceEnabled(context: Context): Boolean {
    val ours = ComponentName(context, LassoService::class.java)
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == ours }
}

/**
 * Whether Android may block turning on Lasso's service until the user allows restricted settings.
 * Android 13+ does this only for apps installed from a file, not from an app store or adb.
 * Whether the user already allowed it can't be read without a system permission.
 */
private fun isRestricted(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val source = context.packageManager.getInstallSourceInfo(context.packageName).packageSource
    return isRestrictedSource(Build.VERSION.SDK_INT, source)
}

/** [isRestricted] for an Android version and a `PackageInstaller.PACKAGE_SOURCE_*` value. */
internal fun isRestrictedSource(sdk: Int, packageSource: Int): Boolean =
    sdk >= Build.VERSION_CODES.TIRAMISU &&
        (packageSource == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE ||
            packageSource == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)

/** How this phone can open an app with a double tap on its back, if it can. */
internal enum class BackTap { QUICK_TAP, SAMSUNG, NONE }

internal fun backTap(context: Context) = when {
    // Set by Pixels that have Quick Tap; missing on older Pixels and other phones.
    context.packageManager.hasSystemFeature("com.google.android.feature.QUICK_TAP") -> BackTap.QUICK_TAP
    Build.MANUFACTURER.equals("samsung", ignoreCase = true) -> BackTap.SAMSUNG
    else -> BackTap.NONE
}

/** Opens Pixel's Quick Tap settings, or the main Settings screen if that page isn't there. */
private fun openQuickTapSettings(context: Context) {
    try {
        context.startActivity(Intent("com.google.android.settings.gestures.QUICK_TAP_SETTINGS"))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

@Composable
private fun SettingsScreen(serviceEnabled: Boolean, restricted: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val backTap = remember { backTap(context) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Lasso", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (backTap == BackTap.NONE) "Tap the accessibility button in any app. The screen dims, and all text becomes selectable."
            else "Double-tap the back of your phone, or tap the accessibility button, in any app. The screen dims, and all text becomes selectable."
        )

        if (serviceEnabled) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Text("Lasso is on.", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
            }
        } else {
            DisclosureCard(restricted, onAgree = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        }

        Text("Settings", style = MaterialTheme.typography.titleMedium)
        when (backTap) {
            BackTap.QUICK_TAP -> Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { openQuickTapSettings(context) },
            ) {
                Text("Double-tap the back of the phone", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Tap here to open Quick Tap, then choose Open app → Lasso.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            BackTap.SAMSUNG -> Column {
                Text("Double-tap the back of the phone", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Install Good Lock from the Galaxy Store and add its RegiStar module. In RegiStar, " +
                        "open Back-Tap action → Double tap → Open app → Lasso.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            BackTap.NONE -> Unit
        }
        Column {
            Text("Floating button or two-finger swipe", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Works on any phone. In the Settings app, open Accessibility → Lasso → Lasso shortcut, " +
                    "and pick the accessibility button or gesture. Tap it again to close Lasso.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SettingSwitch(Prefs.PAUSE_MEDIA, "Pause media while selecting", "Pauses videos and music, and resumes them when you're done.")

        Text("Try it", style = MaterialTheme.typography.titleMedium)
        Text(
            "Select part of this sentence to test Lasso. Words, whole paragraphs, or text spread " +
                "across several lines all work.",
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedButton(enabled = serviceEnabled, onClick = { LassoService.instance?.trigger() }) {
            Text("Select text on this screen")
        }

        TextButton(onClick = { openPrivacyPolicy(context) }) { Text("Privacy policy") }
    }
}

/** Opens the privacy policy in the browser. Google Play requires a link to it inside the app. */
private fun openPrivacyPolicy(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, PRIVACY_POLICY_URL.toUri()))
    } catch (_: ActivityNotFoundException) {
        // No browser; nothing to open it with.
    }
}

private const val PRIVACY_POLICY_URL = "https://github.com/saied89/Lasso/blob/main/PRIVACY.md"

/** Google Play requires telling users what the accessibility service does before they turn it on. */
@Composable
private fun DisclosureCard(restricted: Boolean, onAgree: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Turn on Lasso", style = MaterialTheme.typography.titleMedium)
            Text(
                "Lasso uses Android's accessibility service to:\n" +
                    "• take a screenshot when you open Lasso, and recognize the text in it on the phone\n" +
                    "• watch for the Back key while Lasso is open, to close it\n\n" +
                    "Nothing is saved or sent anywhere. Lasso has no internet access."
            )
            if (restricted) {
                Text(
                    "Android may block the switch because Lasso was installed from a file. Open App info → ⋮ → " +
                        "Allow restricted settings, then try again.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(onClick = onAgree) { Text("Agree and open settings") }
        }
    }
}

@Composable
private fun SettingSwitch(key: String, title: String, summary: String) {
    val context = LocalContext.current
    var checked by remember { mutableStateOf(Prefs.get(context, key)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = {
            checked = it
            Prefs.set(context, key, it)
        })
    }
}
