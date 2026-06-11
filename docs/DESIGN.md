# KWave: Locked Design Specification

> **Status: LOCKED.** This document is the single source of truth for the public API,
> rendering math, color/shadow model, and behavior of the KWave library. Every downstream
> agent (build, renderer, tests, sample, docs) implements against this verbatim. If an
> implementation detail is ambiguous, this document wins; do not improvise divergent behavior.

- **Maven coordinates:** `red.rankorr:kwave:0.1.0`
- **Kotlin package / Android namespace:** `red.rankorr.kwave`
- **License:** Apache-2.0, `Copyright 2026 Jessy Bonnotte (Shyzkanza)`
- **KMP targets:** `androidTarget()`, `iosArm64()`, `iosSimulatorArm64()`, `jvm()`
- **Core deps (commonMain):** `compose.runtime`, `compose.foundation`, `compose.ui`,
  `kotlinx-collections-immutable`, `lifecycle-runtime-compose`. **No `material3` in the library core.**

---

## 1. What KWave is

KWave is a Compose Multiplatform library that draws a full-bleed animated wave background. It is a
stack of vertically-breathing sinusoidal wave layers filling a Canvas, with depth shading and a
crest highlight. Under the drop-in composable the motion has three organic strands (§10): each
layer's amplitude **breathes** (swells and recedes at its own rate), its crests **sway** slowly
side to side (scaled by the layer's `breathDepth` and the config-wide `WaveConfig.sway`, §2.2),
and the whole surface **drifts** horizontally at a gentle, per-layer-parallaxed rate (`drift`).
Each strand has its own off switch (`WaveConfig.sway = 0f`, `drift = 0f`; both together restore
the exact pre-0.2.0 in-place breathing); explicit horizontal translation remains an opt-in
external signal (§10). It is theme-free: it reads no `MaterialTheme`, all colors are supplied
through the public `WaveColors` API.

It ships **two composable entry points**:

1. **`KWave(config, modifier, speed, phaseShift, isPlaying, respectReducedMotion, drift, maxFps)`**,
   the drop-in / auto composable. It owns its own animation loop (`withFrameNanos`), is
   lifecycle-aware, honors system reduce-motion, and randomizes its initial phase so multiple
   instances do not visually synchronize.
2. **`KWave(config, phase, time, modifier)`**, the stateless / controlled composable. A pure
   function of `(phase, time)` with **no internal state**: deterministic, used by screenshot
   tests and by callers that want external synchronization (pager offset, scroll).

The library derives from a reference `WaveHeroBackground` renderer. The math
(`waveYAt`, `layerAmp`, `regionBelow`, `regionAbove`) is ported faithfully, with a small set of
corrections and generalizations documented in §7 and §8.

---

## 2. Rendering model (the math, ported from the reference)

All geometry is expressed as **fractions of canvas height/width**, so the component scales to any
size. The reference engine constants are kept as internal renderer constants (caller cannot see
them):

| Constant | Value | Meaning |
|----------|-------|---------|
| `WAVE_SAMPLES` | `96` | number of polyline samples across the width per wave |
| `HARMONIC_2_PHASE_MUL` | `1.5f` | phase multiplier applied to the 2nd-harmonic sinusoid |
| `BASE_GRADIENT_END_FRAC` | `0.78f` | default vertical gradient end fraction (now overridable via `WaveConfig.gradientEnd`) |
| `GRADIENT_END_MIN` | `0.04f` | floor for `WaveConfig.gradientEnd` (avoids a degenerate zero-span gradient) |
| `SHADOW_ALPHA` | `0.14f` | total peak alpha of the cast shadow (distributed across the bands) |
| `SHADOW_BANDS` | `10` | stacked bands forming the cast-shadow falloff above each crest |
| `SHADOW_REACH_FRAC` | `0.028f` | reach of the cast shadow above the crest, fraction of height |
| `SHADOW_EASE` | `2f` | easing exponent of the band spans (blur-like decay, no traceable boundary) |
| `FILL_DEPTH_DARKEN` | `0.22f` | darkening of the bottom stop of each layer's body-fill gradient |
| `FILL_CREST_HIGHLIGHT_MIX` | `0.35f` | how much of `WaveColors.highlight` tints the fill's crest stop |
| `FROM_WAVE_DARKEN` | `0.6f` | lerp fraction toward `Color.Black` for `ShadowMode.FromWave` |
| `SWAY_WEIGHT` | `1.2f` | peak crest sway (radians of phase) per unit of `breathDepth` |
| `SWAY_FREQ_RATIO` | `0.7f` | sway angular frequency as a fraction of the layer's `breathSpeed` |
| `SWAY_OFFSET_MUL` | `1.7f` | multiplier on `breathOffset` deriving the sway's own phase offset |

The per-layer `harmonic` weight (ex-`HARMONIC_2_WEIGHT = 0.25f`) becomes a **per-layer field**
(`WaveLayerSpec.harmonic`). `harmonic = 0f` yields a **pure sine** wave (no second harmonic).

### 2.1 Amplitude with breathing

```
layerAmp(layer, time) =
    height * layer.amplitude * (1f + layer.breathDepth * sin(time * layer.breathSpeed + layer.breathOffset))
```

When the `sin(...)` term is `0` (e.g. `time = 0` with `breathOffset = 0`), the amplitude returns
to its **nominal** value `height * layer.amplitude`. This invariant is tested.

### 2.2 Wave Y at horizontal position x

```
t    = x / width
tau  = 2 * PI
sway = config.sway * layer.breathDepth * SWAY_WEIGHT
       * sin(time * layer.breathSpeed * SWAY_FREQ_RATIO + layer.breathOffset * SWAY_OFFSET_MUL)
ph   = phase * layer.speed + layer.phaseOffset + sway

y1  = layer.crests * tau * t + ph
y2  = layer.harmonic_freq * tau * t + ph * HARMONIC_2_PHASE_MUL + 1f

waveYAt(x) = height * layer.baseFrac
           + layerAmp(layer, time) * ( sin(y1) + layer.harmonic * sin(y2) )
```

- `layer.crests` is the **primary spatial frequency** (ex-`c1`): how many crest groups span the
  width. Renamed for caller clarity.
- **`sway` is the slow organic crest lean** (added in 0.2.0): a time-driven side-to-side phase
  oscillation layered on top of the amplitude breathing, so the surface rolls instead of only
  pulsing vertically. It is **scaled by `breathDepth`** and by the config-wide **`WaveConfig.sway`**
  weight: a non-breathing layer (`breathDepth = 0`) — or any layer under a `sway = 0f` config —
  has **zero sway** and stays a pure function of `phase` ("no breathing ⇒ no time-driven motion",
  and `WaveConfig.sway = 0f` ⇒ the exact pre-0.2.0 waveform; both are tested). Its frequency rides
  the layer's own `breathSpeed` (slower, by `SWAY_FREQ_RATIO`) and its phase is decorrelated from
  the breathing cycle via `breathOffset * SWAY_OFFSET_MUL`.
- **Double-precision arguments:** `phase` and `time` are `Double`s inside the engine (the public
  composables take `Float` and widen). Every time/phase-driven trigonometric argument is computed
  in double precision so the motion keeps frame-level resolution after days of continuous runtime
  (a `Float` accumulator degrades below the frame delta after a few hours). Tested at ~11 days.
  The guarantee covers the **drop-in** (whose integrators are double-precision end to end); a
  stateless caller passing a very large `Float` `time` is limited by the resolution of its own
  `Float` input.
- The **second-harmonic spatial frequency** (ex-`c2`) is an internal renderer concern. The public
  `harmonic` field controls the **weight** of the second harmonic, not its frequency. The renderer
  derives the harmonic frequency internally (default `crests * 2f`, matching the reference ratio of
  roughly 2×). `harmonic = 0f` ⇒ the `+ layer.harmonic * sin(y2)` term vanishes ⇒ **pure sine**.
- `+ 1f` on `y2` is the reference's fixed harmonic phase bias, preserved.

### 2.3 Sampled crest, regions and ribbons

The renderer samples each layer's crest **once** per frame into a row of `WAVE_SAMPLES + 1` y
values (the single trigonometric pass per layer); every path below is then built from that row:

- **`regionBelow(crest)`**: the crest polyline, then closed down to `(width, height)` →
  `(0, height)`. This is the layer's **body-fill** region.
- **`crestRibbon(crest, offsetPx)`**: the crest polyline forward, then the same polyline offset
  vertically by `offsetPx` backward, closed — a **constant-thickness band that follows the edge**
  (both edges are the same curve, shifted; it can never self-intersect). A negative offset hugs
  the crest from above (the cast-shadow bands).

### 2.4 Draw order

1. **Background gradient:** vertical gradient over the ordered `WaveColors` stops, from `startY = 0`
   to `endY = height * config.gradientEnd` (skipped entirely in waves-only mode, §3.1bis).
2. **One interleaved pass per layer, back to front** (see §4 for the shadow colors):
   - **cast shadow** — `SHADOW_BANDS` overlapping ribbons hugging the crest from **above**,
     spanning `[0, SHADOW_REACH_FRAC * height * (k / N)^SHADOW_EASE]` for `k = 1..SHADOW_BANDS`,
     each at `peak / SHADOW_BANDS` alpha. The eased spans bunch the bands against the edge and
     spread the tail, so the accumulated profile decays smoothly like a **blur** (steep at the
     edge, long soft tail, no traceable outer boundary). Painted **before** the layer's own fill,
     it lands on the background and the layers further back only — the diffuse elevation shadow of
     a translucent sheet resting on the sheet behind;
   - **body fill** — `regionBelow` painted with a **three-stop vertical depth gradient**: the
     **crest light** (the palette-derived fill color lifted toward `WaveColors.highlight` by
     `FILL_CREST_HIGHLIGHT_MIX`) at the top of the crest envelope (`baseY - ampMax`, where
     `baseY = height * baseFrac` and `ampMax = height * amplitude * (1 + breathDepth)`), the plain
     fill at the envelope bottom, and the fill darkened by `FILL_DEPTH_DARKEN` at the canvas
     bottom — the layer's resolved alpha baked into every stop. The crest light **replaces any
     separate rim/highlight shape**: a thin band tracing a wide crest reads as a "string"; a lift
     blended into the fill itself is soft by construction and has nothing to trace.

**Why interleaved.** Painting each layer's shadow before the nearer layers' fills means it can
**never bleed over a wave in front of it** (the front fills cover it). The historical two-pass
design (all fills, then all FX) let a back layer's shadow smear across the front waves. See §8 for
the layer-count safety contract.

---

## 3. Color model: `WaveColors`

`WaveColors` is an `@Immutable` **regular class** (not a `data class`) with **no public
constructor**. It is built only through factory functions. Internally it resolves three things:

- **`backgroundStops: List<Color>`**: ordered gradient stops for the canvas background.
- **`fillColorFor(layerIndex, layerCount): Color`**: the per-layer fill color, **derived from the
  palette** by sampling at the layer's normalized depth. **It is never a hardcoded `Color.Black`.**
  (This is the main correction over the reference, which always filled with `Color.Black`.)
- **`highlight: Color`**: the crest-light tint, blended into the top stop of each wave's body-fill
  gradient (reference's `lightColor` analog).

The factories deliberately **couple** the background and the wave palette (one call yields a
coherent scene). To **decouple** them, `withBackground(...)` (§3.1bis) replaces only the
background stops afterwards, including the fully transparent **waves-only** mode.

### 3.1 Factories

#### `WaveColors.gradient(top: Color, bottom: Color)`
Simple vertical auto-gradient. `backgroundStops = [top, bottom]`. Per-layer fill samples the
`top→bottom` gradient at each layer's depth (back layers lean toward `top`, front layers toward
`bottom`), giving depth tinting without any black overlay. `highlight` is a lightened
variant of `top`. **When `top == bottom`** the two-stop gradient is flat and a uniform per-layer
fill would be invisible over the same-color background, so this case **routes to `solid(top)`** and
inherits its depth ramp (below).

#### `WaveColors.palette(colors: List<Color>)` ("rainbow")
The rainbow rides the **wave fills**, not the backdrop. Each wave layer is tinted by **sampling the
palette at its depth**: `fillColorFor(i, n)` evaluates the multi-stop palette at `i / max(1, n-1)`,
so each layer carries a distinct hue drawn from the palette (`fillStops = colors`). The
**background**, however, is **not** the full saturated palette (which would out-shout the waves). It
is a muted **two-stop wash derived from the palette extremes**, each darkened (`backgroundStops
= [darken(colors.first), darken(colors.last)]`), so the sky recedes behind the colorful wave fills.
`highlight` is a lightened sample near the front of the palette. Coercion: an empty list falls back
to a single neutral color; a single-element list behaves like `solid`.

#### `WaveColors.solid(color: Color)`
Single flat color. `backgroundStops = [color, color]`. The per-layer fill **ramps by depth** around
`color` (a slightly **darker back**, a slightly **lighter front**) rather than repeating `color`
verbatim. A uniform same-color fill over the same-color background would be invisible (the waves
would disappear), so the ramp keeps the layers visible; the auto per-layer **alpha** (§3.2) adds
further separation on top. `highlight` is a lightened variant of `color`.

### 3.1bis Background override: `withBackground`

`withBackground` returns a copy with **only the background stops replaced**; the wave-fill palette
and the highlight are untouched. Three overloads:

- **`withBackground(color: Color)`**: flat backdrop. **`Color.Transparent` is the waves-only
  mode**: when every background stop is fully transparent the renderer **skips the background pass
  entirely** (no full-canvas rect is drawn), so KWave can sit on top of caller-provided content
  (an image, another composable). Locked by the `kwave_waves_only` golden, which paints a magenta
  backdrop behind a transparent-background KWave.
- **`withBackground(top: Color, bottom: Color)`**: two-stop vertical gradient backdrop.
- **`withBackground(stops: List<Color>)`**: multi-stop backdrop. Coercion: empty ⇒ behaves like
  `Color.Transparent` (waves-only); single element ⇒ behaves like the flat overload.

Example: `WaveColors.palette(rainbow).withBackground(Color(0xFF101820))` keeps the rainbow on the
waves over a custom near-black sky.

### 3.2 Auto depth-alpha

Per-layer **alpha is auto-assigned by depth**: the **back** layer is the most **transparent**, the
**front** layer the most **opaque**. With `n` layers, layer `i` (0 = back) gets an alpha ramped from
a low floor (≈ `0.40f`, matching the reference back-layer alpha) up to `1.0f` at the front:

```
autoAlpha(i, n) = lerp(BACK_ALPHA_FLOOR, 1f, i / max(1, n - 1))   // BACK_ALPHA_FLOOR ≈ 0.40f
```

For `n = 1`, the single layer is fully opaque (`1f`). A `WaveLayerSpec.alpha` override (non-null)
**replaces** the auto value for that layer.

---

## 4. Shadow model: `ShadowMode`

`ShadowMode` is a **sealed interface**. It controls the **cast (elevation) shadow** each layer
projects on the content behind it — the diffuse band hugging the crest from above, §2.4. The
crest **light** is not a `ShadowMode` concern: it lives inside each wave's body-fill gradient,
tinted by `WaveColors.highlight` (§2.4).

- **`ShadowMode.Auto`** (DEFAULT). For each layer, pick **black or white** by the **luminance**
  of that layer's local wave (fill) color: a **light** wave color gets a **dark (black)** shadow; a
  **dark** wave color gets a **light (white)** shadow — a soft back-glow that reads as atmospheric
  light on dark palettes. Luminance threshold uses standard relative luminance
  (`0.2126*r + 0.7152*g + 0.0722*b`) with a `0.5` cutoff.
- **`ShadowMode.FromWave`**: the shadow is **that layer's color darkened**: `lerp(layerColor,
  Color.Black, FROM_WAVE_DARKEN)` with `FROM_WAVE_DARKEN ≈ 0.6f`.
- **`ShadowMode.None`**: no cast shadow is drawn (only the per-layer body fills
  + background gradient).
- **`ShadowMode.Custom(color: Color, alpha: Float)`**: explicit shadow color/alpha for every
  layer's cast shadow; `alpha` is coerced into `[0,1]`. The supplied `alpha` is the shadow's
  **total peak opacity** (it actually drives the rendered shadow): the renderer distributes it
  across the stacked bands via `shadowPeakAlpha(shadow) / SHADOW_BANDS` per band, so the
  accumulated opacity right against the edge approximates the requested peak.

The cast shadow always **fades to transparent away from the edge**: its accumulated peak is
`ShadowMode.Custom.alpha` for a custom shadow and `SHADOW_ALPHA` otherwise, decaying smoothly
across the eased stacked bands of §2.4.

---

## 5. Layer spec: `WaveLayerSpec`

`WaveLayerSpec` is an `@Immutable` **regular class** (advanced / low-level). All fields have sane
defaults; **all values are coerced into valid ranges** at construction (see §6). Fields:

| Field | Type | Default | Meaning |
|-------|------|---------|---------|
| `baseFrac` | `Float` | `0.5f` | vertical centre, fraction of height, coerced `[0,1]` |
| `amplitude` | `Float` | `0.03f` | peak displacement, fraction of height, coerced `≥ 0` |
| `speed` | `Float` | `1f` | multiplier on the caller's `phase` for this layer |
| `phaseOffset` | `Float` | `0f` | constant horizontal phase offset (radians) |
| `breathDepth` | `Float` | `0.2f` | breathing depth, coerced `[0,1]` |
| `breathSpeed` | `Float` | `0.25f` | breathing angular frequency (rad/s), coerced `≥ 0` |
| `breathOffset` | `Float` | `0f` | breathing phase offset (radians) |
| `crests` | `Float` | `0.8f` | primary spatial frequency (ex-`c1`), coerced `≥ 0` |
| `harmonic` | `Float` | `0.25f` | second-harmonic **weight**; `0f` ⇒ **pure sine**, coerced `≥ 0` |
| `alpha` | `Float?` | `null` | per-layer opacity override; `null` ⇒ system auto-by-depth (§3.2); coerced `[0,1]` |
| `tint` | `Color?` | `null` | per-layer fill override; `null` ⇒ sampled from palette (§3) |

> **Naming map vs. reference:** `ampFrac→amplitude`, `speedMul→speed`, `c1→crests`,
> `HARMONIC_2_WEIGHT (global)→harmonic (per-layer)`. The reference's `c2` (harmonic frequency) is
> internalized; callers control harmonic **weight**, not frequency.

---

## 6. Validation / clamping rules

Coercion happens **at construction** of every public config type, so invalid input never reaches
the renderer:

- `baseFrac` → `coerceIn(0f, 1f)`
- `amplitude` → `coerceAtLeast(0f)` (negative amplitude ⇒ `0`, a flat line, no crash)
- `breathDepth` → `coerceIn(0f, 1f)` (`breathDepth > 1` clamped to `1`)
- `breathSpeed` → `coerceAtLeast(0f)`
- `crests` → `coerceAtLeast(0f)`
- `harmonic` → `coerceAtLeast(0f)`
- `alpha` (when non-null) → `coerceIn(0f, 1f)`
- `ShadowMode.Custom.alpha` → `coerceIn(0f, 1f)`
- `WaveConfig.gradientEnd` → `coerceIn(GRADIENT_END_MIN, 1f)` (floor `GRADIENT_END_MIN ≈ 0.04`; the
  lower bound is above `0` so the background `verticalGradient` never spans zero height. An `endY`
  of `0` equal to `startY` is a degenerate brush that paints a flat, broken color)
- `generate(waveCount = ...)` → `waveCount.coerceAtLeast(1)`; `generate(variation = ...)` →
  `variation.coerceIn(0f, 1f)`; `generate(spacing = ...)` → `spacing.coerceAtLeast(0f)`
- `WaveColors.palette([])` → falls back to one neutral color; `palette([c])` ⇒ behaves like `solid(c)`;
  `gradient(a, a)` (equal stops) ⇒ behaves like `solid(a)`

These coercions are part of the contract and are covered by tests (e.g. `baseFrac > 1`, negative
amplitude, `breathDepth > 1`).

---

## 7. The `fillMaxSize` bug fix (and other corrections)

The reference renderer does `Canvas(modifier.fillMaxSize())`, which **silently overrides the
caller's layout**: a caller passing `Modifier.size(200.dp)` would still get a full-screen canvas.

**Correction (LOCKED):** the renderer calls **`Canvas(modifier)`** and does **not** chain
`.fillMaxSize()`. Full-bleed is the **caller's** choice: for a full-screen background, the caller
passes `Modifier.fillMaxSize()`. The drop-in `KWave` documents this; it does **not** inject
`fillMaxSize` for the caller. The `modifier` parameter **MUST be honored** in both overloads.

Other corrections over the reference:

- **Palette-derived fill** instead of hardcoded `Color.Black` per-layer fill (§3).
- **Zero-size guard:** if `size.minDimension <= 0`, the draw block returns immediately (no NaN /
  div-by-zero from `x / width`).
- **Per-layer `harmonic` weight** with `0f ⇒ pure sine` (reference had a fixed global weight).
- **`gradientEnd` configurable** (reference had a fixed `0.78f` constant).
- **Performance:** Path objects are cached and `rewind()`-ed each frame rather than re-allocated
  (§9), and each layer's `regionBelow` is computed **once per frame** and reused for both the fill
  and the shadow band.

---

## 8. Depth-FX layer-count safety contract

Since 0.2.0 the depth FX (the cast shadow) is applied **per layer inside the single
interleaved pass** (§2.4): every layer — including the front-most — casts its shadow on whatever
is behind it, then gets its body fill. There is **no cross-layer indexing at all**
(the historical `dropLast(1)` two-pass design is gone, fixing the back-layer shadows that could
smear over nearer waves). Well-definedness at all layer counts is therefore structural:

- **N = 0** (empty layer list): the background gradient draws; the per-layer loop iterates zero
  times. **No crash, no index-out-of-bounds.** (`WaveConfig` permits an empty layer list, though
  `generate()` always produces `≥ 1`.)
- **N = 1**: the single layer casts its shadow on the background, then fills. **No crash.**
- **N ≥ 2**: each layer's shadow lands only on the background and the layers further back,
  because the nearer fills are painted after it.

This behavior is **explicit and documented in KDoc** on the renderer, and **tested**
(N=0 and N=1 never throw).

---

## 9. Performance contract

- **Cached paths and crest rows:** the renderer `remember`s a `WaveRenderCache` keyed on the
  **layer count**; each frame it `rewind()`s each path and re-fills it, rather than allocating new
  `Path` instances per frame.
- **Cached brushes and colors:** the background gradient, the per-layer body-fill gradients, and
  the per-layer shadow/highlight band colors depend only on `(config, height)` — the fill envelope
  uses the breathing **maximum** `amplitude * (1 + breathDepth)`, never `time` — so the cache
  rebuilds them only when the config or the canvas height changes (any other config change is
  absorbed without reallocating the paths). A steady frame allocates **zero `Path`s and zero
  `Brush`es**.
- **Single trigonometric pass per layer per frame:** the crest is sampled once into the cache's
  `FloatArray` row; the body-fill region and every shadow/highlight ribbon are built from that row
  with plain `lineTo`s (no further `sin` evaluations).
- **Draw-phase-only invalidation:** the drop-in reads all frame-driven state (the time
  accumulator, `speed`, `phaseShift`, `drift`) **inside the `Canvas` draw block**, so an animation
  tick invalidates only the draw phase — the composable never recomposes while animating.
- **True suspension when idle:** `isPlaying = false` (and the lifecycle dropping below `STARTED`)
  suspends the frame loop on a `snapshotFlow` await with **no pending `withFrameNanos`**, so a
  frozen wave requests zero frames and costs zero rendering work (§10).
- **Optional `maxFps` cap:** the loop can throttle how often it **publishes** the integrals;
  skipped frames write no state, so nothing is invalidated, re-drawn, or composited. The motion is
  slow, so `24`–`30` fps is usually indistinguishable from the device rate at a fraction of the
  battery cost (biggest win on 120 Hz displays).
- **Snap-free long-running time:** the loop integrates the live `speed`/`drift` rates per frame in
  double precision (§10) and the waveform arguments are computed in `Double` (§2.2), so the motion
  never degrades on always-on / kiosk screens and live rate changes never jump the surface.
- **`WAVE_SAMPLES = 96`** keeps each polyline cheap while smooth at typical screen widths.
- **Stable inputs:** `WaveConfig.layers` is an `ImmutableList<WaveLayerSpec>` and all public config
  types are `@Immutable`, so Compose can skip recomposition when inputs are unchanged.

---

## 10. The drop-in / auto composable behavior

```kotlin
@Composable
fun KWave(
    config: WaveConfig = WaveConfig.Default,
    modifier: Modifier = Modifier,        // MUST be honored, no forced fillMaxSize
    speed: Float = 1f,                    // breathing/sway-tempo multiplier
    phaseShift: Float = 0f,               // LIVE external signal for deliberate horizontal translation
                                          //   (pager/scroll), read every frame (in the draw phase)
    isPlaying: Boolean = true,
    respectReducedMotion: Boolean = true,
    drift: Float = 0.05f,                 // ambient horizontal travel (radians of phase / second);
                                          //   0f removes the travel (sway remains; see WaveConfig.sway)
    maxFps: Float = 0f,                   // optional update-rate cap; <= 0 = every display frame
)
```

**Motion model: breathing + sway + slow drift.** Three organic strands, all sinusoidal (no abrupt
reversals):

1. **Breathing** — each layer's amplitude swells and recedes at its own (config-driven) rate
   (§2.1). The dominant motion.
2. **Crest sway** — each breathing layer's crests lean slowly side to side (§2.2), scaled by its
   `breathDepth` and the config-wide `WaveConfig.sway` weight (a non-breathing layer never sways;
   `WaveConfig.sway = 0f` disables the sway everywhere).
3. **Ambient drift** — the whole surface travels horizontally at the gentle `drift` rate
   (default `0.05` rad/s, a full phase cycle ≈ 2 minutes). Each layer translates by its own
   `WaveLayerSpec.speed`, which adds parallax.

`drift = 0f` removes the ambient travel only (breathing layers still sway). To restore the exact
pre-0.2.0 in-place breathing, combine `drift = 0f` with `WaveConfig.sway = 0f`.

**Animation integrators.** An internal `withFrameNanos` loop **integrates** the live `speed` and
`drift` rates per frame in double precision (a `Float` seconds accumulator loses frame-level
resolution after a few hours; see §2.2 for the matching double-precision math). Per frame delta
`Δ` (seconds):

```
time       += Δ * speed   // integral of the live tempo: drives breathing + sway
driftPhase += Δ * drift   // integral of the live drift rate: the ambient travel
phase       = initialPhase + phaseShift + driftPhase
```

- **Integrals, not products.** `time`/`phase` are NOT computed as `elapsed * speed` /
  `elapsed * drift`: multiplying the live rate by the total elapsed time would rescale all
  accumulated history, so a mid-animation `speed`/`drift` change (a slider, an animated
  transition) would snap the surface by an amount proportional to uptime. Integrating per frame
  makes a rate change alter the slope only — never the position.
- `initialPhase` is a per-instance random constant (this overload only; see below).
- `speed` is the **breathing/sway tempo** multiplier: it scales how fast the layers move,
  independent of the drift rate (it does **not** scale `drift`; to freeze everything use
  `isPlaying = false`).
- `phaseShift` is a **live external signal for deliberate horizontal translation**, read on **every
  frame** (so a pager/scroll offset can purposely shift the wave's horizontal phase without
  restarting the loop). It is additive, so changing it never rescales history.
- The published integrals and `phaseShift` are read **inside the `Canvas` draw block**, so an
  animation tick invalidates only the draw phase — the composable does not recompose while
  animating (§9).

Internally this renders through the same internal renderer (`drawWaves`) as the **stateless**
overload, with the computed `(phase, time)`.

**Per-instance randomized initial phase.** A `remember { /* random seed */ }` adds a random
constant to the initial phase **only in this auto overload**, so two `KWave`s on the same screen do
**not** breathe in lockstep. The **stateless overload never randomizes**; it must stay deterministic.

**Lifecycle awareness.** Using `lifecycle-runtime-compose`, the loop **pauses when the lifecycle is
below `STARTED`** (app backgrounded / screen not resumed). On resume, `lastNanos` is **reset** so
the accumulator does **not** jump forward by the elapsed background time (no visual snap).

**`isPlaying = false`** freezes the animation on the current frame **and truly suspends the loop**:
it awaits a `snapshotFlow { isPlaying }` with no pending `withFrameNanos`, so the frame clock is not
pumped at all while frozen (zero frames, zero rendering work — the battery contract). Unfreezing
resumes with a fresh frame baseline, so no paused time is folded in (no jump).

**`maxFps`** (default `0` = uncapped) throttles how often the loop **publishes** the integrals to
the snapshot states. Time keeps accruing in the loop's locals; skipped frames write no state, so no
draw is invalidated and nothing is rendered or composited for that frame. On freeze, the integrals
are flushed so the frozen frame shows the exact pause moment.

**Reduced motion.** When `respectReducedMotion = true` **and** the system reduce-motion setting is
**ON**, KWave renders **exactly one static frame** (`phase = initialPhase + phaseShift`, `time = 0`)
and does **not** start the `withFrameNanos` loop at all. It still reacts live to `phaseShift`. When
`respectReducedMotion = false`, the loop runs regardless of the system setting.

---

## 11. The stateless / controlled composable

```kotlin
@Composable
fun KWave(config: WaveConfig, phase: Float, time: Float, modifier: Modifier = Modifier)
```

- **Pure function** of `(phase, time)`: no `withFrameNanos`, no `remember`-ed random, no lifecycle.
  Identical inputs ⇒ identical pixels. This is the **screenshot-test** and **external-sync**
  contract.
- `phase` is the **horizontal phase** applied to every layer (scaled per-layer by
  `WaveLayerSpec.speed`). Hold it **constant** for in-place motion, advance it slowly for drift
  (the way the drop-in uses it), or drive it freely (e.g. a pager offset) for **horizontal
  translation**. `time` is the continuous elapsed seconds that drive the per-layer amplitude
  breathing and crest sway (§2.2).
- Honors `modifier` (no forced `fillMaxSize`); applies the zero-size guard; renders per §2/§3/§4.
- The auto overload renders through the same internal renderer (`drawWaves`); both widen their
  `Float` inputs to the engine's `Double` precision (§2.2).

---

## 12. `WaveConfig`

```kotlin
@Immutable
class WaveConfig(
    val layers: ImmutableList<WaveLayerSpec>,
    val colors: WaveColors,
    val shadow: ShadowMode = ShadowMode.Auto,
    val gradientEnd: Float = 0.78f,          // coerced [GRADIENT_END_MIN, 1] (floor ≈ 0.04)
    val sway: Float = 1f,                    // config-wide crest-sway weight, coerced >= 0;
                                             //   0f = no sway (the exact pre-0.2.0 waveform)
)
```

`@Immutable` **regular class** (not `data class`); see §13. Companion:

- **`val Default: WaveConfig`**: a generic neutral preset. It ports the reference's blue-grey
  default (`baseColor 0xFF455A64`, `darkColor 0xFF263238`, `lightColor 0xFF90A4AE`) **via
  `WaveColors`**: `colors = WaveColors.gradient(Color(0xFF455A64), Color(0xFF263238))`, two layers
  matching the reference (`baseFrac 0.50/0.60`, `amplitude 0.03`, `speed 1.00/0.70`, `phaseOffset
  0.0/2.0`, `breathDepth 0.20`, `crests 0.75/0.85`, `harmonic 0.25`), depth-auto alpha,
  `shadow = ShadowMode.Auto`, `gradientEnd = 0.78f`.

- **`fun generate(waveCount: Int = 3, crests: Float = 1f, harmonic: Float = 0.25f, spacing: Float =
  1f, amplitude: Float = 0.03f, variation: Float = 0.4f, colors: WaveColors, shadow: ShadowMode =
  ShadowMode.Auto, gradientEnd: Float = 0.78f, seed: Int = 0, sway: Float = 1f): WaveConfig`**
  builds `waveCount` layers (coerced `≥ 1`), distributed automatically so the result holds together
  without hand-tuning each layer:
  - **auto-distributed `phaseOffset`** = an even part `(i / waveCount) * 2π` plus a random
    scatter — a horizontal crest stagger between layers. Under the drop-in's default ambient drift
    the per-layer `speed` also yields a gentle parallax; with `drift = 0` the stagger is purely
    static. (`generate()` no longer exposes a high-level `phaseSpread` knob to scale this; its
    effect was a barely-perceptible static crest reshuffle swamped by the phase jitter. The
    low-level `WaveLayerSpec.phaseOffset` is still there for power users who want to set it
    directly.);
  - **auto depth-based alpha** (§3.2), left `null` so the system assigns it;
  - **per-layer breathing and sway** with a clear depth (`breathDepth`/`breathSpeed` ramp
    back→front) and a **fully random `breathOffset`**, so the layers swell and lean on their own
    schedule, never pulsing together (breathing/sway carry most of the ambient motion, so they get
    per-layer variety);
  - **per-layer `tint` sampled from `colors`** (left `null` so `fillColorFor` samples the palette);
  - **`crests`, `harmonic`, and `amplitude`** applied to each layer, then jittered. Here `crests` is
    a **relative crest density** (`1` = baseline; higher = more, tighter crests), **not a literal
    crest count**; it scales the per-layer spatial frequency. `harmonic` is its **twin**, the crest
    **roughness** (`0` = clean rounded sine, higher = choppier/less regular crests via more
    second-harmonic weight; see §2.2/§5);
  - **vertical stacking via `spacing`**: `baseFrac` is spread around the canvas middle by `spacing`
    (`< 1` bunches the layers for more overlap, `> 1` separates them), with a small jitter on top;
  - **`gradientEnd`** passes straight through to the resulting `WaveConfig.gradientEnd` (coerced into
    `[GRADIENT_END_MIN, 1]`), so callers can set the background gradient end in one call instead of
    rebuilding a second `WaveConfig`;
  - **`sway`** passes straight through to `WaveConfig.sway` (coerced `≥ 0`): the config-wide
    crest-sway weight, with `0f` disabling the sway (the pre-0.2.0 waveform).

  **Per-layer jitter (`variation`, `seed`).** Every per-layer property gets a deterministic, seeded
  pseudo-random jitter scaled by `variation ∈ [0, 1]` (speed, amplitude, breathing, phase, crests,
  stacking), so the layers undulate **out of sync** instead of moving as one rigid block. The jitter
  is a **pure function of `seed`**, so the same `(seed, variation, …)` always yields the exact same
  configuration, deterministic for screenshot tests. `seed` is an **advanced** parameter: it sits
  **last** in the signature and should stay at its default `0` unless you specifically need a
  reproducible re-roll (a different layout) or to pin a screenshot. Set `variation = 0` to
  drop the jitter entirely (layers keep only the smooth back→front gradient in size, speed, and
  stacking). `variation` is coerced into `[0, 1]`, `spacing` to `≥ 0`.

---

## 13. `@Immutable`-not-`data` + `ImmutableList` + binary-compat rationale

- **Regular `@Immutable` classes, not `data class`es.** `data class` generates `copy()` and
  `componentN()` as part of the **public ABI**. Adding a constructor parameter later changes the
  `copy()` signature and `componentN()` count → **binary-incompatible**. By using regular classes we
  expose only named-constructor ergonomics plus explicit `withX` helpers where useful,
  keeping the public surface small and additive-friendly. `@Immutable` still tells the
  Compose compiler the type is stable.
- **`layers: ImmutableList<WaveLayerSpec>`** (kotlinx.collections.immutable). A plain `List` is not
  recognized as `@Stable` by Compose (it could be a mutable implementation), defeating recomposition
  skipping. `ImmutableList` lets `@Stable`/`@Immutable` hold and Compose **skip recomposition** when
  inputs are unchanged.
- **binary-compatibility-validator enabled**, `api/` dump **committed**. Any change to the public
  ABI must be a reviewed update of the dump.

---

## 14. Exact public API signatures (with KDoc-level descriptions)

All in package `red.rankorr.kwave`.

```kotlin
/**
 * Color strategy for the wave background and per-layer fills. Theme-free: the caller supplies all
 * colors. Built only through the factory functions below (no public constructor).
 *
 * Internally resolves the ordered background gradient stops, a per-layer fill color DERIVED from the
 * palette (never a hardcoded black), and a highlight color. Per-layer alpha is auto-assigned
 * by depth unless a [WaveLayerSpec.alpha] override is present.
 */
