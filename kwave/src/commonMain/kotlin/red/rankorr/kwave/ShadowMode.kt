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
 * How the diffuse **cast shadow** each wave projects on the content behind it is colored, per
 * layer.
 *
 * The cast shadow is the soft, blur-like darkening hugging each crest from above, painted on the
 * background and the layers further back (never on the wave itself, never on a nearer wave): it
 * reads as the elevation of stacked translucent sheets and is what separates the layers visually.
 * The crest **light** is not a shape controlled here: it lives inside each wave's body-fill
 * gradient, tinted by [WaveColors.highlight].
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
     * **dark** wave color gets a **light (white)** shadow (a soft back-glow that reads as
     * atmospheric light on dark palettes). The luminance cutoff is `0.5` using standard
     * relative luminance (`0.2126*r + 0.7152*g + 0.0722*b`).
     */
    public data object Auto : ShadowMode

    /**
     * The shadow is that layer's own color **darkened** (lerped toward [Color.Black] by roughly
     * `0.6`). This keeps the shadow in the layer's own hue family rather than neutral black/white.
     */
    public data object FromWave : ShadowMode

    /** Draws **no** cast shadow, only the per-layer body fills over the background gradient. */
    public data object None : ShadowMode

    /**
     * Explicit, caller-supplied shadow [color] at the given [alpha] for **every** layer's cast
     * shadow.
     *
     * [alpha] is coerced into `[0, 1]` at construction so an out-of-range value never reaches the
     * renderer.
     *
     * This is a regular [Immutable] class (not a `data class`) for binary-compatibility stability.
     *
     * @param color the shadow color applied to every layer.
     * @param alpha the total peak opacity of the cast shadow, coerced into `[0, 1]`.
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
