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
import androidx.compose.runtime.MutableDoubleState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.flow.first
import kotlin.random.Random

/** Nanoseconds in one second as a [Double], used to convert frame deltas to seconds. */
private const val NANOS_PER_SECOND: Double = 1_000_000_000.0

/**
 * Default ambient [drift][KWave] in radians of phase per second: a slow horizontal travel
 * (a full phase cycle takes about two minutes) that, combined with the per-layer crest sway and
 * amplitude breathing, makes the surface read as living water instead of a static pulse.
 */
private const val DEFAULT_DRIFT: Float = 0.05f

/**
 * Stateless / controlled wave background. It is a pure, deterministic function of ([phase], [time]).
 *
 * This overload owns no animation state: no `withFrameNanos` loop, no randomized seed, no
 * lifecycle awareness. Identical `(phase, time)` inputs always paint identical pixels, which is
 * exactly the contract the screenshot tests and external-sync callers (pager offset, scroll
 * position) rely on. The drop-in [KWave] overload renders through the same internal renderer with
 * the values its own loop computes.
 *
 * It honors [modifier] verbatim. The renderer never forces `fillMaxSize` (the historical bug it
 * corrects, see `DESIGN.md` §7). For a full-bleed background, pass `Modifier.fillMaxSize()`. The
 * underlying [drawWaves] applies a zero-size guard, so a degenerate layout never crashes or paints
 * NaN geometry.
 *
 * @param config the wave configuration (layers, colors, shadow mode, gradient end).
 * @param phase horizontal phase applied to every layer (scaled per layer by [WaveLayerSpec.speed]).
 *   Under the drop-in [KWave] this advances slowly with the ambient drift, while a stateless caller
 *   may drive it freely (e.g. a pager offset for horizontal translation).
 * @param time continuous elapsed seconds driving per-layer amplitude breathing and crest sway.
 * @param modifier layout modifier, honored as-is (pass `Modifier.fillMaxSize()` for full-bleed).
 */
@Composable
public fun KWave(
    config: WaveConfig,
    phase: Float,
    time: Float,
    modifier: Modifier = Modifier,
) {
    // Cached paths/brushes keyed on config: re-created only when it changes, reused each frame.
    val cache = rememberWaveRenderCache(config)
    Canvas(modifier) {
        // BUG FIX (DESIGN.md §7): Canvas(modifier) with NO chained .fillMaxSize(); honor the caller.
        drawWaves(config, phase.toDouble(), time.toDouble(), cache)
    }
}

