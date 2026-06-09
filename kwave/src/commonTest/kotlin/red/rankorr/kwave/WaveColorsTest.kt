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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for the [WaveColors] factories and their resolved stops / per-layer fills. The key
 * correction over the original reference renderer is verified here: the per-layer fill is
 * **palette-derived and never a hardcoded black**.
 */
class WaveColorsTest {

    private val top = Color(0xFF455A64)
    private val bottom = Color(0xFF263238)

    // ── gradient ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun gradient_background_stops_are_top_then_bottom() {
        val colors = WaveColors.gradient(top, bottom)
        assertEquals(listOf(top, bottom), colors.backgroundStops, "gradient stops must be exactly [top, bottom]")
    }

    @Test
    fun gradient_back_layer_leans_top_and_front_layer_leans_bottom() {
        val colors = WaveColors.gradient(top, bottom)
        val back = colors.fillColorFor(layerIndex = 0, layerCount = 3)
        val front = colors.fillColorFor(layerIndex = 2, layerCount = 3)
        assertEquals(top, back, "Back-most layer (depth 0) must sample the top color")
        assertEquals(bottom, front, "Front-most layer (depth 1) must sample the bottom color")
    }

    @Test
    fun gradient_highlight_is_a_lightened_variant_of_top_not_black() {
        val colors = WaveColors.gradient(top, bottom)
        assertNotEquals(Color.Black, colors.highlight, "Highlight must never be black")
        // Lightened toward white → strictly brighter than the top color on every channel.
        assertTrue(colors.highlight.red >= top.red, "Highlight red must be >= top red (lightened)")
        assertTrue(colors.highlight.green >= top.green, "Highlight green must be >= top green (lightened)")
        assertTrue(colors.highlight.blue >= top.blue, "Highlight blue must be >= top blue (lightened)")
    }

    @Test
    fun gradient_with_equal_top_and_bottom_ramps_fill_like_solid() {
        val c = Color(0xFF2E7D32)
        val g = WaveColors.gradient(c, c)
        val s = WaveColors.solid(c)
        assertEquals(s.fillColorFor(0, 4), g.fillColorFor(0, 4), "gradient(c, c) must behave like solid(c) so the waves stay visible")
        assertNotEquals(g.fillColorFor(0, 4), g.fillColorFor(3, 4), "an equal-color gradient must still ramp the fill by depth")
    }

    // ── palette (rainbow) ────────────────────────────────────────────────────────────────────────

    @Test
    fun palette_background_is_a_muted_two_stop_while_fills_keep_every_hue() {
        val rainbow = listOf(Color.Red, Color.Green, Color.Blue, Color.Yellow)
        val colors = WaveColors.palette(rainbow)
        // The background is calmed to a muted 2-stop derived from the palette extremes (not the full
        // saturated span) so the colorful waves stay the subject.
        assertEquals(2, colors.backgroundStops.size, "palette background must be a muted 2-stop, not the full palette")
        assertNotEquals(rainbow, colors.backgroundStops, "palette background must NOT repaint the full saturated palette")
        // The wave fills still carry every supplied hue across depth.
        val fills = (0 until rainbow.size).map { colors.fillColorFor(it, rainbow.size) }
        assertEquals(Color.Red, fills.first(), "back fill samples the first palette color")
        assertEquals(Color.Yellow, fills.last(), "front fill samples the last palette color")
    }

    @Test
    fun palette_tints_endpoints_to_the_palette_extremes() {
        val rainbow = listOf(Color.Red, Color.Green, Color.Blue)
        val colors = WaveColors.palette(rainbow)
        // depth 0 → first stop, depth 1 → last stop (exact endpoints of sampleStops).
        assertEquals(Color.Red, colors.fillColorFor(0, 3), "Back layer must sample the first palette color")
        assertEquals(Color.Blue, colors.fillColorFor(2, 3), "Front layer must sample the last palette color")
    }

    @Test
    fun palette_gives_distinct_hues_to_distinct_layers() {
        val rainbow = listOf(Color.Red, Color.Green, Color.Blue, Color.Magenta)
        val colors = WaveColors.palette(rainbow)
        val fills = (0 until 4).map { colors.fillColorFor(it, 4) }
        assertEquals(fills.toSet().size, fills.size, "Each layer must get a distinct hue from a 4-stop palette of 4 layers")
        assertTrue(fills.none { it == Color.Black }, "No palette-derived fill may be hardcoded black")
    }

    @Test
    fun palette_empty_list_falls_back_to_neutral_and_does_not_throw() {
        val colors = WaveColors.palette(emptyList())
        // Behaves like solid on a neutral color: both background stops identical, no crash.
        assertEquals(2, colors.backgroundStops.size, "Empty palette falls back to a solid (two identical stops)")
        assertEquals(colors.backgroundStops[0], colors.backgroundStops[1], "Neutral fallback stops must be identical")
        assertNotEquals(Color.Black, colors.fillColorFor(0, 3), "Neutral fallback fill must not be black")
    }

    @Test
    fun palette_single_element_behaves_like_solid() {
        val single = WaveColors.palette(listOf(Color(0xFF112233)))
        val solid = WaveColors.solid(Color(0xFF112233))
        assertEquals(solid.backgroundStops, single.backgroundStops, "Single-color palette must match solid stops")
        assertEquals(
            solid.fillColorFor(1, 3),
            single.fillColorFor(1, 3),
            "Single-color palette fill must match solid fill",
        )
    }

    // ── solid ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun solid_background_stops_are_the_same_color_twice() {
        val c = Color(0xFF2E7D32)
        val colors = WaveColors.solid(c)
        assertEquals(listOf(c, c), colors.backgroundStops, "solid background must be the color duplicated")
    }

    @Test
    fun solid_fill_ramps_by_depth_so_same_color_waves_stay_visible() {
        val c = Color(0xFF1565C0)
        val colors = WaveColors.solid(c)
        val back = colors.fillColorFor(0, 4)
        val front = colors.fillColorFor(3, 4)
        assertNotEquals(back, front, "solid fill must ramp by depth (back != front) so waves are visible over the flat background")
        assertNotEquals(c, back, "back fill must differ from the flat background color to be visible")
        assertNotEquals(Color.Black, back, "solid fill must not be black unless the caller supplies black")
    }

    // ── fillColorFor defensive coercion ──────────────────────────────────────────────────────────

    @Test
    fun fillColorFor_coerces_degenerate_indices_and_counts_without_throwing() {
        val colors = WaveColors.gradient(top, bottom)
        // Non-positive count treated as 1; index clamped into range. Must not throw.
        val zeroCount = colors.fillColorFor(layerIndex = 5, layerCount = 0)
        val negIndex = colors.fillColorFor(layerIndex = -3, layerCount = 4)
        val overIndex = colors.fillColorFor(layerIndex = 99, layerCount = 4)
        assertTrue(zeroCount.red in 0f..1f, "Degenerate count must still return a valid color")
        // index -3 clamps to 0 → back-most → top; index 99 clamps to 3 → front-most → bottom.
        assertEquals(top, negIndex, "Negative index must clamp to the back-most layer")
        assertEquals(bottom, overIndex, "Out-of-range index must clamp to the front-most layer")
    }

    @Test
    fun fillColorFor_single_layer_uses_depth_zero() {
        val colors = WaveColors.gradient(top, bottom)
        assertEquals(top, colors.fillColorFor(0, 1), "A single layer (count=1) samples depth 0 → top color")
    }
}
