# KWave gallery

Animated previews and preset stills. Back to the [README](../README.md).

> These GIFs are short looped previews: they are sped up and use a back-and-forth (ping-pong) loop,
> so the motion can look a bit abrupt at the loop seam. The live animation is slow and continuous:
> the layers breathe, their crests sway gently, and the surface drifts sideways with a touch of
> parallax.

## Animated, landscape

| Rainbow | Ocean | Sunset |
|:---:|:---:|:---:|
| ![Rainbow landscape](screenshots/wave-rainbow-h.gif) | ![Ocean landscape](screenshots/wave-ocean-h.gif) | ![Sunset landscape](screenshots/wave-sunset-h.gif) |

## Animated, portrait

| Rainbow | Ocean | Sunset |
|:---:|:---:|:---:|
| ![Rainbow portrait](screenshots/wave-rainbow-v.gif) | ![Ocean portrait](screenshots/wave-ocean-v.gif) | ![Sunset portrait](screenshots/wave-sunset-v.gif) |

These weave-style previews use a tight stack (`spacing = 0.4`), a lower `amplitude`, and the
per-layer phase offset so the wave lines cross each other. See the `generateGif` task in
`sample/build.gradle.kts` for the exact `WaveConfig.generate(...)` call.

## Presets (stills)

| Default | Two-color gradient | Rainbow palette |
|:---:|:---:|:---:|
| ![Default](screenshots/default.png) | ![Gradient](screenshots/gradient.png) | ![Rainbow](screenshots/rainbow.png) |
| **Solid color** | **`FromWave` shadow** | **`Custom` shadow** |
| ![Solid](screenshots/solid.png) | ![FromWave](screenshots/shadow-fromwave.png) | ![Custom](screenshots/shadow-custom.png) |

## Regenerate

```bash
./gradlew :sample:generateGif          # the animated GIFs (docs/screenshots/wave-*.gif)
./gradlew :kwave:recordRoborazziDebug  # the preset stills
```
