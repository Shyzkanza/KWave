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
crest highlight. Under the drop-in composable the waves **oscillate in place** (each layer
swells and recedes at its own rate) rather than drifting sideways; horizontal
translation is an opt-in external signal (§10). It is theme-free: it reads no `MaterialTheme`, all
colors are supplied through the public `WaveColors` API.

It ships **two composable entry points**:

1. **`KWave(config, modifier, speed, phaseShift, isPlaying, respectReducedMotion)`**, the
   drop-in / auto composable. It owns its own animation loop (`withFrameNanos`), is
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
| `SHADOW_ALPHA` | `0.28f` | peak alpha of the soft depth shadow band |
| `LIGHT_ALPHA` | `0.16f` | peak alpha of the highlight lip |
| `SOFT_UP_FRAC` | `0.030f` | vertical softening of the highlight band, fraction of height |
| `SOFT_DOWN_FRAC` | `0.055f` | vertical softening of the shadow band, fraction of height |
| `FROM_WAVE_DARKEN` | `0.6f` | lerp fraction toward `Color.Black` for `ShadowMode.FromWave` |

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
t   = x / width
tau = 2 * PI
ph  = phase * layer.speed + layer.phaseOffset

y1  = layer.crests * tau * t + ph
y2  = layer.harmonic_freq * tau * t + ph * HARMONIC_2_PHASE_MUL + 1f

waveYAt(x) = height * layer.baseFrac
           + layerAmp(layer, time) * ( sin(y1) + layer.harmonic * sin(y2) )
