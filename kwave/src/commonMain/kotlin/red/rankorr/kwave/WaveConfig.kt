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
package red.rankorr.kwave

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlin.math.PI
import kotlin.random.Random

/**
 * Full configuration for a KWave background: the ordered [layers] (back-to-front), the [colors]
 * strategy, the [shadow] mode, and the vertical [gradientEnd] fraction.
 *
 * This is a regular [Immutable] class (not a `data class`) for binary-compatibility stability: it
 * exposes neither `copy()` nor `componentN()`, so new fields can be added later without breaking
 * existing callers. Build one with [Default], [generate], or by passing your own layers.
 *
 * [layers] is an [ImmutableList] (kotlinx.collections.immutable). A plain `List` is not recognized
 * as `@Stable` by the Compose compiler (it could be a mutable implementation), which would defeat
 * recomposition skipping; an [ImmutableList] lets the [Immutable] annotation hold so Compose can
 * skip recomposition when the configuration is unchanged.
 *
 * @param layers ordered wave layers, rendered back-to-front. May be empty (the renderer is safe at
 *   `N = 0`); [generate] always produces at least one.
 * @param colors the color strategy supplying background stops, per-layer fills, and the highlight.
 * @param shadow the depth shadow / highlight mode. Default [ShadowMode.Auto].
 * @param gradientEnd vertical fraction at which the background gradient ends, coerced into
 *   `[GRADIENT_END_MIN, 1]` (the floor avoids a degenerate zero-span gradient). Default `0.78`.
 * @param sway config-wide weight of the crest sway (the slow side-to-side lean of breathing
 *   layers), coerced to `>= 0`. `1` is the nominal organic sway; `0` disables it entirely,
 *   restoring the exact pre-0.2.0 waveform (breathing without sway). Default `1`.
 */
