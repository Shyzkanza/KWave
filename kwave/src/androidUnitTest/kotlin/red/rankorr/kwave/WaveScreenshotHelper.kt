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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.github.takahirom.roborazzi.captureRoboImage

/**
 * THEME-FREE golden-capture helper for the KWave library, adapted from a reference app
 * `ScreenshotHelper.captureToPng` **minus** the app theme.
 *
 * KWave reads no `MaterialTheme` (every color is supplied through [WaveColors]), so the capture
 * wrapper uses no app theme. Content is rendered into a plain, fixed-size box (a
 * neutral [Color.Black] backdrop so any unpainted region is obvious) and the root node is captured
 * with Roborazzi.
 *
 * Output PNGs land under `src/androidUnitTest/roborazzi/<name>.png`, a TRACKED path outside
 * `build/`, so goldens can be committed to source control. Run
 * `./gradlew :kwave:recordRoborazziDebug` to write goldens and `:kwave:verifyRoborazziDebug` to
 * compare against them.
 *
 * @param name golden image base name (the `.png` is added by Roborazzi).
 * @param config the wave configuration to render.
 * @param phase fixed horizontal-phase value (deterministic, no animation loop is used).
 * @param time fixed elapsed-seconds value (deterministic).
 * @param backdrop optional opaque color painted **behind** KWave; used by the waves-only golden to
 *   prove a transparent KWave background really lets the content underneath show through.
 */
fun AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>.captureWave(
    name: String,
    config: WaveConfig,
    phase: Float = 0f,
    time: Float = 0f,
    backdrop: Color? = null,
) {
    setContent {
        // No app theme, no MaterialTheme: a fixed-size canvas with a neutral black backdrop.
        // The STATELESS KWave overload is used so identical (phase, time) yield identical pixels.
        val sized = Modifier
            .size(CAPTURE_WIDTH_DP.dp, CAPTURE_HEIGHT_DP.dp)
            .fillMaxSize()
        if (backdrop != null) {
            Box(Modifier.background(backdrop)) {
                KWave(config = config, phase = phase, time = time, modifier = sized)
            }
        } else {
            KWave(config = config, phase = phase, time = time, modifier = sized)
        }
    }
    waitForIdle()
    onRoot().captureRoboImage("$ROBORAZZI_DIR/$name.png")
}

/** Fixed capture width in dp, so goldens are size-stable across runs/machines. */
private const val CAPTURE_WIDTH_DP: Int = 360

/** Fixed capture height in dp. */
private const val CAPTURE_HEIGHT_DP: Int = 640

/** Tracked output directory (outside `build/`) for recorded/compared golden images. */
private const val ROBORAZZI_DIR: String = "src/androidUnitTest/roborazzi"
