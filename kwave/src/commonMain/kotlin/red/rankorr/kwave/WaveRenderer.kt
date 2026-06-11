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
import androidx.compose.ui.graphics.lerp
import kotlin.math.pow

// Engine constants (internal to the renderer, never caller-facing).

/** Number of polyline samples taken across the width per wave (kept cheap yet smooth). */
internal const val WAVE_SAMPLES: Int = 96

/** Total peak opacity of the diffuse shadow each layer casts on the content behind it. */
private const val SHADOW_ALPHA: Float = 0.14f

/**
 * Number of stacked bands forming the cast-shadow falloff above each crest. The bands share one
 * color at `peak / SHADOW_BANDS` alpha and overlap from the edge up, so their accumulation is
 * densest against the edge and fades away from it. Kept high enough that each band edge changes
 * the opacity by under ~1.5% (no traceable line anywhere).
 */
private const val SHADOW_BANDS: Int = 10

/**
 * Reach of the cast shadow **above** the crest (onto the content behind), as a fraction of canvas
 * height. Paired with [SHADOW_EASE], it reads as a blurred drop shadow, not a contact line.
 */
private const val SHADOW_REACH_FRAC: Float = 0.028f

/**
 * Easing exponent of the cast-shadow band spans (`reach * (k / N)^EASE`): the bands bunch up near
 * the edge and spread out in the tail, so the accumulated profile decays smoothly like a blur
 * (steep at the edge, long soft tail) instead of linearly — a linear profile has a perceptible
 * outer boundary that reads as a stroke.
 */
private const val SHADOW_EASE: Float = 2f

/** Lerp fraction toward [Color.Black] used by [ShadowMode.FromWave]. */
private const val FROM_WAVE_DARKEN: Float = 0.6f

/** Standard relative-luminance cutoff separating "light" from "dark" wave colors. */
private const val LUMINANCE_CUTOFF: Float = 0.5f

/** Lerp fraction toward black applied to the bottom stop of each layer's body-fill gradient. */
private const val FILL_DEPTH_DARKEN: Float = 0.22f

/**
 * How much of [WaveColors.highlight] is mixed into the **top stop** of each layer's body-fill
 * gradient. This is the crest light: instead of painting a separate rim shape (a thin band along
 * a 2000-px-wide crest reads as a "string"), the light lives inside the fill itself — the crest
 * region of every wave is gently lifted toward the palette's highlight and fades into the body
 * color across the breathing envelope. No added geometry, nothing to trace.
 */
private const val FILL_CREST_HIGHLIGHT_MIX: Float = 0.35f

/**
 * Cached, reusable per-frame rendering objects for the wave renderer: one crest-sample row, one
 * relief row and one "below" [Path] per layer, the stacked cast-shadow / rim-light band [Path]s,
 * plus every frame-invariant paint object (the background [Brush], the per-layer body-fill
 * [Brush]es, and the per-layer shadow/rim band [Color]s with their per-band alpha baked in).
 *
 * The renderer fills [crests] once per layer per frame (the single trigonometric pass) and rebuilds
 * every path from that row by [Path.rewind]ing the cached instances; the brushes/colors depend only
 * on the configuration and the canvas height, so [prepare] rebuilds them **only** when either
 * changes. Together this is the core of the performance contract: a steady frame allocates zero
 * [Path]s and zero [Brush]es. Obtain a cache via [rememberWaveRenderCache].
 *
 * @property crests per-layer crest sample rows (one y per sample column), refilled each frame.
 * @property below per-layer body-fill paths (the region below each crest).
 * @property shadowBands per-layer stacked cast-shadow band ribbons above the crest.
 */
internal class WaveRenderCache(layerCount: Int) {
    private val count = layerCount.coerceAtLeast(0)
    val crests: List<FloatArray> = List(count) { FloatArray(WAVE_SAMPLES + 1) }
    val below: List<Path> = List(count) { Path() }
    val shadowBands: List<List<Path>> = List(count) { List(SHADOW_BANDS) { Path() } }

