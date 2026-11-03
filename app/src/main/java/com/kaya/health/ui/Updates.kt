package com.kaya.health.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kaya.health.data.Updater
import java.text.DateFormat
import java.util.Date

const val UPDATES_EXPLAINED = "Kaya can check for a newer version by itself. Here's exactly what goes online:\n\n" +
    "• Once a day, Kaya asks github.com \"what's the latest version of Kaya?\". That question is all it sends: no account, no ID, nothing about you, your band or your health.\n\n" +
    "• If there's a newer version, you'll see it here. Nothing downloads until you tap Update.\n\n" +
    "• Kaya downloads the update from the project's GitHub page and checks it's signed by the same developer before Android asks you to confirm the install. Your data stays as it is.\n\n" +
    "Nothing else in Kaya uses the internet. You can turn this off any time in Settings."

/** Release notes without the markdown symbols. */
private fun plain(md: String) = md.lines()
    .map { it.replace("**", "").replace("`", "").removePrefix("### ").removePrefix("## ").removePrefix("# ").trim() }
    .takeWhile { !it.startsWith("Install") }
    .filter { it.isNotBlank() && !it.startsWith("APK SHA") && !it.startsWith("Signing certificate") }
    .joinToString("\n")

/** Home: asks once whether to check for updates online, then shows a new version when there is one. */
@Composable
fun UpdateHomeCard() {
    val context = LocalContext.current
    val ui by Updater.state.collectAsState()
    var answered by remember { mutableStateOf(Updater.asked(context)) }
    var hidden by remember { mutableStateOf(false) }
    if (!answered) {
        Panel(accent = Area.mind) {
            PanelLabel("Get updates inside Kaya?", Icons.Rounded.SystemUpdate, Area.mind, info = UPDATES_EXPLAINED)
            Muted("Kaya can check github.com once a day for a newer version, so you never have to look for it yourself. Only the question \"what's the latest version?\" is sent: nothing about you or your health. You tap Update when there's one; nothing installs by itself.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { Updater.setEnabled(context, true); answered = true }, modifier = Modifier.weight(1f)) { Text("Turn on") }
                OutlinedButton(onClick = { Updater.setEnabled(context, false); answered = true }, modifier = Modifier.weight(1f)) { Text("No thanks") }
            }
        }
        return
    }
    val rel = ui.available ?: return
    if (hidden || Updater.snoozed(context, rel.version)) return
    Panel(accent = Area.mind) {
        PanelLabel("Kaya ${rel.version} is ready", Icons.Rounded.SystemUpdate, Area.mind)
        UpdateBody(rel, ui.phase)
        if (ui.phase !is Updater.Phase.Downloading && ui.phase !is Updater.Phase.Installing) TextButton(onClick = { Updater.snooze(context, rel.version); hidden = true }) { Text("Later") }
    }
}

@Composable
private fun UpdateBody(rel: Updater.Release, phase: Updater.Phase) {
    val context = LocalContext.current
    var more by remember { mutableStateOf(false) }
    val notes = plain(rel.notes)
    if (notes.isNotBlank()) {
        Text(if (more) notes else notes.lines().take(4).joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (notes.lines().size > 4) TextButton(onClick = { more = !more }) { Text(if (more) "Less" else "What's new") }
    }
    when (phase) {
        is Updater.Phase.Downloading -> {
            Muted("Downloading… ${phase.percent}%")
            LinearProgressIndicator(progress = { phase.percent / 100f }, modifier = Modifier.fillMaxWidth(), color = Area.mind)
        }
        Updater.Phase.Installing -> Muted("Checking the signature and opening Android's installer…")
        Updater.Phase.NeedsPermission -> {
            Muted("Android needs you to allow Kaya to install updates (one time). Switch on \"Allow from this source\", come back, and tap Update again.")
            Button(onClick = { Updater.update(context, rel) }, modifier = Modifier.fillMaxWidth()) { Text("Update") }
        }
        is Updater.Phase.Failed -> {
            Text(phase.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Button(onClick = { Updater.update(context, rel) }, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
        }
        else -> Button(onClick = { Updater.update(context, rel) }, modifier = Modifier.fillMaxWidth()) {
            Text("Update" + if (rel.size > 0) " (%.1f MB)".format(rel.size / 1_048_576.0) else "")
        }
    }
}

/** Settings: the on/off switch with the full explanation, check now, and the current version. */
@Composable
fun UpdatesCard() {
    val context = LocalContext.current
    val ui by Updater.state.collectAsState()
    var on by remember { mutableStateOf(Updater.enabled(context)) }
    Panel(accent = Area.mind) {
        PanelLabel("Updates", Icons.Rounded.SystemUpdate, Area.mind, info = UPDATES_EXPLAINED)
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text("Check for updates online", style = MaterialTheme.typography.titleSmall)
                Muted("Once a day, asks github.com for the latest version. Sends nothing about you.")
            }
            Switch(checked = on, onCheckedChange = { on = it; Updater.setEnabled(context, it) })
        }
        Muted("You're on Kaya ${Updater.currentVersion(context)}" +
            (if (ui.lastChecked > 0) " · checked ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ui.lastChecked))}" else ""))
        val rel = ui.available
        when {
            rel != null -> { Text("Kaya ${rel.version} is available", style = MaterialTheme.typography.titleSmall, color = Area.mind); UpdateBody(rel, ui.phase) }
            ui.phase == Updater.Phase.Checking -> Muted("Checking…")
            ui.phase == Updater.Phase.UpToDate -> Muted("You have the latest version.")
            ui.phase is Updater.Phase.Failed -> Text((ui.phase as Updater.Phase.Failed).message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row {
            if (on) TextButton(onClick = { Updater.check(context) }) { Text("Check now") }
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.RELEASES_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text("All versions on GitHub") }
        }
    }
}
