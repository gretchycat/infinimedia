# InfiniMedia agent guidance

InfiniMedia is Infiniforge's standalone media player for audio, video, and images. It owns its playback experience, queues, settings, and installable Android project. It should work with Android-provided storage access when Infinifolder is not installed.

## Independence and integration

- Build this repository from its own checkout with its pinned library dependencies and documented Android toolchain. Do not depend on sibling application source, build outputs, or workspace-level paths.
- Keep this app's Gradle build independent. Use public APIs from linked libraries such as libinfinisound, libinfinivideo, libinfiniselect, and libinfiniui where appropriate; do not make another app a build dependency.
- Use Android SAF and granted content URIs as the baseline for opening media. Integrate with Infinifolder through documented storage/provider contracts when available, and handle its absence gracefully.
- Keep queue entries and saved playlists based on stable, authoritative resource identities. Do not persist temporary relay links or another app's private paths.
- Keep reusable decoding and processing in libraries where that fits their API boundaries; keep player UI, queue behavior, and app-owned state in this application.

## Project focus

- Aim to play supported audio, video, and image formats through platform capabilities and declared libraries. Describe actual format coverage and limitations accurately; “universal” describes the intended role, not a guarantee that every codec or format works on every device.
- Preserve playback, URI-grant, playlist, and resource-identity behavior documented by this repository.
- Keep standalone build instructions and dependency revisions current as modules or linked libraries change.

## Ecosystem workflow for agents

1. Read `docs/ECOSYSTEM.md` first to understand the component map and ownership boundaries.
2. Before changing a public interface, read this repository's architecture and API documentation listed below and inspect its compatibility notes.
3. Check the owning shared library repository before implementing behavior that may already exist elsewhere. Do not conclude a capability is unavailable just because its implementation is outside the current repository.
4. Keep application responsibilities, shared library APIs, and other applications' private data/source separate.
5. Before a cross-repository change, check the exact dependency pin and the relevant interoperability contract. Dependency versions and protocol versions are separate; do not infer protocol incompatibility from a library-version mismatch.
6. Update the local documentation index and relevant API/behavior documentation when public behavior changes. `docs/ECOSYSTEM.md` is generated from the central manifest and must not be edited locally.

## Documentation index

- `README.md` — project purpose, prerequisites, and standalone build instructions.
- `AGENTS.md` — repository-specific development rules and this ecosystem workflow.
- `docs/README.md` — local documentation index.
- `docs/ECOSYSTEM.md` — generated ecosystem-wide inventory, boundaries, dependencies, versions, and protocols.
- `docs/architecture.md` — public API and architecture documentation; read before changing interfaces or behavior.
- `docs/storage.md` — public API and architecture documentation; read before changing interfaces or behavior.
- `docs/audio-formats.md` — public API and architecture documentation; read before changing interfaces or behavior.
- `docs/video-formats.md` — public API and architecture documentation; read before changing interfaces or behavior.