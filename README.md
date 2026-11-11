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

## Fresh checkout build

Install JDK 17 or 21, Python 3, Android SDK platform 36, build tools 35.0.0,
NDK 27.2.12479018 and CMake 3.22.1. Set `ANDROID_HOME` (or `sdk.dir` in this
checkout's untracked `local.properties`).

```sh
git clone https://github.com/gretchycat/infinimedia.git
cd infinimedia
python3 scripts/fetch-dependencies.py
./gradlew assembleDebug testDebugUnitTest
```

The debug APK is `build/outputs/apk/debug/InfiniMedia-debug.apk`. Optionally pass
`-PtargetAbi=arm64-v8a` to restrict application packaging. Source dependencies
and their transitive closure are pinned in `dependencies.lock.json`; fetching
never requires a sibling application. It refuses to overwrite changed dependency
checkouts. Run `python3 scripts/fetch-dependencies.py --check` to inspect pins.

For Termux, install its JDK, clang, aapt2, Python, patchelf and libopenmpt packages.
Set the supported Gradle property `android.aapt2FromMavenOverride` to your installed
aapt2 executable. The Termux native bridge uses `PREFIX`, not a fixed machine
path. Its native tracker adapter currently targets arm64; other hosts retain the
existing portable tracker implementation and use the NDK for the other bridges.

Compilation and local unit tests do not validate Android grants, audio output,
casting, codecs installed on a device, or storage provider lifecycle behavior.
See the architecture audit in Infinifolder for executed verification results.

Extracted from gretchycat/infinifolder's viewer-audio, viewer-video and viewer-host
sources; the originating repository retains their history. Preserve bundled
third-party notices and skin/font attribution. See LICENSE.md.
