package com.kaya.health.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Colours for the macros, taken from the current theme. */
object MacroColors {
    val protein get() = Area.energy
    val carbs get() = Area.body
    val fat get() = Area.activity
    val fiber get() = Area.food
}

/** Protein / carbs / fat / fiber in grams, side by side. */
@Composable
fun MacroLine(protein: Double, carbs: Double, fat: Double, fiber: Double, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        MacroStat("Protein", protein, MacroColors.protein)
        MacroStat("Carbs", carbs, MacroColors.carbs)
        MacroStat("Fat", fat, MacroColors.fat)
        MacroStat("Fiber", fiber, MacroColors.fiber)
    }
}

@Composable
private fun MacroStat(label: String, grams: Double, color: Color) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("${grams.roundToInt()} g", style = MaterialTheme.typography.titleMedium)
    }
}

/** Where today's calories came from: protein, carbs and fat as shares of the total, as one bar. */
@Composable
fun MacroSplitBar(protein: Double, carbs: Double, fat: Double) {
    val p = protein * 4; val c = carbs * 4; val f = fat * 9
    val total = p + c + f
    if (total <= 0) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape)) {
            listOf(p to MacroColors.protein, c to MacroColors.carbs, f to MacroColors.fat).filter { it.first > 0 }.forEach { (v, col) ->
                Box(Modifier.weight((v / total).toFloat()).height(10.dp).background(col))
            }
        }
        Text(
            "Calories from protein ${(100 * p / total).roundToInt()}% · carbs ${(100 * c / total).roundToInt()}% · fat ${(100 * f / total).roundToInt()}%",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
