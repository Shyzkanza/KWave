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

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import red.rankorr.kwave.KWave
import red.rankorr.kwave.WaveColors
import red.rankorr.kwave.WaveConfig
import java.io.File

// Dev tool (not shipped). Renders full-size still previews of representative configs, so visual
// tuning is judged at a realistic hero-banner size instead of the small test goldens.
// Run: ./gradlew :sample:generatePreview   →  /tmp/kwave-previews/*.png

private const val PREVIEW_WIDTH = 2000
private const val PREVIEW_HEIGHT = 1250
private const val PREVIEW_OUTPUT_DIR = "/tmp/kwave-previews"

private class Preview(val name: String, val config: WaveConfig, val time: Float = 0f)

private val PREVIEWS = listOf(
    Preview(
        "solid-orange-3",
        WaveConfig.generate(waveCount = 3, colors = WaveColors.solid(Color(0xFFEF6C00))),
    ),
    Preview(
        "solid-orange-5",
        WaveConfig.generate(waveCount = 5, colors = WaveColors.solid(Color(0xFFEF6C00))),
        time = 3f,
    ),
    Preview(
        "solid-teal-4",
        WaveConfig.generate(waveCount = 4, colors = WaveColors.solid(Color(0xFF00897B)), seed = 13),
    ),
    Preview(
        "gradient-blue-3",
        WaveConfig.generate(
            waveCount = 3,
            colors = WaveColors.gradient(Color(0xFF1565C0), Color(0xFF0D47A1)),
            seed = 7,
        ),
    ),
    Preview(
        "rainbow-5",
        WaveConfig.generate(
            waveCount = 5,
            colors = WaveColors.palette(
                listOf(
                    Color(0xFFE53935),
                    Color(0xFFFB8C00),
                    Color(0xFFFDD835),
                    Color(0xFF43A047),
                    Color(0xFF1E88E5),
                ),
            ),
            seed = 3,
        ),
        time = 2f,
    ),
)

fun main() {
    File(PREVIEW_OUTPUT_DIR).mkdirs()
    for (preview in PREVIEWS) {
        val scene = ImageComposeScene(
            width = PREVIEW_WIDTH,
            height = PREVIEW_HEIGHT,
            density = Density(1f),
        ) {
            KWave(
                config = preview.config,
                phase = 0f,
                time = preview.time,
                modifier = Modifier.fillMaxSize(),
            )
        }
        try {
            val png = scene.render().encodeToData()!!.bytes
            val out = File("$PREVIEW_OUTPUT_DIR/${preview.name}.png")
            out.writeBytes(png)
            println("Wrote ${out.path} (${out.length() / 1024} KB)")
        } finally {
            scene.close()
        }
    }
}
