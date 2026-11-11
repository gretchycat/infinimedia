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

All four Winamp skin archives are packaged by `modules/viewer-audio/src/main/assets/skins`; original embedded author readmes are preserved. The optional original SF3 bank and its upstream license/provenance live in [`test-assets/soundfonts`](test-assets/soundfonts/README.md). They are outside production source sets, preserving the existing portable synthesizer/user-import behavior. Media tests require every archive and verify the bank's original hash. No asset fixture is read from Folder or another app.

## Android verification checkpoint

On 2026-11-11, implementation commit `9f53814` produced its debug APK and passed 76 unit tests in the user's independent Termux checkout. `./build.sh --max-workers=1 --console=plain` ran with SDK environment variables unset and no local.properties, plus a guard rejecting project/source roots outside that checkout. The 11 build-environment regression checks also passed. APK inspection found exactly four Winamp archives, no SF3 bank and no advanced Folder storage implementations. These checks do not establish device UI, grants or playback behavior. Ubuntu testing and other platform ports remain deferred.
