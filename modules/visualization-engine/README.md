# Visualization Engine

Pure Kotlin simulation and direction core used by Infinimedia's live visual-media
pane. This module has no Android, UI, decoder or application dependencies.

## Current composition

- `AudioFeatureFrame` carries playback-position time, RMS/peak levels, calibrated
  spectrum bands, onset classes, beat pulse and tempo confidence. The Android
  adapter also reports spectral centroid, stereo width/balance and zero-crossing
  rate.
- `MusicalFeatureTracker` derives kick-like low-band, snare-like mid-band and
  cymbal-like high-band onsets independently from each spectrum update. These are
  heuristic frequency-region detections, not claims of instrument recognition.
  Positive spectral flux is kept separate from the slower band envelopes so a
  sustained note does not retrigger transient effects. Bass/body onsets estimate
  tempo with interval folding, smoothing and a confidence score.
- `MusicDirector` smooths energy and frequency-band levels and selects the
  current intensity state with hysteresis.
- Effects expose activation checks and resource descriptors; the director's
  spectrum thresholds wake the laser, transients wake lightning, and fire stays
  available to consume residual heat.
- Effects declare the shared fields they read and write, along with resource
  cost and tags. Effects deposit values into a shared additive buffer.
- The spectrum fuels a full-width, center-mirrored fire source row: low bands
  begin at both edges and high bands meet at the center. Fire contributes upward
  velocity, heat, light and smoke density; lightning contributes a brief flash,
  charge and localized heat where its bolt lands.
- The shared solver advects heat and smoke through the velocity field, diffuses
  them, smooths momentum, and applies decay. Fire's upward acceleration is
  limited to a quarter-scale contribution. Any future effect can steer fields
  by adding vectors, so heat does not need effect-specific movement rules.
- Field contributions are applied in batches, then fields diffuse or decay.
  Effects do not need to know which other effect produced a value.
- `MusicVisualizationEngine` supports seeded deterministic startup, gradually
  blended palettes, manual per-effect enablement, snapshots and hard reset.

The Android view and PCM analysis adapter are in `modules/viewer-audio`. The
decoder library supplies a 2048-frame Hann-windowed spectrum using a shared
-72 dBFS to 0 dBFS band scale; it averages stereo channel power so opposite-phase
content is retained. The analyzer emits feature diagnostics during playback.
The simulation and event tracker can be tested without a device:

```sh
./gradlew :visualization-engine:test
```

The current implementation uses a 320×180 CPU simulation field at up to 30
updates per second. Infinimedia uploads the fire, smoke and lightning fields to
an OpenGL ES 2 compositor, which blends and scales them in the visual pane.
Tempo confidence and the onset classes are estimates from decoded PCM; playback
hardware testing is still needed to judge their musical quality across formats
and source levels. Detailed musical-structure analysis, additional effect
families and serializable presets remain future work.