@Immutable
class WaveColors private constructor(/* internal */) {
    /** Copy with ONLY the background replaced by a flat [color]; the wave palette and highlight are
     *  untouched. Color.Transparent = waves-only mode: the renderer skips the background pass. */
    fun withBackground(color: Color): WaveColors

    /** Copy with ONLY the background replaced by a [top] → [bottom] vertical gradient. */
    fun withBackground(top: Color, bottom: Color): WaveColors

    /** Copy with ONLY the background replaced by the ordered multi-stop [stops] gradient.
     *  Empty ⇒ transparent (waves-only); single element ⇒ flat. */
    fun withBackground(stops: List<Color>): WaveColors

    companion object {
        /** Simple vertical auto-gradient from [top] (canvas top) to [bottom] (canvas bottom).
         *  When [top] == [bottom] the flat gradient routes to [solid] so the monochrome waves
         *  stay visible (a uniform fill would vanish into the same-color background). */
        fun gradient(top: Color, bottom: Color): WaveColors

        /**
         * Rainbow: each wave layer is tinted by sampling the palette at its depth (the rainbow rides
         * the wave FILLS). The BACKGROUND is a muted 2-stop wash darkened from the palette extremes,
         * not the full saturated palette, so the colorful waves stay the subject. Empty list ⇒
         * neutral fallback; single element ⇒ behaves like [solid].
         */
        fun palette(colors: List<Color>): WaveColors

        /** Single flat [color]. The per-layer fill RAMPS by depth (darker back → lighter front) so the
         *  same-color waves stay visible over the same-color background; auto per-layer alpha adds more
         *  separation. */
        fun solid(color: Color): WaveColors
    }
}

