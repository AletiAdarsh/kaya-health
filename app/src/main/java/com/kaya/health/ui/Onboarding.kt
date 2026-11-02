package com.kaya.health.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.health.connect.client.PermissionController
import com.kaya.health.band.BandViewModel

/** First screen after install: what Kaya is, the privacy promise, then set up or restore. */
@Composable
fun WelcomeScreen(vm: AppViewModel, onStart: () -> Unit) = androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier.size(84.dp).clip(RoundedCornerShape(26.dp)).background(Brush.linearGradient(listOf(Area.energy, Area.mind))),
            contentAlignment = Alignment.Center,
        ) { Text("K", color = Color(0xFF00201C), fontWeight = FontWeight.Black, fontSize = 44.sp) }
        Column {
            Text("Kaya", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
            Text("काया · \"the body\"", style = MaterialTheme.typography.titleMedium, color = Area.energy)
        }
        Text("Honest health numbers from your Noise REP band.", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Point(Icons.Rounded.Bluetooth, Area.vitals, "Straight from your band", "Heart rate, sleep, steps and more, read directly over Bluetooth. Live heart rate every 2 seconds.")
        Point(Icons.Rounded.Science, Area.sleep, "Lab maths, not guesses", "Calories from lab equations, sleep from a model tested in a sleep lab, and every number explains itself.")
        Point(Icons.Rounded.Restaurant, Area.food, "Food, weight and habits", "A goal plan that adjusts every day, a food diary, Craving SOS and streaks.")
        Point(Icons.Rounded.Lock, Area.body, "Private by design", "No account, no server, no internet permission. Your data stays on your phone and in backups you choose.")
        Spacer(Modifier.height(8.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Get started", style = MaterialTheme.typography.titleMedium) }
        RestoreButton(vm, "I have a Kaya backup: restore it")
        Text(
            "Independent and open source. Not affiliated with Noise. Not a medical device.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Point(icon: ImageVector, accent: Color, title: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Muted(text)
        }
    }
}

private const val PREFS = "kaya_profile"
private const val KEY = "connectDone"

fun connectStepDone(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

/** After setup: grant what Kaya needs, each explained, each optional. */
@Composable
fun ConnectScreen(vm: AppViewModel, band: BandViewModel, onDone: () -> Unit) = androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background) {
    val context = LocalContext.current
    val hc by vm.hc.collectAsState()
    var tick by remember { mutableIntStateOf(0) }   // re-read grants after each prompt
    val bt = remember(tick) { BandViewModel.hasPermissions(context) }
    val notif = remember(tick) {
        Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++; if (BandViewModel.hasPermissions(context)) band.connectAndSync()
    }
    val hcLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { vm.refreshAll(); tick++ }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        tick++; com.kaya.health.data.Reminders.schedule(context)
    }
    fun finish() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY, true) }
        onDone()
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Step 3 of 3", style = MaterialTheme.typography.labelLarge, color = Area.energy)
        Text("Connect", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Muted("Each one is optional and can be changed later in Settings. Kaya only uses them for what's written here.")
        Grant(Icons.Rounded.Bluetooth, Area.vitals, "Your band", "Bluetooth, to read your Noise REP directly. Turn the band on and keep it near the phone.", bt) {
            btLauncher.launch(BandViewModel.requiredPermissions())
        }
        Grant(Icons.Rounded.Restaurant, Area.food, "Food & weight", "Health Connect, Android's on-phone health store, where Kaya saves the food and weigh-ins you log.", hc.connected) {
            hcLauncher.launch(vm.permissionsToRequest())
        }
        Grant(Icons.Rounded.Notifications, Area.energy, "Reminders", "Notifications for the reminders you switch on (weigh-in, food check, bedtime, craving heads-up).", notif) {
            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = ::finish, modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Text(if (bt && hc.connected && notif) "Open Kaya" else "Continue", style = MaterialTheme.typography.titleMedium)
        }
        if (!(bt && hc.connected && notif)) TextButton(onClick = ::finish, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Skip for now") }
    }
}

@Composable
private fun Grant(icon: ImageVector, accent: Color, title: String, text: String, granted: Boolean, onAllow: () -> Unit) {
    Panel(accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (granted) Icon(Icons.Rounded.CheckCircle, contentDescription = "Allowed", tint = Area.food)
            else FilledTonalButton(onClick = onAllow, colors = ButtonDefaults.filledTonalButtonColors()) { Text("Allow") }
        }
        Muted(text)
    }
}
