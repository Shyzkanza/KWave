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

import androidx.compose.ui.graphics.Color
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [WaveConfig] and its companion ([WaveConfig.Default], [WaveConfig.generate]): default
 * preset shape, `generate()` coercion/phase distribution, `gradientEnd` coercion, and the
 * N=0 / N=1 `dropLast(1)` safety the renderer relies on.
 */
class WaveConfigTest {

    private val colors = WaveColors.gradient(Color(0xFF455A64), Color(0xFF263238))

    // ── Default preset ───────────────────────────────────────────────────────────────────────────

    @Test
    fun default_preset_has_two_layers_and_auto_shadow() {
        val d = WaveConfig.Default
        assertEquals(2, d.layers.size, "Ported reference default uses two layers")
        assertEquals(ShadowMode.Auto, d.shadow, "Default shadow mode is Auto")
        assertEquals(0.78f, d.gradientEnd, "Default gradientEnd is 0.78")
    }

    @Test
    fun default_preset_fills_are_palette_derived_not_black() {
        val d = WaveConfig.Default
        for (i in d.layers.indices) {
            val fill = d.colors.fillColorFor(i, d.layers.size)
            assertTrue(fill != Color.Black, "Default per-layer fill must be palette-derived, never hardcoded black")
        }
    }

    // ── gradientEnd coercion ─────────────────────────────────────────────────────────────────────

    @Test
    fun gradientEnd_is_coerced_into_valid_range_with_a_positive_floor() {
        val over = WaveConfig(persistentListOf(WaveLayerSpec()), colors, gradientEnd = 3f)
        val under = WaveConfig(persistentListOf(WaveLayerSpec()), colors, gradientEnd = -1f)
        val zero = WaveConfig(persistentListOf(WaveLayerSpec()), colors, gradientEnd = 0f)
        assertEquals(1f, over.gradientEnd, "gradientEnd > 1 clamps to 1")
        assertEquals(GRADIENT_END_MIN, under.gradientEnd, "gradientEnd below the floor clamps up to GRADIENT_END_MIN")
        assertEquals(GRADIENT_END_MIN, zero.gradientEnd, "gradientEnd of 0 clamps up — a zero-span gradient is degenerate")
        assertTrue(GRADIENT_END_MIN > 0f, "The gradient-end floor must be strictly positive")
    }

    // ── generate(): waveCount coercion ───────────────────────────────────────────────────────────

    @Test
    fun generate_coerces_waveCount_to_at_least_one() {
        assertEquals(1, WaveConfig.generate(waveCount = 0, colors = colors).layers.size, "waveCount=0 coerces to 1")
        assertEquals(1, WaveConfig.generate(waveCount = -5, colors = colors).layers.size, "waveCount<0 coerces to 1")
    }

    @Test
    fun generate_produces_the_requested_layer_count() {
        for (n in 1..8) {
            assertEquals(n, WaveConfig.generate(waveCount = n, colors = colors).layers.size, "generate must honor waveCount=$n")
        }
    }

    // ── generate(): phase distribution ───────────────────────────────────────────────────────────

    @Test
    fun generate_distributes_phaseOffset_across_layers() {
        // variation=0 isolates the base distribution (no random jitter on phaseOffset).
        val cfg = WaveConfig.generate(waveCount = 4, variation = 0f, colors = colors)
        val offsets = cfg.layers.map { it.phaseOffset }
        // Documented: phaseOffset(i) = (i / waveCount) * 2π → strictly increasing, first = 0.
        assertEquals(0f, offsets.first(), "First layer phase offset must be 0")
        for (i in 1 until offsets.size) {
            assertTrue(offsets[i] > offsets[i - 1], "Phase offsets must strictly increase across layers (i=$i)")
        }
    }

    @Test
    fun generate_applies_crests_amplitude_and_harmonic_to_every_layer() {
        // variation=0 isolates the base values (no jitter), so every layer carries the request exactly.
        val cfg = WaveConfig.generate(waveCount = 5, crests = 1.3f, harmonic = 0.7f, amplitude = 0.05f, variation = 0f, colors = colors)
        cfg.layers.forEach { layer ->
            assertEquals(1.3f, layer.crests, "crests must be applied to every generated layer")
            assertEquals(0.7f, layer.harmonic, "harmonic must be applied to every generated layer")
            assertEquals(0.05f, layer.amplitude, "amplitude must be applied to every generated layer")
        }
    }

    @Test
    fun generate_leaves_alpha_and_tint_null_for_auto_resolution() {
        val cfg = WaveConfig.generate(waveCount = 4, colors = colors)
        cfg.layers.forEach { layer ->
            assertTrue(layer.alpha == null, "generate leaves alpha null so the system auto-assigns by depth")
            assertTrue(layer.tint == null, "generate leaves tint null so the fill is sampled from the palette")
        }
    }

    @Test
    fun generate_harmonic_zero_yields_pure_sine_layers() {
        // variation=0 isolates the base value; harmonic=0 must reach every layer untouched.
        val cfg = WaveConfig.generate(waveCount = 4, harmonic = 0f, variation = 0f, colors = colors)
        cfg.layers.forEach { layer ->
            assertEquals(0f, layer.harmonic, "harmonic=0 must produce pure-sine layers (no second harmonic)")
        }
    }

    @Test
    fun generate_breathing_varies_per_layer_so_layers_dont_pulse_in_unison() {
        val cfg = WaveConfig.generate(waveCount = 4, colors = colors)
        val breathOffsets = cfg.layers.map { it.breathOffset }
        assertEquals(breathOffsets.toSet().size, breathOffsets.size, "Per-layer breath offsets must differ so layers don't breathe together")
    }

