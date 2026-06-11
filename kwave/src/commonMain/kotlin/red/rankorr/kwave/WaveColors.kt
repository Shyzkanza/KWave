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
import androidx.compose.ui.graphics.lerp

/**
 * Color strategy for the wave background and per-layer fills.
 *
 * KWave is theme-free: the caller supplies every color through this type, and the library reads no
 * `MaterialTheme`. A [WaveColors] instance is built **only** through the [gradient], [palette] and
 * [solid] factory functions; the primary constructor is private, which keeps the public surface
 * small and lets the internal representation evolve without breaking binary compatibility.
 *
 * Internally a [WaveColors] resolves three things consumed by the renderer:
 *
 * 1. [backgroundStops]: the ordered gradient stops painted as the canvas background.
 * 2. [fillColorFor]: the per-layer fill color, **derived from the palette** by sampling at the
 *    layer's normalized depth. This is the chief correction over the original reference renderer,
 *    which always filled each layer with a hardcoded [Color.Black]; here the fill is **never** black
 *    unless the caller explicitly supplies a black-based palette.
 * 3. [highlight]: the crest-light tint, blended into the top of each wave's body-fill gradient.
 *
 * Per-layer **alpha** is not part of [WaveColors]: it is auto-assigned by depth (see [autoAlpha])
 * unless a [WaveLayerSpec.alpha] override is present.
 *
 * This class is [Immutable]: all of its inputs are immutable [Color] values captured at
 * construction, so the Compose compiler may treat instances as stable and skip recomposition when
 * the same instance is reused.
 *
 * @see gradient
 * @see palette
 * @see solid
 */
