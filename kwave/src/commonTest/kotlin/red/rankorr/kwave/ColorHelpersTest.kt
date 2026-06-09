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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the internal color-resolution helpers ([sampleStops], [relativeLuminance], [autoAlpha],
 * [lighten], [darken], [lerp01]) and the [ShadowMode.Auto] luminance-pick contract.
 *
 * The renderer's `resolveShadowColor` / `resolveHighlightColor` are private, so the documented
 * "light wave → dark shadow, dark wave → light shadow" rule (`DESIGN.md` §4) is verified through the
 * public [relativeLuminance] + the documented `0.5` cutoff: a black-vs-white pick by luminance.
 */
class ColorHelpersTest {

    private val tol = 1e-3f

    // ── relativeLuminance + ShadowMode.Auto luminance pick ───────────────────────────────────────

    @Test
    fun relativeLuminance_uses_standard_coefficients() {
        assertEquals(0f, relativeLuminance(Color.Black), tol, "Black luminance is 0")
        assertEquals(1f, relativeLuminance(Color.White), tol, "White luminance is 1")
        assertEquals(0.2126f, relativeLuminance(Color.Red), tol, "Red coefficient is 0.2126")
        assertEquals(0.7152f, relativeLuminance(Color.Green), tol, "Green coefficient is 0.7152")
        assertEquals(0.0722f, relativeLuminance(Color.Blue), tol, "Blue coefficient is 0.0722")
    }

    @Test
    fun shadow_auto_picks_dark_shadow_for_light_wave() {
        // Light wave color (luminance > 0.5) → Auto picks a black shadow.
        val lightWave = Color.White
        val cutoff = 0.5f
        val shadowIsBlack = relativeLuminance(lightWave) > cutoff
        assertTrue(shadowIsBlack, "A light wave (luminance > 0.5) must select a dark (black) shadow")
    }

    @Test
    fun shadow_auto_picks_light_shadow_for_dark_wave() {
        // Dark wave color (luminance <= 0.5) → Auto picks a white shadow.
        val darkWave = Color(0xFF0D47A1) // deep blue, low luminance
        val cutoff = 0.5f
        val shadowIsBlack = relativeLuminance(darkWave) > cutoff
        assertTrue(!shadowIsBlack, "A dark wave (luminance <= 0.5) must select a light (white) shadow")
    }

