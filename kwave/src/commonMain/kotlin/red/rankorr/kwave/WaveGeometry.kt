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

import kotlin.math.PI
import kotlin.math.sin

/**
 * Pure wave geometry: no [androidx.compose.ui.graphics.drawscope.DrawScope], no Canvas, no Compose.
 *
 * These functions take the canvas [width]/[height] as plain parameters so the exact same math used
 * by the renderer can be exercised in fast JVM unit tests with no UI. The renderer
 * ([drawWaves]) delegates to them; this file is the single source of truth for the waveform
 * equations described in `DESIGN.md` §2.
 *
 * [phase] and [time] are [Double]s internally (the public composables take `Float` and widen):
 * every time/phase-driven trigonometric argument is computed in double precision, so the waveform
 * stays smooth even after the animation has run continuously for days (a `Float` accumulator loses
 * frame-level resolution after a few hours).
 */
internal object WaveGeometry {

    /** `2 * PI` as a [Float]. */
    const val TAU: Float = 2f * PI.toFloat()

    /** `2 * PI` as a [Double]; used for the double-precision trigonometric arguments. */
    const val TAU_D: Double = 2.0 * PI

    /** Phase multiplier applied to the second-harmonic sinusoid (from the reference engine). */
    const val HARMONIC_2_PHASE_MUL: Float = 1.5f

    /** Ratio used to derive the harmonic spatial frequency from a layer's [WaveLayerSpec.crests]. */
    const val HARMONIC_FREQ_RATIO: Float = 2f

    /** Fixed harmonic phase bias preserved from the reference (`+ 1f` on the harmonic argument). */
    const val HARMONIC_PHASE_BIAS: Float = 1f

    /**
     * Peak crest sway in radians of phase per unit of [WaveLayerSpec.breathDepth]: the sway
     * amplitude is `breathDepth * SWAY_WEIGHT`, so a non-breathing layer (`breathDepth = 0`) never
     * sways and stays fully static under a constant phase.
     */
    const val SWAY_WEIGHT: Float = 1.2f

    /**
     * Sway angular frequency as a fraction of the layer's [WaveLayerSpec.breathSpeed]. Below `1`
     * so the sway is slower than the breathing and the two never read as one synchronized pulse.
     */
    const val SWAY_FREQ_RATIO: Float = 0.7f

    /**
     * Multiplier applied to [WaveLayerSpec.breathOffset] to derive the sway's own phase offset,
     * decorrelating the sway cycle from the breathing cycle of the same layer.
     */
    const val SWAY_OFFSET_MUL: Float = 1.7f

    /**
     * Breathing-modulated amplitude for a layer, in pixels:
     *
     * ```
     * height * amplitude * (1 + breathDepth * sin(time * breathSpeed + breathOffset))
     * ```
     *
     * When the `sin(...)` term is `0` (e.g. `time = 0` with `breathOffset = 0`) the result returns
     * to the nominal `height * amplitude`.
     *
     * @param layer the layer whose amplitude is being computed.
     * @param time continuous elapsed seconds driving the breathing.
     * @param height canvas height in pixels.
     */
    fun layerAmp(layer: WaveLayerSpec, time: Double, height: Float): Float {
        val breath = 1.0 + layer.breathDepth * sin(time * layer.breathSpeed + layer.breathOffset)
        return (height * layer.amplitude * breath).toFloat()
    }

