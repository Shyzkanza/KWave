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
 */
internal object WaveGeometry {

    /** `2 * PI` as a [Float]. */
    const val TAU: Float = 2f * PI.toFloat()

    /** Phase multiplier applied to the second-harmonic sinusoid (from the reference engine). */
    const val HARMONIC_2_PHASE_MUL: Float = 1.5f

    /** Ratio used to derive the harmonic spatial frequency from a layer's [WaveLayerSpec.crests]. */
    const val HARMONIC_FREQ_RATIO: Float = 2f

    /** Fixed harmonic phase bias preserved from the reference (`+ 1f` on the harmonic argument). */
    const val HARMONIC_PHASE_BIAS: Float = 1f

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
    fun layerAmp(layer: WaveLayerSpec, time: Float, height: Float): Float {
        val breath = 1f + layer.breathDepth * sin(time * layer.breathSpeed + layer.breathOffset)
        return height * layer.amplitude * breath
    }

    /**
     * Vertical crest position of a layer at horizontal pixel [x], per `DESIGN.md` §2.2:
     *
     * ```
     * t   = x / width
     * ph  = phase * speed + phaseOffset
     * y1  = crests * 2π * t + ph
     * y2  = (crests * 2) * 2π * t + ph * 1.5 + 1
     * y(x) = height * baseFrac + layerAmp * (sin(y1) + harmonic * sin(y2))
     * ```
     *
     * The harmonic frequency is derived internally as `crests * HARMONIC_FREQ_RATIO`; the public
     * [WaveLayerSpec.harmonic] field is the harmonic's **weight**. `harmonic = 0` ⇒ pure sine.
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
     *   [WaveLayerSpec.speed]); a constant ambient offset under the drop-in [KWave], or any
     *   caller-driven value under the stateless overload.
     * @param time elapsed seconds (drives the breathing amplitude).
     * @param width canvas width in pixels.
     * @param height canvas height in pixels.
     */
    fun waveYAt(
        x: Float,
        layer: WaveLayerSpec,
        phase: Float,
        time: Float,
        width: Float,
        height: Float,
    ): Float {
        val baseLine = height * layer.baseFrac
        if (width <= 0f) return baseLine
        val t = x / width
        val ph = phase * layer.speed + layer.phaseOffset
        val y1 = layer.crests * TAU * t + ph
        val harmonicFreq = layer.crests * HARMONIC_FREQ_RATIO
        val y2 = harmonicFreq * TAU * t + ph * HARMONIC_2_PHASE_MUL + HARMONIC_PHASE_BIAS
        val amp = layerAmp(layer, time, height)
        val result = baseLine + amp * (sin(y1) + layer.harmonic * sin(y2))
        // Finite guard: never let a NaN/Inf coordinate reach the Path sink.
        return if (result.isFinite()) result else baseLine
    }
}
