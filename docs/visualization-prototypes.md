# Visualization prototypes

Infinimedia keeps a small set of player-owned visualization prototypes. They are
presentation views, not a reusable GPU effects engine.

## Retained

- VisualizerView draws the segmented 16-band spectrum in the player console
  and as a small overlay in the visual-media pane.
- WinampMainWindowView can switch among spectrum, oscilloscope, and VU modes.
- RealAudioVisualizerBridge reads decoded PCM through libinfinisound's shared
  analyzer and supplies the existing view models. The views do not decode audio.
- Album artwork, still and animated images, video, ANSI output, and ANSI Music
  keep their existing media presentation paths.

## Removed

The app no longer contains the live CPU heat/smoke simulation, wind and
lightning effects, shared mutable simulation fields, or their OpenGL overlay.
The modules/visualization-engine module has been removed, and the app no longer
requires GLES solely for that overlay.

## Reusable effects boundary

The standalone [libinfinifx](https://github.com/infiniforge/libinfinifx)
repository owns the reusable GPU effects direction. This checkout does not
declare it as a dependency and does not include an adapter. A later integration
should use its public API and pass normalized metadata from the existing
libinfinisound analysis path; it should not move app playback, media decoding,
or lifecycle ownership into the library.

These retained views are prototypes. No device visual acceptance or performance
result is claimed by this document.