```

- `layer.crests` is the **primary spatial frequency** (ex-`c1`): how many crest groups span the
  width. Renamed for caller clarity.
- The **second-harmonic spatial frequency** (ex-`c2`) is an internal renderer concern. The public
  `harmonic` field controls the **weight** of the second harmonic, not its frequency. The renderer
  derives the harmonic frequency internally (default `crests * 2f`, matching the reference ratio of
  roughly 2×). `harmonic = 0f` ⇒ the `+ layer.harmonic * sin(y2)` term vanishes ⇒ **pure sine**.
- `+ 1f` on `y2` is the reference's fixed harmonic phase bias, preserved.

### 2.3 Filled regions

- **`regionBelow(layer, phase, time)`**: polyline of `WAVE_SAMPLES + 1` points along the crest,
  then closed down to `(width, height)` → `(0, height)`. This is the layer's fill + shadow region.
- **`regionAbove(layer, phase, time)`**: from `(0,0)` → `(width,0)` then back across the crest
  (sampled `WAVE_SAMPLES downTo 0`), closed. This is the highlight-lip region.

### 2.4 Draw order (back-to-front)

1. **Background gradient:** vertical gradient over the ordered `WaveColors` stops, from `startY = 0`
   to `endY = height * config.gradientEnd`.
2. **Per-layer fill:** for each layer in order, draw `regionBelow` filled with the layer's
   **palette-derived fill color** (see §3) at the layer's resolved alpha.
3. **Depth FX (`dropLast(1)`):** for every layer **except the last (front-most)**, draw:
   - a **shadow band** (`regionBelow`): see §4 for color, vertical gradient fading to transparent
     from `baseY - ampMax` to `baseY + ampMax + height * SOFT_DOWN_FRAC`;
   - a **highlight lip** (`regionAbove`): see §4, vertical gradient from transparent up to the
     highlight color, from `baseY - ampMax - height * SOFT_UP_FRAC` to `baseY + ampMax`.

   where `baseY = height * layer.baseFrac` and `ampMax = height * layer.amplitude * (1f + layer.breathDepth)`.

The `dropLast(1)` is a depth effect: the front-most layer gets only a solid fill
(it is the foreground "water surface"), while the layers behind it get the shadow + highlight
that create edge-less depth. See §8 for the N=0 / N=1 safety contract.

---

## 3. Color model: `WaveColors`

`WaveColors` is an `@Immutable` **regular class** (not a `data class`) with **no public
constructor**. It is built only through factory functions. Internally it resolves three things:

- **`backgroundStops: List<Color>`**: ordered gradient stops for the canvas background.
- **`fillColorFor(layerIndex, layerCount): Color`**: the per-layer fill color, **derived from the
  palette** by sampling at the layer's normalized depth. **It is never a hardcoded `Color.Black`.**
  (This is the main correction over the reference, which always filled with `Color.Black`.)
- **`highlight: Color`**: the lip color (reference's `lightColor` analog).

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

`ShadowMode` is a **sealed interface**. It controls both the **depth shadow band** below each crest
and the **highlight lip** above it (the highlight uses the inverted logic of the shadow).

- **`ShadowMode.Auto`** (DEFAULT). For each layer, pick **black or white** by the **luminance**
  of that layer's local wave (fill) color: a **light** wave color gets a **dark (black)** shadow; a
  **dark** wave color gets a **light (white)** shadow. The highlight lip uses the opposite of the
  shadow: light-wave-gets-light-highlight is too subtle, so the highlight is the brighter of
  black/white. For a dark wave the highlight is white, for a light wave the highlight leans to the
  layer's own highlight color. Luminance threshold uses standard relative luminance
  (`0.2126*r + 0.7152*g + 0.0722*b`) with a `0.5` cutoff.
- **`ShadowMode.FromWave`**: the shadow is **that layer's color darkened**: `lerp(layerColor,
  Color.Black, FROM_WAVE_DARKEN)` with `FROM_WAVE_DARKEN ≈ 0.6f`. The highlight is the same layer
  color **lightened** by the inverse amount.
- **`ShadowMode.None`**: no shadow band and no highlight lip are drawn (only the flat per-layer
  fills + background gradient). `dropLast(1)` still applies structurally but draws nothing.
- **`ShadowMode.Custom(color: Color, alpha: Float)`**: explicit shadow color/alpha for every
  layer's shadow band; `alpha` is coerced into `[0,1]`. The supplied `alpha` is the band's **peak
  opacity** (it actually drives the rendered band): the renderer applies it via `shadowPeakAlpha`
  when painting, rather than baking it into the resolved color, so it is not lost to the band
  gradient's own top alpha stop. The highlight lip uses the same color with the inverse-luminance
  treatment.

The shadow band always **fades to transparent**: its peak is `ShadowMode.Custom.alpha` for a custom
shadow and `SHADOW_ALPHA` otherwise, fading to base `0f`. The highlight always **fades from
transparent up to peak** (`LIGHT_ALPHA`), per the vertical-gradient geometry of §2.4.

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

## 8. `dropLast(1)` depth-FX safety contract

The depth FX (shadow + highlight) is applied to `layers.dropLast(1)`, every layer except the
front-most. This must be well-defined at all layer counts:

- **N = 0** (empty layer list): the background gradient draws; the per-layer loop and the
  `dropLast(1)` loop both iterate zero times. **No crash, no index-out-of-bounds.** (`WaveConfig`
  permits an empty layer list, though `generate()` always produces `≥ 1`.)
- **N = 1**: the single layer is the front-most → `dropLast(1)` is empty → it receives a flat fill
  only, no shadow/highlight. **No crash.**
- **N ≥ 2**: layers `0..n-2` receive depth FX; layer `n-1` (front) receives flat fill only.

`dropLast(1)` is preferred over indexed access (`layers[i]` / `layers.size - 1`) precisely because
it is **inherently IOOB-safe** for N=0 and N=1. This behavior is **explicit and documented in
KDoc** on the renderer, and **tested** (N=0 and N=1 never throw).

---

## 9. Performance contract

- **Cached paths:** the renderer `remember`s a `List<Path>` keyed on **layer count**; each frame it
  `rewind()`s each path and re-fills it, rather than allocating new `Path` instances per frame.
- **Single path per layer per frame:** `regionBelow` is built once and reused for the fill draw and
  the shadow-band draw. `regionAbove` is built once for the highlight.
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
    speed: Float = 1f,                    // breathing-tempo multiplier (how fast layers bob in place)
    phaseShift: Float = 0f,               // LIVE external signal for deliberate horizontal translation
                                          //   (pager/scroll), read every recomposition
    isPlaying: Boolean = true,
    respectReducedMotion: Boolean = true,
)
```

