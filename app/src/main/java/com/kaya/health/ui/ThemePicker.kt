package com.kaya.health.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Pick a look by seeing it: each option is a small mock-up drawn in that theme's own colours
 * (background, a card, three score arcs and two bars). Tapping one applies it straight away.
 */
@Composable
fun ThemePicker(selected: String, onPick: (Look) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Look.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { look -> ThemeTile(look, look.name == selected, Modifier.weight(1f)) { onPick(look) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ThemeTile(look: Look, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = look.palette
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .clip(shape)
            .background(p.bg)
            .border(if (isSelected) 2.5.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.primary else p.line, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "${p.label} theme. ${p.blurb}"; this.selected = isSelected }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Mini card in this theme.
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(if (p.bold) 9.dp else 12.dp)).background(p.panel)
                .border(if (p.bold) 1.5.dp else 1.dp, if (p.bold) p.accents.energy else p.line, RoundedCornerShape(if (p.bold) 9.dp else 12.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(p.accents.energy to 0.7f, p.accents.sleep to 0.5f, p.accents.activity to 0.3f).forEach { (col, f) -> MiniRing(col, f) }
            }
            MiniBar(p.accents.food, 0.6f, p.panelHighest)
            MiniBar(p.accents.body, 0.35f, p.panelHighest)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.label, color = p.text, style = MaterialTheme.typography.titleSmall, fontWeight = if (p.bold) FontWeight.ExtraBold else FontWeight.SemiBold)
                Text(p.blurb, color = p.muted, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            if (isSelected) Box(
                Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(15.dp)) }
        }
        // Accent strip: all seven colours at a glance.
        Row(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)) {
            p.accents.all.forEach { Box(Modifier.weight(1f).height(6.dp).background(it)) }
        }
    }
}

@Composable
private fun MiniRing(color: Color, fraction: Float) {
    Canvas(Modifier.size(28.dp)) {
        val w = 3.5.dp.toPx()
        val arc = Size(size.width - w, size.height - w)
        val tl = Offset(w / 2, w / 2)
        drawArc(color.copy(alpha = 0.2f), 135f, 270f, false, tl, arc, style = Stroke(w, cap = StrokeCap.Round))
        drawArc(color, 135f, 270f * fraction, false, tl, arc, style = Stroke(w, cap = StrokeCap.Round))
    }
}

@Composable
private fun MiniBar(color: Color, fraction: Float, track: Color) {
    Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(track)) {
        Box(Modifier.fillMaxWidth(fraction).height(5.dp).clip(CircleShape).background(color))
    }
}

@Suppress("unused") private val keepWidth = Modifier.width(0.dp)
