package com.kaya.health.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Info
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaya.health.engine.Contributor

private fun c(hex: Long) = Color(hex)

/** Seven accents, one per area of the app. */
class Accents(val energy: Color, val sleep: Color, val activity: Color, val vitals: Color, val mind: Color, val food: Color, val body: Color) {
    val all get() = listOf(energy, sleep, activity, vitals, mind, food, body)
}

/** A complete look: background, card and text colours plus the accents. Cards are always solid. */
class Palette(
    val label: String, val blurb: String, val dark: Boolean,
    val bg: Color, val panel: Color, val panelHigh: Color, val panelHighest: Color, val line: Color,
    val text: Color, val muted: Color, val accents: Accents,
    /** Bold: thick coloured outlines and heavier type (a cel-shaded, anime-like feel). */
    val bold: Boolean = false,
)

private val ClassicAccents = Accents(c(0xFF3DD6C3), c(0xFF8B7CFF), c(0xFFFF7A59), c(0xFFFF5C7A), c(0xFF5BC8FF), c(0xFF7BD88F), c(0xFFFFC857))
private val LightAccents = Accents(c(0xFF00A693), c(0xFF6B5BD6), c(0xFFE8622C), c(0xFFE0405F), c(0xFF1E8FD9), c(0xFF2FA65A), c(0xFFC98F00))

private val ClassicPalette = Palette(
    "Classic", "Calm dark, soft colours", true,
    c(0xFF0A0C0F), c(0xFF12161B), c(0xFF1A1F26), c(0xFF20262E), c(0xFF232A33), c(0xFFECEFF3), c(0xFF8B95A1), ClassicAccents,
)
private val LightPalette = Palette(
    "Light", "Clean and bright for daytime", false,
    c(0xFFF4F6F8), c(0xFFFFFFFF), c(0xFFEEF1F4), c(0xFFE6EAEF), c(0xFFDCE1E7), c(0xFF14181D), c(0xFF5F6B78), LightAccents,
)

/** The app's look. Each person picks one in Settings (or on the welcome screen). */
enum class Look(val palette: Palette) {
    CLASSIC(ClassicPalette),
    MIDNIGHT(
        Palette(
            "Midnight", "Deep navy, cool blues", true,
            c(0xFF0B1020), c(0xFF121A2E), c(0xFF1A2440), c(0xFF222E52), c(0xFF26324F), c(0xFFEAF0FF), c(0xFF8E9BB8),
            Accents(c(0xFF38D9F5), c(0xFF7C8CFF), c(0xFFFF8A5B), c(0xFFFF5C7A), c(0xFF4FA8FF), c(0xFF5EE0A0), c(0xFFFFC44D)),
        ),
    ),
    EMBER(
        Palette(
            "Ember", "Warm charcoal, amber glow", true,
            c(0xFF120D0A), c(0xFF1C1511), c(0xFF261C16), c(0xFF30241C), c(0xFF3A2B21), c(0xFFF6EDE6), c(0xFFA8988C),
            Accents(c(0xFFFFB347), c(0xFFC29BFF), c(0xFFFF6A3D), c(0xFFFF4D5E), c(0xFF6CC5FF), c(0xFF9BD86A), c(0xFFFFD166)),
        ),
    ),
    FOREST(
        Palette(
            "Forest", "Dark green, fresh mint", true,
            c(0xFF0A110D), c(0xFF111A15), c(0xFF18241D), c(0xFF1F2E25), c(0xFF25362C), c(0xFFE8F3EC), c(0xFF8FA79A),
            Accents(c(0xFF5BE3B0), c(0xFF9C8CFF), c(0xFFFF9E5E), c(0xFFFF6B7D), c(0xFF66C6FF), c(0xFFB4E86A), c(0xFFF2CF5B)),
        ),
    ),
    BOLD(
        Palette(
            "Bold", "High contrast, thick outlines, punchy colour", true,
            c(0xFF0D0D14), c(0xFF171722), c(0xFF20202E), c(0xFF2A2A3B), c(0xFF3A3A55), c(0xFFFFFFFF), c(0xFFB3B3CF),
            Accents(c(0xFF00E5D1), c(0xFF8C6BFF), c(0xFFFF6B2C), c(0xFFFF2E63), c(0xFF2EB8FF), c(0xFF3DFF8E), c(0xFFFFD000)),
            bold = true,
        ),
    ),
    LIGHT(LightPalette),
}

