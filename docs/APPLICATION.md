# InfiniMedia application

## Purpose

InfiniMedia is InfiniForge's independent Android audio/video player, playlist manager, and media-presentation application. It plays supported audio, video, and animated or still visual media while owning the playback interface, queues, playlists, player settings, and presentation lifecycle. “Universal” is the product direction; available codecs and behavior depend on the app's declared libraries, Android device, source capabilities, and documented format limits.

## Primary use cases

- Open media from Android intents, local files, SAF, or an optional Infinifolder resource handoff.
- Build and persist queues/playlists, inspect available metadata/artwork, navigate items, and use supported gapless/preload behavior.
- Play supported audio including documented MIDI and tracker formats, with optional user-imported soundfonts where supported.
- Play supported video and present supported image/animation items in the visual-media surface.
- Use the retained PCM-driven spectrum, oscilloscope, and VU prototypes plus a libinfinifx graph over audio artwork and static images, custom player themes/Winamp-style presentation, and available cast or external playback routes.
- Queue supported audio, video, and image resources recursively from a Folder directory/container when its provider is installed.

## Implementation status

Status note: implementation statements below reflect inspected source and current repository documentation. This audit did not rerun builds or device acceptance; existing checkpoints and remaining checks remain in the README and format guides.

### Implemented in this checkout

This checkout contains an Android app with separate audio and video viewer modules. The audio host owns its player UI/service, playlist persistence and ordering, Android output, casting routes, and presentation; libinfinisound owns reusable decoding, synthesis, and audio analysis APIs. The audio module retains lightweight PCM-driven spectrum, oscilloscope, and VU prototypes and now hosts a two-stage libinfinifx graph over audio artwork/static images. A temporary app-owned random policy replaces one graph effect per changed visual playlist item. Video controls/lifecycle are in the app's video module, with playback provided through libinfinivideo. libinfiniui and libinfinicanvas provide shared presentation and image functionality.

Source and current format guides document playlist and queue handling, gapless queue behavior, metadata/artwork work, MIDI and supported tracker rendering, image/animation presentation, visualization prototypes, ANSI/ANSI Music presentation, and a packaged custom skin. Cast and DLNA integration code is present. Only the app's documented supported formats/codecs are claimed; unsupported codecs should report failure rather than substitute invented audio. Device codec availability, receiver compatibility, real playback, and visual timing still have acceptance limits noted in the guides.

The [generated ecosystem map](ECOSYSTEM.md) lists component owners, exact dependency sources, and the current interapplication contracts.

## Responsibilities and shared libraries

InfiniMedia owns playback controls, queue/playlist behavior, app settings, Android media lifecycle/output, and presentation. It owns the `FxArtworkView`, bitmap texture upload, analyzer-to-metadata adapter, and temporary random effect-stage policy; reusable shaders, graph execution, and effect state belong to libinfinifx. The old shared-field app simulation remains removed. It does not own remote-storage drivers, archive/filesystem-image readers, or general-purpose codec implementations.

- libinfinisound: audio decoding, synthesis, analysis, and related audio processing.
- libinfinifx: reusable GLES 3 effect graph and built-in texture filters/generators.
- libinfinivideo: reusable video playback API/backend.
- libinfiniselect: open/save selection, grant-aware resource references, and lazy resolution.
- libinfiniui and libinfinicanvas: shared Android viewer/presentation and image decoding/animation APIs.
- libinfiniscribe: capability and processing integrations used for selected media/document routes where declared.

Exact dependency pins are authoritative in this repository's Gradle configuration and dependencies.lock.json.

## Interoperability

Infinifolder may supply remote and container access through its public provider contract. InfiniMedia stores the authoritative resource location in queue/playlist data and resolves a current content-access URI through libinfiniselect; temporary relay URIs are not durable playlist identities. Local and SAF playback remain available without Folder. Folder can hand supported resources to Media with Android intents and grants. No special runtime dependency on Infinifolder, Infiniscribe, or Infinikey is required.

## Planned and potential work

The product direction includes deliberate effect selection in place of the temporary random stage-replacement policy, richer palette and animation workflows, richer album-oriented organization beyond current directory/cover-first queueing, saved non-destructive effect settings, playback ranges, and deterministic video export. Silence removal and any additional casting/output behavior need explicit design and implementation before being described as supported. The app does not currently contain the former heat, momentum, fire, smoke, wind, or lightning simulation.

## Limitations and outstanding work

- “Universal” does not guarantee every codec, container, MIDI dialect, or device decoder. Format guides distinguish actual decoding from recognition and association.
- Playback, Cast/DLNA receiver behavior, platform codec availability, Android grants, and timing need real-device checks beyond compilation/JVM tests.
- The retained spectrum, oscilloscope, and VU views are app-owned presentation prototypes, not a general timeline editor or deterministic renderer. FX rendering is a separate GLES 3 path over artwork/static bitmap sources; video, animated image, and ANSI FX integration is deferred.
- Random effect-stage replacement is temporary test behavior; there is no deliberate effect selection UI or saved effect recipe yet.
- Layered playlist compositions, range editing, compositing workflows, and video export are not established as implemented features.
- InfiniMedia does not implement storage backends or replace Infinifolder as the suite's file-navigation app.

## Documentation audit

- Consistent: the README and architecture guide identify Media as an independent player with app-owned playback, queues, playlists, and presentation, using shared decode APIs.
- Incomplete: the PCM-driven spectrum, oscilloscope, and VU prototypes remain app-owned. The removed shared simulation is not an implemented feature. The first libinfinifx consumer adapter is implemented in source but still needs a build and Android visual acceptance.
- Unverified: codecs and some playback, Cast/DLNA, grant, and timing behavior still require device acceptance beyond JVM/build evidence.
- Reconciliation: format guides remain authoritative for actual codec support; the app does not claim every recognized media format decodes.