    // Brush/color cache keys; NaN height guarantees the first prepare() always builds.
    private var cachedConfig: WaveConfig? = null
    private var cachedHeight: Float = Float.NaN

    /** Background gradient brush; valid after [prepare] (`null` in waves-only mode). */
    var background: Brush? = null
        private set

    private val fillBrushes = arrayOfNulls<Brush>(count)
    private val shadowColors = arrayOfNulls<Color>(count)

    /** Cached body-fill gradient for layer [index] (depth-darkened, layer alpha baked), or `null` past [count]. */
    fun fillBrush(index: Int): Brush? = fillBrushes.getOrNull(index)

    /** Cached cast-shadow band color (per-band alpha baked) for layer [index], or `null` past [count]. */
    fun shadowBandColor(index: Int): Color? = shadowColors.getOrNull(index)

    /**
     * Rebuilds the frame-invariant paint objects if ([config], [height]) differ from the cached
     * pair; a no-op on every steady frame. Equality (not identity) is used on [config] so a caller
     * that rebuilds an equal config each recomposition still hits the cache.
     */
    fun prepare(config: WaveConfig, height: Float) {
        if (height == cachedHeight && config == cachedConfig) return
        cachedConfig = config
        cachedHeight = height

        background = if (config.colors.hasVisibleBackground) {
            Brush.verticalGradient(
                colors = config.colors.backgroundStops,
                startY = 0f,
                endY = height * config.gradientEnd,
            )
        } else {
            null // Waves-only mode: no background pass at all.
        }

        val layers = config.layers
        val n = layers.size
        for (i in 0 until count) {
            val layer = layers.getOrNull(i)
            if (layer == null) {
                fillBrushes[i] = null
                shadowColors[i] = null
                continue
            }
            fillBrushes[i] = buildFillBrush(config, layer, i, n, height)
            // Every layer casts a shadow on whatever is behind it, unless disabled.
            shadowColors[i] = if (config.shadow !is ShadowMode.None) {
                resolveShadowBandColor(config.shadow, resolveFill(config.colors, layer, i, n))
            } else {
                null
            }
        }
    }
}

/**
 * Remembers a [WaveRenderCache] sized for [config]'s layer count. The cache instance is re-created
 * only when the **layer count** changes (the paths/rows only depend on it); any other config change
 * is absorbed by [WaveRenderCache.prepare], which rebuilds just the brushes/colors. Per-frame draws
 * therefore reuse the same [Path]/[Brush] objects rather than allocating.
 *
 * @param config the configuration whose layer count sizes the cache.
 */
@Composable
internal fun rememberWaveRenderCache(config: WaveConfig): WaveRenderCache {
    val layerCount = config.layers.size
    return remember(layerCount) { WaveRenderCache(layerCount) }
}