/** Current look; reading it inside a composable re-draws when it changes. */
object KayaLook {
    var current by androidx.compose.runtime.mutableStateOf(Look.CLASSIC)
    /** Classic follows the phone's light/dark setting; every other look is fixed. */
    var systemDark by androidx.compose.runtime.mutableStateOf(true)
    val palette: Palette get() = if (current == Look.CLASSIC && !systemDark) LightPalette else current.palette
    val bold get() = palette.bold
}

/** One accent per area of the app, used for icons, rings and highlights. Follows the chosen [Look]. */
object Area {
    private val a get() = KayaLook.palette.accents
    val energy get() = a.energy
    val sleep get() = a.sleep
    val activity get() = a.activity
    val vitals get() = a.vitals
    val mind get() = a.mind
    val food get() = a.food
    val body get() = a.body
}

private fun schemeFor(p: Palette) = if (p.dark) darkColorScheme(
    primary = p.accents.energy, onPrimary = c(0xFF06120F),
    primaryContainer = p.panelHighest, onPrimaryContainer = p.text,
    secondary = p.accents.body, onSecondary = c(0xFF1C1400),
    secondaryContainer = p.panelHigh, onSecondaryContainer = p.text,
    tertiary = p.accents.sleep,
    background = p.bg, onBackground = p.text,
    surface = p.bg, onSurface = p.text,
    surfaceVariant = p.panel, onSurfaceVariant = p.muted,
    surfaceContainerLowest = p.bg, surfaceContainerLow = p.panel,
    surfaceContainer = p.panel, surfaceContainerHigh = p.panelHigh, surfaceContainerHighest = p.panelHighest,
    outline = p.line, outlineVariant = p.line,
    error = p.accents.vitals,
) else lightColorScheme(
    primary = p.accents.energy, onPrimary = Color.White,
    primaryContainer = p.panelHigh, onPrimaryContainer = p.text,
    secondary = p.accents.body, onSecondary = Color.White,
    secondaryContainer = p.panelHigh, onSecondaryContainer = p.text,
    tertiary = p.accents.sleep,
    background = p.bg, onBackground = p.text,
    surface = p.bg, onSurface = p.text,
    surfaceVariant = p.panel, onSurfaceVariant = p.muted,
    surfaceContainerLowest = p.panel, surfaceContainerLow = p.panel,
    surfaceContainer = p.panel, surfaceContainerHigh = p.panelHigh, surfaceContainerHighest = p.panelHighest,
    outline = p.line, outlineVariant = p.line,
    error = p.accents.vitals,
)

private val KayaType = Typography(
    displayLarge = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp),
    displayMedium = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

@Composable
fun KayaTheme(content: @Composable () -> Unit) {
    KayaLook.systemDark = isSystemInDarkTheme()
    val p = KayaLook.palette
    val type = if (p.bold) KayaType.copy(
        displayLarge = KayaType.displayLarge.copy(fontWeight = FontWeight.Black),
        displayMedium = KayaType.displayMedium.copy(fontWeight = FontWeight.Black),
        headlineLarge = KayaType.headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
        headlineMedium = KayaType.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
        headlineSmall = KayaType.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
        titleLarge = KayaType.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = KayaType.titleMedium.copy(fontWeight = FontWeight.Bold),
        labelMedium = KayaType.labelMedium.copy(fontWeight = FontWeight.Bold),
    ) else KayaType
    MaterialTheme(colorScheme = schemeFor(p), typography = type, content = content)
}

/** Opens the side menu; provided by KayaApp so every page header can show the ☰ button. */
val LocalOpenMenu = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

/** Page title with an accent bar, used at the top of every section. */
@Composable
fun PageHeader(title: String, subtitle: String?, accent: Color, icon: ImageVector? = null, trailing: @Composable () -> Unit = {}) {
    val openMenu = LocalOpenMenu.current
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (openMenu != null) {
            androidx.compose.material3.IconButton(onClick = openMenu, modifier = Modifier.padding(end = 4.dp)) {
                Icon(androidx.compose.material.icons.Icons.Rounded.Menu, contentDescription = "Menu")
            }
        }
        if (icon != null) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing()
    }
}

/** Card with a soft border and an optional accent glow at the top. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    onClick: (() -> Unit)? = null,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val bold = KayaLook.bold
    val shape = RoundedCornerShape(if (bold) 20.dp else 24.dp)
    Card(
        modifier = modifier.fillMaxWidth().clip(shape).let { if (onClick != null) it.clickable(onClick = onClick) else it },
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        // Bold: a thick solid outline in the card's colour, like cel-shaded art.
        border = if (bold) BorderStroke(2.dp, accent ?: MaterialTheme.colorScheme.outline)
        else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box {
            if (accent != null) {
                Box(
                    Modifier.fillMaxWidth().height(90.dp)
                        .background(Brush.verticalGradient(listOf(accent.copy(alpha = if (bold) 0.18f else 0.14f), Color.Transparent)))
                )
            }
            Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}

/** Small icon + label row used as a card heading. */
@Composable
fun PanelLabel(text: String, icon: ImageVector?, accent: Color, trailing: String? = null, info: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = accent, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (info != null) InfoDot(text, info)
    }
}