**Motion model: in-place breathing, no horizontal drift.** The ambient horizontal `phase` is
**held constant**; the surface never slides sideways. The only ambient motion is the per-layer
amplitude **breathing**, each layer swelling and receding at its own (config-driven) rate, smoothly
(sinusoidal, no abrupt reversals).

**Animation accumulator.** An internal `withFrameNanos` loop accumulates `elapsed` seconds, then:

```
phase = initialPhase + phaseShift   // ambient phase is constant: NO horizontal travel
time  = elapsed * speed             // drives the per-layer amplitude breathing (the visible motion)
```

- `initialPhase` is a per-instance random constant (this overload only; see below).
- `speed` is the **breathing/bob tempo** multiplier: it scales how fast the layers swell in place,
  **not** a drift speed.
- `phaseShift` is a **live external signal for deliberate horizontal translation**, read on **every
  recomposition** (so a pager/scroll offset can purposely shift the wave's horizontal phase without
  restarting the loop). With `phaseShift = 0` the waves only breathe in place.

Internally this delegates to the **stateless** overload with the computed `(phase, time)`.

**Per-instance randomized initial phase.** A `remember { /* random seed */ }` adds a random
constant to the initial phase **only in this auto overload**, so two `KWave`s on the same screen do
**not** breathe in lockstep. The **stateless overload never randomizes**; it must stay deterministic.

**Lifecycle awareness.** Using `lifecycle-runtime-compose`, the loop **pauses when the lifecycle is
below `STARTED`** (app backgrounded / screen not resumed). On resume, `lastNanos` is **reset** so
the accumulator does **not** jump forward by the elapsed background time (no visual snap).

**`isPlaying = false`** freezes the animation on the current frame (the loop suspends; the last
`(phase, time)` keeps rendering).

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
  `WaveLayerSpec.speed`). Hold it **constant** for in-place breathing (the way the drop-in uses it),
  or drive it freely (e.g. a pager offset) for **horizontal translation**. `time` is
  the continuous elapsed seconds that drive the per-layer amplitude breathing.
- Honors `modifier` (no forced `fillMaxSize`); applies the zero-size guard; renders per §2/§3/§4.
- This is the function the auto overload delegates to.

---

## 12. `WaveConfig`

```kotlin
@Immutable
class WaveConfig(
    val layers: ImmutableList<WaveLayerSpec>,
    val colors: WaveColors,
    val shadow: ShadowMode = ShadowMode.Auto,
    val gradientEnd: Float = 0.78f,          // coerced [GRADIENT_END_MIN, 1] (floor ≈ 0.04)
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
  ShadowMode.Auto, gradientEnd: Float = 0.78f, seed: Int = 0): WaveConfig`** builds `waveCount`
  layers (coerced `≥ 1`), distributed automatically so the result holds together
  without hand-tuning each layer:
  - **auto-distributed static `phaseOffset`** = an even part `(i / waveCount) * 2π` plus a random
    scatter. This is a **static horizontal crest stagger between layers, not motion**: the drop-in
    holds the ambient phase constant, so it never animates. (`generate()` no longer exposes a
    high-level `phaseSpread` knob to scale this; its effect was a barely-perceptible static crest
    reshuffle swamped by the phase jitter. The low-level `WaveLayerSpec.phaseOffset` is still there
    for power users who want to set it directly.);
  - **auto depth-based alpha** (§3.2), left `null` so the system assigns it;
  - **per-layer breathing** with a clear depth (`breathDepth`/`breathSpeed` ramp back→front) and a
    **fully random `breathOffset`**, so the layers swell on their own schedule, never pulsing
    together (breathing is now the only visible ambient motion, so it is given per-layer variety);
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
    rebuilding a second `WaveConfig`.

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
 * How depth shadow bands and highlight lips are colored, per layer.
 */
