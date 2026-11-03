package com.kaya.health.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Feedback
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kaya.health.band.BandViewModel

const val ISSUES_URL = "https://github.com/AletiAdarsh/kaya-health/issues"

/**
 * Feedback without Kaya sending anything itself: it only opens the browser on the project's public GitHub issue form,
 * pre-filled with technical details (no health data, names or IDs). The user sees and edits everything before sending.
 */
@Composable
fun FeedbackCard(band: BandViewModel, onTour: () -> Unit) {
    val context = LocalContext.current
    val info by band.info.collectAsState()
    val status = rememberBandStatus(band)
    fun details() = buildString {
        appendLine("---")
        appendLine("Kaya ${appVersion(context)} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Band firmware: ${info?.firmware ?: "unknown"} · band status: ${status.short}")
    }
    Panel(accent = Area.mind) {
        PanelLabel("Feedback & requests", Icons.Rounded.Feedback, Area.mind)
        Muted("Something wrong, confusing or missing? Tell us on the project's public issue page. It opens in your browser with your app, Android and band versions filled in; no health data. You can read and edit everything before sending (a free GitHub account is needed).")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { openIssue(context, "bug", "Problem: ", "What happened?\n\n\nWhat did you expect?\n\n\nSteps to see it again:\n1. \n\n" + details()) }, modifier = Modifier.weight(1f)) {
                Text("Report a problem")
            }
            OutlinedButton(onClick = { openIssue(context, "enhancement", "Idea: ", "What would you like Kaya to do?\n\n\nWhy would it help you?\n\n" + details()) }, modifier = Modifier.weight(1f)) {
                Text("Request a feature")
            }
        }
        Row {
            TextButton(onClick = { open(context, ISSUES_URL) }) { Text("See all reports") }
            TextButton(onClick = onTour) { Text("Take the tour again") }
        }
    }
}

private fun appVersion(c: Context) = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "?"

private fun openIssue(c: Context, label: String, title: String, body: String) =
    open(c, "$ISSUES_URL/new?labels=$label&title=${Uri.encode(title)}&body=${Uri.encode(body)}")

private fun open(c: Context, url: String) {
    runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
