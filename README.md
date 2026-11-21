# InfiniMedia
InfiniMedia is InfiniForge's independent audio/video player and media-presentation app. It owns
playback, playlists, and visual presentation while consuming shared decoding and resource APIs. See
the [application scope and implementation status](docs/APPLICATION.md).

Independent Android audio/video player, playlists, visualizations, casting and
Winamp skins. Application ID: `com.infinimedia`; Android 8.0 (API 26) or newer.
Audio/video UI lives in `modules/viewer-audio` and `modules/viewer-video`.
Reusable decoding, synthesis and timing belong to libinfinisound/libinfinivideo.

## Visualization pipeline

The player retains its existing PCM-driven 16-band spectrum prototype and the classic Winamp spectrum, oscilloscope, and VU views. The media pane now also uses the standalone [libinfinifx](https://github.com/infiniforge/libinfinifx) GLES 3 library as a two-stage effect graph over audio artwork, static images, and a generated fallback image when a track has no art. Each distinct playlist visual item currently replaces one randomly selected pipeline stage with a different random effect. This temporary selection policy is isolated in `RandomFxPipeline` and is intended to be replaced by deliberate selection later; it adds no effect-picker UI.

Available random stages are CRT, handheld LCD, water refraction, heat ripple, album-art rotozoom, kaleidoscope, and the fire generator with explicit alpha composition. The existing PCM analyzer remains in libinfinisound; its smoothed bands and VU values are adapted to optional normalized FX metadata. Rotozoom responds continuously to RMS and bass energy; host-supplied timestamped onset/beat events add a short zoom and rotation pulse. Infinimedia's current adapter maps levels only and does not invent a second beat detector. Animated image, video, ANSI, and ANSI Music continue on their existing presentation paths; the current library backend does not accept video's external OES texture.

The earlier shared-field heat/smoke/wind/lightning app overlay remains removed. The retained spectrum, oscilloscope, and VU views remain prototypes and their layout has not changed. The new GPU surface and random graph behavior are source-implemented but have not yet been device-verified in this checkout. See [visualization integration status](docs/visualization-prototypes.md) and [architecture](docs/architecture.md).

External storage is selected through libinfiniselect. Infinifolder adds advanced
storage when installed; Android SAF remains available on its own. The APK does
not include Infinifolder's remote clients, torrent engine or virtual filesystem
implementation. The small shared UI and resource-support libraries adapt content
URIs without depending on another application's source.

See [InfiniMedia architecture](docs/architecture.md), [storage integration](docs/storage.md),
and the [documentation index](docs/README.md) for the app-owned boundaries and
format guides. InfiniFolder is an optional provider companion; the keyboard app
is not part of this storage integration.

## Storage identities and playlists

InfiniMedia keeps each resource's authoritative URI separate from its current
Android content URI. The authoritative URI is used in queue snapshots and saved
playlists; the content URI is resolved lazily through libinfiniselect when the
player needs bytes. Relative playlist entries resolve against the playlist's
authoritative location. Remote, archive and filesystem-image URI syntax stays
inside the storage libraries and InfiniFolder's IPC provider. Expiring Folder
relay links are never written into playlists.

Version 0.1.1 resolves queued identities with libinfiniselect on a worker before
opening audio, images, video or a gapless preload. It keeps valid current grants
and requests a fresh access link when needed, including canonical SAF locations
which this application cannot read directly. A resolution failure stops with the
provider's error and leaves the queue intact; it does not mark every track
unreadable or attempt to open an SFTP address through Android's local adapter.
Install the current InfiniFolder build alongside client updates when testing
remote playlists; the optional storage provider runs in that application.

## Remote metadata enrichment

Remote tag and artwork reads now use a 30-second cooperative budget for positional
sources and 10 seconds for other inputs, with 16 MiB and 512-read limits per probe.
A failed playlist metadata probe is retried once after the first pass over queued
audio. A blocked provider call may outlast the elapsed-time budget; SFTP over cellular
still needs a post-change device check.

The 0.1.1 Android build passed `./build.sh --console=plain` on Termux with all
91 unit tests passing, including a saved-SFTP-playlist regression. The packaged
manifest reports version code 2. This does not establish device playback or
successful access to a user's saved remote connection.

Version 0.1.2 starts the next audio item over silent ANSI output while the
terminal finishes scrolling, and crossfades visual image changes for one second.
Its Android build passed with 95 unit tests; the APK reports version code 3.
Device playback and visual transition behavior still need on-device acceptance.

## Queueing media

The queue add flow selects audio, video and images together; InfiniMedia requests all three supported media categories without a category-checkbox prompt. From InfiniFolder, **Queue all media in InfiniMedia** recursively gathers supported audio, video and image items from a directory or supported container. A readable `cover.*` image is queued first for each source directory so it appears as that directory’s first playlist item. Runtime format capabilities and MIME/extension filters determine which items are offered.

## ANSI art and ANSI Music

`.ans` and `.ansi` files can be queued alongside audio, images and video. InfiniMedia
detects UTF-8 versus CP437, interprets ANSI terminal control and color sequences in
an 80-column display, and draws the output in the visual-media pane. Long output
scrolls at 9600 baud (8N1 timing). Embedded ANSI Music commands use libinfinisound's
PC-speaker synthesizer and play through the end; the finished frame remains visible
if the tune outlasts the text. If a silent ANSI item is followed by audio, that audio
starts over the still-scrolling terminal; the ANSI sequence remains visible until
complete. Other ANSI items advance when their display and any embedded audio finish.
Visual image changes crossfade over one second. ANSI Music is played locally;
disconnect Cast before starting an ANSI item.

## Build script

`./build.sh` builds the debug APK and runs the application's JVM unit tests.
It fetches only the pinned library closure before invoking this checkout's Gradle wrapper.
Additional Gradle arguments are forwarded unchanged, for example `--max-workers=1` or `-PtargetAbi=arm64-v8a`.

## Fresh checkout build

Install JDK 17 or 21, Python 3, Android SDK platform 36, build tools 35.0.0,
NDK 27.2.12479018 and CMake 3.22.1. Set `ANDROID_HOME` (or `sdk.dir` in this
checkout's untracked `local.properties`).

```sh
git clone https://github.com/infiniforge/infinimedia.git
cd infinimedia
./build.sh --max-workers=1 --console=plain
```

The debug APK is `build/outputs/apk/debug/InfiniMedia-debug.apk`. Optionally pass
`-PtargetAbi=arm64-v8a` to restrict application packaging. Source dependencies
and their transitive closure are pinned in `dependencies.lock.json`; fetching
never requires a sibling application. It refuses to overwrite changed dependency
checkouts. Run `python3 scripts/fetch-dependencies.py --check` to inspect pins.
The flattened app pins choose one exact revision for each shared library and
verify that it descends from every library's declared dependency commit.

For Termux, install its JDK, clang, aapt2, Python, patchelf and libopenmpt packages.
The build script configures native aapt2 automatically; direct Gradle calls need
`android.aapt2FromMavenOverride` set to the installed executable. The Termux native bridge uses `PREFIX`, not a fixed machine
path. Its native tracker adapter currently targets arm64; other hosts retain the
existing portable tracker implementation and use the NDK for the other bridges.

Compilation and local unit tests do not validate Android grants, audio output,
casting, codecs installed on a device, or storage provider lifecycle behavior.
This README records InfiniMedia build checkpoints; the [historical suite-wide
audit](https://github.com/infiniforge/infinifolder/blob/main/docs/architecture-audit.md)
retains the earlier cross-app verification matrix.

Extracted from gretchycat/infinifolder's viewer-audio, viewer-video and viewer-host
sources; the originating repository retains their history. Original InfiniMedia code
is licensed under MIT; preserve bundled third-party notices and skin/font attribution.
See LICENSE.md.

## Build environment discovery

`./build.sh` honors `ANDROID_HOME`, `ANDROID_SDK_ROOT` or this checkout's `local.properties`. With none configured, it discovers an installed SDK in `~/android-sdk`, `~/Android/Sdk` or `~/Library/Android/sdk`, and reports a clear error if absent. It does not install an SDK or write local configuration. On Termux it passes the installed native `aapt2` explicitly and defaults arm64 packaging to `arm64-v8a`; explicit Gradle arguments can override that default. Direct `./gradlew` invocation still requires SDK/tool configuration.

Run `python3 scripts/test-android-build-env.py` for the build entry point's isolated regression checks.

## Owned media assets

Infinimedia Default is the sole packaged skin, generated reproducibly from `theme/` by `scripts/package-default-theme.py`. The four original Winamp archives are preserved under `test-assets/skins` for compatibility tests, outside the APK; original embedded author readmes are preserved. The optional original SF3 bank and its upstream license/provenance live in [`test-assets/soundfonts`](test-assets/soundfonts/README.md). They are outside production source sets, preserving the existing portable synthesizer/user-import behavior. Media tests require every archive and verify the bank's original hash. No asset fixture is read from Folder or another app.

## Android verification checkpoint

On 2026-11-11, implementation commit `9f53814` produced its debug APK and passed 76 unit tests in the user's independent Termux checkout. `./build.sh --max-workers=1 --console=plain` ran with SDK environment variables unset and no local.properties, plus a guard rejecting project/source roots outside that checkout. The 11 build-environment regression checks also passed. APK inspection found exactly four Winamp archives, no SF3 bank and no advanced Folder storage implementations. These checks do not establish device UI, grants or playback behavior. Ubuntu testing and other platform ports remain deferred.

The ANSI and ANSI Music implementation also built on 2026-11-11 and passed all 86 JVM unit tests. The generated and packaged Android intent filters include `.ans` and `.ansi`; the debug APK is 70 MiB. No device playback or UI test was run, so audio output, terminal rendering and playlist timing still need runtime validation.

## Infinimedia Default theme

The custom theme in [`theme/`](theme/README.md) is the default for new installations.
Existing imported skins and explicit palette choices remain available; saved
references to removed bundled archives fall back to this default artwork.
Its corrected `main.iff` is the full opaque faceplate with native palette cycling.
The visual media pane is framed with the VIDEO title sprite and GEN side/bottom
pieces, scales with the player, and retains title-bar collapse and its 16:9 stage.
Skin metadata names it **Infinimedia Default** and preserves the original artwork
attribution to Sven Kistner, Zarko Jovic, GuidoD and Wildrose-Wally.

Skin images use Canvas format discovery, so logical `main.bmp` sprite lookups can
resolve IFF, PNG, SVG and other supported decoders. `theme.json` can select an
arbitrary filename or up to four same-size image layers. IFF palette cycling
animates while the player is resumed and pauses with its lifecycle. SVG/SVGZ,
including the preserved [`infini.svg`](infini.svg) source, render as still images;
custom SVG color-cycling metadata is ignored. The default faceplate is authored
in `theme/main.ora`; the skin packages the ORA unchanged and Canvas cycles its
visible IFF layer directly over the static base. The complete decoded canvas is
scaled to fit the Winamp faceplate.

```sh
python3 scripts/test-package-default-theme.py
# After ./build.sh, on a local Android 10+/Termux runtime:
python3 scripts/verify-theme-runtime.py
```

The build and theme tests require Python 3. No image conversion package is
needed to build the default skin.

The runtime probe checks real SVG/IFF/ORA pixels, cache play/pause/clear, format
signature detection, and frame resizing. It exports full-frame PNG previews to
`build/theme-runtime/`. It does not exercise Activity layout, touch input, video
surfaces, Android permissions or visible on-device playback.

## Default-theme verification checkpoint (2026-11-11)

Implementation 9938277 passed `./build.sh --max-workers=1 --console=plain` in the
independent user checkout with SDK variables unset, no local.properties and a
source-root guard. All 80 app tests and 11 build-environment checks passed.
The APK contains exactly `assets/skins/Infini.wsz`, with no SF3 or other skin
archives. All 8 Android raster/lifecycle checks and 3 packaging checks passed.
The generated skin bytes match between source packaging, the independent build
and its APK (SHA-256 `2165e2594f635273c881cb21e7117bc20aa63058e2c27d1744ac72b9d933717b`).
This supersedes the earlier four-skin packaging checkpoint. Activity appearance,
touch and video surface composition still need device acceptance. Ubuntu tests
and other-platform ports remain deferred.

## File associations

The exported `InfiniMediaActivity` handles Android `ACTION_VIEW` with `CATEGORY_DEFAULT`, including `audio/*`, `video/*`, specific audio/playlist MIME types and content/file URIs. These filters are generated from linked runtime capabilities during the build. No system-wide audio default is changed automatically.

In Android's file-opening resolver, choose Infinimedia and **Always** when offered. A sending app that uses `Intent.createChooser` forces a one-time choice and cannot save an Android default. The app-info “Open by default” screen primarily manages supported web links and may not offer an audio-player selection. See [Android's intent resolution documentation](https://developer.android.com/guide/components/intents-filters).

Inside Infinifolder, use **Open with… → Remember in InfiniFolder → All files of this type**, then choose Infinimedia. Those are local preferences, separate from Android defaults. The Folder update migrates saved choices pointing to its removed internal media players; the shared preference update lets explicit type-wide choices supersede old extension defaults.

The runtime probe checks the built APK's binary manifest with Android's own IntentFilter matcher for opaque content IDs and audio/video/playlist MIME types. It also verifies static SVG/SVGZ rendering, unchanged IFF cycling and first-frame pixels, frame assembly, scheduler deadlines and cache lifecycle. It does not automate Android's visible resolver or default-setting UI.

## Static SVG / audio-default verification checkpoint

Implementation f3d7bea passed this repository's build.sh in the independent user checkout: 80 unit tests, 11 environment checks and all three theme packaging tests. All 13 real Android runtime groups passed against APK SHA-256 `a1bad381fe7f40f74cad1f3983b2228227dec88d5ae34daf9b4b5b8154d0fdb6`. They verify static SVG/SVGZ, unchanged IFF cycling, saved-default serialization/precedence, packaged audio intent matching, frame pixels and lifecycle/deadline behavior. Removed SVG cycling classes are absent from the APK. Visible Android chooser/Always interaction remains manual acceptance. This supersedes the earlier SVG playback experiment; no system default was changed by the probe.

## Authoring-thumbnail verification follow-up

The updated Canvas/Core/UI pins fix ORA/KRA authoring identity, large SVGZ classification and PostScript thumbnail aliases. Implementation aea7644 passed the independent build.sh with 80 unit tests and 11 environment cases; all three skin packaging checks passed. The Android probe passed 16 groups, or 17 with `--image-fixtures /path/to/filesystem-test-images/images` to include the original SVGZ. The real EPS/ORA/KRA and original SVGZ outputs were visually checked. Tested APK SHA-256: `468ce8fb555a1b114247b179b8b94e6f5656e04e879adb1da152654f3dcb43f8`. The fixture option changes test tooling only. No storage implementation or image-viewer screen dependency was added.