/**
 * Draws the full KWave background into this [DrawScope] for a given [phase] and [time], using a
 * caller-owned [cache] for zero per-frame [Path]/[Brush] allocation.
 *
 * Draw order, per `DESIGN.md` §2.4: first the background gradient over
 * [WaveColors.backgroundStops] from `y = 0` to `y = height * gradientEnd` (skipped entirely when
 * every stop is fully transparent — waves-only mode, see [WaveColors.withBackground]); then **one
 * interleaved pass per layer, back to front**:
 *
 * 1. the layer's **cast shadow** — a diffuse, eased stack of bands hugging the crest from
 *    **above**, painted on whatever is already behind it (the background and the layers further
 *    back), like the blurred drop shadow of a sheet resting on the sheet below;
 * 2. the layer's **body fill** (the region below its crest), painted with a vertical depth
 *    gradient: the crest light (the fill gently lifted toward [WaveColors.highlight], see
 *    [FILL_CREST_HIGHLIGHT_MIX]) fading to the palette color across the breathing envelope, then
 *    darkened by [FILL_DEPTH_DARKEN] toward the canvas bottom. There is deliberately **no
 *    separate rim/highlight shape**: a thin band tracing a wide crest reads as a "string"; the
 *    light lives inside the fill instead.
 *
 * Because each layer's shadow is painted **before** the layers in front of it, it can never
 * bleed over a nearer wave: the front fills cover it. (The previous two-pass design painted all
 * FX after all fills, so a back layer's shadow could smear across the front waves.) The crest is
 * sampled **once** per layer into the cache's row and shared by every path of that layer.
 *
 * Zero-size guard. If `size.minDimension <= 0` the function returns immediately, avoiding a
 * `x / width` division by zero and any NaN geometry. At `N = 0` only the background draws; at any
 * `N` the per-layer pass needs no cross-layer indexing, so there is nothing to go out of bounds.
 *
 * The caller's `modifier` is honored by the enclosing `Canvas` (the renderer never forces
 * `fillMaxSize`); this function only paints into whatever size the layout produced.
 *
 * [phase] and [time] are [Double]s so a long-running animation never loses frame-level precision
 * (the public composables widen their `Float` inputs).
 *
 * @param config the wave configuration (layers, colors, shadow mode, gradient end, sway).
 * @param phase horizontal phase applied to every layer (scaled per-layer by [WaveLayerSpec.speed]).
 * @param time continuous elapsed seconds driving per-layer amplitude breathing and crest sway.
 * @param cache caller-owned render cache; must be sized for `config.layers.size` (see
 *   [rememberWaveRenderCache]). If it is smaller, the missing layers fall back to freshly
 *   allocated rows/paths/brushes.
 */
internal fun DrawScope.drawWaves(
    config: WaveConfig,
    phase: Double,
    time: Double,
    cache: WaveRenderCache,
) {
    // Zero-size guard: avoid div-by-zero / NaN from x / width.
    if (size.minDimension <= 0f) return

    val h = size.height
    val layers = config.layers
    val n = layers.size

    // Rebuild the frame-invariant brushes/colors only when config or height changed (no-op otherwise).
    cache.prepare(config, h)

    // Background gradient — skipped entirely when every stop is fully transparent (waves-only
    // mode, see WaveColors.withBackground), saving the full-canvas pass.
    if (config.colors.hasVisibleBackground) {
        drawRect(
            brush = cache.background ?: Brush.verticalGradient(
                colors = config.colors.backgroundStops,
                startY = 0f,
                endY = h * config.gradientEnd,
            ),
            size = size,
        )
    }

    if (n == 0) return

    // One interleaved pass per layer, back to front: cast shadow (behind it), then body fill.
    val swayScale = config.sway
    val fxEnabled = config.shadow !is ShadowMode.None
    layers.forEachIndexed { index, layer ->
        // Single trigonometric pass for this layer; every path below reuses the row.
        val crest = cache.crests.getOrNull(index) ?: FloatArray(WAVE_SAMPLES + 1)
        sampleCrest(crest, layer, phase, time, swayScale)

        if (fxEnabled) drawCastShadow(config, layer, index, n, crest, cache)

        val below = cache.below.getOrNull(index)?.also { it.rewind() } ?: Path()
        buildRegionBelow(below, crest)
        drawPath(below, brush = cache.fillBrush(index) ?: buildFillBrush(config, layer, index, n, h))
    }
}

/**
 * Draws the diffuse shadow layer [index] casts on the content behind it: [SHADOW_BANDS]
 * overlapping ribbons hugging the crest from **above**, with [SHADOW_EASE]-eased spans so the
 * accumulated profile decays like a blur (densest against the edge, long soft tail, no traceable
 * outer boundary). Painted **before** the layer's own fill, so it only ever darkens what is
 * behind this layer — never the layer itself, never a nearer wave.
 */
