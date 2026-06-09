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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope

// ── Engine constants (internal to the renderer, never caller-facing) ────────────────────────────

/** Number of polyline samples taken across the width per wave (kept cheap yet smooth). */
internal const val WAVE_SAMPLES: Int = 96

/** Peak opacity of the soft depth shadow band. */
private const val SHADOW_ALPHA: Float = 0.28f

/** Peak opacity of the luminous highlight lip. */
private const val LIGHT_ALPHA: Float = 0.16f

/** Vertical softening of the highlight band, as a fraction of canvas height. */
private const val SOFT_UP_FRAC: Float = 0.030f

/** Vertical softening of the shadow band, as a fraction of canvas height. */
private const val SOFT_DOWN_FRAC: Float = 0.055f

/** Lerp fraction toward [Color.Black] used by [ShadowMode.FromWave]. */
private const val FROM_WAVE_DARKEN: Float = 0.6f

/** Lerp fraction toward [Color.White] used by [ShadowMode.FromWave] for the highlight. */
private const val FROM_WAVE_LIGHTEN: Float = 0.6f

/** Standard relative-luminance cutoff separating "light" from "dark" wave colors. */
private const val LUMINANCE_CUTOFF: Float = 0.5f

/**
 * Cached, reusable [Path] objects for the wave renderer — one "below" path and one "above" path per
 * layer.
 *
 * The renderer [rewind]s these every frame instead of allocating fresh [Path] instances, which is
 * the core of the performance contract. Obtain a cache via [rememberWavePaths] keyed on the layer
 * count so the allocation only happens when the number of layers changes.
 *
 * @property below per-layer fill/shadow paths (the region below each crest).
 * @property above per-layer highlight-lip paths (the region above each crest).
 */
internal class WavePathCache(layerCount: Int) {
    private val count = layerCount.coerceAtLeast(0)
    val below: List<Path> = List(count) { Path() }
    val above: List<Path> = List(count) { Path() }
}

/**
 * Remembers a [WavePathCache] sized for [layerCount]. The cache is re-created only when the layer
 * count changes, so per-frame draws reuse the same [Path] instances (rewinding them) rather than
 * allocating.
 *
 * @param layerCount number of wave layers the cache must hold paths for.
 */
@Composable
internal fun rememberWavePaths(layerCount: Int): WavePathCache =
    remember(layerCount) { WavePathCache(layerCount) }

/**
 * Draws the full KWave background into this [DrawScope] for a given [phase] and [time], using
 * caller-owned cached [paths] for zero per-frame [Path] allocation.
 *
 * Draw order (back-to-front), per `DESIGN.md` §2.4:
 * 1. the background vertical gradient over [WaveColors.backgroundStops], from `y = 0` to
 *    `y = height * gradientEnd`;
 * 2. each layer's flat fill (the region below its crest), tinted from the palette (or the layer's
 *    [WaveLayerSpec.tint] override) at the layer's resolved alpha;
 * 3. the depth FX — a soft shadow band and a luminous highlight lip — for every layer **except the
 *    front-most** ([List.dropLast] of size `1`).
 *
 * **`dropLast(1)` depth-FX contract (`DESIGN.md` §8).** The depth FX is applied to all layers
 * except the last because the front-most layer is the opaque "water surface" and needs no soft
 * edge. Using `dropLast(1)` (rather than indexed access) is inherently index-out-of-bounds-safe:
 * - at **N = 0** the per-layer loop and the FX loop both iterate zero times — only the background
 *   draws, no crash;
 * - at **N = 1** the single layer is front-most, the FX loop is empty, and it gets a flat fill
 *   only;
 * - at **N ≥ 2** layers `0..n-2` get depth FX and layer `n-1` gets a flat fill.
 *
 * **Zero-size guard.** If `size.minDimension <= 0` the function returns immediately, avoiding a
 * `x / width` division by zero and any NaN geometry.
 *
 * The caller's `modifier` is honored by the enclosing `Canvas` (the renderer never forces
 * `fillMaxSize`); this function only paints into whatever size the layout produced.
 *
 * @param config the wave configuration (layers, colors, shadow mode, gradient end).
 * @param phase horizontal phase applied to every layer (scaled per-layer by [WaveLayerSpec.speed]).
 * @param time continuous elapsed seconds driving per-layer amplitude breathing.
 * @param paths caller-owned path cache; must be sized for `config.layers.size` (see
 *   [rememberWavePaths]). If it is smaller, the missing layers fall back to freshly allocated paths.
 */
