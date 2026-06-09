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

import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import red.rankorr.kwave.ShadowMode
import red.rankorr.kwave.WaveColors
import red.rankorr.kwave.WaveConfig

/** Which shadow mode the selector currently exposes. Maps 1:1 onto [ShadowMode]. */
enum class ShadowChoice(val label: String) {
    Auto("Auto"),
    FromWave("FromWave"),
    None("None"),
    Custom("Custom"),
}

/** Which color strategy the color-mode switch currently exposes. Maps onto a [WaveColors] factory. */
enum class ColorChoice(val label: String) {
    Gradient("Gradient (top → bottom)"),
    Palette("Rainbow palette"),
}

/**
 * Hoisted, observable state for every live control in the sample, plus a [config] that derives a
 * [WaveConfig] from the current values.
 *
 * Each field is a Compose snapshot state, so editing it from the [ControlPanel] re-runs only the
 * composables that read it. The [config] uses `derivedStateOf` so the [WaveConfig] is rebuilt only
 * when a value it actually depends on changes.
 *
 * `speed` deliberately is **not** folded into [config]: it is the drop-in [red.rankorr.kwave.KWave]
 * overload's own breathing-tempo multiplier parameter, read live by the wave's animation loop, so the
 * sample passes it straight through rather than baking it into the config.
 */
class WaveControlState {

    // ── WaveConfig.generate(...) inputs ──────────────────────────────────────────────────────────

    /** Number of layers. Coerced to `>= 1` by the library; the slider is clamped to a sane range. */
    var waveCount by mutableIntStateOf(DEFAULT_WAVE_COUNT)

    /** Relative crest density applied to every layer (`crests`). */
    var crests by mutableFloatStateOf(DEFAULT_CRESTS)

    /** Crest roughness applied to every layer (`harmonic`): 0 = rounded sine, higher = choppier. */
    var harmonic by mutableFloatStateOf(DEFAULT_HARMONIC)

    /** Vertical spread of the layers (`spacing`): smaller bunches them together for more overlap. */
    var spacing by mutableFloatStateOf(DEFAULT_SPACING)

    /** Peak displacement fraction applied to every layer (`amplitude`). */
    var amplitude by mutableFloatStateOf(DEFAULT_AMPLITUDE)

    /** Amount of per-layer pseudo-random jitter passed to `generate()` (`variation`, `[0, 1]`). */
    var variation by mutableFloatStateOf(DEFAULT_VARIATION)

    /** Seed for the deterministic jitter; bump it (Randomize) to roll a new organic layout. */
    var seed by mutableIntStateOf(DEFAULT_SEED)

    /** Vertical fraction at which the background gradient ends (`gradientEnd`, coerced `[0,1]`). */
    var gradientEnd by mutableFloatStateOf(DEFAULT_GRADIENT_END)

    /** Breathing-tempo multiplier passed to the drop-in KWave overload (NOT part of WaveConfig). */
    var speed by mutableFloatStateOf(DEFAULT_SPEED)

    // ── ShadowMode selector ──────────────────────────────────────────────────────────────────────

    var shadowChoice by mutableStateOf(ShadowChoice.Auto)

    /** Color used when [shadowChoice] is [ShadowChoice.Custom]. */
    var customShadowColor by mutableStateOf(Color(0xFF101820))

    /** Alpha used when [shadowChoice] is [ShadowChoice.Custom] (coerced `[0,1]` by the library). */
    var customShadowAlpha by mutableFloatStateOf(DEFAULT_CUSTOM_SHADOW_ALPHA)

    // ── Color-mode switch ────────────────────────────────────────────────────────────────────────

    var colorChoice by mutableStateOf(ColorChoice.Gradient)

    /** Top swatch for the [ColorChoice.Gradient] mode (also the back-most layer tint). */
    var gradientTop by mutableStateOf(Color(0xFF455A64))

    /** Bottom swatch for the [ColorChoice.Gradient] mode (also the front-most layer tint). */
    var gradientBottom by mutableStateOf(Color(0xFF263238))

    /** The ordered palette sampled across the wave stack in [ColorChoice.Palette] mode. */
    var paletteColors by mutableStateOf(DEFAULT_PALETTE)

    // ── Derived config ───────────────────────────────────────────────────────────────────────────

    private fun resolveShadow(): ShadowMode = when (shadowChoice) {
        ShadowChoice.Auto -> ShadowMode.Auto
        ShadowChoice.FromWave -> ShadowMode.FromWave
        ShadowChoice.None -> ShadowMode.None
        ShadowChoice.Custom -> ShadowMode.Custom(customShadowColor, customShadowAlpha)
    }

    private fun resolveColors(): WaveColors = when (colorChoice) {
        ColorChoice.Gradient -> WaveColors.gradient(gradientTop, gradientBottom)
        ColorChoice.Palette -> WaveColors.palette(paletteColors)
    }

    /**
     * The live [WaveConfig] derived from the current control values. `speed` is intentionally
     * excluded — it is a parameter of the drop-in composable, not of the config.
     */
    val config: State<WaveConfig> = derivedStateOf {
        WaveConfig.generate(
            waveCount = waveCount,
            crests = crests,
            harmonic = harmonic,
            spacing = spacing,
            amplitude = amplitude,
            variation = variation,
            colors = resolveColors(),
            shadow = resolveShadow(),
            gradientEnd = gradientEnd,
            seed = seed,
        )
    }

    companion object {
        const val DEFAULT_WAVE_COUNT: Int = 3
        const val DEFAULT_CRESTS: Float = 1f
        const val DEFAULT_HARMONIC: Float = 0.25f
        const val DEFAULT_SPACING: Float = 1f
        const val DEFAULT_AMPLITUDE: Float = 0.03f
        const val DEFAULT_VARIATION: Float = 0.4f
        const val DEFAULT_SEED: Int = 0
        const val DEFAULT_GRADIENT_END: Float = 0.78f
        const val DEFAULT_SPEED: Float = 1f
        const val DEFAULT_CUSTOM_SHADOW_ALPHA: Float = 0.4f

        /** Default rainbow palette for the [ColorChoice.Palette] mode. */
        val DEFAULT_PALETTE: List<Color> = listOf(
            Color(0xFF6A1B9A), // violet
            Color(0xFF1565C0), // blue
            Color(0xFF2E7D32), // green
            Color(0xFFF9A825), // amber
            Color(0xFFC62828), // red
        )
    }
}
