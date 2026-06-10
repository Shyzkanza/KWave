# Contributing to KWave

Thanks for your interest in improving KWave. This guide covers the local workflow the CI enforces,
so you can reproduce every check before opening a pull request.

## Prerequisites

- **JDK 17** (the build targets Java 17; the library bytecode targets Java 11).
- The Gradle wrapper is committed, so use `./gradlew` and do not install Gradle globally.
- macOS is required to build/run the **iOS** targets; Android, JVM, detekt, and the JVM tests run
  on any platform.

## Project layout

- `kwave/`: the published Compose Multiplatform library (`red.rankorr:kwave`). Common code
  lives in `kwave/src/commonMain/kotlin`, with `androidMain`, `iosMain`, and `jvmMain` actuals.
- `sample/`: a Compose Desktop sample app and visual test harness. **Not published** and excluded
  from the API surface.

## Build

```bash
# Compile the whole project.
./gradlew build

# Compile just the library (all targets it can build on this OS).
./gradlew :kwave:assemble

# Compile the iOS targets (macOS only).
./gradlew :kwave:compileKotlinIosArm64 :kwave:compileKotlinIosSimulatorArm64
```

## Test

```bash
# Fast JVM / commonTest unit tests (geometry purity, coercion, color/shadow logic).
./gradlew :kwave:jvmTest

# Golden screenshot verification (Roborazzi on Robolectric).
./gradlew :kwave:verifyRoborazziDebug
```

If you change the renderer's visual output on purpose, regenerate and commit the golden baselines:

```bash
./gradlew :kwave:recordRoborazzi
```

Roborazzi diff reports are written under `kwave/build/outputs/roborazzi/` and
`kwave/build/reports/roborazzi/` when verification fails.

## Static analysis (detekt)

```bash
./gradlew detekt
```

Configuration lives in `config/detekt/detekt.yml` (it builds upon detekt's default rule set).
Detekt covers the library source sets and the sample. HTML/XML reports land in
`*/build/reports/detekt/`.

## Binary compatibility (apiCheck / apiDump)

KWave's public API is locked by the
[binary-compatibility-validator](https://github.com/Kotlin/binary-compatibility-validator). The
committed `api/` dump **is** the public surface.

```bash
# Fail if the current public API diverges from the committed dump.
./gradlew apiCheck

# Intentionally changing the public API? Regenerate the dump and commit it.
./gradlew apiDump
```

The `sample` module is excluded from API validation. Any public-API change must be intentional and
reviewed. `apiCheck` runs in CI and will fail the build otherwise.

## API documentation (Dokka)

```bash
# Generate the HTML API docs for the library.
./gradlew :kwave:dokkaHtml
```

Public symbols are documented with KDoc; please document new public API the same way.

## Run the sample

The Compose Desktop sample doubles as a manual visual test harness, with sliders for `waveCount`,
`crests`, `harmonic`, `spacing`, `amplitude`, `speed`, `variation`, `gradientEnd`, a shadow-mode
selector, and a gradient/rainbow color switch:

```bash
./gradlew :sample:run
```

## Pull request checklist

Before pushing, run the same gates as CI:

```bash
./gradlew detekt apiCheck :kwave:jvmTest :kwave:verifyRoborazziDebug
```

- [ ] Code compiles on all targets you touched (`iosMain` changes built on macOS).
- [ ] `detekt` passes.
- [ ] `apiCheck` passes, or `apiDump` was re-run and the updated `api/` dump is committed.
- [ ] JVM tests pass; new behavior is covered by `commonTest` and/or a Roborazzi golden.
- [ ] New public API is documented with KDoc.
- [ ] `CHANGELOG.md` has an entry under `[Unreleased]`.

## Conventions

- The library core has **no `material3`** dependency and reads **no `MaterialTheme`**; all colors
  flow through `WaveColors`. Keep it that way (the sample may use `material3` for its own UI chrome).
- Public config types are regular `@Immutable` classes (not `data class`es) for ABI stability;
  prefer additive changes and explicit `withX` helpers over exposing `copy()`.
- Source-level KDoc and inline comments are written in **English**.

## License

By contributing you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE), consistent with the rest of the project.
