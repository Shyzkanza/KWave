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
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.FileImageOutputStream

// Dev tool (not shipped: the sample module is not published). It renders the README
// preview GIFs headlessly, with no external tools. Run: ./gradlew :sample:generateGif

private const val FORWARD_FRAMES = 40
private const val TIME_STEP = 0.45f
private const val FRAME_DELAY_MS = 70
private const val OUTPUT_DIR = "docs/screenshots"

private class Look(val name: String, val colors: WaveColors)
private class Orientation(val suffix: String, val width: Int, val height: Int)

private val LOOKS = listOf(
    Look(
        "rainbow",
        WaveColors.palette(
            listOf(
                Color(0xFFFF5252),
                Color(0xFFFFB300),
                Color(0xFF66BB6A),
                Color(0xFF29B6F6),
                Color(0xFFAB47BC),
            ),
        ),
    ),
    Look(
        "ocean",
        WaveColors.palette(
            listOf(
                Color(0xFF90E0EF),
                Color(0xFF00B4D8),
                Color(0xFF0077B6),
                Color(0xFF03045E),
            ),
        ),
    ),
    Look(
        "sunset",
        WaveColors.palette(
            listOf(
                Color(0xFFFFD166),
                Color(0xFFFF8C42),
                Color(0xFFEF476F),
                Color(0xFF6A4C93),
            ),
        ),
    ),
)

private val ORIENTATIONS = listOf(
    Orientation("h", width = 600, height = 250),
    Orientation("v", width = 300, height = 540),
)

fun main() {
    File(OUTPUT_DIR).mkdirs()
    for (look in LOOKS) {
        // Tightly packed, lower-amplitude layers with strong per-layer phase offset, so the wave
        // lines weave and cross each other (small spacing = overlap, even phase spread = crossings).
        val config = WaveConfig.generate(
            waveCount = 6,
            crests = 1.2f,
            spacing = 0.4f,
            amplitude = 0.026f,
            variation = 0.3f,
            colors = look.colors,
        )
        for (orientation in ORIENTATIONS) {
            // The stateless KWave is a pure function of (phase, time). Phase stays constant so the
            // waves breathe in place; we sweep time to capture the motion.
            val forward = (0 until FORWARD_FRAMES).map { i ->
                renderFrame(config, orientation.width, orientation.height, time = i * TIME_STEP)
            }
            // Boomerang (forward then reverse) gives a seamless loop without matching periods.
            val frames = forward + forward.subList(1, forward.size - 1).reversed()
            val out = File("$OUTPUT_DIR/wave-${look.name}-${orientation.suffix}.gif")
            writeAnimatedGif(frames, FRAME_DELAY_MS, out)
            println("Wrote ${out.path}: ${frames.size} frames, ${out.length() / 1024} KB")
        }
    }
}

private fun renderFrame(config: WaveConfig, width: Int, height: Int, time: Float): BufferedImage {
    val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
        KWave(config = config, phase = 0f, time = time, modifier = Modifier.fillMaxSize())
    }
    return try {
        val png = scene.render().encodeToData()!!.bytes
        toIntRgb(ImageIO.read(ByteArrayInputStream(png)))
    } finally {
        scene.close()
    }
}

private fun writeAnimatedGif(frames: List<BufferedImage>, delayMs: Int, file: File) {
    val writer = ImageIO.getImageWritersBySuffix("gif").next()
    val params = writer.defaultWriteParam
    val type = ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB)
    val metadata = writer.getDefaultImageMetadata(type, params)
    val format = metadata.nativeMetadataFormatName
    val root = metadata.getAsTree(format) as IIOMetadataNode

    childNamed(root, "GraphicControlExtension").apply {
        setAttribute("disposalMethod", "none")
        setAttribute("userInputFlag", "FALSE")
        setAttribute("transparentColorFlag", "FALSE")
        setAttribute("delayTime", (delayMs / 10).toString())
        setAttribute("transparentColorIndex", "0")
    }
    // NETSCAPE application extension: loop forever (loop count 0).
    childNamed(root, "ApplicationExtensions").appendChild(
        IIOMetadataNode("ApplicationExtension").apply {
            setAttribute("applicationID", "NETSCAPE")
            setAttribute("authenticationCode", "2.0")
            userObject = byteArrayOf(0x1, 0x0, 0x0)
        },
    )
    metadata.setFromTree(format, root)

    FileImageOutputStream(file).use { output ->
        writer.output = output
        writer.prepareWriteSequence(null)
        frames.forEach { frame -> writer.writeToSequence(IIOImage(frame, null, metadata), params) }
        writer.endWriteSequence()
    }
    writer.dispose()
}

private fun childNamed(root: IIOMetadataNode, name: String): IIOMetadataNode {
    for (i in 0 until root.length) {
        val item = root.item(i)
        if (item.nodeName.equals(name, ignoreCase = true)) return item as IIOMetadataNode
    }
    return IIOMetadataNode(name).also(root::appendChild)
}

private fun toIntRgb(src: BufferedImage): BufferedImage {
    if (src.type == BufferedImage.TYPE_INT_RGB) return src
    val dst = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
    dst.createGraphics().apply { drawImage(src, 0, 0, null); dispose() }
    return dst
}