private fun DrawScope.drawCastShadow(
    config: WaveConfig,
    layer: WaveLayerSpec,
    index: Int,
    layerCount: Int,
    crest: FloatArray,
    cache: WaveRenderCache,
) {
    val color = cache.shadowBandColor(index)
        ?: resolveShadowBandColor(config.shadow, resolveFill(config.colors, layer, index, layerCount))
    val reach = size.height * SHADOW_REACH_FRAC
    for (band in 1..SHADOW_BANDS) {
        val fraction = band.toFloat() / SHADOW_BANDS
        val eased = fraction.pow(SHADOW_EASE)
        val path = cache.shadowBands.getOrNull(index)?.get(band - 1)?.also { it.rewind() } ?: Path()
        buildCrestRibbon(path, crest, offsetPx = -reach * eased)
        drawPath(path, color = color)
    }
}

// Frame-invariant paint construction (shared by the cache and the uncached fallback path).

/**
 * Builds the body-fill gradient for [layer], three stops top to bottom:
 * 1. the **crest light** at the top of the breathing envelope — the resolved fill gently lifted
 *    toward [WaveColors.highlight] by [FILL_CREST_HIGHLIGHT_MIX] (the in-fill replacement for a
 *    separate rim shape);
 * 2. the resolved fill color at the bottom of the envelope;
 * 3. the fill darkened by [FILL_DEPTH_DARKEN] at the canvas bottom.
 *
 * The layer's resolved alpha is baked into every stop. Depends only on the config and [height],
 * never on `phase`/`time`, so it is cacheable.
 */
private fun buildFillBrush(config: WaveConfig, layer: WaveLayerSpec, index: Int, count: Int, height: Float): Brush {
    val fill = resolveFill(config.colors, layer, index, count)
    val alpha = layer.alpha ?: autoAlpha(index, count)
    val crestLight = lerp(fill, config.colors.highlight, FILL_CREST_HIGHLIGHT_MIX)
    val top = crestLight.copy(alpha = fill.alpha * alpha)
    val mid = fill.copy(alpha = fill.alpha * alpha)
    val darkened = darken(fill, FILL_DEPTH_DARKEN)
    val bottom = darkened.copy(alpha = darkened.alpha * alpha)

    val baseY = height * layer.baseFrac
    val ampMax = height * layer.amplitude * (1f + layer.breathDepth)
    // Keep a strictly positive span even for a degenerate flat layer at the canvas bottom.
    val startY = (baseY - ampMax).coerceAtMost(height - 1f)
    // The crest light fades out across the breathing envelope, then the body darkens to the bottom.
    val midFraction = ((baseY + ampMax - startY) / (height - startY)).coerceIn(0.01f, 0.99f)
    return Brush.verticalGradient(
        0f to top,
        midFraction to mid,
        1f to bottom,
        startY = startY,
        endY = height,
    )
}

// Color resolution.

/**
 * Resolves a layer's fill color: the explicit [WaveLayerSpec.tint] when set, otherwise the
 * palette-sampled color from [colors] at the layer's depth (never a hardcoded black).
 */
private fun resolveFill(colors: WaveColors, layer: WaveLayerSpec, index: Int, count: Int): Color =
    layer.tint ?: colors.fillColorFor(index, count)

/**
 * Resolves the cast-shadow color for a layer given the [shadow] mode and the layer's local [fill]
 * color, per `DESIGN.md` §4:
 * - [ShadowMode.Auto]: black if the fill is light, white if it is dark (luminance cutoff `0.5`);
 * - [ShadowMode.FromWave]: the fill darkened toward black;
 * - [ShadowMode.Custom]: the supplied color; its peak opacity is [ShadowMode.Custom.alpha],
 *   applied per band via [resolveShadowBandColor] (not baked here);
 * - [ShadowMode.None]: never reaches here (the caller short-circuits).
 *
 * The returned color is the base hue; [resolveShadowBandColor] bakes the per-band alpha on top.
 */
