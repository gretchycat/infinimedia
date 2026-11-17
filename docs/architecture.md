# InfiniMedia architecture

InfiniMedia is an independent Android media player. Its own Gradle root owns the
application, audio and video viewer modules, and the pure Kotlin visualization
engine. It does not include Infinifolder or Infiniscribe as Gradle projects and
does not import their application source.

## Application ownership

- The application root owns launch routing, app configuration, exported intent
  handling, and generated Android file-opening filters.
- `modules/viewer-audio` owns audio playback UI and service behavior, queues,
  playlists, Cast integration, and the bridge from shared audio decoding to
  Android output.
- `modules/viewer-video` owns video playback controls, lifecycle, and media
  source leases while using the public `libinfinivideo` API for playback.
- `modules/visualization-engine` owns the portable visualization simulation and
  its effect contracts.
- `libinfinisound`, `libinfinivideo`, `libinfiniselect`, and `libinfiniui` own
  reusable APIs and adapters in their respective repositories.

Some shared library types retain the `com.infinifolder.*` package namespace for
compatibility. That namespace does not mean the Infinifolder application is a
build dependency. Application and library revisions are pinned within this
checkout; see [standalone build instructions](../README.md#fresh-checkout-build).

## Storage boundary

The app opens local and user-granted SAF resources through Android content URIs.
`libinfiniselect` preserves authoritative resource identity separately from a
current Android access URI and can resolve resources exposed by Infinifolder's
provider. Infinifolder remains an optional companion: without it, Media retains
its Android SAF and local-file workflows. See [storage integration](storage.md).