/**
 * How the diffuse cast shadow each wave projects on the content behind it is colored, per layer.
 * (The crest light is not controlled here: it lives in the body-fill gradient, tinted by
 * WaveColors.highlight.)
 */
sealed interface ShadowMode {
    /** DEFAULT. Per layer, pick black or white by the luminance of the local wave color
     *  (light wave ⇒ dark shadow, dark wave ⇒ light/back-glow shadow). */
    data object Auto : ShadowMode
    /** Shadow = the layer's color darkened (lerp toward Black ~0.6). */
    data object FromWave : ShadowMode
    /** No cast shadow. */
    data object None : ShadowMode
    /** Explicit shadow [color] for every layer; [alpha] (coerced [0,1]) is the shadow's total PEAK
     *  opacity and actually drives the rendered shadow (distributed across the stacked bands). */
    class Custom(val color: Color, val alpha: Float) : ShadowMode
}

/**
 * Advanced low-level specification for a single wave layer. All geometry is a fraction of canvas
 * size. Values are coerced into valid ranges at construction.
 *
 * @param baseFrac     vertical centre, fraction of height [0,1]. Default 0.5.
 * @param amplitude    peak displacement, fraction of height, >= 0. Default 0.03.
 * @param speed        multiplier on the caller's phase for this layer. Default 1.
 * @param phaseOffset  constant horizontal phase offset (radians). Default 0.
 * @param breathDepth  amplitude-breathing depth [0,1]. Default 0.2.
 * @param breathSpeed  breathing angular frequency (rad/s), >= 0. Default 0.25.
 * @param breathOffset breathing phase offset (radians). Default 0.
 * @param crests       primary spatial frequency (>= 0). Default 0.8.
 * @param harmonic     second-harmonic WEIGHT (>= 0). 0 ⇒ pure sine. Default 0.25.
 * @param alpha        per-layer opacity [0,1]; null ⇒ auto by depth. Default null.
 * @param tint         per-layer fill override; null ⇒ sampled from palette. Default null.
 */