@Immutable
public class WaveColors private constructor(
    /**
     * Ordered gradient stops for the canvas background, top-to-bottom. Always contains at least
     * two entries (a single supplied color is duplicated so the vertical gradient is well-defined).
     */
    public val backgroundStops: List<Color>,
    /**
     * The ordered palette the per-layer fill is sampled from. For [gradient] this is `[top, bottom]`;
     * for [palette] it is the supplied (coerced) color list; for [solid] it is a single color.
     */
    private val fillStops: List<Color>,
    /** The crest-light tint: the renderer blends it into the top of each wave's body-fill gradient. */
    public val highlight: Color,
) {

    /**
     * Resolves the fill color for the layer at [layerIndex] within a stack of [layerCount] layers.
     *
     * The color is **derived from the palette**, never a hardcoded black. The layer's normalized
     * depth `layerIndex / (layerCount - 1)` (back = `0`, front = `1`) is used to sample the
     * configured [fillStops]:
     *
     * - For a [gradient], back layers lean toward the `top` color and front layers toward `bottom`.
     * - For a [palette] (rainbow), each layer picks a distinct hue from the multi-stop palette.
     * - For a [solid], the fill ramps slightly by depth (darker back, lighter front) so the
     *   same-color waves stay visible; the auto per-layer alpha adds further separation.
     *
     * Indices and counts are coerced defensively so the function never throws: a non-positive
     * [layerCount] is treated as `1`, and [layerIndex] is clamped into `0 until layerCount`.
     *
     * @param layerIndex zero-based layer index, `0` = back-most.
     * @param layerCount total number of layers in the stack.
     * @return the palette-sampled fill color for that layer.
     */
    public fun fillColorFor(layerIndex: Int, layerCount: Int): Color {
        val count = layerCount.coerceAtLeast(1)
        val index = layerIndex.coerceIn(0, count - 1)
        val depth = if (count <= 1) 0f else index.toFloat() / (count - 1)
        return sampleStops(fillStops, depth)
    }

    /**
     * Whether the background has any visible stop. When every stop is fully transparent the
     * renderer skips the background pass entirely (no full-canvas rect is drawn), which is the
     * "waves-only" mode enabled by `withBackground(Color.Transparent)`.
     */
    internal val hasVisibleBackground: Boolean = backgroundStops.any { it.alpha > 0f }

    /**
     * Returns a copy of this [WaveColors] with only the **background** replaced by a flat [color];
     * the wave-fill palette and the highlight are untouched. This decouples the backdrop from the
     * waves: the factories ([gradient]/[palette]/[solid]) build a coherent scene where both derive
     * from the same colors, and this override swaps the backdrop afterwards.
     *
     * Pass [Color.Transparent] for the **waves-only** mode: the renderer then skips the background
     * pass entirely, so KWave can sit on top of your own background (an image, another composable).
     *
     * @param color the flat background color ([Color.Transparent] disables the background pass).
     */
    public fun withBackground(color: Color): WaveColors =
        WaveColors(
            backgroundStops = listOf(color, color),
            fillStops = fillStops,
            highlight = highlight,
        )

    /**
     * Returns a copy of this [WaveColors] with only the **background** replaced by a [top] → [bottom]
     * vertical gradient; the wave-fill palette and the highlight are untouched (see
     * [withBackground] for the rationale).
     *
     * @param top background color at the top of the canvas.
     * @param bottom background color at the gradient end (`WaveConfig.gradientEnd`).
     */
    public fun withBackground(top: Color, bottom: Color): WaveColors =
        WaveColors(
            backgroundStops = listOf(top, bottom),
            fillStops = fillStops,
            highlight = highlight,
        )

    /**
     * Returns a copy of this [WaveColors] with only the **background** replaced by the ordered
     * multi-stop [stops] gradient; the wave-fill palette and the highlight are untouched (see
     * [withBackground] for the rationale).
     *
     * Coercion: an **empty** list behaves like `withBackground(Color.Transparent)` (waves-only);
     * a **single-element** list behaves like the flat-color overload.
     *
     * @param stops ordered background gradient stops, top to bottom.
     */
    public fun withBackground(stops: List<Color>): WaveColors = when {
        stops.isEmpty() -> withBackground(Color.Transparent)
        stops.size == 1 -> withBackground(stops.first())
        else -> WaveColors(
            backgroundStops = stops.toList(),
            fillStops = fillStops,
            highlight = highlight,
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WaveColors) return false
        return backgroundStops == other.backgroundStops &&
            fillStops == other.fillStops &&
            highlight == other.highlight
    }

    override fun hashCode(): Int {
        var result = backgroundStops.hashCode()
        result = 31 * result + fillStops.hashCode()
        result = 31 * result + highlight.hashCode()
        return result
    }

    override fun toString(): String =
        "WaveColors(backgroundStops=$backgroundStops, fillStops=$fillStops, highlight=$highlight)"

    public companion object {

        /**
         * Builds a simple vertical auto-gradient running from [top] (canvas top) to [bottom]
         * (canvas bottom).
         *
         * The background paints the `top → bottom` gradient, and each layer's fill is sampled from
         * the same two-stop gradient at its depth: back layers lean toward [top], front layers
         * toward [bottom]. This produces depth tinting without any black overlay. The
         * [highlight] is a lightened variant of [top].
         *
         * @param top color at the top of the canvas.
         * @param bottom color at the bottom of the canvas.
         */
        public fun gradient(top: Color, bottom: Color): WaveColors {
            // Two identical colors form a flat gradient whose uniform per-layer fill would be
            // invisible (the same color over the same-color background). Route to solid() so its
            // depth ramp keeps the waves visible.
            if (top == bottom) return solid(top)
            val stops = listOf(top, bottom)
            return WaveColors(
                backgroundStops = stops,
                fillStops = stops,
                highlight = lighten(top, HIGHLIGHT_LIGHTEN),
            )
        }

        /**
         * Builds a "rainbow" palette: every supplied hue is carried by the **wave fills**, while the
         * **background** is a muted wash so the colorful waves stay the subject.
         *
         * Each wave layer is tinted by sampling the full palette at its depth (`fillColorFor(i, n)`
         * evaluates the palette at `i / (n - 1)`), so each layer carries a distinct hue. The
         * background, however, is **not** the full saturated palette (which would out-shout the
         * waves): it is a muted 2-stop gradient derived from the palette extremes (darkened first →
         * darkened last). The [highlight] is a lightened sample taken near the front of the palette.
         *
         * Coercion:
         * - an **empty** list falls back to a single neutral color (behaves like [solid] on that
         *   neutral color);
         * - a **single-element** list behaves like [solid] on that color.
         *
         * @param colors the ordered palette to sample across the wave stack.
         */
        public fun palette(colors: List<Color>): WaveColors {
            val coerced = when {
                colors.isEmpty() -> listOf(NEUTRAL_FALLBACK)
                else -> colors
            }
            if (coerced.size == 1) return solid(coerced.first())
            // Highlight sampled near the front of the palette, then lightened.
            val frontSample = sampleStops(coerced, HIGHLIGHT_SAMPLE_DEPTH)
            return WaveColors(
                // The rainbow belongs on the WAVES, not the backdrop. Painting the full saturated
                // palette across the whole canvas makes the background out-shout the waves; instead
                // derive a calm, muted 2-stop wash from the palette extremes (darkened) so the sky
                // recedes behind the colorful wave fills.
                backgroundStops = listOf(
                    darken(coerced.first(), PALETTE_BG_TOP_DARKEN),
                    darken(coerced.last(), PALETTE_BG_BOTTOM_DARKEN),
                ),
                fillStops = coerced,
                highlight = lighten(frontSample, HIGHLIGHT_LIGHTEN),
            )
        }

        /**
         * Builds a single flat [color]. The background is a flat fill of [color] (the two background
         * stops are identical). The per-layer fill **ramps by depth** around [color] (a slightly
         * darker back, a slightly lighter front) instead of repeating it verbatim: a uniform
         * same-color fill would be invisible over the same-color background, so the waves would
         * disappear. The ramp (plus the auto per-layer alpha, see [autoAlpha]) keeps them visible.
         * The [highlight] is a lightened variant of [color].
         *
         * @param color the single flat base color; the background is flat, the fills ramp around it.
         */
        public fun solid(color: Color): WaveColors {
            return WaveColors(
                backgroundStops = listOf(color, color),
                // Ramp the fill by depth (darker back → lighter front) instead of repeating the flat
                // color: a same-color fill over the same-color background is invisible, so the waves
                // would vanish. The ramp keeps the layers visible and separated.
                fillStops = listOf(
                    darken(color, SOLID_FILL_BACK_DARKEN),
                    lighten(color, SOLID_FILL_FRONT_LIGHTEN),
                ),
                highlight = lighten(color, HIGHLIGHT_LIGHTEN),
            )
        }
    }
}