/**
 * Drop-in animated wave background. This overload owns its animation loop and renders the
 * waves for you; most callers only need this one.
 *
 * It integrates elapsed time with an internal `withFrameNanos` loop and derives an organic,
 * water-like motion from it. Per frame delta `Δ` (seconds):
 *
 * ```
 * time       += Δ * speed   // per-layer amplitude breathing + crest sway
 * driftPhase += Δ * drift   // slow ambient travel (parallaxed per layer by WaveLayerSpec.speed)
 * phase       = initialPhase + phaseShift + driftPhase
 * ```
 *
 * [speed] and [drift] are **integrated**, not multiplied by the total elapsed time, so changing
 * either mid-animation alters the tempo from that frame on without snapping the accumulated
 * position.
 *
 * The visible motion has three strands: each layer's amplitude **breathes** (swells and recedes) at
 * its own config-driven rate, its crests **sway** slowly side to side (scaled by the layer's
 * `breathDepth` and the config-wide [WaveConfig.sway], see `DESIGN.md` §2.2), and the whole surface
 * **drifts** horizontally at the gentle [drift] rate (each layer translating by its own
 * [WaveLayerSpec.speed], which adds parallax). Set `drift = 0f` to remove the ambient travel
 * (breathing layers still sway); also set `WaveConfig.sway = 0f` to restore the exact in-place
 * breathing of pre-0.2.0 releases. [phaseShift] is added live and read on every frame, so an
 * external signal (a pager offset or scroll position) can still translate the wave without
 * restarting the loop. Internally it renders through the same renderer as the stateless [KWave]
 * overload.
 *
 * Performance contract:
 * - All frame-driven state is read **inside the draw phase**, so a running animation re-draws
 *   without recomposing anything.
 * - `isPlaying = false` (and the lifecycle dropping below STARTED) truly suspends the frame loop:
 *   no `withFrameNanos` ticks, no draw invalidations, zero rendering work while frozen.
 * - Elapsed time is integrated in double precision and the waveform arguments are computed in
 *   double precision, so the motion stays frame-smooth even after days on screen (kiosk /
 *   always-on usage).
 * - [maxFps] optionally caps how often the animation invalidates, trading temporal resolution for
 *   battery (the motion is slow, so `24`–`30` is usually indistinguishable from the device rate).
 *
 * Behaviors:
 * - Lifecycle-aware. The loop runs only while the host lifecycle is at least
 *   [Lifecycle.State.STARTED]; when the app is backgrounded (or the screen leaves the foreground)
 *   it suspends. On resume, the frame clock baseline is reset so the accumulator does not jump
 *   forward by the time spent in the background, so there is no visual snap.
 * - `isPlaying = false` freezes the animation: the loop suspends and the last `(phase, time)`
 *   keeps rendering. Unfreezing resumes without a time jump.
 * - Reduced motion. When [respectReducedMotion] is `true` and the system reduce-motion
 *   setting is on, KWave renders exactly one static frame (computed from [phaseShift] and
 *   `time = 0`) and never starts the loop. When `false`, the loop runs regardless of the system
 *   setting.
 * - Per-instance randomized phase. A `remember`-ed random constant is added to the initial
 *   phase only in this overload, so two `KWave`s on the same screen do not march in lockstep.
 *   The stateless overload never randomizes; it stays deterministic.
 *
 * The [modifier] is honored as-is (no forced `fillMaxSize`); pass `Modifier.fillMaxSize()` for a
 * full-bleed background.
 *
 * @param config wave configuration. Default [WaveConfig.Default].
 * @param modifier layout modifier, honored as-is (pass `Modifier.fillMaxSize()` for full-bleed).
 * @param speed breathing/sway-tempo multiplier (faster/slower bob), applied from the current frame
 *   on when changed live. It does **not** scale [drift]; to freeze all motion use
 *   `isPlaying = false`. Default `1`.
 * @param phaseShift live external phase signal (pager/scroll), read every frame. Default `0`.
 * @param isPlaying `false` freezes on the current frame and suspends the loop entirely. Default `true`.
 * @param respectReducedMotion when `true` (default) and the system reduce-motion setting is on,
 *   render one static frame. `false` is an escape hatch for callers that gate motion themselves
 *   (e.g. via [isPlaying]); it ignores the system setting, so prefer the default. Default `true`.
 * @param drift ambient horizontal travel in radians of phase per second, parallaxed per layer by
 *   [WaveLayerSpec.speed], applied from the current frame on when changed live. `0f` removes the
 *   ambient travel (breathing layers still sway; also set `WaveConfig.sway = 0f` for the strict
 *   pre-0.2.0 in-place look). Default `0.05` (one phase cycle ≈ 2 minutes).
 * @param maxFps optional cap on the animation update rate, in frames per second; `<= 0` (default)
 *   updates on every display frame. The loop still wakes per frame, but skipped updates publish no
 *   state change, so nothing is invalidated, re-drawn, or composited — the dominant battery cost.
 */
@Composable
public fun KWave(
    config: WaveConfig = WaveConfig.Default,
    modifier: Modifier = Modifier,
    speed: Float = 1f,
    phaseShift: Float = 0f,
    isPlaying: Boolean = true,
    respectReducedMotion: Boolean = true,
    drift: Float = DEFAULT_DRIFT,
    maxFps: Float = 0f,
) {
    // Per-instance random initial phase so multiple instances don't synchronize. RANDOM ONLY here;
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

    // Integrated animation accumulators: `time` is the integral of `speed` over the running frames,
    // `driftPhase` the integral of `drift`. Integrating (rather than multiplying the live value by
    // the total elapsed time) means a mid-animation speed/drift change alters the slope only —
    // no position snap proportional to uptime. Double precision keeps frame-level resolution
    // for weeks of continuous runtime.
    val timeSeconds = remember { mutableDoubleStateOf(0.0) }
    val driftPhase = remember { mutableDoubleStateOf(0.0) }

    // Live values readable from the loop / draw phase without restarting anything.
    val currentSpeed = rememberUpdatedState(speed)
    val currentPhaseShift = rememberUpdatedState(phaseShift)
    val currentDrift = rememberUpdatedState(drift)
    val currentIsPlaying = rememberUpdatedState(isPlaying)
    val currentMaxFps = rememberUpdatedState(maxFps)

    // Lifecycle gate: the loop is active only when at least STARTED (hard pause on background).
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val isResumed = lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    // The loop is (re)launched on lifecycle changes only; `isPlaying = false` suspends it from the
    // inside (no frame ticks while frozen). speed/drift are integrated per frame inside the loop.
    LaunchedFrameLoop(
        running = isResumed,
        time = timeSeconds,
        driftPhase = driftPhase,
        isPlaying = currentIsPlaying,
        speed = currentSpeed,
        drift = currentDrift,
        maxFps = currentMaxFps,
    )

    val cache = rememberWaveRenderCache(config)
    Canvas(modifier) {
        // All frame-driven state is read HERE, inside the draw block: each animation tick
        // invalidates only the draw phase, never recomposing this composable.
        val phase = initialPhase + currentPhaseShift.value + driftPhase.doubleValue
        drawWaves(config, phase, timeSeconds.doubleValue, cache)
    }
}