@Immutable
class WaveLayerSpec(
    val baseFrac: Float = 0.5f,
    val amplitude: Float = 0.03f,
    val speed: Float = 1f,
    val phaseOffset: Float = 0f,
    val breathDepth: Float = 0.2f,
    val breathSpeed: Float = 0.25f,
    val breathOffset: Float = 0f,
    val crests: Float = 0.8f,
    val harmonic: Float = 0.25f,
    val alpha: Float? = null,
    val tint: Color? = null,
)

/**
 * Full configuration: ordered [layers] (back-to-front), [colors] strategy, [shadow] mode, the
 * vertical [gradientEnd] fraction coerced into [GRADIENT_END_MIN, 1] (default 0.78; the floor
 * ≈ 0.04 avoids a degenerate zero-span background gradient), and the config-wide [sway] weight
 * (coerced >= 0; 0f = no crest sway, the exact pre-0.2.0 waveform). Regular @Immutable class for
 * ABI stability.
 */
@Immutable
class WaveConfig(
    val layers: ImmutableList<WaveLayerSpec>,
    val colors: WaveColors,
    val shadow: ShadowMode = ShadowMode.Auto,
    val gradientEnd: Float = 0.78f,          // coerced [GRADIENT_END_MIN, 1]
    val sway: Float = 1f,                    // coerced >= 0; 0f = no sway
) {
    companion object {
        /** Generic neutral preset (ported blue-grey reference default, expressed via WaveColors). */
        val Default: WaveConfig

        /**
         * Builds [waveCount] (>= 1) auto-generated layers: a phaseOffset = even
         * distribution + scatter (static stagger; gentle parallax under the drop-in's drift), auto
         * depth-based alpha, per-layer breathing/sway, tint sampled from [colors], vertical
         * stacking spread by [spacing]. [crests] sets crest density and [harmonic]
         * sets crest roughness. Every per-layer property gets a deterministic seeded jitter scaled by
         * [variation] in [0, 1] (pure function of [seed]) so the layers desync; [variation] = 0 drops
         * the jitter.
         *
         * @param crests      RELATIVE crest density per layer (1 = baseline; higher = more/tighter
         *                    crests), NOT a literal crest count; then jittered. Default 1.
         * @param harmonic    crest ROUGHNESS, the twin of [crests]: 0 = clean rounded sine, higher =
         *                    choppier/less regular crests (2nd-harmonic weight); then jittered. Default 0.25.
         * @param spacing     vertical spread; < 1 overlaps layers more, > 1 separates them. Coerced >= 0.
         * @param variation   per-layer pseudo-random jitter amount in [0, 1]. Default 0.4.
         * @param gradientEnd background gradient end fraction, coerced [GRADIENT_END_MIN, 1]. Default 0.78.
         * @param seed        ADVANCED: deterministic jitter seed; leave at 0 unless you need a
         *                    reproducible re-roll or to pin a screenshot. Default 0.
         * @param sway        config-wide crest-sway weight, coerced >= 0; 0f disables the sway
         *                    (the pre-0.2.0 waveform). Default 1.
         */
        fun generate(
            waveCount: Int = 3,
            crests: Float = 1f,
            harmonic: Float = 0.25f,
            spacing: Float = 1f,
            amplitude: Float = 0.03f,
            variation: Float = 0.4f,
            colors: WaveColors,
            shadow: ShadowMode = ShadowMode.Auto,
            gradientEnd: Float = 0.78f,
            seed: Int = 0,
            sway: Float = 1f,
        ): WaveConfig
    }
}

