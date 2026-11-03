package dev.saeed.lasso

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LassoTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    SettingsScreen(serviceEnabled, Modifier.padding(padding))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        serviceEnabled = isServiceEnabled(this)
    }
}

private fun isServiceEnabled(context: Context): Boolean {
    val ours = ComponentName(context, LassoService::class.java)
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == ours }
}

@Composable
private fun SettingsScreen(serviceEnabled: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Lasso", style = MaterialTheme.typography.headlineMedium)
        Text("Double-tap the back of your phone, or double-press Volume Down, in any app. The screen dims, and all text becomes selectable.")

        if (serviceEnabled) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Text("Lasso is on.", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
            }
        } else {
            DisclosureCard(onAgree = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        }

        Text("Settings", style = MaterialTheme.typography.titleMedium)
        Column {
            Text("Double-tap the back of the phone", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Set it up in the Settings app: System → Gestures → Quick Tap → Open app → Lasso.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Column {
            Text("Floating button or two-finger swipe", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Works on any phone. In the Settings app, open Accessibility → Lasso → Lasso shortcut, " +
                    "and pick the accessibility button or gesture. Tap it again to close Lasso.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SettingSwitch(Prefs.VOLUME_TRIGGER, "Double-press Volume Down", "Opens text selection.")
        SettingSwitch(Prefs.RESTORE_VOLUME, "Undo the volume change", "The first press lowers the volume one step. Raise it back.")
        SettingSwitch(Prefs.OCR, "Recognize text in the screenshot", "Makes words selectable in web views, images and games. Text is recognized on the phone.")
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
    }
}

/** Google Play requires telling users what the accessibility service does before they turn it on. */
@Composable
private fun DisclosureCard(onAgree: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Turn on Lasso", style = MaterialTheme.typography.titleMedium)
            Text(
                "Lasso uses Android's accessibility service to:\n" +
                    "• read the text shown on screen, only when you trigger it, to make it selectable\n" +
                    "• take a screenshot at that moment and recognize the text in it, on the phone\n" +
                    "• listen for volume key presses, to notice the double-press\n\n" +
                    "Nothing is saved or sent anywhere. Lasso has no internet access."
            )
            Text(
                "If you installed Lasso from a file, Android may block the switch. Open App info → ⋮ → " +
                    "Allow restricted settings, then try again.",
                style = MaterialTheme.typography.bodySmall,
            )
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
