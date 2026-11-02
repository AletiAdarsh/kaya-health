package com.kaya.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kaya.health.ui.KayaTheme

/**
 * Health Connect requires every app to show a privacy explanation. Android opens this screen
 * from the Health Connect permission page ("Read privacy policy").
 */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KayaTheme {
                Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Kaya and your health data", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("• Kaya reads steps, distance, heart rate, workouts, sleep, calories, weight and food from Health Connect, only to show them to you and calculate honest calorie estimates.")
                    Text("• Kaya writes only the weight and food entries you add yourself.")
                    Text("• Everything stays on this phone. Kaya has no account, no server, and no internet permission.")
                    Text("• You can remove access any time in Health Connect settings.")
                    Button(onClick = { finish() }) { Text("Close") }
                }
            }
        }
    }
}