// Color resolution helpers (internal to the color model).

/** Floor alpha applied to the back-most layer; the front layer ramps to fully opaque. */
internal const val BACK_ALPHA_FLOOR: Float = 0.40f

/** Lerp fraction toward white used to derive highlight colors from a base color. */
private const val HIGHLIGHT_LIGHTEN: Float = 0.45f

/** Depth darken applied to the back-most fill in solid/monochrome palettes (keeps same-color waves visible). */
private const val SOLID_FILL_BACK_DARKEN: Float = 0.30f

/** Depth lighten applied to the front-most fill in solid/monochrome palettes. */
private const val SOLID_FILL_FRONT_LIGHTEN: Float = 0.12f

/** Depth at which [WaveColors.palette] samples its highlight (near the front of the palette). */
private const val HIGHLIGHT_SAMPLE_DEPTH: Float = 0.85f

/** Darken applied to the first palette color when deriving the muted background top in [WaveColors.palette]. */
private const val PALETTE_BG_TOP_DARKEN: Float = 0.25f

/** Darken applied to the last palette color when deriving the muted background bottom in [WaveColors.palette]. */
private const val PALETTE_BG_BOTTOM_DARKEN: Float = 0.15f

/** Neutral fallback color used when [WaveColors.palette] is given an empty list. */
private val NEUTRAL_FALLBACK: Color = Color(0xFF455A64)

/**
 * Auto-assigns a layer's opacity from its depth: the back-most layer ([index] `0`) is the most
 * transparent (floored at [BACK_ALPHA_FLOOR]) and the front-most layer is fully opaque (`1f`).
 *
 * For a single layer (`count == 1`) the result is `1f`. Inputs are coerced so the function never
 * throws on degenerate values.
 *
 * @param index zero-based layer index, `0` = back-most.
 * @param count total number of layers.
 * @return the auto alpha in `[BACK_ALPHA_FLOOR, 1f]`.
 */
internal fun autoAlpha(index: Int, count: Int): Float {
    val n = count.coerceAtLeast(1)
    if (n <= 1) return 1f
    val i = index.coerceIn(0, n - 1)
    val fraction = i.toFloat() / (n - 1)
    return lerp01(BACK_ALPHA_FLOOR, 1f, fraction)
}

/**
 * Samples an ordered list of color [stops] at normalized position [t] in `[0, 1]` with linear
 * interpolation between adjacent stops. An empty list returns a neutral fallback; a single stop is
 * returned directly. [t] is clamped into `[0, 1]`.
 */
internal fun sampleStops(stops: List<Color>, t: Float): Color {
    if (stops.isEmpty()) return NEUTRAL_FALLBACK
    if (stops.size == 1) return stops.first()
    val clamped = t.coerceIn(0f, 1f)
    val segments = stops.size - 1
    val scaled = clamped * segments
    val lower = scaled.toInt().coerceIn(0, segments - 1)
    val localT = scaled - lower
    return lerp(stops[lower], stops[lower + 1], localT)
}

/**
 * Standard relative luminance of a [color] (`0.2126*r + 0.7152*g + 0.0722*b`) in `[0, 1]`. Used by
 * [ShadowMode.Auto] to decide whether a layer's shadow should be black or white.
 */
internal fun relativeLuminance(color: Color): Float {
    return 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue
}

/** Lerps [color] toward [Color.White] by [fraction] (clamped `[0, 1]`), preserving alpha. */
internal fun lighten(color: Color, fraction: Float): Color {
    return lerp(color, Color.White, fraction.coerceIn(0f, 1f)).copy(alpha = color.alpha)
}

/** Lerps [color] toward [Color.Black] by [fraction] (clamped `[0, 1]`), preserving alpha. */
internal fun darken(color: Color, fraction: Float): Color {
    return lerp(color, Color.Black, fraction.coerceIn(0f, 1f)).copy(alpha = color.alpha)
}

/** Simple scalar linear interpolation from [start] to [stop] by [fraction] in `[0, 1]`. */
internal fun lerp01(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction.coerceIn(0f, 1f)
}
