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

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * How the depth **shadow band** below each wave crest and the luminous **highlight lip** above it
 * are colored, per layer.
 *
 * The shadow band is the soft darkening drawn just under each crest (except the front-most layer)
 * that creates edge-less depth; the highlight lip is the bright "water surface" reflection drawn
 * just above the same crests. The highlight always uses the **inverted** logic of the shadow, so a
 * single [ShadowMode] choice controls both consistently.
 *
 * The default is [Auto], which adapts to each layer's local wave color so a single mode looks
 * correct over both light and dark palettes.
 *
 * @see WaveConfig.shadow
 */
public sealed interface ShadowMode {

    /**
     * Default, palette-adaptive mode.
     *
     * For each layer the shadow is **black or white**, chosen by the relative luminance of that
     * layer's local wave (fill) color: a **light** wave color gets a **dark (black)** shadow, and a
     * **dark** wave color gets a **light (white)** shadow. The highlight lip uses the **inverted**
     * pick (the luminous opposite of the shadow). The luminance cutoff is `0.5` using standard
     * relative luminance (`0.2126*r + 0.7152*g + 0.0722*b`).
     */
    public data object Auto : ShadowMode

    /**
     * The shadow is that layer's own color **darkened** (lerped toward [Color.Black] by roughly
     * `0.6`), and the highlight is the same layer color **lightened** by the inverse amount. This
     * keeps shadow and highlight in the layer's own hue family rather than neutral black/white.
     */
    public data object FromWave : ShadowMode

    /** Draws **no** shadow band and **no** highlight lip — only the flat per-layer fills over the
     * background gradient. The depth-FX loop still iterates structurally but paints nothing. */
    public data object None : ShadowMode

    /**
     * Explicit, caller-supplied shadow [color] at the given [alpha] for **every** layer's shadow
     * band. The highlight lip reuses [color] under the inverse-luminance treatment.
     *
     * [alpha] is coerced into `[0, 1]` at construction so an out-of-range value never reaches the
     * renderer.
     *
     * This is a regular [Immutable] class (not a `data class`) for binary-compatibility stability.
     *
     * @param color the shadow color applied to every layer.
     * @param alpha the peak opacity of the shadow color, coerced into `[0, 1]`.
     */
    @Immutable
    public class Custom(
        public val color: Color,
        alpha: Float,
    ) : ShadowMode {
        /** The peak shadow opacity, coerced into `[0, 1]`. */
        public val alpha: Float = alpha.coerceIn(0f, 1f)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Custom) return false
            return color == other.color && alpha == other.alpha
        }

        override fun hashCode(): Int = 31 * color.hashCode() + alpha.hashCode()

        override fun toString(): String = "ShadowMode.Custom(color=$color, alpha=$alpha)"
    }
}
