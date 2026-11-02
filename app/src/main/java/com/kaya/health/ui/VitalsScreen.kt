package com.kaya.health.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.kaya.health.band.BandViewModel
import com.kaya.health.band.Vital

@Composable
fun VitalsScreen(band: BandViewModel, losingWeight: Boolean = false) {
    val live by band.live.collectAsState()
    val busy by band.busy.collectAsState()
    var open by remember { mutableStateOf<Vital?>(null) }
    LaunchedEffect(Unit) { band.refreshVitals() }

    open?.let { v ->
        BackHandler { open = null }
        VitalDetail(band, v, onBack = { open = null })
        return
    }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        card(1) { PageHeader("Vitals", "Everything your band measures", Area.vitals, Icons.Rounded.MonitorHeart) }
        card(2) { HeartHero(band, live, enabled = true) }
        card(3) { ForYouCard(band, losingWeight = losingWeight) }
        card(4) { VitalsSection(band, onOpen = { open = it }) }
    }
}
