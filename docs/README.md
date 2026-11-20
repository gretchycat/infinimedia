# Infinimedia documentation

This directory documents InfiniMedia's player, media-format behavior, and its
integration with shared libraries. The application owns playback UI, queues,
playlists, player settings, casting, and media presentation.

- [Application description and implementation status](APPLICATION.md).
- [Application architecture](architecture.md)
- [Storage and URI integration](storage.md)
- [Audio and sound formats](audio-formats.md)
- [Audio processing integration](audio-processing-library.md)
- [Video playback](video-formats.md)
- [Winamp skin integration](winamp-skins.md)
- [Theme rendering](theme-rendering.md)
- [Historical JVM audio fixture report](history/audio-playback-report-2026-11-10.md)

Build prerequisites and the standalone build command are in the application
[README](../README.md). The `libinfinisound` and `libinfinivideo` repositories own
their reusable codec and playback APIs.

## Shared ecosystem documentation

- [InfiniForge ecosystem map](ECOSYSTEM.md) — generated overview; synchronized from the organization manifest.

- [Planned playlist ownership migration](TODO.md).
