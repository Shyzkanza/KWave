# KWave — Public API Reference

> Concise reference of every public symbol. See [`DESIGN.md`](./DESIGN.md) for the full narrative,
> rendering math, and behavior contracts. This list IS the binary-compatibility surface (validated
> by binary-compatibility-validator; `api/` dump committed).

- **Package / namespace:** `red.rankorr.kwave`
- **Coordinates:** `red.rankorr:kwave:0.1.0-SNAPSHOT`
- All public config types are `@Immutable` **regular classes** (not `data class`es) for ABI stability.

---

## `WaveColors`  — `@Immutable class` (no public constructor)

Built only via factories. Resolves background gradient stops, per-layer fill (palette-derived,
never hardcoded black), and a highlight color. Per-layer alpha is auto by depth unless overridden.

| Symbol | Signature | Notes |
|--------|-----------|-------|
| `gradient` | `fun gradient(top: Color, bottom: Color): WaveColors` | simple vertical auto-gradient top→bottom |
| `palette` | `fun palette(colors: List<Color>): WaveColors` | rainbow on the **wave fills** (layers tinted by depth sample); **background** is a muted 2-stop wash darkened from the palette extremes. Empty ⇒ neutral fallback; single ⇒ like `solid` |
| `solid` | `fun solid(color: Color): WaveColors` | single flat color; fill ramps by depth (darker back → lighter front) so same-color waves stay visible, plus auto alpha. `gradient(a, a)` routes here |

---

## `ShadowMode`  — `sealed interface`

Controls per-layer depth shadow band + luminous highlight lip (highlight = inverted shadow logic).

| Symbol | Signature | Notes |
|--------|-----------|-------|
| `Auto` | `data object Auto : ShadowMode` | **DEFAULT.** Black or white per layer by local wave-color luminance (light ⇒ dark shadow, dark ⇒ light shadow) |
| `FromWave` | `data object FromWave : ShadowMode` | shadow = layer color lerped toward Black ~0.6; highlight = it lightened |
| `None` | `data object None : ShadowMode` | no shadow band, no highlight lip |
| `Custom` | `class Custom(val color: Color, val alpha: Float) : ShadowMode` | explicit color + alpha (coerced `[0,1]`) for every layer |

---

## `WaveLayerSpec`  — `@Immutable class` (advanced/low-level)

All values coerced into valid ranges at construction.

```kotlin
class WaveLayerSpec(
    val baseFrac: Float = 0.5f,       // vertical centre, fraction of height [0,1]
    val amplitude: Float = 0.03f,     // peak displacement, fraction of height, >= 0
    val speed: Float = 1f,            // multiplier on caller phase for this layer
    val phaseOffset: Float = 0f,      // constant horizontal phase offset (radians)
    val breathDepth: Float = 0.2f,    // amplitude-breathing depth [0,1]
    val breathSpeed: Float = 0.25f,   // breathing angular frequency (rad/s), >= 0
    val breathOffset: Float = 0f,     // breathing phase offset (radians)
    val crests: Float = 0.8f,         // primary spatial frequency (ex-c1), >= 0
    val harmonic: Float = 0.25f,      // 2nd-harmonic WEIGHT, >= 0; 0 ⇒ pure sine
    val alpha: Float? = null,         // per-layer opacity [0,1]; null ⇒ auto by depth
    val tint: Color? = null,          // per-layer fill override; null ⇒ sampled from palette
)
```

---

## `WaveConfig`  — `@Immutable class`

```kotlin
class WaveConfig(
    val layers: ImmutableList<WaveLayerSpec>,        // kotlinx.collections.immutable
    val colors: WaveColors,
    val shadow: ShadowMode = ShadowMode.Auto,
    val gradientEnd: Float = 0.78f,                  // coerced [GRADIENT_END_MIN, 1] (floor ≈ 0.04)
)
```

### Companion

| Symbol | Signature | Notes |
|--------|-----------|-------|
| `Default` | `val Default: WaveConfig` | generic neutral preset (ported blue-grey reference default, via `WaveColors.gradient`) |
| `generate` | see below | builds `waveCount` layers; coerces `waveCount >= 1` |

