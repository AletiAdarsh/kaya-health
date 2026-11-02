@file:OptIn(ExperimentalLayoutApi::class)

package com.kaya.health.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun sizeText(b: Long) = if (b < 1_048_576) "${(b + 1023) / 1024} KB" else "%.1f MB".format(b / 1_048_576.0)

private fun hourLabel(h: Int) = when { h == 0 -> "12 am"; h < 12 -> "$h am"; h == 12 -> "12 pm"; else -> "${h - 12} pm" }

/** Restore picker, used on the welcome screen too (after a reinstall). */
@Composable
fun RestoreButton(vm: AppViewModel, label: String = "Restore from a backup file") {
    var picked by remember { mutableStateOf<android.net.Uri?>(null) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> picked = uri }
    OutlinedButton(onClick = { open.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
    picked?.let { uri ->
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text("Restore this backup?") },
            text = { Text("Your settings and band history are replaced with the backup's (band days already on this phone are kept if they're fuller). Food and weigh-ins are added back to Health Connect, skipping any already there. Kaya restarts afterwards.") },
            confirmButton = { TextButton(onClick = { picked = null; vm.restoreBackup(uri, relink = true) }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { picked = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun BackupCard(vm: AppViewModel) {
    val s by vm.backup.collectAsState()
    LaunchedEffect(Unit) { vm.refreshBackup() }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.backupTarget(uri, uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':'))
    }

    Panel(accent = Area.mind) {
        PanelLabel("Backup", Icons.Rounded.CloudUpload, Area.mind, if (s.uri != null && s.daily) "daily at ${hourLabel(s.hour)}" else "off")
        Muted(
            "Kaya has no server and never uploads anything by itself. If you want a backup, choose where it goes: " +
                "a file on your phone, or in your own Google Drive, OneDrive or Dropbox. Kaya rewrites that one file every day."
        )

        if (s.uri == null) {
            Button(onClick = { create.launch("kaya-backup.zip") }, modifier = Modifier.fillMaxWidth()) { Text("Choose where to back up") }
        } else {
            Text(s.fileName ?: "Backup file chosen", style = MaterialTheme.typography.titleMedium)
            Muted(
                when {
                    s.lastError != null -> "Last backup failed: ${s.lastError}"
                    s.lastAt != null -> "Last backup " + s.lastAt!!.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM, h:mm a")) +
                        " · " + sizeText(s.lastBytes)
                    else -> "No backup yet."
                }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Back up every day", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = s.daily, onCheckedChange = { vm.backupDaily(it, s.hour) })
            }
            if (s.daily) {
                Text("At", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(6, 12, 18, 21, 23).forEach { h -> FilterChip(selected = s.hour == h, onClick = { vm.backupDaily(true, h) }, label = { Text(hourLabel(h)) }) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::backupNow, modifier = Modifier.weight(1f)) { Text("Back up now") }
                OutlinedButton(onClick = { create.launch("kaya-backup.zip") }, modifier = Modifier.weight(1f)) { Text("Change file") }
            }
        }
        RestoreButton(vm)

        Spacer(Modifier.width(1.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Android backup", style = MaterialTheme.typography.bodyLarge)
                Muted("Optional. Android's own backup, end-to-end encrypted with your screen lock. Restores Kaya automatically when you reinstall it or move to a new phone.")
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = s.androidBackup, onCheckedChange = vm::androidBackup)
        }
    }
}
