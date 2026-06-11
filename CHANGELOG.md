# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Planned

- **Vertical flip / top-anchor** (targeted for a 1.x release, **not yet implemented**). Anchors the
  waves to the top of the canvas and fills upward, for headers and inverted hero layouts.
  Today the fill is always bottom-anchored.

## [0.2.0] - 2026-06-11

More natural motion and a tighter battery/performance contract.

> **Note:** the drop-in `KWave` overload gained two parameters, which changes its binary signature.
> Source-compatible (the new parameters are trailing with defaults), but consumers compiled against
> 0.1.0 must recompile — standard for a 0.x minor.

### Added

- **Crest sway**: each breathing layer's crests now lean slowly side to side on top of the
  amplitude breathing, so the surface rolls organically instead of only pulsing vertically. The
  sway is scaled by the layer's `breathDepth` (a `breathDepth = 0` layer has zero sway and stays
  fully static), runs slower than the breathing, and is phase-decorrelated from it per layer.
- **Elevation-style depth shading.** Each wave now casts a diffuse, blur-like shadow on the
  content **behind** it (background and further-back layers), like stacked translucent sheets,
  and its crest is gently lit from **inside the fill** (the top of each wave's gradient is lifted
  toward `WaveColors.highlight`) instead of carrying a separate highlight shape — a thin band
  tracing a wide crest read as a "string". The render pass is interleaved per layer
  (shadow → fill, back to front), so a shadow can never bleed over a nearer wave — the 0.1.x
  two-pass design let back-layer shadows smear across the front waves, and its straight vertical
  fades washed the waves instead of shading their edges. `ShadowMode` keeps its semantics for the
  shadow (`Auto` by luminance, `Custom.alpha` = the shadow's total peak opacity, `None` disables
  it), and every layer now gets the FX, including the front-most.
- **Per-wave depth gradient fill.** Each layer's body is now painted with a subtle vertical
  gradient (its palette color at the crest, slightly darkened toward the bottom) instead of a flat
  color, giving every wave internal depth.
- **`WaveColors.withBackground(...)`**: decouples the backdrop from the wave palette. The factories
  still build a coherent scene from one set of colors; `withBackground(color)` /
  `withBackground(top, bottom)` / `withBackground(stops)` then replace **only** the background,
  leaving the wave fills and highlight untouched. `withBackground(Color.Transparent)` is the
  **waves-only** mode: the renderer skips the background pass entirely, so KWave can sit on top of
  caller-provided content (locked by the new `kwave_waves_only` golden).
- **`sway` parameter on `WaveConfig` and `WaveConfig.generate`**: the config-wide weight of the
  crest sway, coerced `>= 0`. `1` (default) is the nominal organic roll; `0f` disables the sway and
  restores the exact 0.1.x waveform (breathing without sway).
- **`drift` parameter on the drop-in `KWave`**: a gentle ambient horizontal travel in radians of
  phase per second, parallaxed per layer by `WaveLayerSpec.speed`. Default `0.05` (a full phase
  cycle ≈ 2 minutes); `0f` removes the travel. Combine `drift = 0f` with `WaveConfig.sway = 0f` to
  fully restore the in-place motion of 0.1.x.
- **`maxFps` parameter on the drop-in `KWave`**: an optional cap on the animation update rate.
  Skipped frames publish no state, so nothing is invalidated, re-drawn, or composited — a large
  battery win on 120 Hz displays (`24`–`30` is usually indistinguishable for this slow motion).
  `<= 0` (the default) updates on every display frame.

### Changed

- **Default drop-in motion is now breathing + crest sway + slow drift** (was: strictly in-place
  breathing). Both new strands are subtle and sinusoidal, and each has an off switch (`drift = 0f`,
  `WaveConfig.sway = 0f`).
