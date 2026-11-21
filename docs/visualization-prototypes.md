# Visualization prototypes and FX pipeline

Infinimedia keeps a small set of player-owned visualization prototypes and now
hosts a reusable effects graph from libinfinifx. The app owns presentation,
bitmap sources, metadata adaptation, and GLES lifecycle; the library owns
reusable effects and graph execution.

## Retained prototypes

- `VisualizerView` remains the segmented 16-band spectrum prototype in the
  player console and classic Winamp modes. In the standard audio-artwork pane,
  a full-width instance remains visible until an InfiniFX frame successfully
  swaps to the TextureView surface, and returns if rendering fails. Its quiet
  baseline stays visible before audio analysis arrives. A randomized graph
  failure retries a minimal lightning-only libinfinifx graph; diagnostics record
  the original error, surface readiness, and successful frame swaps.
- `WinampMainWindowView` can switch among spectrum, oscilloscope, and VU modes.
- `RealAudioVisualizerBridge` reads decoded PCM through libinfinisound's shared
  analyzer. The views do not decode or analyze audio independently.
- Album artwork, still and animated images, video, ANSI output, and ANSI Music
  retain their existing media-presentation paths.

## libinfinifx host pipeline

`modules/viewer-audio` pins libinfinifx through `dependencies.lock.json` and
includes its source checkout as a standalone Gradle composite build. The
app-owned `FxArtworkView` is a `TextureView` with a private EGL/GLES 3 render
thread. It uploads a caller-owned bitmap to `GL_TEXTURE_2D`, supplies monotonic
timing and source/destination geometry, and renders into the TextureView surface.
TextureView participates in normal Android child ordering, so the processed
artwork can sit between the underlying album-art ImageView and the existing
title/spectrum overlays. The spectrum fallback is hidden only after the first
successful `eglSwapBuffers`. This replaces a SurfaceView configured as a media
overlay, whose special surface z-order could place it behind ordinary content
in the host window ([Android SurfaceView documentation](https://developer.android.com/reference/android/view/SurfaceView)). If a randomized effect graph fails, the host retries the lightning generator alone through libinfinifx and logs the original error. The original artwork remains visible underneath the transparent output; the full-width spectrum prototype stays available until a rendered frame has successfully swapped and returns if the lightning-only graph also fails.

`RandomFxPipeline` currently holds two sequential image-effect stages. On each
changed visual playlist item it chooses one stage at random and replaces it with
a different available effect ID. Each graph then adds an independent
`generator:lightning-spectrum` branch and alpha-blends it over the processed
artwork. Its 16 colored electric bars span the entire output width and use the
current normalized audio bands; with missing/silent bands, a low autonomous
baseline remains visible. The segmented prototype is also full-width and keeps a
quiet baseline while acting as the first-frame or error fallback. The options are CRT, LCD, water refraction, heat ripple, album-art rotozoom,
kaleidoscope, and the independent fire generator
followed by an explicit alpha blend. This is temporary testing behavior, not a
selection UI; deliberate user/app selection can later replace this policy while
retaining the public graph/effect contracts. Tapping the visual pane reveals
**EDIT FX** beside the full-screen button for the same four-second overlay
window. It adjusts gain, maximum height, spacing, glow, and idle baseline,
applying changes to the active graph as the sliders move. The editor starts with a 14% autonomous height floor so the bars remain visible with a silent or missing band
snapshot. Renderer status, band levels, and errors are recorded in the app's
`DiagnosticLog` under `audio.fx.*`. Effect IDs and parameter schemas come from
libinfinifx.

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

On 2026-11-20, after pinning libinfinifx commit
`7bc9ae8b115ba54b469c765bd5d34f77deb46f5c`, the standalone Termux command
`./build.sh --no-daemon --max-workers=1 --console=plain` completed successfully.
It assembled `InfiniMedia-debug.apk` and passed all 121 JVM unit tests, including
the five `FxPipelineTest` cases for valid generated graphs, lightning overlay,
lightning-only fallback validation, visible idle baseline, and editor parameter
propagation. The build includes the TextureView/EGL renderer, lightning generator graph
branch, silent-band visibility floor, parameter editor, and `audio.fx.*`
diagnostic events. The pinned
libinfinisound Termux compatibility update remains at
`3412205da2501248a0c89f5ab9402049fc89cfd9`.

The standalone libinfinifx validation at the same library commit also passed
`:fx-core:test`, built its GLES library and sample APK, and passed
`python3 scripts/validate-shaders.py` (12 shader checks). These are compile,
portable unit-test, and shader-source checks only. `adb devices -l` reported no
attached device, so the lightning appearance, orientation, TextureView
composition, frame rate, and thermal behavior have **not been verified on an
Android device**. See [app architecture](architecture.md) and the
[libinfinifx effect guide](https://github.com/infiniforge/libinfinifx/blob/main/docs/effects.md).

On 2026-11-21, after changing the editor to a tap-revealed overlay and keeping a
full-width spectrum fallback until a successful EGL buffer swap, the same
standalone command `./build.sh --no-daemon --max-workers=1 --console=plain`
completed successfully again and passed all 121 JVM tests. It rebuilt
`InfiniMedia-debug.apk`. `adb devices -l` still reports no attached device, so
this build is not visual device acceptance. The supplied prior diagnostic bundle
contains no `audio.fx.*` render records; the current build records source size,
surface availability/destruction, skipped render inputs, errors, and successful
swaps to make the next capture actionable.
