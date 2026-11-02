package com.kaya.health.ui

import android.content.Context
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import kotlinx.coroutines.launch

private const val PREFS_ON = "kaya_profile"
private const val BACKUP_KEY = "backupStepDone"
private const val TOUR_KEY = "tourDone"

private fun prefs(c: Context) = c.getSharedPreferences(PREFS_ON, Context.MODE_PRIVATE)
fun backupStepDone(context: Context) = prefs(context).getBoolean(BACKUP_KEY, false)
fun tourDone(context: Context) = prefs(context).getBoolean(TOUR_KEY, false)
fun setTourDone(context: Context, done: Boolean) = prefs(context).edit { putBoolean(TOUR_KEY, done) }

/** Step 4: where the nightly backup goes. Google Drive is recommended; skipping needs an explicit "I understand". */
@Composable
fun BackupStepScreen(vm: AppViewModel, onDone: () -> Unit) = Surface(color = MaterialTheme.colorScheme.background) {
    val context = LocalContext.current
    val s by vm.backup.collectAsState()
    var confirmSkip by remember { mutableStateOf(false) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.backupTarget(uri, uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':'))
    }
    fun finish() { prefs(context).edit { putBoolean(BACKUP_KEY, true) }; onDone() }
    val chosen = s.uri != null

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Step 4 of 4", style = MaterialTheme.typography.labelLarge, color = Area.energy)
        Text("Back up your data", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Muted("Kaya has no server, so your history lives only on this phone. Choose where Kaya saves one backup file every night, so uninstalling or changing phones never loses anything.")
        Choice(Icons.Rounded.CloudUpload, Area.energy, "Google Drive", "Safe even if you lose the phone. On the next screen tap ☰, choose Drive, then Save.", recommended = true) {
            create.launch("kaya-backup.zip")
        }
        Choice(Icons.Rounded.PhoneAndroid, Area.mind, "This phone", "A file in Downloads or Documents. Survives a reinstall, but not a lost or reset phone.") {
            create.launch("kaya-backup.zip")
        }
        if (chosen) Panel(accent = Area.food) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Area.food)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.fileName ?: "Backup file chosen", style = MaterialTheme.typography.titleMedium)
                    Muted(s.lastAt?.let { "First backup saved at ${it.clock()}. Then every night." } ?: "Saving the first backup…")
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Button(onClick = ::finish, enabled = chosen, modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Text(if (chosen) "Continue" else "Choose where to back up", style = MaterialTheme.typography.titleMedium)
        }
        if (!chosen) TextButton(onClick = { confirmSkip = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Skip without a backup") }
        Muted("You can change this any time in Settings → Backup.")
    }
    if (confirmSkip) AlertDialog(
        onDismissRequest = { confirmSkip = false },
        title = { Text("Continue without a backup?") },
        text = { Text("If you uninstall Kaya, or reset or lose your phone, your sleep history, habits and settings can't be recovered. (Food and weight stay in Health Connect.)") },
        confirmButton = { TextButton(onClick = { confirmSkip = false; finish() }) { Text("I understand, skip") } },
        dismissButton = { TextButton(onClick = { confirmSkip = false }) { Text("Choose a backup") } },
    )
}

@Composable
private fun Choice(icon: ImageVector, accent: Color, title: String, text: String, recommended: Boolean = false, onClick: () -> Unit) {
    Panel(accent = if (recommended) accent else null, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (recommended) Tag("Recommended", accent)
        }
        Muted(text)
    }
}

private class TourPage(val icon: ImageVector, val accent: () -> Color, val title: String, val text: String)

private val TOUR = listOf(
    TourPage(Icons.Rounded.Home, { Area.energy }, "Your day at a glance",
        "Home shows Energy, Sleep and Effort, then food, water, weight and vitals. Tap a score to see exactly how it's worked out."),
    TourPage(Icons.Rounded.Bluetooth, { Area.vitals }, "The band chip, top right",
        "It always says what the band link is doing: Connecting, Syncing, Band 80%, or Band busy when NoiseFit is using it. Tap it for details and Try again. Kaya lets go of the band in the background and reconnects when you open it."),
    TourPage(Icons.Rounded.Menu, { Area.mind }, "Everything else is in the menu",
        "Tap ☰ for Activity, Sleep, Vitals, Stats, Mind, Food, Body, Discipline and Settings."),
    TourPage(Icons.Rounded.Restaurant, { Area.food }, "Log food in seconds",
        "Food is a diary by meal. Recent turns anything you've eaten before into one tap, and \"What can I eat?\" suggests meals that fit what's left."),
    TourPage(Icons.Rounded.Bedtime, { Area.sleep }, "Wear the band to bed",
        "Sleep stages, efficiency, breathing and sleeping heart rate appear each morning. Vitals has live heart rate any time."),
    TourPage(Icons.Rounded.Insights, { Area.energy }, "Stats, with the research",
        "35+ numbers from your own data, each with an ⓘ explaining the science and a healthy range. Many fill in after a few days and nights."),
    TourPage(Icons.Rounded.EmojiEvents, { Area.body }, "Stay on track",
        "Discipline has habits, a morning plan and Craving SOS for when a craving hits. Add the Kaya widget to your home screen for calories left and one-tap SOS. Something not right? Settings → Feedback."),
)

/** Short swipeable tour: once after setup, and any time from Settings. The band syncs in the background meanwhile. */
@Composable
fun TourScreen(band: com.kaya.health.band.BandViewModel? = null, onDone: () -> Unit) = Surface(color = MaterialTheme.colorScheme.background) {
    val pager = rememberPagerState { TOUR.size }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Quick tour", style = MaterialTheme.typography.labelLarge, color = Area.energy, modifier = Modifier.weight(1f))
            TextButton(onClick = onDone) { Text("Skip") }
        }
        HorizontalPager(pager, Modifier.weight(1f)) { i ->
            val p = TOUR[i]; val accent = p.accent()
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(28.dp)).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Icon(p.icon, contentDescription = null, tint = accent, modifier = Modifier.size(48.dp))
                }
                Spacer(Modifier.height(28.dp))
                Text(p.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text(p.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // While the tour runs, the band is read in the background; show it so the wait is visible.
        band?.let { b ->
            val st by b.syncStatus.collectAsState()
            if (st.running || st.lastAt != null) Text(
                if (st.running) "Reading your band in the background…" else "Your band's data is ready.",
                style = MaterialTheme.typography.bodySmall, color = if (st.running) Area.mind else Area.food,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
            repeat(TOUR.size) { i ->
                Box(Modifier.padding(4.dp).size(if (pager.currentPage == i) 10.dp else 8.dp).clip(CircleShape)
                    .background(if (pager.currentPage == i) Area.energy else MaterialTheme.colorScheme.outline))
            }
        }
        val last = pager.currentPage == TOUR.size - 1
        Button(onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
            modifier = Modifier.fillMaxWidth().height(54.dp)) { Text(if (last) "Start using Kaya" else "Next", style = MaterialTheme.typography.titleMedium) }
    }
}
