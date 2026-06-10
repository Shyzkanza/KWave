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

import androidx.compose.runtime.Composable

/**
 * Desktop / JVM reduce-motion signal.
 *
 * The JVM (Compose Desktop) target has no standard cross-platform "reduce motion" system setting,
 * so this actual always reports `false` and animations run by default. Desktop callers that want to
 * honor a user preference can gate [KWave] themselves via `isPlaying`.
 *
 * @return always `false`.
 */
@Composable
internal actual fun rememberReducedMotion(): Boolean = false