sealed interface ShadowMode {
    /** DEFAULT. Per layer, pick black or white by the luminance of the local wave color
     *  (light wave ⇒ dark shadow, dark wave ⇒ light shadow); highlight uses the inverted pick. */
    data object Auto : ShadowMode
    /** Shadow = the layer's color darkened (lerp toward Black ~0.6); highlight = it lightened. */
    data object FromWave : ShadowMode
    /** No shadow band and no highlight lip. */
    data object None : ShadowMode
    /** Explicit shadow [color] for every layer; [alpha] (coerced [0,1]) is the band's PEAK opacity
     *  and actually drives the rendered band (applied at paint time, not baked into the color). */
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
 * Full configuration: ordered [layers] (back-to-front), [colors] strategy, [shadow] mode, and the
 * vertical [gradientEnd] fraction coerced into [GRADIENT_END_MIN, 1] (default 0.78; the floor
 * ≈ 0.04 avoids a degenerate zero-span background gradient). Regular @Immutable class for ABI
 * stability.
 */
@Immutable
class WaveConfig(
    val layers: ImmutableList<WaveLayerSpec>,
    val colors: WaveColors,
    val shadow: ShadowMode = ShadowMode.Auto,
    val gradientEnd: Float = 0.78f,          // coerced [GRADIENT_END_MIN, 1]
) {
    companion object {
        /** Generic neutral preset (ported blue-grey reference default, expressed via WaveColors). */
        val Default: WaveConfig

        /**
         * Builds [waveCount] (>= 1) auto-generated layers: a STATIC phaseOffset = even
         * distribution + scatter, auto depth-based alpha, per-layer breathing, tint sampled from
         * [colors], vertical stacking spread by [spacing]. [crests] sets crest density and [harmonic]
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
        ): WaveConfig
    }
}

/**
 * Drop-in animated wave background. Owns its animation loop (withFrameNanos): lifecycle-aware
 * (pauses below STARTED, resets on resume), honors system reduce-motion (one static frame when on),
 * and randomizes its initial phase per instance so multiple instances don't sync. The ambient phase
 * is held CONSTANT (no horizontal drift); the only ambient motion is the per-layer amplitude
 * breathing (the waves oscillate in place). Internally: phase = initialPhase + phaseShift,
 * time = elapsed * speed.
 *
 * @param config               wave configuration. Default [WaveConfig.Default].
 * @param modifier             layout modifier, HONORED as-is (pass Modifier.fillMaxSize() for full-bleed).
 * @param speed                breathing-tempo multiplier (how fast layers bob in place). Default 1.
 * @param phaseShift           live external signal for DELIBERATE horizontal translation
 *                             (pager/scroll), read every recomposition. Default 0.
 * @param isPlaying            false freezes on the current frame. Default true.
 * @param respectReducedMotion when true and system reduce-motion is on, render one static frame. Default true.
 */
@Composable
fun KWave(
    config: WaveConfig = WaveConfig.Default,
    modifier: Modifier = Modifier,
    speed: Float = 1f,
    phaseShift: Float = 0f,
    isPlaying: Boolean = true,
    respectReducedMotion: Boolean = true,
)

/**
 * Stateless / controlled wave background. Pure deterministic function of ([phase], [time]) with no
 * internal state, for screenshot tests and perfect external sync. Honors [modifier] as-is.
 *
 * @param phase horizontal phase applied to every layer (scaled per-layer by [WaveLayerSpec.speed]).
 *              Hold constant for in-place breathing, or drive it (e.g. a pager offset) for deliberate
 *              horizontal translation.
 * @param time  continuous elapsed seconds driving per-layer amplitude breathing.
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
- `generate()` coerces `waveCount >= 1` and distributes `phaseOffset` across layers.
- `N = 0` and `N = 1` never throw; `dropLast` is safe (no IOOB).
- Coercion of extreme inputs: `baseFrac > 1`, negative `amplitude`, `breathDepth > 1`.
- `WaveColors` factories (`gradient` / `palette` / `solid`) resolve the expected background stops
  and per-layer fills (palette-sampled, never black).
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
