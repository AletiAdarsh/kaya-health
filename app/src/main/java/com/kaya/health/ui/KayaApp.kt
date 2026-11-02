package com.kaya.health.ui

import androidx.compose.material.icons.rounded.CheckCircle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kaya.health.band.BandState
import com.kaya.health.band.BandViewModel
import kotlinx.coroutines.launch

/** Every page. The first seven are in the side menu; the rest open from inside other pages. */
enum class Dest(val label: String, val icon: ImageVector?, val accent: Color, val blurb: String = "") {
    HOME("Home", Icons.Rounded.Home, Area.energy, "Scores and your day"),
    ACTIVITY("Activity", Icons.AutoMirrored.Rounded.DirectionsWalk, Area.activity, "Steps, zones, calories"),
    SLEEP("Sleep", Icons.Rounded.Bedtime, Area.sleep, "Last night, stages, score"),
    VITALS("Vitals", Icons.Rounded.MonitorHeart, Area.vitals, "Heart, SpO2, stress, HRV"),
    STATS("Stats", Icons.Rounded.Insights, Area.energy, "Every number, with the research"),
    MIND("Mind", Icons.Rounded.SelfImprovement, Area.mind, "Breathing, stress"),
    FOOD("Food", Icons.Rounded.Restaurant, Area.food, "Log meals, target"),
    BODY("Body", Icons.Rounded.MonitorWeight, Area.body, "Weight goal and plan"),
    DISCIPLINE("Discipline", Icons.Rounded.EmojiEvents, Area.body, "Habits, cravings, focus"),
    SETTINGS("Settings", Icons.Rounded.Settings, Area.energy),
    CALORIES("Calories", null, Area.food),
    DEVICE("Band", null, Area.energy),
    CHECK("Check", null, Area.energy),
}

private val menuItems = listOf(Dest.HOME, Dest.ACTIVITY, Dest.SLEEP, Dest.VITALS, Dest.STATS, Dest.MIND, Dest.FOOD, Dest.BODY, Dest.DISCIPLINE)