- **The stateless `KWave(config, phase, time)` overload paints different pixels than 0.1.0** for
  layers with `breathDepth > 0` (the sway term participates in the waveform; it stays a pure
  deterministic function of its inputs). Downstream screenshot goldens must be re-recorded, or pass
  a `WaveConfig` with `sway = 0f` for the 0.1.x waveform.
- **`speed` and `drift` are integrated per frame** instead of being multiplied by the total
  elapsed time, so changing either live (a slider, an animated transition) alters the tempo from
  that frame on without snapping the accumulated position. Note that `speed` does not scale
  `drift`; use `isPlaying = false` to freeze all motion.
- **Frame-driven state is read in the draw phase.** A running animation now invalidates only the
  draw pass; the `KWave` composable no longer recomposes on every frame.
- **The renderer caches all frame-invariant paint objects.** The background gradient, per-layer
  resolved fill colors, and per-layer shadow/highlight band brushes are rebuilt only when the
  config or the canvas height changes; a steady frame allocates zero `Path`s and zero `Brush`es
  (previously the brushes were re-created every frame). The `Path` cache survives any config change
  that keeps the layer count.
- **The waveform engine computes in double precision** and the drop-in integrates elapsed time in
  double precision, so the motion keeps frame-level smoothness after days of continuous runtime
  (kiosk / always-on screens). A `Float` seconds accumulator degraded after a few hours.
