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

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi golden screenshot catalogue for KWave, rendered through the **stateless**
 * `KWave(config, phase, time)` overload at fixed `(phase, time)` so each PNG is deterministic and
 * stable across runs (no animation loop, no randomization).
 *
 * Capture is THEME-FREE (see [captureWave]) — the library reads no `MaterialTheme`, so no app theme
 * wraps the content.
 *
 * Covered cases:
 * - [WaveConfig.Default] (ported blue-grey preset)
 * - a simple top→bottom gradient ([WaveColors.gradient])
 * - a rainbow palette ([WaveColors.palette])
 * - N=2 layers (minimal depth-FX case)
 * - N=5 layers (extended stack)
 * - a solid / monochrome palette ([WaveColors.solid]) — waves stay visible via the depth ramp
 * - each shadow mode: [ShadowMode.FromWave], [ShadowMode.Custom] (high alpha), [ShadowMode.None]
 *
 * Workflow: `./gradlew :kwave:recordRoborazziDebug` records goldens, `:kwave:verifyRoborazziDebug`
 * fails CI on a pixel diff.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WaveScreenshotTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun screenshot_default_preset() {
        rule.captureWave(
            name = "kwave_default",
            config = WaveConfig.Default,
            phase = 0f,
            time = 0f,
        )
    }

    @Test
    fun screenshot_gradient_simple() {
        rule.captureWave(
            name = "kwave_gradient_simple",
            config = WaveConfig.generate(
                waveCount = 3,
                colors = WaveColors.gradient(Color(0xFF1565C0), Color(0xFF0D47A1)),
            ),
            phase = 0f,
            time = 0f,
        )
    }

    @Test
    fun screenshot_rainbow_palette() {
        rule.captureWave(
            name = "kwave_rainbow_palette",
            config = WaveConfig.generate(
                waveCount = 5,
                colors = WaveColors.palette(
                    listOf(
                        Color(0xFFE53935), // red
                        Color(0xFFFB8C00), // orange
                        Color(0xFFFDD835), // yellow
                        Color(0xFF43A047), // green
                        Color(0xFF1E88E5), // blue
                    ),
                ),
            ),
            phase = 0f,
            time = 0f,
        )
    }

    @Test
    fun screenshot_two_layers() {
        rule.captureWave(
            name = "kwave_n2",
            config = WaveConfig.generate(
                waveCount = 2,
                colors = WaveColors.gradient(Color(0xFF2E7D32), Color(0xFF1B5E20)),
            ),
            phase = 0f,
            time = 0f,
        )
    }

    @Test
    fun screenshot_five_layers() {
        rule.captureWave(
            name = "kwave_n5",
            config = WaveConfig.generate(
                waveCount = 5,
                colors = WaveColors.gradient(Color(0xFF6A1B9A), Color(0xFF311B92)),
            ),
            phase = 0f,
            time = 0f,
        )
    }

    @Test
    fun screenshot_solid_monochrome() {
        // Regression for the reported "invisible waves on a single color" bug: a solid / same-color
        // palette must still show visibly separated waves (the fill now ramps by depth instead of
        // vanishing into the flat background).
        rule.captureWave(
            name = "kwave_solid_monochrome",
            config = WaveConfig.generate(
                waveCount = 4,
                colors = WaveColors.solid(Color(0xFF00897B)), // teal, like the reported case
            ),
            phase = 0f,
            time = 0f,
        )
    }

    @Test
    fun screenshot_shadow_fromwave() {
        rule.captureWave(
            name = "kwave_shadow_fromwave",
            config = WaveConfig.generate(
                waveCount = 4,
                colors = WaveColors.gradient(Color(0xFF26A69A), Color(0xFF00695C)),
                shadow = ShadowMode.FromWave,
            ),
            phase = 0f,
            time = 0f,
        )
    }

    @Test
    fun screenshot_shadow_custom() {
        // Captures a Custom shadow at a high alpha so the (now-honored) Custom.alpha is visible and
        // the regression — Custom.alpha being overwritten by the engine default — would be caught.
        rule.captureWave(
            name = "kwave_shadow_custom",
            config = WaveConfig.generate(
                waveCount = 4,
                colors = WaveColors.gradient(Color(0xFF26A69A), Color(0xFF00695C)),
                shadow = ShadowMode.Custom(Color(0xFF1A237E), alpha = 0.7f),
            ),
            phase = 0f,
            time = 0f,
        )
    }

    @Test
    fun screenshot_shadow_none() {
        rule.captureWave(
            name = "kwave_shadow_none",
            config = WaveConfig.generate(
                waveCount = 4,
                colors = WaveColors.gradient(Color(0xFF26A69A), Color(0xFF00695C)),
                shadow = ShadowMode.None,
            ),
            phase = 0f,
            time = 0f,
        )
    }

    @Test
    fun screenshot_variation_zero() {
        // Locks the rigid 'smooth' fallback (variation=0, no jitter) so the uniform extreme is
        // acceptable-by-design rather than an untested rigidity cliff.
        rule.captureWave(
            name = "kwave_variation_zero",
            config = WaveConfig.generate(
                waveCount = 4,
                variation = 0f,
                colors = WaveColors.gradient(Color(0xFF455A64), Color(0xFF263238)),
            ),
            phase = 0f,
            time = 0f,
        )
    }

    @Test
    fun screenshot_variation_max() {
        // Locks the high-chaos extreme (variation=1, full jitter).
        rule.captureWave(
            name = "kwave_variation_max",
            config = WaveConfig.generate(
                waveCount = 5,
                variation = 1f,
                colors = WaveColors.gradient(Color(0xFF455A64), Color(0xFF263238)),
            ),
            phase = 0f,
            time = 0f,
        )
    }
}
