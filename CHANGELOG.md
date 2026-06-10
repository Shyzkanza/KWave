# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Planned

- **Vertical flip / top-anchor** (targeted for a 1.x release, **not yet implemented**). Anchors the
  waves to the top of the canvas and fills upward, for headers and inverted hero layouts.
  Today the fill is always bottom-anchored.

## [0.1.0] - Unreleased

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

[Unreleased]: https://github.com/Shyzkanza/KWave/compare/0.1.0...HEAD
[0.1.0]: https://github.com/Shyzkanza/KWave/releases/tag/0.1.0