- **`WaveColors` now implements structural equality** (`equals`/`hashCode`/`toString`). Two factory
  calls with identical inputs compare equal, so a `WaveConfig` rebuilt with the same values keeps
  matching `remember` keys (including the renderer's internal cache) instead of being treated as a
  new configuration.

### Fixed

- **`isPlaying = false` no longer pumps frames.** The frozen loop previously kept a
  `withFrameNanos` pending on every frame (only to track the clock baseline), forcing the frame
  clock to keep producing frames at the display rate while visually frozen. The loop now truly
  suspends (zero frames, zero rendering work) and resumes without a time jump.
- **Changing `speed` mid-animation no longer snaps the wave.** It was previously multiplied by the
  total elapsed time, so a live change rescaled all accumulated history (the jump grew with
  uptime); it is now integrated per frame (see *Changed*).

## [0.1.0] - 2026-06-10

Initial release of KWave: animated, customizable layered wave hero backgrounds for Compose
Multiplatform.

### Added

- **`KWave` drop-in composable** that owns its own `withFrameNanos` animation loop. It is
  lifecycle-aware (pauses below `STARTED`, resumes with no time jump), honors the system
  reduce-motion setting (renders a single static frame when on), supports an `isPlaying` freeze and
  a `speed` multiplier, and reads a live `phaseShift` every recomposition for pager/scroll sync. It
  randomizes its initial phase per instance so multiple instances do not synchronize.
- **Stateless `KWave(config, phase, time, modifier)` composable**: a deterministic function
  of `(phase, time)` with no internal state, for screenshot tests and external
  synchronization.
- **`WaveColors`** color strategy (theme-free; no `MaterialTheme`) built through factories:
  `gradient(top, bottom)`, `palette(colors)` (rainbow, depth-sampled per layer), and `solid(color)`.
  Per-layer fills are palette-derived, never a hardcoded black.
- **`ShadowMode`** sealed interface: `Auto` (default, per-layer black/white by luminance),
  `FromWave`, `None`, and `Custom(color, alpha)`. Controls both the depth shadow band and the
  luminous highlight lip.
- **`WaveConfig`** with a neutral `Default` preset and a `generate(waveCount, crests, harmonic,
  spacing, amplitude, variation, colors, shadow, gradientEnd, seed)` factory that auto-distributes
  the static per-layer phase, alpha-by-depth, breathing, and per-layer tint. `crests` is a relative
  crest density (`1` = baseline) and its twin `harmonic` is the crest roughness (`0` = clean rounded
  sine, higher = choppier).
- **`harmonic` (crest roughness) param on `generate()`**, the twin of `crests` (density):
  `0` is a clean rounded sine, higher mixes in more second-harmonic weight for choppier, less regular
  crests. Jittered per layer like the other generator inputs.
- **`WaveLayerSpec`** advanced low-level layer spec with all values coerced into valid ranges at
  construction, plus `withTint` / `withAlpha` ergonomic overrides.
- **Configurable `gradientEnd`** vertical gradient fraction.
- **Per-layer `harmonic` weight** with `0f` producing a pure sine wave.
- **`spacing`, `variation`, and `seed` parameters on `WaveConfig.generate`**. `spacing` controls
  the vertical spread/overlap of the layers, `variation` (in `[0, 1]`) is the amount of per-layer
  pseudo-random jitter that desyncs the layers, and `seed` makes that jitter
  deterministic (same seed ⇒ same layout; change it for a different one).
- Renderer honors the caller's `Modifier` verbatim (no forced `fillMaxSize`), guards against
  zero-size layouts, caches `Path` objects across frames, and keeps the `dropLast(1)` depth effect
  safe at any layer count (including `N = 0` and `N = 1`).
- **Kotlin Multiplatform targets:** `androidTarget`, `iosArm64`, `iosSimulatorArm64`, `jvm`.
- Stable public API: regular `@Immutable` classes (not `data class`es) and `ImmutableList` layers,
  tracked by the binary-compatibility-validator with a committed `api/` dump; Dokka API docs.
- Compose Desktop **sample** app (`./gradlew :sample:run`) doubling as a live visual test harness.

### Changed

- **Drop-in motion is now in-place breathing, not horizontal drift.** The drop-in `KWave` holds the
  ambient `phase` constant (`phase = initialPhase + phaseShift`); the only ambient motion is the
  per-layer amplitude breathing (`time = elapsed * speed`), so the waves oscillate in place rather
  than marching sideways. `speed` is now the breathing/bob tempo multiplier (not a drift speed), and
  `phaseShift` remains a live external signal for explicit horizontal translation (e.g. a pager
  offset).
- **`solid()` and same-color `gradient()` now ramp the per-layer fill by depth** (darker back →
  lighter front) so monochrome/same-color waves stay visible over the same-color background, instead
  of relying on alpha alone.
- **`palette()` background calmed to a muted wash** (rainbow stays on the wave fills). The background
  is no longer the full saturated multi-stop palette; it is a muted two-stop gradient derived
  (darkened) from the palette extremes, so the colorful waves stay the subject.
- **`generate()` gained `gradientEnd`; `seed` moved to the end as an advanced param.** Callers can
  now set the background gradient end directly instead of rebuilding a second `WaveConfig`; `seed` is
  now the last argument and documented as advanced (leave at `0` unless you need a reproducible
  re-roll or to pin a screenshot).
- **`phaseSpread` removed from `generate()`.** Its effect was a barely-perceptible static crest
  reshuffle, swamped in practice by the per-layer phase jitter, so the high-level factory no longer
  exposes it. The low-level `WaveLayerSpec.phaseOffset` is **unchanged** and still lets power users
  set a per-layer horizontal phase directly.

### Fixed

- **`ShadowMode.Custom.alpha` now drives the rendered shadow band.** It was previously ignored
  (a no-op); the supplied alpha is now applied as the band's peak opacity.
- **Degenerate background gradient when `gradientEnd → 0`.** `WaveConfig.gradientEnd` is now floored
  at a small positive minimum (`GRADIENT_END_MIN ≈ 0.04`), so the background `verticalGradient` never
  spans zero height (which painted a flat, broken color).

[Unreleased]: https://github.com/Shyzkanza/KWave/compare/0.2.0...HEAD
[0.2.0]: https://github.com/Shyzkanza/KWave/compare/0.1.0...0.2.0
[0.1.0]: https://github.com/Shyzkanza/KWave/releases/tag/0.1.0