    /**
     * Horizontal crest sway for a layer, in radians of phase, per `DESIGN.md` §2.2:
     *
     * ```
     * sway = swayScale * breathDepth * SWAY_WEIGHT
     *      * sin(time * breathSpeed * SWAY_FREQ_RATIO + breathOffset * SWAY_OFFSET_MUL)
     * ```
     *
     * The sway adds a slow side-to-side lean to the crests on top of the amplitude breathing, so
     * the surface rolls organically instead of only pulsing vertically. It is scaled by
     * [WaveLayerSpec.breathDepth]: a layer with `breathDepth = 0` has **zero** sway and stays fully
     * static, preserving the "no breathing ⇒ no time-driven motion" contract. [swayScale] is the
     * config-wide weight ([WaveConfig.sway]); `0` disables the sway entirely, restoring the exact
     * pre-0.2.0 waveform (breathing without sway).
     *
     * @param layer the layer to evaluate.
     * @param time continuous elapsed seconds.
     * @param swayScale config-wide sway weight (`>= 0`, `1` = nominal, `0` = no sway).
     */
    fun layerSway(layer: WaveLayerSpec, time: Double, swayScale: Float = 1f): Double {
        if (layer.breathDepth == 0f || swayScale <= 0f) return 0.0
        val arg = time * (layer.breathSpeed * SWAY_FREQ_RATIO) + layer.breathOffset * SWAY_OFFSET_MUL
        return swayScale * layer.breathDepth * SWAY_WEIGHT * sin(arg)
    }

    /**
     * Vertical crest position of a layer at horizontal pixel [x], per `DESIGN.md` §2.2:
     *
     * ```
     * t    = x / width
     * sway = swayScale * breathDepth * SWAY_WEIGHT
     *      * sin(time * breathSpeed * SWAY_FREQ_RATIO + breathOffset * SWAY_OFFSET_MUL)
     * ph   = phase * speed + phaseOffset + sway
     * y1   = crests * 2π * t + ph
     * y2   = (crests * 2) * 2π * t + ph * 1.5 + 1
     * y(x) = height * baseFrac + layerAmp * (sin(y1) + harmonic * sin(y2))
     * ```
     *
     * The harmonic frequency is derived internally as `crests * HARMONIC_FREQ_RATIO`; the public
     * [WaveLayerSpec.harmonic] field is the harmonic's **weight**. `harmonic = 0` ⇒ pure sine.
     * `sway` (see [layerSway]) is the slow organic lean of the crests; it vanishes when
     * `breathDepth = 0` or `swayScale = 0`, so a non-breathing layer (or a `sway = 0` config) is a
     * pure function of [phase].
     *
     * If [width] is non-positive the function returns the layer's nominal base line
     * (`height * baseFrac`), so it is safe to call on a degenerate size. The result is also guarded
     * to be finite: [phase] and [WaveLayerSpec.speed] are uncoerced public inputs, so a non-finite
     * or extreme value (which would make `sin(...)` yield `NaN`/`Inf` and corrupt the `Path`) falls
     * back to the base line rather than emitting `NaN` coordinates.
     *
     * @param x horizontal position in pixels.
     * @param layer the layer to evaluate.
     * @param phase horizontal phase applied to the waveform (scaled per-layer by
     *   [WaveLayerSpec.speed]); a slow drift plus an external shift under the drop-in [KWave], or
     *   any caller-driven value under the stateless overload.
     * @param time elapsed seconds (drives the breathing amplitude and the crest sway).
     * @param width canvas width in pixels.
     * @param height canvas height in pixels.
     * @param swayScale config-wide sway weight ([WaveConfig.sway]; `1` = nominal, `0` = no sway).
     */
    fun waveYAt(
        x: Float,
        layer: WaveLayerSpec,
        phase: Double,
        time: Double,
        width: Float,
        height: Float,
        swayScale: Float = 1f,
    ): Float {
        val baseLine = height * layer.baseFrac
        if (width <= 0f) return baseLine
        val t = (x / width).toDouble()
        val ph = phase * layer.speed + layer.phaseOffset + layerSway(layer, time, swayScale)
        val y1 = layer.crests * TAU_D * t + ph
        val harmonicFreq = layer.crests * HARMONIC_FREQ_RATIO
        val y2 = harmonicFreq * TAU_D * t + ph * HARMONIC_2_PHASE_MUL + HARMONIC_PHASE_BIAS
        val amp = layerAmp(layer, time, height)
        val result = baseLine + amp * (sin(y1) + layer.harmonic * sin(y2)).toFloat()
        // Finite guard: never let a NaN/Inf coordinate reach the Path sink.
        return if (result.isFinite()) result else baseLine
    }
}
