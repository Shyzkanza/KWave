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
import kotlin.test.assertSame

/**
 * Tests for the [ShadowMode] sealed hierarchy: the data-object singletons and [ShadowMode.Custom]
 * alpha coercion + value-equality.
 */
class ShadowModeTest {

    @Test
    fun data_objects_are_singletons() {
        assertSame(ShadowMode.Auto, ShadowMode.Auto, "Auto must be a singleton")
        assertSame(ShadowMode.FromWave, ShadowMode.FromWave, "FromWave must be a singleton")
        assertSame(ShadowMode.None, ShadowMode.None, "None must be a singleton")
    }

    @Test
    fun custom_alpha_above_one_is_clamped() {
        assertEquals(1f, ShadowMode.Custom(Color.Red, alpha = 4f).alpha, "Custom alpha > 1 must clamp to 1")
    }

    @Test
    fun custom_alpha_below_zero_is_clamped() {
        assertEquals(0f, ShadowMode.Custom(Color.Red, alpha = -2f).alpha, "Custom alpha < 0 must clamp to 0")
    }

    @Test
    fun custom_preserves_in_range_alpha() {
        assertEquals(0.42f, ShadowMode.Custom(Color.Blue, alpha = 0.42f).alpha, "In-range alpha must be preserved")
    }

    @Test
    fun custom_value_equality_and_hashCode() {
        val a = ShadowMode.Custom(Color(0xFF112233), alpha = 0.5f)
        val b = ShadowMode.Custom(Color(0xFF112233), alpha = 0.5f)
        assertEquals(a, b, "Two Custom with same color+alpha must be equal")
        assertEquals(a.hashCode(), b.hashCode(), "Equal Custom must share a hashCode")
    }

    @Test
    fun custom_differs_on_color_or_alpha() {
        val base = ShadowMode.Custom(Color.Red, 0.5f)
        assertNotEquals(base, ShadowMode.Custom(Color.Green, 0.5f), "Different color must not be equal")
        assertNotEquals(base, ShadowMode.Custom(Color.Red, 0.6f), "Different alpha must not be equal")
    }

    @Test
    fun custom_equals_is_clamp_aware() {
        // Both alphas clamp to 1f, so the two instances must compare equal.
        assertEquals(
            ShadowMode.Custom(Color.Red, 2f),
            ShadowMode.Custom(Color.Red, 9f),
            "Custom equality must compare the coerced alpha (both clamp to 1)",
        )
    }

    // ── shadowPeakAlpha (renderer band-peak resolution) ───────────────────────────────────────────

    @Test
    fun shadowPeakAlpha_uses_the_custom_alpha_as_the_band_peak() {
        // Regression: Custom.alpha was previously overwritten by the engine default and had no
        // rendered effect. shadowPeakAlpha must surface the caller's alpha so the band honors it.
        assertEquals(0.1f, shadowPeakAlpha(ShadowMode.Custom(Color.Black, 0.1f)), "Custom must use its own alpha as the band peak")
        assertEquals(0.9f, shadowPeakAlpha(ShadowMode.Custom(Color.Black, 0.9f)), "A higher Custom alpha must raise the band peak")
        assertNotEquals(
            shadowPeakAlpha(ShadowMode.Custom(Color.Black, 0.1f)),
            shadowPeakAlpha(ShadowMode.Custom(Color.Black, 0.9f)),
            "Different Custom alphas must produce different band peaks (the fix)",
        )
    }

    @Test
    fun shadowPeakAlpha_uses_a_shared_engine_default_for_non_custom_modes() {
        val auto = shadowPeakAlpha(ShadowMode.Auto)
        val fromWave = shadowPeakAlpha(ShadowMode.FromWave)
        val none = shadowPeakAlpha(ShadowMode.None)
        assertEquals(auto, fromWave, "Auto and FromWave share the engine default peak alpha")
        assertEquals(auto, none, "None resolves the same default (it is short-circuited before drawing)")
        assertNotEquals(auto, shadowPeakAlpha(ShadowMode.Custom(Color.Black, 0.05f)), "A low Custom alpha must differ from the default peak")
    }
}