/**
 * Drop-in animated wave background. Owns its animation loop (withFrameNanos): lifecycle-aware
 * (pauses below STARTED, resets on resume), honors system reduce-motion (one static frame when on),
 * and randomizes its initial phase per instance so multiple instances don't sync. Motion = per-layer
 * amplitude breathing + crest sway (§2.2) + slow ambient drift (parallaxed per layer by
 * WaveLayerSpec.speed). Internally the loop INTEGRATES the live rates per frame (§10):
 * time += Δ * speed, driftPhase += Δ * drift, phase = initialPhase + phaseShift + driftPhase —
 * so changing speed/drift live alters the tempo without snapping the accumulated position.
 *
 * @param config               wave configuration. Default [WaveConfig.Default].
 * @param modifier             layout modifier, HONORED as-is (pass Modifier.fillMaxSize() for full-bleed).
 * @param speed                breathing/sway-tempo multiplier (does NOT scale drift; freeze with
 *                             isPlaying). Default 1.
 * @param phaseShift           live external signal for DELIBERATE horizontal translation
 *                             (pager/scroll), read every frame. Default 0.
 * @param isPlaying            false freezes on the current frame and suspends the loop (zero frames
 *                             while frozen). Default true.
 * @param respectReducedMotion when true and system reduce-motion is on, render one static frame. Default true.
 * @param drift                ambient horizontal travel in radians of phase per second; 0f removes
 *                             the travel (sway remains; add WaveConfig.sway = 0f for the strict
 *                             pre-0.2.0 in-place look). Default 0.05.
 * @param maxFps               cap on the animation update rate (fps); <= 0 updates every display
 *                             frame. Skipped updates publish no state, so nothing re-renders. Default 0.
 */