@Immutable
public class WaveConfig(
    public val layers: ImmutableList<WaveLayerSpec>,
    public val colors: WaveColors,
    public val shadow: ShadowMode = ShadowMode.Auto,
    gradientEnd: Float = 0.78f,
    sway: Float = 1f,
) {
    /**
     * Vertical fraction at which the background gradient ends, coerced into `[GRADIENT_END_MIN, 1]`.
     * The lower bound is deliberately above `0`: an end fraction of `0` would make the background
     * `verticalGradient` span zero height (`startY == endY`), a degenerate brush that paints a flat,
     * broken color instead of the intended gradient.
     */
    public val gradientEnd: Float = gradientEnd.coerceIn(GRADIENT_END_MIN, 1f)

    /**
     * Config-wide weight of the crest sway, coerced to `>= 0`. `1` = nominal, `0` = no sway (the
     * exact pre-0.2.0 waveform). Per layer the sway amplitude is additionally scaled by
     * [WaveLayerSpec.breathDepth], so non-breathing layers never sway regardless of this value.
     */
    public val sway: Float = sway.coerceAtLeast(0f)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WaveConfig) return false
        return layers == other.layers &&
            colors == other.colors &&
            shadow == other.shadow &&
            gradientEnd == other.gradientEnd &&
            sway == other.sway
    }

    override fun hashCode(): Int {
        var result = layers.hashCode()
        result = 31 * result + colors.hashCode()
        result = 31 * result + shadow.hashCode()
        result = 31 * result + gradientEnd.hashCode()
        result = 31 * result + sway.hashCode()
        return result
    }

    override fun toString(): String =
        "WaveConfig(layers=$layers, colors=$colors, shadow=$shadow, gradientEnd=$gradientEnd, sway=$sway)"

    public companion object {

        /**
         * A generic, neutral blue-grey preset suitable as a drop-in starting point.
         *
         * It ports the original reference renderer default (`baseColor 0xFF455A64`,
         * `darkColor 0xFF263238`, `lightColor 0xFF90A4AE`) but expressed through [WaveColors]: the
         * background and per-layer fills come from `WaveColors.gradient(0xFF455A64, 0xFF263238)`, so
         * fills are palette-derived rather than the reference's hardcoded black. Two layers mirror
         * the reference geometry, alpha is auto-assigned by depth, the shadow mode is
         * [ShadowMode.Auto], and [gradientEnd] is `0.78`.
         */
        public val Default: WaveConfig = WaveConfig(
            layers = persistentListOf(
                WaveLayerSpec(
                    baseFrac = 0.50f,
                    amplitude = 0.03f,
                    speed = 1.00f,
                    phaseOffset = 0.0f,
                    breathDepth = 0.20f,
                    breathSpeed = 0.25f,
                    breathOffset = 0.0f,
                    crests = 0.75f,
                    harmonic = 0.25f,
                ),
                WaveLayerSpec(
                    baseFrac = 0.60f,
                    amplitude = 0.03f,
                    speed = 0.70f,
                    phaseOffset = 2.0f,
                    breathDepth = 0.20f,
                    breathSpeed = 0.30f,
                    breathOffset = 1.5f,
                    crests = 0.85f,
                    harmonic = 0.25f,
                ),
            ),
            colors = WaveColors.gradient(Color(0xFF455A64), Color(0xFF263238)),
            shadow = ShadowMode.Auto,
            gradientEnd = 0.78f,
        )

        /**
         * Builds a [WaveConfig] of [waveCount] auto-generated layers stacked back-to-front.
         *
         * The generator distributes the layers automatically so the result looks varied
         * without the caller hand-tuning each [WaveLayerSpec]. Every per-layer property is
         * given a deterministic, seeded pseudo-random jitter (scaled by [variation]) so the layers
         * differ from one another instead of forming one rigid block:
         *
         * - Independent breathing and sway (the time-driven motion): `breathSpeed` is jittered and
         *   `breathOffset` is fully random, so under the drop-in [KWave] each layer swells, recedes
         *   and sways on its own schedule, never pulsing in unison.
         * - Varied amplitude: per-layer `amplitude` wobbles around the requested value, so crests
         *   are not all the same height.
         * - Crest shape: [crests] sets how dense the crests are and [harmonic] how round vs
         *   choppy they look; both are jittered per layer so no two layers share an identical profile.
         * - Staggered crests: per-layer `phaseOffset`/`speed` give each layer a different
         *   horizontal crest position (an even `(i / waveCount) * 2π` distribution plus a random
         *   scatter). Under the drop-in's slow ambient drift the per-layer `speed` also produces a
         *   gentle parallax (each layer translating at its own rate); with `drift = 0` it is a
         *   purely static stagger.
         * - Depth alpha / palette tint: left `null`, so alpha is auto-assigned by depth (back
         *   transparent → front opaque) and the fill is sampled from [colors] at the layer's depth.
         * - Vertical stacking / overlap: `baseFrac` is spread around the canvas middle by
         *   [spacing]: a smaller [spacing] bunches the layers together (more overlap), a larger one
         *   separates them; a small jitter is added on top.
         *
         * The jitter is a pure function of [seed], so the same arguments always yield the exact same
         * configuration, deterministic for screenshot tests. Set [variation] to `0` to drop the
         * random jitter (layers keep only the smooth back→front gradient in size and stacking).
         *
         * [waveCount] is coerced to `>= 1`; [variation] is coerced into `[0, 1]`.
         *
         * @param waveCount number of layers to generate (coerced to `>= 1`). Default `3`.
         * @param crests relative crest density per layer (`1` = baseline; higher = more, tighter
         *   crests). A density, **not** a literal crest count. Then jittered. Default `1`.
         * @param harmonic crest roughness: `0` is a clean rounded sine, higher is choppier/less
         *   regular (the weight of the second harmonic). Then jittered. Default `0.25`.
         * @param spacing vertical spread of the layers; `< 1` bunches them together for more overlap,
         *   `> 1` separates them. Default `1`.
         * @param amplitude base peak displacement fraction (then jittered per layer). Default `0.03`.
         * @param variation amount of per-layer pseudo-random jitter in `[0, 1]`; `0` is smooth/uniform,
         *   `1` is strongly varied. Default `0.4`.
         * @param colors the color strategy for background, fills, and highlight.
         * @param shadow the depth shadow / highlight mode. Default [ShadowMode.Auto].
         * @param gradientEnd vertical fraction at which the background gradient ends, coerced into
         *   `[GRADIENT_END_MIN, 1]`. Default `0.78`.
         * @param seed *advanced*: seed for the deterministic jitter; leave at `0` unless you need a
         *   reproducible re-roll or to pin a screenshot. Default `0`.
         * @param sway config-wide weight of the crest sway, coerced to `>= 0` (see
         *   [WaveConfig.sway]); `0` disables the sway (the pre-0.2.0 waveform). Default `1`.
         */
        public fun generate(
            waveCount: Int = 3,
            crests: Float = 1f,
            harmonic: Float = 0.25f,
            spacing: Float = 1f,
            amplitude: Float = 0.03f,
            variation: Float = 0.4f,
            colors: WaveColors,
            shadow: ShadowMode = ShadowMode.Auto,
            gradientEnd: Float = 0.78f,
            seed: Int = 0,
            sway: Float = 1f,
        ): WaveConfig {
            val count = waveCount.coerceAtLeast(1)
            val amount = variation.coerceIn(0f, 1f)
            // Vertical extent the layers span: smaller `spacing` bunches them (more overlap), larger
            // separates them. Centered on the middle of the back→front band.
            val spread = (BASE_FRONT - BASE_BACK) * spacing.coerceAtLeast(0f)
            val center = (BASE_BACK + BASE_FRONT) / 2f
            val random = Random(seed)
            val layers = (0 until count).map { i ->
                // Normalized back-to-front depth in [0, 1] (0 for a single layer).
                val depth = if (count <= 1) 0f else i.toFloat() / (count - 1)
                // Symmetric deterministic jitter in [-amount*strength, +amount*strength].
                fun jitter(strength: Float): Float = (random.nextFloat() - 0.5f) * 2f * amount * strength
                WaveLayerSpec(
                    // Stack vertically around `center`, spread by `spacing`, lightly jittered.
                    baseFrac = center + (depth - 0.5f) * spread + jitter(BASE_JITTER),
                    // Amplitude wobbles around the requested value so crests vary in height.
                    amplitude = amplitude * (1f + jitter(AMP_JITTER)),
                    // Per-layer horizontal-shift magnitude varies around a back→front baseline: a
                    // static crest offset, plus a gentle parallax under the drop-in's ambient drift.
                    speed = (SPEED_BACK + (SPEED_FRONT - SPEED_BACK) * depth) * (1f + jitter(SPEED_JITTER)),
                    // Even distribution plus a random scatter.
                    phaseOffset = (i.toFloat() / count) * TAU + jitter(PHASE_JITTER),
                    breathDepth = (BREATH_DEPTH_BASE + BREATH_DEPTH_STEP * depth) * (1f + jitter(BREATH_DEPTH_JITTER)),
                    // Different breathing rates + fully random phase => no shared pulse.
                    breathSpeed = (BREATH_SPEED_BASE + BREATH_SPEED_STEP * i) * (1f + jitter(BREATH_SPEED_JITTER)),
                    breathOffset = random.nextFloat() * TAU,
                    crests = crests * (1f + jitter(CREST_JITTER)),
                    harmonic = harmonic * (1f + jitter(HARMONIC_JITTER)),
                    // alpha = null -> auto by depth; tint = null -> sampled from palette.
                )
            }.toImmutableList()
            return WaveConfig(
                layers = layers,
                colors = colors,
                shadow = shadow,
                gradientEnd = gradientEnd,
                sway = sway,
            )
        }
    }
}

