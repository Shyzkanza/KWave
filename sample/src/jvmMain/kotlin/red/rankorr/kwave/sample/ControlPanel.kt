/*
 * Copyright 2026 Jessy Bonnotte (Shyzkanza)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package red.rankorr.kwave.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Layer-count slider bounds for the sample (library coerces to `>= 1`; this caps the visual range). */
private const val MIN_WAVES = 1
private const val MAX_WAVES = 8

private const val MAX_CRESTS = 4f
private const val MAX_HARMONIC = 1.5f
private const val MAX_SPACING = 2f
private const val MAX_AMPLITUDE = 0.12f
private const val MAX_SPEED = 4f

/**
 * Floating Material3 control card. Edits the hoisted [state] in place; every change flows into
 * `state.config` and the live [red.rankorr.kwave.KWave] behind it.
 *
 * This is the only place in the sample that touches Material3. The KWave library itself reads no
 * theme. The card is compact and scrollable so it fits over the wave on small windows.
 */
@Composable
fun ControlPanel(
    state: WaveControlState,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.width(320.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            // Pin the content color: an alpha-modified surface no longer matches contentColorFor(),
            // so unspecified text would fall back to black, invisible on the dark card. onSurface
            // keeps every label legible.
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle("KWave controls")

            // ── Geometry ────────────────────────────────────────────────────────────────────────
            LabeledSlider(
                label = "Wave count",
                value = state.waveCount.toFloat(),
                valueText = state.waveCount.toString(),
                onValueChange = { state.waveCount = it.roundToInt().coerceIn(MIN_WAVES, MAX_WAVES) },
                valueRange = MIN_WAVES.toFloat()..MAX_WAVES.toFloat(),
                steps = MAX_WAVES - MIN_WAVES - 1,
            )
            LabeledSlider(
                label = "Crests",
                value = state.crests,
                valueText = format2(state.crests),
                onValueChange = { state.crests = it },
                valueRange = 0f..MAX_CRESTS,
            )
            LabeledSlider(
                label = "Roughness",
                value = state.harmonic,
                valueText = format2(state.harmonic),
                onValueChange = { state.harmonic = it },
                valueRange = 0f..MAX_HARMONIC,
            )
            LabeledSlider(
                label = "Spacing (overlap)",
                value = state.spacing,
                valueText = format2(state.spacing),
                onValueChange = { state.spacing = it },
                valueRange = 0.1f..MAX_SPACING,
            )
            LabeledSlider(
                label = "Amplitude",
                value = state.amplitude,
                valueText = format3(state.amplitude),
                onValueChange = { state.amplitude = it },
                valueRange = 0f..MAX_AMPLITUDE,
            )
            LabeledSlider(
                label = "Speed",
                value = state.speed,
                valueText = format2(state.speed),
                onValueChange = { state.speed = it },
                valueRange = 0f..MAX_SPEED,
            )
            LabeledSlider(
                label = "Gradient end",
                value = state.gradientEnd,
                valueText = format2(state.gradientEnd),
                onValueChange = { state.gradientEnd = it },
                valueRange = 0f..1f,
            )
            LabeledSlider(
                label = "Variation (randomness)",
                value = state.variation,
                valueText = format2(state.variation),
                onValueChange = { state.variation = it },
                valueRange = 0f..1f,
            )
            Button(
                onClick = { state.seed++ },
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Text("Randomize layout (seed ${state.seed})")
            }

            // ── Shadow mode ─────────────────────────────────────────────────────────────────────
            SectionTitle("Shadow mode")
            ChipRow(
                choices = ShadowChoice.entries,
                selected = state.shadowChoice,
                label = { it.label },
                onSelect = { state.shadowChoice = it },
            )
            if (state.shadowChoice == ShadowChoice.Custom) {
                SwatchRow(
                    label = "Shadow color",
                    selected = state.customShadowColor,
                    swatches = SHADOW_SWATCHES,
                    onSelect = { state.customShadowColor = it },
                )
                LabeledSlider(
                    label = "Shadow alpha",
                    value = state.customShadowAlpha,
                    valueText = format2(state.customShadowAlpha),
                    onValueChange = { state.customShadowAlpha = it },
                    valueRange = 0f..1f,
                )
            }

            // ── Color mode ──────────────────────────────────────────────────────────────────────
            SectionTitle("Color mode")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.colorChoice.label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.colorChoice == ColorChoice.Palette,
                    onCheckedChange = {
                        state.colorChoice = if (it) ColorChoice.Palette else ColorChoice.Gradient
                    },
                )
            }
            when (state.colorChoice) {
                ColorChoice.Gradient -> {
                    SwatchRow(
                        label = "Top",
                        selected = state.gradientTop,
                        swatches = GRADIENT_SWATCHES,
                        onSelect = { state.gradientTop = it },
                    )
                    SwatchRow(
                        label = "Bottom",
                        selected = state.gradientBottom,
                        swatches = GRADIENT_SWATCHES,
                        onSelect = { state.gradientBottom = it },
                    )
                }
                ColorChoice.Palette -> {
                    Text(
                        text = "Palette (sampled by depth)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.paletteColors.forEach { c -> Swatch(c, selected = false, size = 28.dp) }
                    }
                }
            }
        }
    }
}

// ── Reusable building blocks ─────────────────────────────────────────────────────────────────────

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** A label + current value on one line, with the [Slider] beneath. */
@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    valueText: String,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
) {
    Column {
        Row {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}

/** A wrapping row of single-select [FilterChip]s over an enum's entries. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(
    choices: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        choices.forEach { choice ->
            FilterChip(
                selected = choice == selected,
                onClick = { onSelect(choice) },
                label = { Text(label(choice)) },
            )
        }
    }
}

/** A labeled row of color swatches; tapping one selects it. */
@Composable
private fun SwatchRow(
    label: String,
    selected: Color,
    swatches: List<Color>,
    onSelect: (Color) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(72.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            swatches.forEach { c ->
                Swatch(
                    color = c,
                    selected = c == selected,
                    onClick = { onSelect(c) },
                )
            }
        }
    }
}

@Composable
private fun Swatch(
    color: Color,
    selected: Boolean,
    onClick: (() -> Unit)? = null,
    size: androidx.compose.ui.unit.Dp = 24.dp,
) {
    val borderColor =
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val borderWidth = if (selected) 3.dp else 1.dp
    var modifier = Modifier
        .size(size)
        .background(color, RoundedCornerShape(6.dp))
        .border(borderWidth, borderColor, RoundedCornerShape(6.dp))
    if (onClick != null) {
        modifier = modifier.selectable(selected = selected, onClick = onClick)
    }
    Spacer(modifier)
}

private val GRADIENT_SWATCHES = listOf(
    Color(0xFF455A64),
    Color(0xFF263238),
    Color(0xFF1565C0),
    Color(0xFF6A1B9A),
    Color(0xFF00695C),
    Color(0xFFEF6C00),
)

private val SHADOW_SWATCHES = listOf(
    Color(0xFF000000),
    Color(0xFF101820),
    Color(0xFF1A237E),
    Color(0xFFFFFFFF),
)

private fun format2(v: Float): String {
    val scaled = (v * 100).roundToInt()
    return "${scaled / 100}.${(scaled % 100).toString().padStart(2, '0')}"
}

private fun format3(v: Float): String {
    val scaled = (v * 1000).roundToInt()
    return "0.${(scaled % 1000).toString().padStart(3, '0')}"
}
