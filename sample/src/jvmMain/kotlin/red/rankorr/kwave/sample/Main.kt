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
package red.rankorr.kwave.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import red.rankorr.kwave.KWave
import red.rankorr.kwave.WaveConfig

/**
 * Desktop / JVM entry point for the KWave sample harness.
 *
 * Launches a single Compose Desktop window that renders the **auto / drop-in** [KWave] full-bleed
 * behind a side panel of Material3 controls. The controls drive a hoisted [WaveControlState]; every
 * change rebuilds the [WaveConfig] live, so the window doubles as a manual visual test harness for
 * the library's public API.
 *
 * Run with `./gradlew :sample:run`.
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        state = rememberWindowState(width = 1100.dp, height = 720.dp),
        title = "KWave Sample",
    ) {
        // The sample owns its own Material3 theme purely for its control UI. The KWave library reads
        // no MaterialTheme: it is fully theme-free and driven entirely by the WaveConfig built below.
        MaterialTheme(colorScheme = darkColorScheme()) {
            SampleApp()
        }
    }
}

/**
 * Hosts the live control state, derives a [WaveConfig] from it, and lays out the full-bleed [KWave]
 * with the Material3 [ControlPanel] floating over its top-right corner.
 */
@Composable
private fun SampleApp() {
    // Hoisted, mutable control state: the single source of truth the panel edits and the wave reads.
    val state = remember { WaveControlState() }
    // Rebuilt automatically whenever any control the config depends on changes (the getters read
    // Compose snapshot state, so `derivedStateOf` re-evaluates only when one of them actually moves).
    val config by state.config

    // Demo backdrop, deliberately NOT KWave-looking (diagonal magenta → teal): it is only visible
    // when "Waves only" makes the library skip its own background pass, proving the transparency.
    val demoBackdrop = remember {
        Brush.linearGradient(listOf(Color(0xFF4A148C), Color(0xFF00695C)))
    }

    Box(Modifier.fillMaxSize().background(demoBackdrop)) {
        // The drop-in KWave owns its own animation loop; it only needs a full-bleed modifier and the
        // live config. `speed` (breathing/sway tempo) and `drift` (ambient horizontal travel) are
        // drop-in parameters, not config values, so they are passed straight through.
        KWave(
            config = config,
            modifier = Modifier.fillMaxSize(),
            speed = state.speed,
            drift = state.drift,
        )

        ControlPanel(
            state = state,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
        )
    }
}
