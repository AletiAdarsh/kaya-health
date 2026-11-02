@file:OptIn(ExperimentalMaterial3Api::class)

package com.kaya.health.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kaya.health.data.Reminders

private fun timeText(h: Int, m: Int) = "%d:%02d %s".format(if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "am" else "pm")

@Composable
fun RemindersCard() {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var pendingOn by remember { mutableStateOf<Reminders.Kind?>(null) }
    var editing by remember { mutableStateOf<Reminders.Kind?>(null) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingOn?.let { k -> if (granted) { Reminders.set(context, k, Reminders.get(context, k).copy(on = true)); version++ } }
        pendingOn = null
    }

    Panel(accent = Area.mind) {
        PanelLabel("Reminders", Icons.Rounded.Notifications, Area.mind)
        Muted("Each one checks first and stays quiet when there's nothing to say.")
        Reminders.Kind.entries.forEach { k ->
            val s = remember(version) { Reminders.get(context, k) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(k.label, style = MaterialTheme.typography.bodyLarge)
                    Text(k.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (k != Reminders.Kind.MOVE && k != Reminders.Kind.CRAVING && s.on) TextButton(onClick = { editing = k }) { Text("at ${timeText(s.hour, s.minute)}") }
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = s.on, onCheckedChange = { on ->
                    if (on && !Reminders.canNotify(context) && Build.VERSION.SDK_INT >= 33) {
                        pendingOn = k; askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else { Reminders.set(context, k, s.copy(on = on)); version++ }
                })
            }
        }
        if (!Reminders.canNotify(context)) Muted("Notifications are off for Kaya. Switching a reminder on will ask for permission.")
    }

    editing?.let { k ->
        val s = Reminders.get(context, k)
        val state = rememberTimePickerState(initialHour = s.hour, initialMinute = s.minute, is24Hour = false)
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(k.label) },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = { Reminders.set(context, k, s.copy(hour = state.hour, minute = state.minute)); version++; editing = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

