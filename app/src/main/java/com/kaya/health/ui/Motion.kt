package com.kaya.health.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Cards glide up and fade in, one after another ([index] staggers them). Plays once. */
fun Modifier.enter(index: Int = 0): Modifier = composed {
    val alpha = remember { Animatable(0f) }
    val shift = remember { Animatable(28f) }
    LaunchedEffect(Unit) {
        delay(45L * index.coerceAtMost(10))
        launch { alpha.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
        shift.animateTo(0f, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessLow))
    }
    graphicsLayer { this.alpha = alpha.value; translationY = shift.value * density }
}

/** A number that counts up to its value. */
@Composable
fun CountUp(value: Int, style: TextStyle, color: Color = MaterialTheme.colorScheme.onSurface, format: (Int) -> String = { "%,d".format(it) }) {
    val v by animateIntAsState(value, tween(900, easing = FastOutSlowInEasing), label = "count")
    Text(format(v), style = style, color = color)
}

/** One line on a [SmoothChart]. x values are in any unit (e.g. epoch days); the chart scales them. */
data class Series(
    val points: List<Pair<Double, Double>>,
    val color: Color,
    val fill: Boolean = false,
    val dashed: Boolean = false,
    val width: Dp = 3.dp,
    val dots: Boolean = false,
    val label: String = "",
)

/**
 * Smooth, animated line chart: curves drawn in from the left, optional gradient fill, horizontal guide lines,
 * and touch-to-inspect (drag a finger to read the nearest value of the first series).
 */
@Composable
fun SmoothChart(
    series: List<Series>,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    yLines: List<Pair<Double, Color>> = emptyList(),
    xRange: ClosedFloatingPointRange<Double>? = null,
    yPad: Double = 1.0,
    gapAbove: Double = Double.MAX_VALUE,
    inspect: (x: Double, y: Double) -> String = { _, y -> "%.1f".format(y) },
) {
    val all = series.flatMap { it.points } + yLines.map { (xRange?.start ?: 0.0) to it.first }
    if (all.isEmpty()) return
    val x0 = xRange?.start ?: all.minOf { it.first }
    val x1 = (xRange?.endInclusive ?: all.maxOf { it.first }).let { if (it == x0) x0 + 1 else it }
    val lo = all.minOf { it.second } - yPad
    val hi = all.maxOf { it.second } + yPad
    val reveal = remember(series) { Animatable(0f) }
    LaunchedEffect(series) { reveal.snapTo(0f); reveal.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
    var touchX by remember { mutableStateOf<Float?>(null) }
    val grid = MaterialTheme.colorScheme.outline
    val main = series.first()
    val label = MaterialTheme.colorScheme.onSurface

    Box(modifier.fillMaxWidth().height(height)) {
        Canvas(
            Modifier.fillMaxWidth().height(height)
                .pointerInput(series) {
                    detectHorizontalDragGestures(onDragEnd = { touchX = null }, onDragCancel = { touchX = null }) { c, _ -> touchX = c.position.x }
                }
                .pointerInput(series) { detectTapGestures(onPress = { touchX = it.x; tryAwaitRelease(); touchX = null }) },
        ) {
            fun px(x: Double) = ((x - x0) / (x1 - x0) * size.width).toFloat()
            fun py(y: Double) = ((1 - (y - lo) / (hi - lo)) * size.height).toFloat()
            for (i in 1..3) {
                val y = size.height * i / 4
                drawLine(grid.copy(alpha = 0.5f), Offset(0f, y), Offset(size.width, y), 1f)
            }
            yLines.forEach { (v, c) ->
                drawLine(c.copy(alpha = 0.7f), Offset(0f, py(v)), Offset(size.width, py(v)), 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 10f)))
            }
            clipRect(right = size.width * reveal.value) {
                series.forEach { s ->
                    val pts = s.points.sortedBy { it.first }.map { Offset(px(it.first), py(it.second)) to it.first }
                    // split where the gap in x is too large (no data), so lines don't bridge missing nights
                    val runs = ArrayList<MutableList<Offset>>()
                    var prevX: Double? = null
                    for ((o, x) in pts) {
                        if (prevX == null || x - prevX > gapAbove) runs += mutableListOf<Offset>()
                        runs.last() += o; prevX = x
                    }
                    // a lone point (or a run squeezed into a sliver) is a dot, not a line or a filled block
                    runs.filter { it.size < 2 || it.last().x - it.first().x < 6.dp.toPx() }.forEach { run ->
                        drawCircle(s.color, 5.dp.toPx(), run.last())
                        drawCircle(s.color.copy(alpha = 0.25f), 10.dp.toPx(), run.last())
                    }
                    runs.filter { it.size >= 2 && it.last().x - it.first().x >= 6.dp.toPx() }.forEach { run ->
                        val path = smoothPath(run)
                        if (s.fill && run.last().x - run.first().x > size.width * 0.12f) {
                            val area = Path().apply { addPath(path); lineTo(run.last().x, size.height); lineTo(run.first().x, size.height); close() }
                            drawPath(area, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.32f), s.color.copy(alpha = 0f))))
                        }
                        drawPath(
                            path, s.color,
                            style = Stroke(s.width.toPx(), cap = StrokeCap.Round, pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(16f, 12f)) else null),
                        )
                    }
                    if (s.dots) pts.forEach { (o, _) -> drawCircle(s.color.copy(alpha = 0.55f), 3.5.dp.toPx(), o) }
                }
            }
            touchX?.let { tx ->
                val nearest = main.points.minByOrNull { kotlin.math.abs(px(it.first) - tx) } ?: return@let
                val o = Offset(px(nearest.first), py(nearest.second))
                drawLine(label.copy(alpha = 0.35f), Offset(o.x, 0f), Offset(o.x, size.height), 2f)
                drawCircle(main.color, 7.dp.toPx(), o)
                drawCircle(Color.Black, 3.5.dp.toPx(), o)
            }
        }
        touchX?.let { tx -> ChartTooltip(main, x0, x1, tx, inspect) }
    }
}

