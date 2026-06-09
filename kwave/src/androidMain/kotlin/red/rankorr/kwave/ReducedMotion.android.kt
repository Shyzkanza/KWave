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

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Android reduce-motion signal.
 *
 * Android exposes the user's "remove animations" preference through the system
 * [Settings.Global.ANIMATOR_DURATION_SCALE] float: a value of `0` means the user has asked the
 * platform to disable animations (the same signal the OS uses to short-circuit `ValueAnimator`s).
 * We treat a scale of `0` as reduce-motion ON.
 *
 * The value is read once and `remember`-ed against the current [android.content.Context]: the
 * setting changes rarely and a change recreates the activity (and thus re-reads it), so polling a
 * content observer would add cost for no practical benefit.
 *
 * @return `true` when the animator duration scale is `0` (animations disabled).
 */
@Composable
internal actual fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        scale == 0f
    }
}