    // ── sampleStops ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun sampleStops_returns_endpoints_exactly() {
        val stops = listOf(Color.Red, Color.Green, Color.Blue)
        assertEquals(Color.Red, sampleStops(stops, 0f), "t=0 returns the first stop")
        assertEquals(Color.Blue, sampleStops(stops, 1f), "t=1 returns the last stop")
    }

    @Test
    fun sampleStops_clamps_out_of_range_t() {
        val stops = listOf(Color.Red, Color.Blue)
        assertEquals(Color.Red, sampleStops(stops, -5f), "t < 0 clamps to the first stop")
        assertEquals(Color.Blue, sampleStops(stops, 5f), "t > 1 clamps to the last stop")
    }

    @Test
    fun sampleStops_interpolates_midpoint_between_two_stops() {
        val mid = sampleStops(listOf(Color.Black, Color.White), 0.5f)
        // Compose's `lerp` blends in a perceptual color space, so the midpoint of black→white is not
        // exactly 0.5 per sRGB channel. The meaningful, implementation-agnostic guarantee is that the
        // midpoint sits strictly between the two endpoints (a real interpolation, not an endpoint).
        assertTrue(mid.red in 0.05f..0.95f, "Midpoint red must be strictly between black and white")
        assertTrue(mid.green in 0.05f..0.95f, "Midpoint green must be strictly between black and white")
        assertTrue(mid.blue in 0.05f..0.95f, "Midpoint blue must be strictly between black and white")
        val midLum = relativeLuminance(mid)
        assertTrue(
            midLum > relativeLuminance(Color.Black) && midLum < relativeLuminance(Color.White),
            "Midpoint luminance must lie between the black and white endpoints",
        )
    }

    @Test
    fun sampleStops_is_monotonic_in_luminance_for_a_black_to_white_ramp() {
        val ramp = listOf(Color.Black, Color.White)
        val samples = (0..10).map { relativeLuminance(sampleStops(ramp, it / 10f)) }
        for (i in 1 until samples.size) {
            assertTrue(samples[i] >= samples[i - 1], "Luminance must not decrease along a black→white ramp (i=$i)")
        }
    }

    @Test
    fun sampleStops_single_stop_is_returned_directly() {
        assertEquals(Color.Magenta, sampleStops(listOf(Color.Magenta), 0.3f), "A single stop is returned as-is")
    }

    @Test
    fun sampleStops_empty_returns_neutral_fallback_without_throwing() {
        val c = sampleStops(emptyList(), 0.5f)
        assertTrue(c.alpha > 0f, "Empty stops must return an opaque neutral fallback, not crash")
    }

    // ── autoAlpha (depth → opacity) ──────────────────────────────────────────────────────────────

    @Test
    fun autoAlpha_back_layer_is_floored_and_front_layer_is_opaque() {
        val count = 5
        val back = autoAlpha(0, count)
        val front = autoAlpha(count - 1, count)
        assertEquals(BACK_ALPHA_FLOOR, back, tol, "Back-most layer alpha is floored at BACK_ALPHA_FLOOR")
        assertEquals(1f, front, tol, "Front-most layer is fully opaque")
    }

    @Test
    fun autoAlpha_is_monotonic_increasing_front_to_back() {
        val count = 6
        val alphas = (0 until count).map { autoAlpha(it, count) }
        for (i in 1 until alphas.size) {
            assertTrue(alphas[i] >= alphas[i - 1], "Alpha must increase from back to front (index $i)")
        }
    }

    @Test
    fun autoAlpha_single_layer_is_opaque() {
        assertEquals(1f, autoAlpha(0, 1), tol, "A single layer is fully opaque")
    }

    @Test
    fun autoAlpha_coerces_degenerate_inputs_without_throwing() {
        assertEquals(1f, autoAlpha(index = 9, count = 0), tol, "count<=0 treated as 1 → opaque, no crash")
        val v = autoAlpha(index = -4, count = 5)
        assertTrue(v in BACK_ALPHA_FLOOR..1f, "Negative index clamps into the valid alpha band")
    }

    // ── lighten / darken / lerp01 ────────────────────────────────────────────────────────────────

    @Test
    fun lighten_moves_toward_white_and_preserves_alpha() {
        val base = Color(red = 0.2f, green = 0.2f, blue = 0.2f, alpha = 0.5f)
        val lit = lighten(base, 0.5f)
        assertTrue(lit.red > base.red, "lighten must raise channel values")
        assertEquals(base.alpha, lit.alpha, tol, "lighten must preserve the source alpha")
    }

    @Test
    fun darken_moves_toward_black_and_preserves_alpha() {
        val base = Color(red = 0.8f, green = 0.8f, blue = 0.8f, alpha = 0.5f)
        val dark = darken(base, 0.5f)
        assertTrue(dark.red < base.red, "darken must lower channel values")
        assertEquals(base.alpha, dark.alpha, tol, "darken must preserve the source alpha")
    }

    @Test
    fun lerp01_endpoints_and_midpoint() {
        assertEquals(0f, lerp01(0f, 10f, 0f), tol)
        assertEquals(10f, lerp01(0f, 10f, 1f), tol)
        assertEquals(5f, lerp01(0f, 10f, 0.5f), tol)
    }

    @Test
    fun lerp01_clamps_fraction_out_of_range() {
        assertEquals(0f, lerp01(0f, 10f, -2f), tol, "fraction < 0 clamps to start")
        assertEquals(10f, lerp01(0f, 10f, 2f), tol, "fraction > 1 clamps to stop")
    }
}
