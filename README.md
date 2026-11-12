# InfiniMedia

Independent Android audio/video player, playlists, visualizations, casting and
Winamp skins. Application ID: `com.infinimedia`; Android 8.0 (API 26) or newer.
Audio/video UI lives in `modules/viewer-audio` and `modules/viewer-video`.
Reusable decoding, synthesis and timing belong to libinfinisound/libinfinivideo.

External storage is selected through libinfiniselect. Infinifolder adds advanced
storage when installed; Android SAF remains available on its own. The APK does
not include Infinifolder's remote clients, torrent engine or virtual filesystem
implementation. The small shared UI and resource-support libraries adapt content
URIs without depending on another application's source.

## Build script

`./build.sh` builds the debug APK and runs the application's JVM unit tests.
It fetches only the pinned library closure before invoking this checkout's Gradle wrapper.
Additional Gradle arguments are forwarded unchanged, for example `--max-workers=1` or `-PtargetAbi=arm64-v8a`.

## Fresh checkout build

Install JDK 17 or 21, Python 3, Android SDK platform 36, build tools 35.0.0,
NDK 27.2.12479018 and CMake 3.22.1. Set `ANDROID_HOME` (or `sdk.dir` in this
checkout's untracked `local.properties`).

```sh
git clone https://github.com/gretchycat/infinimedia.git
cd infinimedia
./build.sh --max-workers=1 --console=plain
```

The debug APK is `build/outputs/apk/debug/InfiniMedia-debug.apk`. Optionally pass
`-PtargetAbi=arm64-v8a` to restrict application packaging. Source dependencies
and their transitive closure are pinned in `dependencies.lock.json`; fetching
never requires a sibling application. It refuses to overwrite changed dependency
checkouts. Run `python3 scripts/fetch-dependencies.py --check` to inspect pins.

For Termux, install its JDK, clang, aapt2, Python, patchelf and libopenmpt packages.
The build script configures native aapt2 automatically; direct Gradle calls need
`android.aapt2FromMavenOverride` set to the installed executable. The Termux native bridge uses `PREFIX`, not a fixed machine
path. Its native tracker adapter currently targets arm64; other hosts retain the
existing portable tracker implementation and use the NDK for the other bridges.

Compilation and local unit tests do not validate Android grants, audio output,
casting, codecs installed on a device, or storage provider lifecycle behavior.
See the architecture audit in Infinifolder for executed verification results.

Extracted from gretchycat/infinifolder's viewer-audio, viewer-video and viewer-host
sources; the originating repository retains their history. Preserve bundled
third-party notices and skin/font attribution. See LICENSE.md.

## Build environment discovery

`./build.sh` honors `ANDROID_HOME`, `ANDROID_SDK_ROOT` or this checkout's `local.properties`. With none configured, it discovers an installed SDK in `~/android-sdk`, `~/Android/Sdk` or `~/Library/Android/sdk`, and reports a clear error if absent. It does not install an SDK or write local configuration. On Termux it passes the installed native `aapt2` explicitly and defaults arm64 packaging to `arm64-v8a`; explicit Gradle arguments can override that default. Direct `./gradlew` invocation still requires SDK/tool configuration.

Run `python3 scripts/test-android-build-env.py` for the build entry point's isolated regression checks.

## Owned media assets

Infinimedia Default is the sole packaged skin, generated reproducibly from `theme/` by `scripts/package-default-theme.py`. The four original Winamp archives are preserved under `test-assets/skins` for compatibility tests, outside the APK; original embedded author readmes are preserved. The optional original SF3 bank and its upstream license/provenance live in [`test-assets/soundfonts`](test-assets/soundfonts/README.md). They are outside production source sets, preserving the existing portable synthesizer/user-import behavior. Media tests require every archive and verify the bank's original hash. No asset fixture is read from Folder or another app.

## Android verification checkpoint

On 2026-11-11, implementation commit `9f53814` produced its debug APK and passed 76 unit tests in the user's independent Termux checkout. `./build.sh --max-workers=1 --console=plain` ran with SDK environment variables unset and no local.properties, plus a guard rejecting project/source roots outside that checkout. The 11 build-environment regression checks also passed. APK inspection found exactly four Winamp archives, no SF3 bank and no advanced Folder storage implementations. These checks do not establish device UI, grants or playback behavior. Ubuntu testing and other platform ports remain deferred.

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
arbitrary filename or up to four same-size image layers. IFF palette cycling and
the supplied [`infini.svg`](infini.svg) indexed SVG extension animate while the
player is resumed and pause with its lifecycle. See Canvas’s SVG palette-cycling
API documentation for supported metadata and validation limits.

```sh
python3 scripts/test-package-default-theme.py
# After ./build.sh, on a local Android 10+/Termux runtime:
python3 scripts/verify-theme-runtime.py
```

The runtime probe checks real SVG/IFF pixels, cache play/pause/clear, format
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

## SVG color-cycling playback

Flat indexed SVG paints are rasterized once at the decoded size. Playback updates
cached palette-bound pixels, preserving the source palette (including more than
256 colors) and keeping untagged artwork fixed. This replaces per-frame CSS and
vector path rendering. The image schedulers count rendering/publication work
inside the frame interval, so that work is not followed by another full delay.
Raster setup adds a one-time loading cost. Mixed antialiased/overlapping paints
can have small color approximation differences; tagged animated gradient stops
retain the exact vector fallback. The SVG source is unchanged.

`python3 scripts/verify-theme-runtime.py --benchmark` measures decode and warmed
renderer timing and compares sampled frames against the vector reference. This
measures CPU rendering, not visible UI frame rate. The normal probe also tests
all 192 phases in infini.svg, a complete 300-color cycle, duplicate palette IDs,
opacity, still previews, gradient fallback and both playback scheduler deadlines.
