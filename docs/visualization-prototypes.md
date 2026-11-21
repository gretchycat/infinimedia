# Visualization prototypes and FX pipeline

Infinimedia keeps a small set of player-owned visualization prototypes and now
hosts a reusable effects graph from libinfinifx. The app owns presentation,
bitmap sources, metadata adaptation, and GLES lifecycle; the library owns
reusable effects and graph execution.

## Retained prototypes

- `VisualizerView` draws the segmented 16-band spectrum in the player console
  and as a small overlay in the visual-media pane. Its layout and icon spacing
  remain unchanged.
- `WinampMainWindowView` can switch among spectrum, oscilloscope, and VU modes.
- `RealAudioVisualizerBridge` reads decoded PCM through libinfinisound's shared
  analyzer. The views do not decode or analyze audio independently.
- Album artwork, still and animated images, video, ANSI output, and ANSI Music
  retain their existing media-presentation paths.

## libinfinifx host pipeline

`modules/viewer-audio` pins libinfinifx through `dependencies.lock.json` and
includes its source checkout as a standalone Gradle composite build. The
app-owned `FxArtworkView` creates `GlesFxEngine` on a GLES 3 render thread,
uploads a caller-owned bitmap to `GL_TEXTURE_2D`, supplies monotonic timing and
source/destination geometry, and renders into a transparent output surface. The
original artwork remains underneath as a fallback if the FX renderer fails.

`RandomFxPipeline` currently holds two sequential effect stages. On each
changed visual playlist item it chooses one stage at random and replaces it with
a different available effect ID. The options are CRT, LCD, water refraction,
heat ripple, album-art rotozoom, kaleidoscope, and the independent fire generator
followed by an explicit alpha blend. This is temporary testing behavior, not a
selection UI; deliberate user/app selection can later replace this policy while
retaining the public graph/effect contracts. Effect IDs and parameter schemas
come from libinfinifx.

The host maps the existing smoothed 16 spectrum bands to normalized low/mid/high
energy and uses the existing VU RMS/peak values. It does not decode audio again
or generate onset events. Rotozoom responds continuously to RMS/bass energy; a
host-supplied timestamped onset/beat event adds a short zoom and rotation pulse.
The library consumes each stable event ID once. Infinimedia's current analyzer
adapter supplies levels only, so beat-triggered pulses are ready at the library
boundary but are not yet driven by this app. Missing or stale audio metadata
leaves autonomous effects animated and audio-only modulation at its neutral
value.

FX currently runs for audio artwork and static image bitmaps. Video remains on
its existing playback renderer because its current output uses
`GL_TEXTURE_EXTERNAL_OES`; libinfinifx currently accepts `GL_TEXTURE_2D` only.
Animated image, ANSI, and ANSI Music also bypass the FX overlay. These paths
remain on their existing renderers; the library is packaged as an app dependency
and does not need a separate Android application installation.

## Removed simulation

The app no longer contains the previous live CPU heat/smoke simulation, wind and
lightning effects, shared mutable simulation fields, or their OpenGL overlay.
Heat ripple and fire are now independent built-ins in libinfinifx; the app does
not maintain shared effect simulation state.

## Verification status

On 2026-11-20, the standalone Termux build passed with
`ANDROID_HOME="$HOME/android-sdk" ./build.sh --no-daemon --max-workers=1 --console=plain`.
It assembled the debug APK and passed all 118 JVM unit tests, including both
`FxPipelineTest` cases. The blocking native version guard was fixed by updating
the pinned Termux inputs to installed libopenmpt 0.8.10 and libc++ 30 in
libinfinisound commit `3412205da2501248a0c89f5ab9402049fc89cfd9`, and pinning
that commit in this app. This verifies compilation and JVM behavior only; no
Android device visual or performance acceptance has been run. Shader/runtime
appearance and transparent surface composition still need GLES device
verification. See [app architecture](architecture.md) and the
[libinfinifx effect guide](https://github.com/infiniforge/libinfinifx/blob/main/docs/effects.md).