```kotlin
fun generate(
    waveCount: Int = 3,     // number of layers, coerced to >= 1
    crests: Float = 1f,     // RELATIVE crest density per layer (1 = baseline, NOT a literal count); then jittered
    harmonic: Float = 0.25f,// crest ROUGHNESS — 0 = clean rounded sine, higher = choppier (2nd-harmonic weight); then jittered
    spacing: Float = 1f,    // vertical spread; < 1 overlaps layers more, > 1 separates them
    amplitude: Float = 0.03f,// base peak displacement (then jittered per layer)
    variation: Float = 0.4f,// per-layer pseudo-random jitter in [0, 1]; 0 = smooth/uniform
    colors: WaveColors,
    shadow: ShadowMode = ShadowMode.Auto,
    gradientEnd: Float = 0.78f, // background gradient end fraction, coerced [GRADIENT_END_MIN, 1]
    seed: Int = 0,          // ADVANCED — deterministic jitter seed; leave 0 unless pinning a re-roll/screenshot
): WaveConfig
// auto-distributed static phaseOffset, auto depth-alpha, per-layer breathing, tint sampled from
// colors. Every per-layer property gets a deterministic seeded jitter scaled by `variation` so
// layers desync; the jitter is a pure function of `seed`. `variation = 0` drops it. `crests` is a
// relative density (1 = baseline), not a literal crest count; `harmonic` is its twin — the crest
// roughness (0 ⇒ pure sine). The low-level WaveLayerSpec.phaseOffset still exists for power users;
// generate() no longer exposes a high-level phaseSpread param.
```

---

## Composables

### Auto / drop-in (owns the animation loop)

```kotlin
@Composable
fun KWave(
    config: WaveConfig = WaveConfig.Default,
    modifier: Modifier = Modifier,        // honored as-is; pass Modifier.fillMaxSize() for full-bleed
    speed: Float = 1f,                    // breathing-tempo multiplier (how fast layers bob in place)
    phaseShift: Float = 0f,               // live external signal for deliberate horizontal translation
                                          //   (pager/scroll), read every recomposition
    isPlaying: Boolean = true,            // false freezes current frame
    respectReducedMotion: Boolean = true, // + system reduce-motion on ⇒ one static frame
)
```

Internal accumulator: `phase = initialPhase + phaseShift`, `time = elapsed * speed`. The ambient
`phase` is **held constant** — no horizontal drift; the only ambient motion is the per-layer
amplitude **breathing** driven by `time`, so the surface oscillates in place. `speed` is the
breathing/bob tempo. `phaseShift` is a live external signal that translates the waves horizontally
on purpose (e.g. a pager offset). `initialPhase` is a per-instance random constant (this overload
only) so multiple instances don't breathe in lockstep. Lifecycle-aware (pause below STARTED, reset
`lastNanos` on resume). Delegates to the stateless overload.

### Stateless / controlled (pure, deterministic)

```kotlin
@Composable
fun KWave(
    config: WaveConfig,
    phase: Float,                         // horizontal phase of every layer (constant for in-place
                                          //   breathing, or driven for deliberate translation)
    time: Float,                          // elapsed seconds (amplitude breathing)
    modifier: Modifier = Modifier,        // honored as-is
)
```

No internal state / no randomization — identical inputs ⇒ identical pixels (screenshot tests,
external sync).

---

## Behavior invariants (binding)

- `modifier` is **honored as-is** in both overloads — renderer uses `Canvas(modifier)`, **never**
  chains `.fillMaxSize()`.
- Zero-size guard: renderer returns when `size.minDimension <= 0`.
- Per-layer fill is **palette-derived**, never a hardcoded `Color.Black`.
- `harmonic = 0f` ⇒ pure sine for that layer.
- Depth FX applies to `layers.dropLast(1)`; **safe at N=0 and N=1** (no crash, no IOOB).
- All public config values **coerced into valid ranges** at construction (see `DESIGN.md` §6).
