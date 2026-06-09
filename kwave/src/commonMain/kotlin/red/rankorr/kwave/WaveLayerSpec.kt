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

/**
 * Advanced, low-level specification for a **single** wave layer.
 *
 * All geometry is expressed as a fraction of the canvas size, so a layer renders identically at any
 * resolution. This is the power-user surface: most callers should use [WaveConfig.Default] or
 * [WaveConfig.generate] and never touch [WaveLayerSpec] directly.
 *
 * **Validation.** Every value is **coerced into its valid range at construction** (see the
 * per-parameter notes below), so an out-of-range input can never reach the renderer — for example a
 * negative [amplitude] becomes `0` (a flat line, not a crash) and a [baseFrac] above `1` is clamped
 * to `1`. The coerced values are exposed through the read-only properties.
 *
 * This is a regular [Immutable] class (not a `data class`): it deliberately exposes **no** `copy()`
 * or `componentN()` so that adding a field later remains binary-compatible. Use [withTint] /
 * [withAlpha] for the common targeted overrides.
 *
 * @param baseFrac vertical centre of the layer, as a fraction of canvas height. Coerced into
 *   `[0, 1]`. Default `0.5`.
 * @param amplitude peak displacement, as a fraction of canvas height. Coerced to `>= 0` (a negative
 *   value becomes `0`). Default `0.03`.
 * @param speed multiplier applied to the caller's `phase` for this layer (lets layers respond to
 *   `phase` by different amounts); under the constant-phase drop-in it only shifts the static crest
 *   position — set just one of [speed]/[phaseOffset]. Default `1`.
 * @param phaseOffset constant horizontal phase offset, in radians. Together with [speed] this fixes
 *   the layer's static horizontal crest position; set just one. Default `0`.
 * @param breathDepth depth of the amplitude-breathing oscillation, as a fraction of [amplitude].
 *   Coerced into `[0, 1]`. Default `0.2`.
 * @param breathSpeed angular frequency of the breathing oscillation, in radians per second. Coerced
 *   to `>= 0`. Default `0.25`.
 * @param breathOffset phase offset of the breathing oscillation, in radians. Primarily a
 *   [WaveConfig.generate]-internal desync dimension — rarely hand-set. Default `0`.
 * @param crests relative crest density — higher = more, tighter crests across the width (formerly
 *   `c1`); a density, **not** a literal crest count. Coerced to `>= 0`. Default `0.8`.
 * @param harmonic **weight** of the second-harmonic sinusoid (the harmonic's frequency is derived
 *   internally). Coerced to `>= 0`. A value of `0` yields a **pure sine** wave. Default `0.25`.
 * @param alpha per-layer opacity override in `[0, 1]`; `null` means the system auto-assigns alpha by
 *   depth (back transparent → front opaque). When non-null it is coerced into `[0, 1]`. Default
 *   `null`.
 * @param tint per-layer fill-color override; `null` means the fill is sampled from the configured
 *   [WaveColors] palette at this layer's depth. Default `null`.
 */
@Immutable
public class WaveLayerSpec(
    baseFrac: Float = 0.5f,
    amplitude: Float = 0.03f,
    public val speed: Float = 1f,
    public val phaseOffset: Float = 0f,
    breathDepth: Float = 0.2f,
    breathSpeed: Float = 0.25f,
    public val breathOffset: Float = 0f,
    crests: Float = 0.8f,
    harmonic: Float = 0.25f,
    alpha: Float? = null,
    public val tint: Color? = null,
) {
    /** Vertical centre of the layer, coerced into `[0, 1]`. */
    public val baseFrac: Float = baseFrac.coerceIn(0f, 1f)

    /** Peak displacement fraction, coerced to `>= 0`. */
    public val amplitude: Float = amplitude.coerceAtLeast(0f)

    /** Amplitude-breathing depth, coerced into `[0, 1]`. */
    public val breathDepth: Float = breathDepth.coerceIn(0f, 1f)

    /** Breathing angular frequency, coerced to `>= 0`. */
    public val breathSpeed: Float = breathSpeed.coerceAtLeast(0f)

    /** Primary spatial frequency (ex-`c1`), coerced to `>= 0`. */
    public val crests: Float = crests.coerceAtLeast(0f)

    /** Second-harmonic weight (`0` ⇒ pure sine), coerced to `>= 0`. */
    public val harmonic: Float = harmonic.coerceAtLeast(0f)

    /** Per-layer opacity override in `[0, 1]`, or `null` for auto-by-depth. */
    public val alpha: Float? = alpha?.coerceIn(0f, 1f)

    /**
     * Returns a copy of this layer with its [tint] replaced. Provided as the ergonomic override for
     * the most common low-level tweak without exposing a full `data class` `copy()`.
     */
    public fun withTint(tint: Color?): WaveLayerSpec = WaveLayerSpec(
        baseFrac = baseFrac,
        amplitude = amplitude,
        speed = speed,
        phaseOffset = phaseOffset,
        breathDepth = breathDepth,
        breathSpeed = breathSpeed,
        breathOffset = breathOffset,
        crests = crests,
        harmonic = harmonic,
        alpha = alpha,
        tint = tint,
    )

    /**
     * Returns a copy of this layer with its [alpha] override replaced (pass `null` to restore
     * auto-by-depth alpha).
     */
    public fun withAlpha(alpha: Float?): WaveLayerSpec = WaveLayerSpec(
        baseFrac = baseFrac,
        amplitude = amplitude,
        speed = speed,
        phaseOffset = phaseOffset,
        breathDepth = breathDepth,
        breathSpeed = breathSpeed,
        breathOffset = breathOffset,
        crests = crests,
        harmonic = harmonic,
        alpha = alpha,
        tint = tint,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WaveLayerSpec) return false
        return baseFrac == other.baseFrac &&
            amplitude == other.amplitude &&
            speed == other.speed &&
            phaseOffset == other.phaseOffset &&
            breathDepth == other.breathDepth &&
            breathSpeed == other.breathSpeed &&
            breathOffset == other.breathOffset &&
            crests == other.crests &&
            harmonic == other.harmonic &&
            alpha == other.alpha &&
            tint == other.tint
    }

    override fun hashCode(): Int {
        var result = baseFrac.hashCode()
        result = 31 * result + amplitude.hashCode()
        result = 31 * result + speed.hashCode()
        result = 31 * result + phaseOffset.hashCode()
        result = 31 * result + breathDepth.hashCode()
        result = 31 * result + breathSpeed.hashCode()
        result = 31 * result + breathOffset.hashCode()
        result = 31 * result + crests.hashCode()
        result = 31 * result + harmonic.hashCode()
        result = 31 * result + (alpha?.hashCode() ?: 0)
        result = 31 * result + (tint?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "WaveLayerSpec(baseFrac=$baseFrac, amplitude=$amplitude, speed=$speed, " +
            "phaseOffset=$phaseOffset, breathDepth=$breathDepth, breathSpeed=$breathSpeed, " +
            "breathOffset=$breathOffset, crests=$crests, harmonic=$harmonic, alpha=$alpha, tint=$tint)"
}
