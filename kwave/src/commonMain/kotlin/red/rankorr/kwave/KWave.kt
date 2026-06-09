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

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.random.Random

/** Nanoseconds in one second, used to convert frame deltas to elapsed seconds. */
private const val NANOS_PER_SECOND: Float = 1_000_000_000f

/**
 * Stateless / controlled wave background — a **pure, deterministic function of ([phase], [time])**.
 *
 * This overload owns no animation state: no `withFrameNanos` loop, no randomized seed, no
 * lifecycle awareness. Identical `(phase, time)` inputs always paint identical pixels, which is
 * exactly the contract the screenshot tests and external-sync callers (pager offset, scroll
 * position) rely on. The drop-in [KWave] overload delegates to this function with the values its
 * own loop computes.
 *
 * It honors [modifier] verbatim — the renderer never forces `fillMaxSize` (the historical bug it
 * corrects, see `DESIGN.md` §7). For a full-bleed background, pass `Modifier.fillMaxSize()`. The
 * underlying [drawWaves] applies a zero-size guard, so a degenerate layout never crashes or paints
 * NaN geometry.
 *
 * @param config the wave configuration (layers, colors, shadow mode, gradient end).
 * @param phase horizontal phase applied to every layer (scaled per layer by [WaveLayerSpec.speed]).
 *   Under the drop-in [KWave] this is a constant ambient offset — the visible motion is the
 *   breathing driven by [time] — while a stateless caller may drive it freely (e.g. a pager offset
 *   for deliberate horizontal translation).
 * @param time continuous elapsed seconds driving per-layer amplitude breathing.
 * @param modifier layout modifier, honored as-is (pass `Modifier.fillMaxSize()` for full-bleed).
 */
@Composable
public fun KWave(
    config: WaveConfig,
    phase: Float,
    time: Float,
    modifier: Modifier = Modifier,
) {
    // Cached paths keyed on layer count: re-created only when the count changes, rewound each frame.
    val paths = rememberWavePaths(config.layers.size)
    Canvas(modifier) {
        // BUG FIX (DESIGN.md §7): Canvas(modifier) — NO chained .fillMaxSize(); honor the caller.
        drawWaves(config, phase, time, paths)
    }
}

/**
 * Drop-in animated wave background. This overload **owns its animation loop** and renders the
 * waves for you; most callers only need this one.
 *
 * It accumulates elapsed time with an internal `withFrameNanos` loop and derives an **in-place
 * oscillation** — the waves swell and recede rather than travel across the screen:
 *
 * ```
 * phase = phaseShift          // ambient phase is constant: no horizontal travel
 * time  = elapsed * speed     // drives per-layer amplitude breathing (the visible motion)
 * ```
 *
 * The ambient horizontal `phase` is **held constant**, so the surface never slides sideways; the
 * only ambient motion is the per-layer amplitude breathing driven by `time` — each layer swelling at
 * its own (config-driven) rate, smoothly, with no abrupt reversals. [speed] scales how fast that bob
 * is. [phaseShift] is **added live and read on every recomposition**, so an external signal (a pager
 * offset or scroll position) can still translate the wave deliberately without restarting the loop.
 * Internally it delegates to the stateless [KWave] overload with the computed `(phase, time)`.
 *
 * Behaviors:
 * - **Lifecycle-aware.** The loop runs only while the host lifecycle is at least
 *   [Lifecycle.State.STARTED]; when the app is backgrounded (or the screen leaves the foreground)
 *   it suspends. On resume, the frame clock baseline is reset so the accumulator does **not** jump
 *   forward by the time spent in the background — no visual snap.
 * - **`isPlaying = false`** freezes the animation: the loop suspends and the last `(phase, time)`
 *   keeps rendering.
 * - **Reduced motion.** When [respectReducedMotion] is `true` **and** the system reduce-motion
 *   setting is on, KWave renders exactly **one static frame** (computed from [phaseShift] and
 *   `time = 0`) and never starts the loop. When `false`, the loop runs regardless of the system
 *   setting.
 * - **Per-instance randomized phase.** A `remember`-ed random constant is added to the initial
 *   phase **only in this overload**, so two `KWave`s on the same screen do not march in lockstep.
 *   The stateless overload never randomizes — it stays deterministic.
 *
 * The [modifier] is honored as-is (no forced `fillMaxSize`); pass `Modifier.fillMaxSize()` for a
 * full-bleed background.
 *
 * @param config wave configuration. Default [WaveConfig.Default].
 * @param modifier layout modifier — honored as-is (pass `Modifier.fillMaxSize()` for full-bleed).
 * @param speed breathing-tempo multiplier (faster/slower bob). Default `1`.
 * @param phaseShift live external phase signal (pager/scroll), read every recomposition. Default `0`.
 * @param isPlaying `false` freezes on the current frame. Default `true`.
 * @param respectReducedMotion when `true` (default) and the system reduce-motion setting is on,
 *   render one static frame. `false` is an escape hatch for callers that gate motion themselves
 *   (e.g. via [isPlaying]) — it ignores the system setting, so prefer the default. Default `true`.
 */