private fun resolveShadowColor(shadow: ShadowMode, fill: Color): Color = when (shadow) {
    is ShadowMode.Auto ->
        if (relativeLuminance(fill) > LUMINANCE_CUTOFF) Color.Black else Color.White
    is ShadowMode.FromWave -> darken(fill, FROM_WAVE_DARKEN)
    is ShadowMode.Custom -> shadow.color
    is ShadowMode.None -> Color.Transparent
}

/**
 * Peak opacity for the cast shadow: the caller-supplied [ShadowMode.Custom.alpha] for a custom
 * shadow, otherwise the engine default [SHADOW_ALPHA]. This is what makes `Custom.alpha` actually
 * drive the rendered shadow. The peak is distributed across the [SHADOW_BANDS] stacked bands by
 * [resolveShadowBandColor] (each band carries `peak / SHADOW_BANDS`), so the accumulated opacity
 * right against the edge approximates the requested peak.
 */
internal fun shadowPeakAlpha(shadow: ShadowMode): Float = when (shadow) {
    is ShadowMode.Custom -> shadow.alpha
    else -> SHADOW_ALPHA
}

/** The shadow base hue (see [resolveShadowColor]) with the per-band alpha (`peak / SHADOW_BANDS`) baked in. */
private fun resolveShadowBandColor(shadow: ShadowMode, fill: Color): Color {
    val base = resolveShadowColor(shadow, fill)
    return base.copy(alpha = base.alpha * (shadowPeakAlpha(shadow) / SHADOW_BANDS))
}

// Geometry plumbing (math lives in WaveGeometry; this only samples rows and builds Paths).

/**
 * Samples the crest of [layer] into [into] (one y per sample column), the single trigonometric
 * pass per layer per frame; every path (body fill, shadow bands, rim bands) is then built from
 * this row. Delegates to [WaveGeometry.waveYAt] (the single source of truth for the math).
 */
private fun DrawScope.sampleCrest(
    into: FloatArray,
    layer: WaveLayerSpec,
    phase: Double,
    time: Double,
    swayScale: Float,
) {
    val w = size.width
    val h = size.height
    for (i in 0..WAVE_SAMPLES) {
        into[i] = WaveGeometry.waveYAt(w * i / WAVE_SAMPLES, layer, phase, time, w, h, swayScale)
    }
}

/**
 * (Re)builds the "region below the crest" polyline into [target] from the sampled [crest] row:
 * `WAVE_SAMPLES + 1` points along the crest, then closed down to `(width, height)` → `(0, height)`.
 * [target] is assumed to be freshly [rewind]-ed (or empty). This is the layer's body-fill region.
 */
private fun DrawScope.buildRegionBelow(target: Path, crest: FloatArray) {
    val w = size.width
    val h = size.height
    target.moveTo(0f, crest[0])
    for (i in 1..WAVE_SAMPLES) {
        target.lineTo(w * i / WAVE_SAMPLES, crest[i])
    }
    target.lineTo(w, h)
    target.lineTo(0f, h)
    target.close()
}

/**
 * (Re)builds a constant-thickness crest ribbon into [target] from the sampled [crest] row: the
 * crest polyline forward, then the same polyline offset vertically by [offsetPx] backward, closed.
 * A negative [offsetPx] hugs the crest from above (cast-shadow bands). Because both edges are the
 * same curve shifted, the ribbon follows the edge and never self-intersects. [target] is assumed
 * freshly [rewind]-ed (or empty).
 */
private fun DrawScope.buildCrestRibbon(target: Path, crest: FloatArray, offsetPx: Float) {
    val w = size.width
    target.moveTo(0f, crest[0])
    for (i in 1..WAVE_SAMPLES) {
        target.lineTo(w * i / WAVE_SAMPLES, crest[i])
    }
    for (i in WAVE_SAMPLES downTo 0) {
        target.lineTo(w * i / WAVE_SAMPLES, crest[i] + offsetPx)
    }
    target.close()
}

