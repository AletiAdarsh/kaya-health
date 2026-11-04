package com.kaya.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import com.kaya.health.ui.KayaApp
import com.kaya.health.ui.KayaTheme

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN = "kaya_open"
        /** Set when a widget or notification asks to open something ("sos"); KayaApp consumes it. */
        val pendingOpen = androidx.compose.runtime.mutableStateOf<String?>(null)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN)?.let { pendingOpen.value = it }
        probeFrom(intent)
    }

    /** Research: `am start ... --es kaya_probe 26,16` logs those band records' raw layout (read-only, tag KayaProbe). */
    private fun probeFrom(i: android.content.Intent?) {
        i?.getStringExtra("kaya_probe")?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.isNotEmpty() }
            ?.let { com.kaya.health.band.BandViewModel.probeMetrics = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) intent?.getStringExtra(EXTRA_OPEN)?.let { pendingOpen.value = it }
        probeFrom(intent)
        enableEdgeToEdge()
        setContent {
            KayaTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    KayaApp()
                }
            }
        }
    }
}