    @Test
    fun generate_stacks_baseFrac_back_to_front() {
        // variation=0 isolates the base monotonic stacking (no jitter on baseFrac).
        val cfg = WaveConfig.generate(waveCount = 5, variation = 0f, colors = colors)
        val baseFracs = cfg.layers.map { it.baseFrac }
        for (i in 1 until baseFracs.size) {
            assertTrue(baseFracs[i] >= baseFracs[i - 1], "baseFrac must stack downward back-to-front (i=$i)")
        }
        assertTrue(baseFracs.all { it in 0f..1f }, "All baseFrac values must remain in [0,1]")
    }

    // ── N=0 / N=1 dropLast(1) safety ─────────────────────────────────────────────────────────────

    // NOTE: The renderer's WavePathCache allocates Compose `Path` objects, which require the Skiko
    // native library and therefore cannot be instantiated in the plain-JVM commonTest runtime. The
    // dropLast(1) IOOB-safety contract is fully expressible at the layer-list level (below); the
    // path-cache sizing is exercised in the Robolectric (NATIVE graphics) screenshot suite instead.

    @Test
    fun zero_layers_dropLast_is_empty_and_does_not_throw() {
        val empty = WaveConfig(persistentListOf(), colors)
        assertEquals(0, empty.layers.size, "N=0 config is allowed")
        // dropLast(1) on an empty list is empty (the depth-FX loop the renderer runs is a no-op).
        assertEquals(0, empty.layers.dropLast(1).size, "dropLast(1) at N=0 must be empty (IOOB-safe)")
    }

    @Test
    fun single_layer_dropLast_is_empty_so_only_flat_fill_is_drawn() {
        val one = WaveConfig(persistentListOf(WaveLayerSpec()), colors)
        // At N=1 the single layer is front-most → the depth-FX loop iterates zero times.
        assertEquals(0, one.layers.dropLast(1).size, "dropLast(1) at N=1 must be empty (no depth FX, no IOOB)")
    }

    @Test
    fun many_layers_dropLast_leaves_all_but_the_front() {
        val cfg = WaveConfig.generate(waveCount = 5, colors = colors)
        assertEquals(4, cfg.layers.dropLast(1).size, "At N=5 the depth-FX loop covers 4 layers (all but the front)")
    }

    // ── generate(): variation & seed ───────────────────────────────────────────────────────────────

    @Test
    fun generate_variation_zero_keeps_amplitude_uniform_across_layers() {
        val cfg = WaveConfig.generate(waveCount = 5, amplitude = 0.05f, variation = 0f, colors = colors)
        cfg.layers.forEach { layer ->
            assertEquals(0.05f, layer.amplitude, "variation=0 must leave amplitude un-jittered")
        }
    }

    @Test
    fun generate_positive_variation_varies_amplitude_across_layers() {
        val cfg = WaveConfig.generate(waveCount = 6, amplitude = 0.05f, variation = 0.8f, colors = colors)
        val distinct = cfg.layers.map { it.amplitude }.toSet().size
        assertTrue(distinct > 1, "variation>0 must produce per-layer amplitude differences")
    }

    @Test
    fun generate_positive_variation_desyncs_speed_across_layers() {
        val cfg = WaveConfig.generate(waveCount = 6, variation = 0.8f, colors = colors)
        val distinct = cfg.layers.map { it.speed }.toSet().size
        assertTrue(distinct > 1, "variation>0 must desync per-layer speed")
    }

    @Test
    fun generate_is_deterministic_for_a_given_seed() {
        val a = WaveConfig.generate(waveCount = 5, variation = 0.7f, seed = 42, colors = colors)
        val b = WaveConfig.generate(waveCount = 5, variation = 0.7f, seed = 42, colors = colors)
        assertEquals(a, b, "Same (seed, variation, …) must yield identical configs (screenshot determinism)")
    }

    @Test
    fun generate_different_seed_yields_a_different_layout() {
        val a = WaveConfig.generate(waveCount = 5, variation = 0.7f, seed = 1, colors = colors)
        val b = WaveConfig.generate(waveCount = 5, variation = 0.7f, seed = 2, colors = colors)
        assertTrue(a != b, "A different seed must roll a different layout")
    }

    @Test
    fun generate_spacing_controls_vertical_spread_for_overlap() {
        fun spread(c: WaveConfig) = c.layers.maxOf { it.baseFrac } - c.layers.minOf { it.baseFrac }
        val tight = WaveConfig.generate(waveCount = 5, spacing = 0.3f, variation = 0f, colors = colors)
        val wide = WaveConfig.generate(waveCount = 5, spacing = 1.8f, variation = 0f, colors = colors)
        assertTrue(spread(wide) > spread(tight), "A larger spacing must widen the vertical baseFrac spread (less overlap)")
    }

    // ── equality ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun configs_with_same_content_are_equal() {
        val a = WaveConfig(persistentListOf(WaveLayerSpec(baseFrac = 0.4f)), colors, ShadowMode.Auto, 0.7f)
        val b = WaveConfig(persistentListOf(WaveLayerSpec(baseFrac = 0.4f)), colors, ShadowMode.Auto, 0.7f)
        assertEquals(a, b, "Structurally equal configs must be equal")
        assertEquals(a.hashCode(), b.hashCode(), "Equal configs must share a hashCode")
    }
}