@Composable
public fun KWave(
    config: WaveConfig = WaveConfig.Default,
    modifier: Modifier = Modifier,
    speed: Float = 1f,
    phaseShift: Float = 0f,
    isPlaying: Boolean = true,
    respectReducedMotion: Boolean = true,
) {
    // Per-instance random initial phase so multiple instances don't synchronize. RANDOM ONLY here —
    // the stateless overload must stay deterministic.
    val initialPhase = remember { Random.nextFloat() * WaveGeometry.TAU }

    val reducedMotion = rememberReducedMotion()
    val motionOff = respectReducedMotion && reducedMotion

    if (motionOff) {
        // One static frame: no withFrameNanos loop at all. Still react live to phaseShift.
        KWave(
            config = config,
            phase = initialPhase + phaseShift,
            time = 0f,
            modifier = modifier,
        )
        return
    }

    // Accumulated elapsed seconds; the single source of truth for both phase and time.
    val elapsed = remember { mutableFloatStateOf(0f) }

    // Read live on every recomposition without restarting the animation effect.
    val currentSpeed by rememberUpdatedState(speed)
    val currentPhaseShift by rememberUpdatedState(phaseShift)
    val currentIsPlaying by rememberUpdatedState(isPlaying)

    // Lifecycle gate: the loop is active only when at least STARTED (hard pause on background).
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val isResumed = lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    // The loop is (re)launched on lifecycle changes only; `isPlaying` is a soft freeze handled live
    // inside the loop (last (phase, time) keeps rendering), and speed/phaseShift are read live too.
    LaunchedFrameLoop(running = isResumed, elapsed = elapsed, isPlaying = { currentIsPlaying })

    // No horizontal travel: the ambient phase is held constant (only `phaseShift` — an external
    // pager/scroll signal — can move it). All motion is the per-layer amplitude breathing, whose
    // tempo is `time = elapsed * speed`, so the surface oscillates in place (each layer swelling at
    // its own rate) instead of sliding sideways. Breathing is sinusoidal, so the motion is smooth —
    // no abrupt direction reversals.
    val phase = initialPhase + currentPhaseShift
    val time = elapsed.floatValue * currentSpeed

    KWave(
        config = config,
        phase = phase,
        time = time,
        modifier = modifier,
    )
}

/**
 * Drives the [elapsed]-seconds accumulator with a `withFrameNanos` loop while [running] is true.
 *
 * Implementation notes that satisfy `DESIGN.md` §10:
 * - The frame-clock baseline (`lastNanos`) is reset on each (re)launch, so resuming after a pause
 *   does not fold the background time into [elapsed] — the wave continues from where it stopped
 *   rather than snapping forward.
 * - While [isPlaying] reports `false` the accumulator is held constant (the baseline keeps tracking
 *   the clock, so unfreezing does not jump either).
 * - When [running] is false the effect is not active at all (the lifecycle is below `STARTED`),
 *   which is the natural pause point.
 *
 * @param running whether the loop should be active (host resumed and the caller wants animation).
 * @param elapsed the shared accumulator the loop advances, in seconds.
 * @param isPlaying live predicate: when it returns `false`, time accrual is paused without
 *   tearing down the loop.
 */
@Composable
private fun LaunchedFrameLoop(
    running: Boolean,
    elapsed: MutableFloatState,
    isPlaying: () -> Boolean,
) {
    if (!running) return
    LaunchedEffect(Unit) {
        // Reset the baseline on (re)launch so no time-jump occurs after a pause/resume.
        var lastNanos = 0L
        while (true) {
            withFrameNanos { frameNanos ->
                if (lastNanos != 0L) {
                    if (isPlaying()) {
                        val delta = (frameNanos - lastNanos) / NANOS_PER_SECOND
                        elapsed.floatValue += delta
                    }
                    // When frozen, simply advance the baseline without accruing elapsed time.
                }
                lastNanos = frameNanos
            }
        }
    }
}

/**
 * Whether the platform's "reduce motion" accessibility setting is currently on.
 *
 * Actuals: Android reads `Settings.Global.ANIMATOR_DURATION_SCALE == 0` (the OS signal apps use
 * to disable animations); iOS reads `UIAccessibility.isReduceMotionEnabled`; the JVM/Desktop
 * target has no such system setting and returns `false`.
 *
 * @return `true` when the user has asked the system to reduce motion.
 */
@Composable
internal expect fun rememberReducedMotion(): Boolean
