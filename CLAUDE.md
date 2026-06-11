# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

KWave is a Compose Multiplatform library (`red.rankorr:kwave`, published to Maven Central) that
draws animated, layered sinusoidal wave hero backgrounds on a `Canvas`. Targets: Android, iOS
(`iosArm64` + `iosSimulatorArm64`), and JVM. The JVM target powers the Desktop sample and the fast
unit tests.

Two Gradle modules: `kwave/` (the published library) and `sample/` (a Compose Desktop visual test
harness — **not published**, excluded from the API surface).

## Commands

Use the committed wrapper (`./gradlew`); do not install Gradle globally. **JDK 17** is required
(library bytecode targets Java 11). iOS targets build on macOS only.

```bash
./gradlew :kwave:jvmTest                 # fast JVM/commonTest unit tests (the only CI gate)
./gradlew :kwave:jvmTest --tests "red.rankorr.kwave.WaveGeometryTest"   # a single test class
./gradlew :kwave:verifyRoborazziDebug    # golden screenshot verification (Roborazzi/Robolectric)
./gradlew :kwave:recordRoborazzi         # regenerate + commit goldens after intentional visual change
./gradlew detekt                         # static analysis (config/detekt/detekt.yml)
./gradlew apiCheck                        # fail if public API diverges from committed api/ dump
./gradlew apiDump                         # regenerate api/ dump after an intentional public-API change
./gradlew :sample:run                    # run the Desktop sample / visual harness
./gradlew :sample:generatePreview        # full-size (2000x1250) still previews -> /tmp/kwave-previews/
                                         #   ALWAYS judge visual tuning on these, not on the small
                                         #   360x640 goldens (thin FX read very differently at scale)
./gradlew :sample:generateGif            # regenerate the README/gallery GIFs (docs/screenshots/)
./gradlew :kwave:compileKotlinIosArm64 :kwave:compileKotlinIosSimulatorArm64   # iOS compile (macOS)
./gradlew :kwave:dokkaHtml               # API docs
```

Full pre-PR gate (mirrors what must pass; note only `jvmTest` runs in CI — detekt, apiCheck,
Roborazzi, and iOS compilation are enforced locally, not by CI):

```bash
./gradlew detekt apiCheck :kwave:jvmTest :kwave:verifyRoborazziDebug
```

## Architecture

All library code lives under `kwave/src/`. The pipeline is **config → geometry → renderer →
composable**, split so the math is testable without any UI:

- **`WaveGeometry.kt`** (`internal object`) — pure waveform equations (`sin`-based, second-harmonic
  mixing, breathing amplitude, crest sway). No `DrawScope`, no Compose. This is the single source of
  truth for the math (mirrors `docs/DESIGN.md` §2) and is what the fast JVM tests exercise directly.
  Phase/time arguments are `Double` internally (long-running precision); public APIs take `Float`.
- **`WaveRenderer.kt`** (`drawWaves`, `internal`) — consumes geometry + config inside a `DrawScope`
  to paint the gradient background, per-layer fills, depth shadow bands, and highlight lips. Engine
  constants (sample count, alphas) are internal and never caller-facing. A `WaveRenderCache` reuses
  `Path`s every frame and rebuilds brushes/fills only when `(config, height)` changes.
- **`KWave.kt`** — the two public composable entry points, both rendering through `drawWaves`:
  1. **Stateless** `KWave(config, phase, time, ...)` — a pure deterministic function of
     `(phase, time)`. No animation loop, no randomness, no lifecycle. This is the contract the
     screenshot tests and external-sync callers (pager/scroll) rely on.
  2. **Drop-in** `KWave(modifier, ...)` — owns a `withFrameNanos` loop that **integrates** the live
     `speed`/`drift` rates per frame (no snap on live changes), randomizes its initial phase per
     instance, is lifecycle-aware (pauses below `STARTED`, resumes without a time jump), truly
     suspends on `isPlaying = false` (zero frames while frozen), and honors
     `phaseShift`/`speed`/`isPlaying`/`respectReducedMotion`/`drift`/`maxFps`. It owns its own
     `Canvas` and reads all frame-driven state in the draw phase (no per-frame recomposition);
     only the reduced-motion branch delegates to the stateless overload.
- **Config types** — `WaveConfig`, `WaveLayerSpec`, `WaveColors`, `ShadowMode`. `WaveConfig.generate`
  builds a coherent back-to-front layer stack from high-level knobs (waveCount, crests, harmonic,
  spacing, amplitude, variation, gradientEnd, sway) plus a deterministic seeded jitter.

The default motion has three strands: per-layer amplitude **breathing**, a slow **crest sway**
(scaled by `breathDepth` and the config-wide `WaveConfig.sway`; `0f` disables it), and a gentle
ambient **drift** (`drift` param, `0f` disables it; `phaseShift` remains the deliberate external
translation signal). The renderer honors the passed `Modifier` verbatim and never forces
`fillMaxSize()`.

### Platform code (expect/actual)

The only `expect`/`actual` is `rememberReducedMotion(): Boolean` (declared in `KWave.kt`, with
`ReducedMotion.{android,ios,jvm}.kt` actuals). Everything else is in `commonMain`.

## Hard constraints (enforced; do not break)

- **No `material3` and no `MaterialTheme` in the library core.** KWave is theme-free; every color
  flows through `WaveColors`. (The `sample` module may use `material3` for its own UI chrome.)
- **Public config types are regular `@Immutable` classes, not `data class`es** — no `copy()` / no
  `componentN()`, for ABI stability. Prefer additive changes and explicit `withX` helpers (e.g.
  `WaveLayerSpec.withTint` / `withAlpha`). `WaveConfig.layers` is an `ImmutableList`
  (kotlinx.collections.immutable) so the `@Immutable` annotation holds and Compose can skip
  recomposition.
- **`WaveColors` is built only through `gradient` / `palette` / `solid` factories** (private
  constructor). `WaveLayerSpec` coerces every value into a valid range at construction (negative
  amplitude → 0, baseFrac > 1 → clamped), so invalid input can never reach the renderer.
- **Any public-API change must update the committed `api/` dump** via `apiDump` (binary-compatibility-
  validator). `apiCheck` will fail otherwise. New public symbols need KDoc.
- KDoc and inline comments are written in **English**.

## Release / versioning

Publishing is automated by the `Publish` workflow on a SemVer tag (`X.Y.Z`, no `v` prefix). The tag
name overrides `VERSION_NAME` (which stays `-SNAPSHOT` in `gradle.properties` for local dev), the
build runs on macOS so iOS variants are included, and uploads to the Sonatype Central Portal with
`automaticRelease = false` (final release is a manual step in the Portal UI). Add a `CHANGELOG.md`
entry under `[Unreleased]` for any user-facing change.
