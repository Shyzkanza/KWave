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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coercion-at-construction tests for [WaveLayerSpec]: every out-of-range input must be clamped into
 * its valid range so an extreme value can never reach the renderer (`DESIGN.md` §6).
 */
class WaveLayerSpecCoercionTest {

    @Test
    fun baseFrac_above_one_is_clamped_to_one() {
        assertEquals(1f, WaveLayerSpec(baseFrac = 2.5f).baseFrac, "baseFrac > 1 must clamp to 1")
    }

    @Test
    fun baseFrac_below_zero_is_clamped_to_zero() {
        assertEquals(0f, WaveLayerSpec(baseFrac = -0.7f).baseFrac, "baseFrac < 0 must clamp to 0")
    }

    @Test
    fun negative_amplitude_becomes_zero_flat_line_not_crash() {
        assertEquals(0f, WaveLayerSpec(amplitude = -0.5f).amplitude, "Negative amplitude must coerce to 0 (flat line)")
    }

    @Test
    fun breathDepth_above_one_is_clamped_to_one() {
        assertEquals(1f, WaveLayerSpec(breathDepth = 3f).breathDepth, "breathDepth > 1 must clamp to 1")
    }

    @Test
    fun breathDepth_below_zero_is_clamped_to_zero() {
        assertEquals(0f, WaveLayerSpec(breathDepth = -1f).breathDepth, "breathDepth < 0 must clamp to 0")
    }

    @Test
    fun negative_breathSpeed_is_clamped_to_zero() {
        assertEquals(0f, WaveLayerSpec(breathSpeed = -2f).breathSpeed, "Negative breathSpeed must coerce to 0")
    }

    @Test
    fun negative_crests_is_clamped_to_zero() {
        assertEquals(0f, WaveLayerSpec(crests = -1f).crests, "Negative crests must coerce to 0")
    }

    @Test
    fun negative_harmonic_is_clamped_to_zero() {
        assertEquals(0f, WaveLayerSpec(harmonic = -0.3f).harmonic, "Negative harmonic must coerce to 0")
    }

    @Test
    fun alpha_override_is_coerced_into_unit_range() {
        assertEquals(1f, WaveLayerSpec(alpha = 5f).alpha, "alpha > 1 must clamp to 1")
        assertEquals(0f, WaveLayerSpec(alpha = -5f).alpha, "alpha < 0 must clamp to 0")
    }

    @Test
    fun null_alpha_stays_null_for_auto_by_depth() {
        assertNull(WaveLayerSpec(alpha = null).alpha, "null alpha must stay null (auto-by-depth)")
    }

    @Test
    fun defaults_match_the_documented_spec() {
        val s = WaveLayerSpec()
        assertEquals(0.5f, s.baseFrac)
        assertEquals(0.03f, s.amplitude)
        assertEquals(1f, s.speed)
        assertEquals(0f, s.phaseOffset)
        assertEquals(0.2f, s.breathDepth)
        assertEquals(0.25f, s.breathSpeed)
        assertEquals(0f, s.breathOffset)
        assertEquals(0.8f, s.crests)
        assertEquals(0.25f, s.harmonic)
        assertNull(s.alpha)
        assertNull(s.tint)
    }

    @Test
    fun withTint_replaces_only_the_tint_and_preserves_the_rest() {
        val base = WaveLayerSpec(baseFrac = 0.3f, amplitude = 0.05f, crests = 1.2f, alpha = 0.6f)
        val tinted = base.withTint(Color.Red)
        assertEquals(Color.Red, tinted.tint)
        assertEquals(base.baseFrac, tinted.baseFrac)
        assertEquals(base.amplitude, tinted.amplitude)
        assertEquals(base.crests, tinted.crests)
        assertEquals(base.alpha, tinted.alpha)
    }

    @Test
    fun withAlpha_replaces_only_the_alpha_and_preserves_the_rest() {
        val base = WaveLayerSpec(baseFrac = 0.3f, tint = Color.Blue, crests = 1.2f)
        val a = base.withAlpha(0.25f)
        assertEquals(0.25f, a.alpha)
        assertEquals(base.tint, a.tint)
        assertEquals(base.baseFrac, a.baseFrac)
        assertEquals(base.crests, a.crests)
        // withAlpha(null) restores auto-by-depth.
        assertNull(base.withAlpha(null).alpha)
    }

    @Test
    fun equals_and_hashCode_are_consistent_for_equal_specs() {
        val a = WaveLayerSpec(baseFrac = 0.4f, amplitude = 0.02f, crests = 1.1f, tint = Color.Green)
        val b = WaveLayerSpec(baseFrac = 0.4f, amplitude = 0.02f, crests = 1.1f, tint = Color.Green)
        assertEquals(a, b, "Structurally equal specs must be equal")
        assertEquals(a.hashCode(), b.hashCode(), "Equal specs must share a hashCode")
        assertTrue(a !== b, "Distinct instances expected")
    }
}