internal fun DrawScope.drawWaves(
    config: WaveConfig,
    phase: Float,
    time: Float,
    paths: WavePathCache,
) {
    // Zero-size guard: avoid div-by-zero / NaN from x / width.
    if (size.minDimension <= 0f) return

    val h = size.height
    val layers = config.layers
    val n = layers.size

    // 1. Background gradient over the resolved stops.
    drawRect(
        brush = Brush.verticalGradient(
            colors = config.colors.backgroundStops,
            startY = 0f,
            endY = h * config.gradientEnd,
        ),
        size = size,
    )

    if (n == 0) return

    // 2. Per-layer flat fill. Build each "below" path once and reuse it for the shadow band.
    layers.forEachIndexed { index, layer ->
        val below = paths.below.getOrNull(index)?.also { it.rewind() } ?: Path()
        buildRegionBelow(below, layer, phase, time)

        val fill = resolveFill(config.colors, layer, index, n)
        val alpha = layer.alpha ?: autoAlpha(index, n)
        drawPath(below, color = fill.copy(alpha = fill.alpha * alpha))
    }

    // 3. Depth FX for every layer except the front-most (dropLast(1)) — IOOB-safe at N=0 and N=1.
    if (config.shadow is ShadowMode.None) return
    // Peak opacity of the shadow band: the caller's value for Custom, the engine default otherwise.
    val shadowPeak = shadowPeakAlpha(config.shadow)
    layers.dropLast(1).forEachIndexed { index, layer ->
        val baseY = h * layer.baseFrac
        val ampMax = h * layer.amplitude * (1f + layer.breathDepth)

        val fill = resolveFill(config.colors, layer, index, n)
        val shadowColor = resolveShadowColor(config.shadow, fill)
        val highlightColor = resolveHighlightColor(config.shadow, fill, config.colors.highlight)

        // Shadow band — reuses the already-built "below" path for this layer when available.
        val below = paths.below.getOrNull(index) ?: Path().also { buildRegionBelow(it, layer, phase, time) }
        drawPath(
            path = below,
            brush = Brush.verticalGradient(
                colors = listOf(
                    shadowColor.copy(alpha = shadowPeak),
                    shadowColor.copy(alpha = 0f),
                ),
                startY = baseY - ampMax,
                endY = baseY + ampMax + h * SOFT_DOWN_FRAC,
            ),
        )

        // Highlight lip — build the "above" path once and reuse the cached instance.
        val above = paths.above.getOrNull(index)?.also { it.rewind() } ?: Path()
        buildRegionAbove(above, layer, phase, time)
        drawPath(
            path = above,
            brush = Brush.verticalGradient(
                colors = listOf(
                    highlightColor.copy(alpha = 0f),
                    highlightColor.copy(alpha = LIGHT_ALPHA),
                ),
                startY = baseY - ampMax - h * SOFT_UP_FRAC,
                endY = baseY + ampMax,
            ),
        )
    }
}

// ── Color resolution ───────────────────────────────────────────────────────────────────────────

/**
 * Resolves a layer's fill color: the explicit [WaveLayerSpec.tint] when set, otherwise the
 * palette-sampled color from [colors] at the layer's depth (never a hardcoded black).
 */
private fun resolveFill(colors: WaveColors, layer: WaveLayerSpec, index: Int, count: Int): Color =
    layer.tint ?: colors.fillColorFor(index, count)

/**
 * Resolves the shadow-band color for a layer given the [shadow] mode and the layer's local [fill]
 * color, per `DESIGN.md` §4:
 * - [ShadowMode.Auto] — black if the fill is light, white if it is dark (luminance cutoff `0.5`);
 * - [ShadowMode.FromWave] — the fill darkened toward black;
 * - [ShadowMode.Custom] — the supplied color; its **peak opacity** is [ShadowMode.Custom.alpha],
 *   applied by the band via [shadowPeakAlpha] (not baked here, so it is not lost to the band's own
 *   alpha stop);
 * - [ShadowMode.None] — never reaches here (the caller short-circuits).
 *
 * The returned color is the base hue; the renderer applies the band's peak/fade alpha on top (see
 * [shadowPeakAlpha]).
 */