/**
 * Drives the [time] and [driftPhase] integrators with a `withFrameNanos` loop while [running] is
 * true: per frame delta `Δ` seconds, `time += Δ * speed` and `driftPhase += Δ * drift`.
 *
 * Implementation notes that satisfy `DESIGN.md` §10:
 * - [speed] and [drift] are **integrated per frame**, so a live change alters the rate from that
 *   frame on; the accumulated position never jumps (a multiplier change must not rescale history).
 * - The frame-clock baseline (`lastNanos`) is reset on each (re)launch and on each unfreeze, so
 *   resuming after a pause does not fold the paused time into the integrals; the wave continues
 *   from where it stopped rather than snapping forward.
 * - While [isPlaying] is `false` the loop **suspends on a [snapshotFlow]** instead of ticking: with
 *   no pending `withFrameNanos` the frame clock is not pumped at all, so a frozen wave costs zero
 *   frames (the battery contract). Unfreezing resumes the loop with a fresh baseline.
 * - [maxFps] throttles how often the integrals are **published** to the snapshot states: they keep
 *   accruing in locals, but skipped frames write no state, so no draw is invalidated. On exit from
 *   a play burst the integrals are flushed so the freeze frame is current.
 * - When [running] is false the effect is not in composition at all (the lifecycle is below
 *   `STARTED`), which is the natural pause point.
 *
 * @param running whether the loop should be active (host resumed and the caller wants animation).
 * @param time published integral of `speed` in seconds (drives breathing + sway).
 * @param driftPhase published integral of `drift` in radians (the ambient horizontal travel).
 * @param isPlaying live flag: while `false`, the loop suspends without tearing down the effect.
 * @param speed live breathing/sway-tempo multiplier, integrated per frame.
 * @param drift live ambient drift rate in radians of phase per second, integrated per frame.
 * @param maxFps live update-rate cap in frames per second; `<= 0` publishes every frame.
 */
@Composable
private fun LaunchedFrameLoop(
    running: Boolean,
    time: MutableDoubleState,
    driftPhase: MutableDoubleState,
    isPlaying: State<Boolean>,
    speed: State<Float>,
    drift: State<Float>,
    maxFps: State<Float>,
) {
    if (!running) return
    LaunchedEffect(Unit) {
        // Continue from the last published values (lifecycle relaunches keep the wave position).
        var totalTime = time.doubleValue
        var totalDrift = driftPhase.doubleValue
        while (true) {
            // Truly suspend while frozen: no withFrameNanos pending => no frames requested.
            snapshotFlow { isPlaying.value }.first { it }
            // Fresh baseline after every (re)start so no paused time is folded in.
            var lastNanos = 0L
            var lastPublishNanos = 0L
            while (isPlaying.value) {
                withFrameNanos { frameNanos ->
                    if (lastNanos != 0L) {
                        val delta = (frameNanos - lastNanos) / NANOS_PER_SECOND
                        totalTime += delta * speed.value
                        totalDrift += delta * drift.value
                        val cap = maxFps.value
                        val minInterval = if (cap > 0f) (NANOS_PER_SECOND / cap).toLong() else 0L
                        if (minInterval == 0L || frameNanos - lastPublishNanos >= minInterval) {
                            time.doubleValue = totalTime
                            driftPhase.doubleValue = totalDrift
                            lastPublishNanos = frameNanos
                        }
                    } else {
                        lastPublishNanos = frameNanos
                    }
                    lastNanos = frameNanos
                }
            }
            // Flush any throttled remainder so the frozen frame shows the exact pause moment.
            time.doubleValue = totalTime
            driftPhase.doubleValue = totalDrift
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
