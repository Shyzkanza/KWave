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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure JVM geometry tests for [WaveGeometry], no UI, no Compose runtime. These exercise the exact
 * waveform math the renderer delegates to (`DESIGN.md` §2) so a regression in the equations is
 * caught without rendering a single pixel.
 */
class WaveGeometryTest {

    private val width = 1000f
    private val height = 800f
    private val tolerance = 1e-3f

    // ── Determinism / purity ─────────────────────────────────────────────────────────────────────

    @Test
    fun waveYAt_is_deterministic_for_identical_inputs() {
        val layer = WaveLayerSpec()
        val a = WaveGeometry.waveYAt(x = 250f, layer = layer, phase = 1.23f, time = 4.56f, width = width, height = height)
        val b = WaveGeometry.waveYAt(x = 250f, layer = layer, phase = 1.23f, time = 4.56f, width = width, height = height)
        assertEquals(a, b, "Same inputs must yield identical output (pure function)")
    }

    // ── Geometry purity at phase = 0 / time = 0 ──────────────────────────────────────────────────

    @Test
    fun waveYAt_at_phase0_time0_matches_the_closed_form_equation() {
        // A pure-sine layer (harmonic = 0) with no phase offset evaluated at phase=0/time=0.
        val layer = WaveLayerSpec(
            baseFrac = 0.5f,
            amplitude = 0.03f,
            phaseOffset = 0f,
            breathDepth = 0f, // remove breathing so layerAmp is exactly height*amplitude at time=0
            crests = 0.8f,
            harmonic = 0f,
        )
        val x = 250f
        val t = x / width
        val expected = height * layer.baseFrac +
            (height * layer.amplitude) * sin(layer.crests * (2f * PI.toFloat()) * t)
        val actual = WaveGeometry.waveYAt(x, layer, phase = 0f, time = 0f, width = width, height = height)
        assertEquals(expected, actual, tolerance, "waveYAt must match the documented closed form at phase=0/time=0")
    }

    @Test
    fun waveYAt_at_x0_phase0_time0_pureSine_equals_baseline() {
        // At x=0, t=0 → sin(0) = 0, so a pure-sine layer with no phaseOffset sits exactly on baseFrac.
        val layer = WaveLayerSpec(baseFrac = 0.5f, amplitude = 0.04f, phaseOffset = 0f, harmonic = 0f, breathDepth = 0f)
        val y = WaveGeometry.waveYAt(x = 0f, layer = layer, phase = 0f, time = 0f, width = width, height = height)
        assertEquals(height * layer.baseFrac, y, tolerance, "x=0 pure-sine with no offset must equal the baseline")
    }

    @Test
    fun waveYAt_harmonic_zero_is_pure_sine_no_harmonic_contribution() {
        // Two layers identical except harmonic weight; at a point where the harmonic term is non-zero,
        // a harmonic=0 layer must equal the pure sine and differ from a harmonic>0 layer.
        val base = WaveLayerSpec(baseFrac = 0.5f, amplitude = 0.05f, crests = 1f, breathDepth = 0f)
        val pure = WaveLayerSpec(baseFrac = 0.5f, amplitude = 0.05f, crests = 1f, harmonic = 0f, breathDepth = 0f)
        val withHarmonic = WaveLayerSpec(baseFrac = 0.5f, amplitude = 0.05f, crests = 1f, harmonic = 0.5f, breathDepth = 0f)

        val x = 137f
        val pureY = WaveGeometry.waveYAt(x, pure, phase = 0.7f, time = 0f, width = width, height = height)
        val harmY = WaveGeometry.waveYAt(x, withHarmonic, phase = 0.7f, time = 0f, width = width, height = height)

        // Pure sine must equal baseline + amp*sin(y1) exactly (no second term).
        val t = x / width
        val ph = 0.7f * base.speed
        val y1 = base.crests * (2f * PI.toFloat()) * t + ph
        val expectedPure = height * 0.5f + (height * 0.05f) * sin(y1)
        assertEquals(expectedPure, pureY, tolerance, "harmonic=0 must be a pure sine with no second-harmonic term")
        assertTrue(harmY != pureY, "harmonic>0 must differ from the pure sine at a point where sin(y2) != 0")
    }

    // ── Breathing returns to nominal when the sin term is 0 ──────────────────────────────────────

