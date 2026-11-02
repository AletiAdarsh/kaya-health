package com.kaya.health.ui

import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kaya.health.band.BandState
import com.kaya.health.band.BandViewModel
import java.time.Duration
import java.time.Instant

/** What the band link is doing, in words a user understands. */
data class BandStatus(val short: String, val title: String, val text: String, val color: Color, val working: Boolean, val canRetry: Boolean)

@Composable
fun rememberBandStatus(band: BandViewModel): BandStatus {
    val context = LocalContext.current
    val state by band.state.collectAsState()
    val battery by band.battery.collectAsState()
    val sync by band.syncStatus.collectAsState()
    val permitted = BandViewModel.hasPermissions(context)
    val btOn = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true }.getOrDefault(true)
    val reconnecting by band.reconnecting.collectAsState()
    val saved = sync.lastAt?.let { "Showing what Kaya saved at ${it.clock()}." } ?: "Kaya hasn't read the band yet on this phone."
    return when {
        !permitted -> BandStatus("Allow Bluetooth", "Bluetooth permission needed",
            "Kaya needs the Nearby devices permission to talk to your band. Nothing else uses it.", Area.vitals, false, true)
        !btOn -> BandStatus("Bluetooth off", "Bluetooth is off",
            "Turn Bluetooth on in quick settings, then come back. $saved", Area.vitals, false, false)
        reconnecting -> BandStatus("Reconnecting…", "Reconnecting",
            "The Bluetooth link dropped (this happens when another app ends its session with the band). Kaya is connecting again by itself. $saved", Area.mind, true, false)
        state is BandState.Connecting -> BandStatus("Connecting…", "Connecting to your band",
            "Looking for your Noise REP. Keep it on and near the phone; this usually takes a few seconds. $saved", Area.mind, true, false)
        state is BandState.Connected && sync.running -> BandStatus("Syncing…", "Reading your band",
            (sync.step ?: "Reading today's data") + ". New data appears as it arrives.", Area.energy, true, false)
        state is BandState.Connected && sync.failed -> BandStatus("Band busy", "Your band is busy",
            "The band talks to one app at a time, and another app (usually NoiseFit) is using it. Kaya will try again by itself in a minute, or close NoiseFit and tap Try again. $saved", Area.body, false, true)
        state is BandState.Connected -> BandStatus("Band ${battery?.percent?.let { "$it%" } ?: "on"}", "Connected",
            "Connected directly over Bluetooth" + (battery?.let { ", battery ${it.percent}%" + if (it.charging) " (charging)" else "" } ?: "") + ". " +
                (sync.lastAt?.let { "Last synced ${ago(it)}." } ?: ""), Area.energy, false, true)
        state is BandState.Failed && (state as BandState.Failed).reason.startsWith("No Noise REP") -> BandStatus("Not found", "Couldn't find your band",
            "No Noise REP nearby. Wake the band or put it on the charger, keep it within a few metres, then tap Try again. $saved", Area.vitals, false, true)
        state is BandState.Failed -> BandStatus("Disconnected", "The band disconnected",
            "${(state as BandState.Failed).reason.removeSuffix(".")}. Tap Try again, or reopen Kaya; it reconnects by itself. $saved", Area.vitals, false, true)
        else -> BandStatus("Not connected", "Not connected",
            "Kaya lets go of the band while it's in the background so other apps can sync, and reconnects when you open it. $saved", Area.mind, false, true)
    }
}

private fun ago(t: Instant): String {
    val m = Duration.between(t, Instant.now()).toMinutes()
    return when { m < 1 -> "just now"; m < 60 -> "$m min ago"; m < 24 * 60 -> "${m / 60} h ago"; else -> "at ${t.clock()}" }
}

/** Header chip: always says what's happening; tap for the explanation and Try again. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BandStatusChip(band: BandViewModel, onOpenBand: () -> Unit) {
    val s = rememberBandStatus(band)
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.clip(CircleShape).background(s.color.copy(alpha = 0.14f)).clickable { open = true }.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (s.working) { CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = s.color); Spacer(Modifier.width(6.dp)) }
        Text(s.short, style = MaterialTheme.typography.labelLarge, color = s.color)
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(s.title, style = MaterialTheme.typography.headlineSmall)
            Text(s.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (s.canRetry) Button(onClick = { band.connectAndSync(force = true); open = false }, modifier = Modifier.weight(1f)) { Text("Try again") }
                OutlinedButton(onClick = { open = false; onOpenBand() }, modifier = Modifier.weight(1f)) { Text("Band details") }
            }
        }
    }
}

fun bandHasPermission(context: Context) = BandViewModel.hasPermissions(context)