@Composable
fun KayaApp(vm: AppViewModel = viewModel(), band: BandViewModel = viewModel()) {
    val profile by vm.profile.collectAsState()
    // Apply the chosen look as soon as the profile loads or changes.
    LaunchedEffect(profile.look) { KayaLook.current = runCatching { Look.valueOf(profile.look) }.getOrDefault(Look.CLASSIC) }
    val snack by vm.snack.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var destName by rememberSaveable { mutableStateOf(Dest.HOME.name) }
    val dest = Dest.valueOf(destName)
    var back by remember { mutableStateOf<Dest?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val go: (Dest) -> Unit = { d ->
        back = if (d in menuItems || d == Dest.SETTINGS) null else dest
        destName = d.name
        scope.launch { drawer.close() }
    }
    val permissionLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { vm.refreshAll() }

    LifecycleResumeEffect(Unit) {
        band.onForeground()
        vm.refreshAll()
        // Leaving Kaya: give the band back so other apps (NoiseFit) can sync.
        onPauseOrDispose { band.releaseIfIdle() }
    }
    // Ask for Bluetooth once setup is done (a fresh install has no permission yet), then connect.
    val context = androidx.compose.ui.platform.LocalContext.current
    val btLauncher = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {
        if (com.kaya.health.band.BandViewModel.hasPermissions(context)) band.connectAndSync()
    }
    // Set up: Welcome → About you → Connect. Existing installs (already set up) skip the Connect step's screen
    // but still get the Bluetooth prompt if it's missing.
    var connectDone by rememberSaveable { mutableStateOf(connectStepDone(context) || profile.setupDone && BandViewModel.hasPermissions(context)) }
    LaunchedEffect(profile.setupDone, connectDone) {
        if (profile.setupDone && connectDone && !com.kaya.health.band.BandViewModel.hasPermissions(context))
            btLauncher.launch(com.kaya.health.band.BandViewModel.requiredPermissions())
    }
    LaunchedEffect(snack) { snack?.let { snackbar.showSnackbar(it); vm.snackShown() } }
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(enabled = !drawer.isOpen && dest != Dest.HOME) { destName = (back ?: Dest.HOME).name; back = null }

    if (!profile.setupDone) {
        var started by rememberSaveable { mutableStateOf(false) }
        BackHandler(enabled = started) { started = false }
        if (!started) WelcomeScreen(vm) { started = true }
        else Scaffold(containerColor = MaterialTheme.colorScheme.background) { p -> Box(Modifier.fillMaxSize().padding(p)) { ProfileScreen(vm, firstRun = true) } }
        return
    }
    if (!connectDone) {
        ConnectScreen(vm, band) { connectDone = true }
        return
    }
    val backupState by vm.backup.collectAsState()
    var backupDone by rememberSaveable { mutableStateOf(backupStepDone(context) || backupState.uri != null) }
    if (!backupDone) {
        BackupStepScreen(vm) { backupDone = true }
        return
    }
    var showTour by rememberSaveable { mutableStateOf(!tourDone(context)) }
    if (showTour) {
        TourScreen(band) { setTourDone(context, true); showTour = false }
        return
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = true,
        scrimColor = Color.Black.copy(alpha = 0.55f),
        drawerContent = { Menu(vm, band, dest, go) },
    ) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
            CompositionLocalProvider(LocalOpenMenu provides { scope.launch { drawer.open() } }) {
                Box(Modifier.fillMaxSize().padding(padding)) {
                    AnimatedContent(
                        dest,
                        transitionSpec = { (fadeIn(tween(260)) + slideInHorizontally(tween(320)) { it / 12 }) togetherWith fadeOut(tween(160)) },
                        label = "page",
                    ) { d ->
                        when (d) {
                            Dest.HOME -> HomeScreen(vm, band, go)
                            Dest.ACTIVITY -> ActivityScreen(vm, band, go)
                            Dest.SLEEP -> SleepScreen(band, vm)
                            Dest.VITALS -> VitalsScreen(band, losingWeight = profile.goalWeightKg != null)
                            Dest.MIND -> MindScreen(band)
                            Dest.STATS -> StatsScreen(vm, band)
                            Dest.FOOD -> FoodScreen(vm)
                            Dest.BODY -> BodyScreen(vm, band)
                            Dest.DISCIPLINE -> DisciplineScreen(vm)
                            Dest.SETTINGS -> ProfileScreen(
                                vm, firstRun = false,
                                onOpenCheck = { go(Dest.CHECK) }, onOpenBand = { go(Dest.DEVICE) },
                                onConnectHealth = { permissionLauncher.launch(vm.permissionsToRequest()) },
                                band = band, onTour = { showTour = true },
                            )
                            Dest.CALORIES -> TodayScreen(vm, onConnect = { permissionLauncher.launch(vm.permissionsToRequest()) }, onOpenProfile = { go(Dest.SETTINGS) })
                            Dest.DEVICE -> BandScreen(band)
                            Dest.CHECK -> CheckScreen(vm)
                        }
                    }
                    SyncBar(band, Modifier.align(Alignment.TopCenter), Modifier.align(Alignment.BottomCenter))
                    val pending by com.kaya.health.MainActivity.pendingOpen
                    if (pending == "sos") {
                        val body by vm.body.collectAsState()
                        val today by vm.today.collectAsState()
                        CravingSos(
                            weightKg = body.latestKg ?: profile.fallbackWeightKg, heightCm = profile.heightCm,
                            targetKcal = today.targetKcal ?: body.targetKcal, eatenToday = today.eatenKcal, currentKg = body.latestKg,
                            onLogFood = { f -> vm.addFood(f.name, f.kcal, f.protein, AppViewModel.mealTypeNow(), f.carbs, f.fat, f.fiber) },
                            onClose = { com.kaya.health.MainActivity.pendingOpen.value = null },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Menu(vm: AppViewModel, band: BandViewModel, current: Dest, go: (Dest) -> Unit) {
    val body by vm.body.collectAsState()
    val bandState by band.state.collectAsState()
    val battery by band.battery.collectAsState()
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
        modifier = Modifier.width(300.dp),
    ) {
        Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 18.dp)) {
            // brand
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, bottom = 16.dp)) {
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(13.dp))
                        .background(Brush.linearGradient(listOf(Area.energy, Area.mind))),
                    contentAlignment = Alignment.Center,
                ) { Text("K", color = Color(0xFF00201C), fontWeight = FontWeight.Black, fontSize = 22.sp) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Kaya", style = MaterialTheme.typography.titleLarge)
                    Text("honest health", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // goal mini card
            body.plan?.let { p ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Area.body.copy(alpha = 0.10f)).clickable { go(Dest.BODY) }.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("%.1f → %.1f kg".format(p.currentKg, p.goalKg), style = MaterialTheme.typography.titleMedium)
                    Bar(p.progress, Area.body, height = 6.dp)
                    Text("Eat ${p.dailyTarget.kcal()} kcal today", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(14.dp))
            }
            menuItems.forEach { d -> MenuRow(d, d == current) { go(d) } }
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
            Spacer(Modifier.height(12.dp))
            MenuRow(Dest.SETTINGS, current == Dest.SETTINGS || current == Dest.CHECK) { go(Dest.SETTINGS) }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { go(Dest.DEVICE) }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Watch, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Noise REP", style = MaterialTheme.typography.titleMedium)
                    Text(
                        rememberBandStatus(band).title + (battery?.takeIf { bandState is BandState.Connected }?.let { " · ${it.percent}%" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(Modifier.size(9.dp).clip(CircleShape).background(if (bandState is BandState.Connected) Area.food else Area.vitals))
            }
        }
    }
}

@Composable
private fun MenuRow(d: Dest, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) d.accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(d.accent.copy(alpha = if (selected) 0.28f else 0.12f)),
            contentAlignment = Alignment.Center,
        ) { Icon(d.icon!!, contentDescription = null, tint = d.accent, modifier = Modifier.size(21.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(d.label, style = MaterialTheme.typography.titleMedium, color = if (selected) d.accent else MaterialTheme.colorScheme.onSurface)
            if (d.blurb.isNotEmpty()) Text(d.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Box(Modifier.size(width = 4.dp, height = 22.dp).clip(CircleShape).background(d.accent))
    }
}


/**
 * Like NoiseFit's sync indicator: a thin moving line at the top while Kaya talks to the band, and a small pill
 * saying what it's doing. Saved data is already on screen; this only says fresher numbers are on the way.
 */
@Composable
private fun SyncBar(band: com.kaya.health.band.BandViewModel, top: Modifier, bottom: Modifier) {
    val st by band.syncStatus.collectAsState()
    val vitals by band.vitalsLoading.collectAsState()
    val sleep by band.sleep.collectAsState()
    val running = st.running || vitals || sleep.loading
    var showDone by remember { mutableStateOf(false) }
    LaunchedEffect(running) {
        if (!running && (st.lastAt != null || st.failed)) { showDone = true; kotlinx.coroutines.delay(if (st.failed) 4000 else 2500); showDone = false }
    }
    androidx.compose.animation.AnimatedVisibility(running, top, enter = fadeIn(), exit = fadeOut()) {
        androidx.compose.material3.LinearProgressIndicator(
            Modifier.fillMaxWidth().height(3.dp), color = Area.energy, trackColor = Color.Transparent,
        )
    }
    androidx.compose.animation.AnimatedVisibility(running || showDone, bottom.padding(bottom = 18.dp), enter = fadeIn(), exit = fadeOut()) {
        Row(
            Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (running) androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Area.energy)
            else Icon(androidx.compose.material.icons.Icons.Rounded.CheckCircle, null, Modifier.size(16.dp), tint = if (st.failed) Area.body else Area.food)
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    st.running -> st.step ?: "Syncing with your band"
                    sleep.loading -> "Updating last night's sleep"
                    vitals -> "Updating vitals from your band"
                    st.failed -> "Band busy with another app · retrying shortly"
                    else -> "Synced" + (st.lastMs?.let { " in %.1f s".format(it / 1000.0) } ?: "")
                },
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