@Composable
fun KWave(
    config: WaveConfig = WaveConfig.Default,
    modifier: Modifier = Modifier,
    speed: Float = 1f,
    phaseShift: Float = 0f,
    isPlaying: Boolean = true,
    respectReducedMotion: Boolean = true,
    drift: Float = 0.05f,
    maxFps: Float = 0f,
)

/**
 * Stateless / controlled wave background. Pure deterministic function of ([phase], [time]) with no
 * internal state, for screenshot tests and perfect external sync. Honors [modifier] as-is.
 *
 * @param phase horizontal phase applied to every layer (scaled per-layer by [WaveLayerSpec.speed]).
 *              Hold constant for in-place motion, advance slowly for drift, or drive it (e.g. a
 *              pager offset) for deliberate horizontal translation.
 * @param time  continuous elapsed seconds driving per-layer amplitude breathing and crest sway.
 */
@Composable
fun KWave(
    config: WaveConfig,
    phase: Float,
    time: Float,
    modifier: Modifier = Modifier,
)
```

---

## 15. Test contract (what the test agent must cover)

**`commonTest` (kotlin-test, JVM, no UI):**
- Geometry purity at `phase = 0`, `time = 0` (stable expected Y for a known layer).
- Breathing returns to nominal when the `sin` term is `0`.
- Crest sway: a breathing layer sways over time (y differs at two instants of equal breathing
  amplitude); a `breathDepth = 0` layer is **fully time-invariant** (no sway, no breathing);
  `swayScale = 0` (i.e. `WaveConfig.sway = 0f`) removes the sway while keeping the breathing alive.
- Long-running precision: a single 60 fps frame still advances the breathing after ~11 days of
  continuous runtime (double-precision arguments, §2.2).
- `WaveConfig.sway` coercion (negative → 0, default 1) and `generate(sway = …)` pass-through.
- `generate()` coerces `waveCount >= 1` and distributes `phaseOffset` across layers.
- `N = 0` and `N = 1` never throw (the per-layer FX pass needs no cross-layer indexing).
- Coercion of extreme inputs: `baseFrac > 1`, negative `amplitude`, `breathDepth > 1`.
- `WaveColors` factories (`gradient` / `palette` / `solid`) resolve the expected background stops
  and per-layer fills (palette-sampled, never black).
- `withBackground` replaces only the background stops (fills/highlight untouched), follows the
  documented list coercion, and a fully transparent background reports `hasVisibleBackground =
  false` (the renderer's skip condition; the waves-only rendering itself is locked by the
  `kwave_waves_only` golden).
- `ShadowMode.Auto` luminance pick: light wave ⇒ dark shadow, dark wave ⇒ light shadow.

**`androidUnitTest` (Robolectric `@GraphicsMode NATIVE`, `@Config sdk = 34`, Roborazzi):**
- Golden screenshots of: `Default`, gradient-simple, rainbow-palette, `N = 2`, `N = 5`, all via
  the **stateless** overload at fixed `(phase, time)`.
- **Theme-free capture wrapper** (neutral `Surface`, **no app theme**; the lib reads no
  `MaterialTheme`). Adapt `captureWave(name, config, phase, time)` from a reference `ScreenshotHelper`
  minus any app theme.

---

## 16. Non-goals / explicit exclusions

- **No `material3`** in the library core. The sample may use it for its UI chrome, but the library
  must compile and render with no `MaterialTheme` present.
- **No `productFlavors`** (app-only concept).
- **No forced iOS framework `isStatic`.**
- The **`sample`** module (Compose Desktop/JVM) is **not published** and is **excluded from
  publishing**.
- **Top-anchored / upward fill is out of scope for 0.1.0.** The fill is always **bottom-anchored**
  today (waves rise from the bottom). A vertical flip / top-anchor option (waves anchored to the
  **top**, filling **upward**) is a **planned 1.x feature**, not yet implemented.