    @Test
    fun layerAmp_returns_to_nominal_when_breathing_sin_is_zero() {
        // sin(time*breathSpeed + breathOffset) == 0 when the argument is 0 → time=0, breathOffset=0.
        val layer = WaveLayerSpec(amplitude = 0.03f, breathDepth = 0.5f, breathSpeed = 0.25f, breathOffset = 0f)
        val amp = WaveGeometry.layerAmp(layer, time = 0f, height = height)
        assertEquals(height * layer.amplitude, amp, tolerance, "Breathing at the zero-crossing must equal the nominal amplitude")
    }

    @Test
    fun layerAmp_returns_to_nominal_at_breathing_period() {
        // Argument == 2π also produces sin == 0; nominal amplitude must be recovered there too.
        val layer = WaveLayerSpec(amplitude = 0.03f, breathDepth = 0.8f, breathSpeed = 1f, breathOffset = 0f)
        val timeOnePeriod = (2f * PI.toFloat()) / layer.breathSpeed
        val amp = WaveGeometry.layerAmp(layer, time = timeOnePeriod, height = height)
        assertEquals(height * layer.amplitude, amp, tolerance, "After one full breathing period the amplitude returns to nominal")
    }

    @Test
    fun layerAmp_peaks_above_and_below_nominal_within_breath_envelope() {
        val layer = WaveLayerSpec(amplitude = 0.02f, breathDepth = 0.5f, breathSpeed = 1f, breathOffset = 0f)
        val nominal = height * layer.amplitude
        // Peak (sin = +1) at argument π/2 → time = (π/2)/breathSpeed.
        val tHigh = (PI.toFloat() / 2f) / layer.breathSpeed
        // Trough (sin = -1) at argument 3π/2.
        val tLow = (3f * PI.toFloat() / 2f) / layer.breathSpeed
        val high = WaveGeometry.layerAmp(layer, tHigh, height)
        val low = WaveGeometry.layerAmp(layer, tLow, height)
        assertEquals(nominal * (1f + layer.breathDepth), high, tolerance, "Breathing peak = nominal * (1 + breathDepth)")
        assertEquals(nominal * (1f - layer.breathDepth), low, tolerance, "Breathing trough = nominal * (1 - breathDepth)")
    }

    // ── Degenerate width safety ──────────────────────────────────────────────────────────────────

    @Test
    fun waveYAt_returns_baseline_for_nonpositive_width() {
        val layer = WaveLayerSpec(baseFrac = 0.6f, amplitude = 0.1f)
        val atZero = WaveGeometry.waveYAt(x = 500f, layer = layer, phase = 2f, time = 1f, width = 0f, height = height)
        val atNeg = WaveGeometry.waveYAt(x = 500f, layer = layer, phase = 2f, time = 1f, width = -10f, height = height)
        assertEquals(height * layer.baseFrac, atZero, "Zero width must return the nominal baseline, not NaN")
        assertEquals(height * layer.baseFrac, atNeg, "Negative width must return the nominal baseline, not NaN")
    }

    @Test
    fun waveYAt_never_returns_nan_for_finite_inputs() {
        val layer = WaveLayerSpec(amplitude = 0.05f, harmonic = 0.5f, breathDepth = 0.3f)
        for (i in 0..96) {
            val x = width * i / 96f
            val y = WaveGeometry.waveYAt(x, layer, phase = 3.14f, time = 2.5f, width = width, height = height)
            assertTrue(y.isFinite(), "waveYAt must stay finite across the full sample range (x=$x)")
        }
    }

    // ── phaseOffset / speed wiring ───────────────────────────────────────────────────────────────

    @Test
    fun waveYAt_applies_speed_to_phase() {
        // A layer with speed=0 must ignore the caller phase entirely (only phaseOffset matters).
        val frozen = WaveLayerSpec(speed = 0f, phaseOffset = 0f, harmonic = 0f, breathDepth = 0f, amplitude = 0.04f)
        val a = WaveGeometry.waveYAt(x = 333f, layer = frozen, phase = 0f, time = 0f, width = width, height = height)
        val b = WaveGeometry.waveYAt(x = 333f, layer = frozen, phase = 99f, time = 0f, width = width, height = height)
        assertEquals(a, b, tolerance, "speed=0 must make the layer independent of the caller phase")
    }
}