@Composable
private fun ChartTooltip(s: Series, x0: Double, x1: Double, tx: Float, inspect: (Double, Double) -> String) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val w = constraints.maxWidth.toFloat()
        val p = s.points.minByOrNull { kotlin.math.abs(((it.first - x0) / (x1 - x0) * w) - tx) } ?: return@BoxWithConstraints
        Text(
            inspect(p.first, p.second),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .graphicsLayer { translationX = (tx - 60.dp.toPx()).coerceIn(0f, (w - 140.dp.toPx()).coerceAtLeast(0f)) }
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** Catmull-Rom → cubic Bézier, for smooth curves through every point. */
private fun smoothPath(p: List<Offset>): Path {
    val path = Path()
    path.moveTo(p[0].x, p[0].y)
    for (i in 0 until p.size - 1) {
        val p0 = p[maxOf(0, i - 1)]; val p1 = p[i]; val p2 = p[i + 1]; val p3 = p[minOf(p.size - 1, i + 2)]
        val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
        val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
        path.cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
    }
    return path
}

/** Bars that grow up from the baseline, staggered left to right. */
@Composable
fun GrowBars(values: List<Double>, color: Color, modifier: Modifier = Modifier, height: Dp = 100.dp, highlight: Int? = null) {
    val grow = remember(values) { Animatable(0f) }
    LaunchedEffect(values) { grow.snapTo(0f); grow.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val max = (values.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    Canvas(modifier.fillMaxWidth().height(height)) {
        val n = values.size.coerceAtLeast(1)
        val slot = size.width / n
        values.forEachIndexed { i, v ->
            val stagger = ((grow.value * (n + 6) - i) / 6f).coerceIn(0f, 1f)
            val h = (size.height * (v / max) * stagger).toFloat()
            val c = if (highlight == null || highlight == i) color else color.copy(alpha = 0.45f)
            drawRoundRect(
                Brush.verticalGradient(listOf(c, c.copy(alpha = 0.55f)), startY = size.height - h, endY = size.height),
                Offset(i * slot + slot * 0.18f, size.height - h), Size(slot * 0.64f, h),
                androidx.compose.ui.geometry.CornerRadius(8f, 8f),
            )
        }
    }
}

/**
 * A LazyColumn item whose content glides in, staggered by [index]. Several composables inside are laid out
 * top to bottom (a Column, not a Box, so they never draw on top of each other).
 */
fun androidx.compose.foundation.lazy.LazyListScope.card(index: Int, content: @Composable () -> Unit) =
    item {
        androidx.compose.foundation.layout.Column(
            Modifier.enter(index),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) { content() }
    }
