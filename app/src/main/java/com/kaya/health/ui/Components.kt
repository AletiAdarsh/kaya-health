package com.kaya.health.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaya.health.engine.TrendPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun KCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Panel(modifier, content = content)
}

@Composable
fun CardTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun BigNumber(value: String, unit: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = color)
        Text(" $unit", modifier = Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Label on the left, value on the right. */
@Composable
fun LineItem(label: String, value: String, strong: Boolean = false, valueColor: Color? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** A small labelled stat used in rows of 3–4. */
@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Muted(label)
    }
}

@Composable
fun TrendChart(points: List<TrendPoint>, modifier: Modifier = Modifier) {
    if (points.size < 2) {
        Muted("Add a few weigh-ins to see your trend line.")
        return
    }
    val lineColor = MaterialTheme.colorScheme.primary
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
    val values = points.flatMap { listOfNotNull(it.raw, it.trend) }
    val lo = values.min() - 0.5
    val hi = values.max() + 0.5
    Column(modifier) {
        Muted("%.1f kg".format(hi))
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            val n = points.size
            fun x(i: Int) = size.width * i / (n - 1).toFloat()
            fun y(v: Double) = (size.height * (1.0 - (v - lo) / (hi - lo))).toFloat()
            val path = Path()
            points.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(x(i), y(p.trend)) else path.lineTo(x(i), y(p.trend))
            }
            drawPath(path, lineColor, style = Stroke(width = 3.dp.toPx()))
            points.forEachIndexed { i, p ->
                p.raw?.let { drawCircle(dotColor.copy(alpha = 0.7f), radius = 3.dp.toPx(), center = Offset(x(i), y(it))) }
            }
        }
        Muted("%.1f kg".format(lo))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Muted(points.first().date.format(dayFmt))
            Muted(points.last().date.format(dayFmt))
        }
    }
}

@Composable
fun Sparkline(values: List<Double>, modifier: Modifier = Modifier) {
    if (values.size < 2) return
    val color = MaterialTheme.colorScheme.secondary
    val lo = values.min() - 2
    val hi = values.max() + 2
    Canvas(modifier.fillMaxWidth().height(70.dp)) {
        val n = values.size
        fun x(i: Int) = size.width * i / (n - 1).toFloat()
        fun y(v: Double) = (size.height * (1.0 - (v - lo) / (hi - lo))).toFloat()
        val path = Path()
        values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
        drawPath(path, color, style = Stroke(width = 2.5.dp.toPx()))
        values.forEachIndexed { i, v -> drawCircle(color, radius = 3.dp.toPx(), center = Offset(x(i), y(v))) }
    }
}

@Composable
fun Centered(text: String) {
    Text(text, modifier = Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ---------------------------------------------------------------- formatting

val dayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
val fullDayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMM")
private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

fun Instant.clock(): String = timeFmt.format(atZone(ZoneId.systemDefault()))
fun Instant.day(): String = dayFmt.format(atZone(ZoneId.systemDefault()))

fun Double.kcal(): String = "%,d".format(roundToInt())

fun minutesText(min: Long): String = if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"