/** A small ⓘ that opens a short "how this is worked out" note, so explanations stay off the main screen. */
@Composable
fun InfoDot(title: String, text: String) {
    var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.material3.IconButton(onClick = { open = true }, modifier = Modifier.size(30.dp)) {
        Icon(androidx.compose.material.icons.Icons.Outlined.Info, contentDescription = "How this is worked out",
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
    if (open) androidx.compose.material3.AlertDialog(
        onDismissRequest = { open = false },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { open = false }) { Text("Got it") } },
        title = { Text(title.lowercase().replaceFirstChar { it.uppercase() }) },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                text.split("\n\n").forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        },
    )
}

/** Tiles side by side, all the same height. */
@Composable
fun TileRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/** A big number with a smaller unit after it. */
@Composable
fun Figure(value: String, unit: String = "", big: Boolean = true, color: Color = MaterialTheme.colorScheme.onSurface) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = if (big) MaterialTheme.typography.displayMedium else MaterialTheme.typography.headlineMedium, color = color)
        if (unit.isNotEmpty()) Text(
            " $unit", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = if (big) 8.dp else 4.dp),
        )
    }
}

/** Circular score ring that animates as it fills. */
@Composable
fun ScoreRing(score: Int?, accent: Color, label: String, size: Dp = 96.dp, stroke: Dp = 9.dp, onClick: (() -> Unit)? = null) {
    val target = ((score ?: 0) / 100f).coerceIn(0f, 1f)
    val sweep by animateFloatAsState(target, tween(900), label = "ring")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = if (onClick != null) Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(4.dp) else Modifier,
    ) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size)) {
                val w = stroke.toPx()
                val arc = Size(this.size.width - w, this.size.height - w)
                val tl = Offset(w / 2, w / 2)
                drawArc(accent.copy(alpha = 0.15f), 135f, 270f, false, tl, arc, style = Stroke(w, cap = StrokeCap.Round))
                if (score != null) drawArc(
                    Brush.sweepGradient(listOf(accent.copy(alpha = 0.6f), accent, accent.copy(alpha = 0.6f))),
                    135f, 270f * sweep, false, tl, arc, style = Stroke(w, cap = StrokeCap.Round),
                )
            }
            Text(score?.toString() ?: "–", style = if (size >= 90.dp) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge)
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Thin rounded progress bar. */
@Composable
fun Bar(fraction: Double, accent: Color, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val f by animateFloatAsState(fraction.toFloat().coerceIn(0f, 1f), tween(700), label = "bar")
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(accent.copy(alpha = 0.15f))) {
        Box(Modifier.fillMaxWidth(f).height(height).clip(CircleShape).background(accent))
    }
}

/** One line of a score breakdown: label, detail, and points as a mini bar. */
@Composable
fun ContributorRow(c: Contributor, accent: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(c.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("${c.points}/${c.outOf}", style = MaterialTheme.typography.labelLarge)
        }
        Bar(c.points.toDouble() / c.outOf, accent, height = 6.dp)
        Text(c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Compact metric tile for grids. */
@Composable
fun Tile(title: String, value: String, unit: String, caption: String, accent: Color, icon: ImageVector?, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Panel(modifier.fillMaxHeight(), onClick = onClick, padding = 14.dp) {
        PanelLabel(title, icon, accent)
        Figure(value, unit, big = false)
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** A small rounded tag. */
@Composable
fun Tag(text: String, accent: Color) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = accent,
        modifier = Modifier.clip(CircleShape).background(accent.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** "‹ Earlier · day · Later ›" switcher used by the Sleep and Activity pages. */
@Composable
fun DayStrip(date: java.time.LocalDate, accent: Color, onPick: (java.time.LocalDate) -> Unit) {
    val today = java.time.LocalDate.now()
    val title = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday · " + date.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))
        else -> date.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM"))
    }
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.TextButton(onClick = { onPick(date.minusDays(1)) }) { Text("‹ Earlier", color = accent) }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        androidx.compose.material3.TextButton(onClick = { onPick(date.plusDays(1)) }, enabled = date.isBefore(today)) {
            Text("Later ›", color = if (date.isBefore(today)) accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        }
    }
}
