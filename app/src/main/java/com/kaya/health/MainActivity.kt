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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) intent?.getStringExtra(EXTRA_OPEN)?.let { pendingOpen.value = it }
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