// generate() distribution constants.

private const val TAU: Float = 2f * PI.toFloat()

/**
 * Minimum [WaveConfig.gradientEnd]. Above `0` so the background `verticalGradient` always spans a
 * non-zero height. An `endY` of `0` (equal to `startY`) is a degenerate brush that paints flat.
 */
internal const val GRADIENT_END_MIN: Float = 0.04f

private const val BASE_BACK: Float = 0.45f
private const val BASE_FRONT: Float = 0.65f
private const val SPEED_BACK: Float = 1.0f
private const val SPEED_FRONT: Float = 0.7f
// Breathing (and the sway derived from it) carries most of the ambient motion, so it is given a
// clear depth and per-layer tempo variety (the differing rates make each surface move on its own).
private const val BREATH_DEPTH_BASE: Float = 0.28f
private const val BREATH_DEPTH_STEP: Float = 0.10f
private const val BREATH_SPEED_BASE: Float = 0.28f
private const val BREATH_SPEED_STEP: Float = 0.06f

// Per-layer jitter strengths for generate(), each scaled by the caller's `variation` in [0, 1].
private const val BASE_JITTER: Float = 0.03f
private const val AMP_JITTER: Float = 0.5f
private const val SPEED_JITTER: Float = 0.4f
private const val PHASE_JITTER: Float = PI.toFloat()
private const val BREATH_DEPTH_JITTER: Float = 0.4f
private const val BREATH_SPEED_JITTER: Float = 0.5f
private const val CREST_JITTER: Float = 0.3f
private const val HARMONIC_JITTER: Float = 0.3f