private fun resolveShadowColor(shadow: ShadowMode, fill: Color): Color = when (shadow) {
    is ShadowMode.Auto ->
        if (relativeLuminance(fill) > LUMINANCE_CUTOFF) Color.Black else Color.White
    is ShadowMode.FromWave -> darken(fill, FROM_WAVE_DARKEN)
    is ShadowMode.Custom -> shadow.color
    is ShadowMode.None -> Color.Transparent
}

/**
 * Peak opacity for the shadow band: the caller-supplied [ShadowMode.Custom.alpha] for a custom
 * shadow, otherwise the engine default [SHADOW_ALPHA]. This is what makes `Custom.alpha` actually
 * drive the rendered band — applying it here (rather than baking it into [resolveShadowColor]) keeps
 * it from being overwritten by the band gradient's own top alpha stop.
 */
internal fun shadowPeakAlpha(shadow: ShadowMode): Float = when (shadow) {
    is ShadowMode.Custom -> shadow.alpha
    else -> SHADOW_ALPHA
}

/**
 * Resolves the highlight-lip color, using the **inverted** logic of [resolveShadowColor]:
 * - [ShadowMode.Auto] — for a dark fill the highlight is white; for a light fill it leans to the
 *   palette's own [highlight] color (a pure-white lip over a light wave would be invisible);
 * - [ShadowMode.FromWave] — the fill lightened toward white;
 * - [ShadowMode.Custom] — the custom color lightened (the luminous counterpart of the shadow);
 * - [ShadowMode.None] — never reaches here.
 */
private fun resolveHighlightColor(shadow: ShadowMode, fill: Color, highlight: Color): Color = when (shadow) {
    is ShadowMode.Auto ->
        if (relativeLuminance(fill) > LUMINANCE_CUTOFF) highlight else Color.White
    is ShadowMode.FromWave -> lighten(fill, FROM_WAVE_LIGHTEN)
    is ShadowMode.Custom -> lighten(shadow.color, FROM_WAVE_LIGHTEN)
    is ShadowMode.None -> Color.Transparent
}

// ── Geometry plumbing (math lives in WaveGeometry; this only builds Paths) ───────────────────────

/**
 * Vertical crest position for this [DrawScope]'s current size; delegates to [WaveGeometry.waveYAt]
 * (the single source of truth for the waveform math).
 */
private fun DrawScope.waveYAt(x: Float, layer: WaveLayerSpec, phase: Float, time: Float): Float =
    WaveGeometry.waveYAt(x, layer, phase, time, size.width, size.height)

/**
 * (Re)builds the "region below the crest" polyline into [target]: `WAVE_SAMPLES + 1` points along
 * the crest, then closed down to `(width, height)` → `(0, height)`. [target] is assumed to be freshly
 * [rewind]-ed (or empty). This is the layer's fill + shadow region.
 */
private fun DrawScope.buildRegionBelow(target: Path, layer: WaveLayerSpec, phase: Float, time: Float) {
    val w = size.width
    val h = size.height
    for (i in 0..WAVE_SAMPLES) {
        val x = w * i / WAVE_SAMPLES
        val y = waveYAt(x, layer, phase, time)
        if (i == 0) target.moveTo(x, y) else target.lineTo(x, y)
    }
    target.lineTo(w, h)
    target.lineTo(0f, h)
    target.close()
}

/**
 * (Re)builds the "region above the crest" polyline into [target]: from `(0,0)` → `(width,0)` then
 * back across the crest (sampled `WAVE_SAMPLES downTo 0`), closed. [target] is assumed to be freshly
 * [rewind]-ed (or empty). This is the highlight-lip region.
 */
private fun DrawScope.buildRegionAbove(target: Path, layer: WaveLayerSpec, phase: Float, time: Float) {
    val w = size.width
    target.moveTo(0f, 0f)
    target.lineTo(w, 0f)
    for (i in WAVE_SAMPLES downTo 0) {
        val x = w * i / WAVE_SAMPLES
        target.lineTo(x, waveYAt(x, layer, phase, time))
    }
    target.close()
}
