# InfiniMedia storage integration

InfiniMedia keeps its own queues, playlists, settings, and playback state. A
resource's authoritative URI is its stable identity; a current `content://` URI
is the Android handle used to read it. Saved playlists retain authoritative
URIs and do not persist temporary Infinifolder relay links.

## Access paths

1. Local files and Android Storage Access Framework (SAF) grants are supported
   without Infinifolder.
2. InfiniMedia uses the public `libinfiniselect` API to retain and resolve
   resource references, including references handled by Infinifolder.
3. When installed, Infinifolder supplies the optional storage provider and owns
   its remote, archive, filesystem-image, and credential implementations.

The client does not package Infinifolder's storage engine or access its private
files. If the provider is missing, disabled, or denies a request, the app keeps
its local and SAF workflows and reports the source error. Relative playlist
entries resolve through the selected source contract rather than client-side
parsing of Infinifolder URI schemes.

This describes the implemented API boundary. Provider grants, remote reads,
playback, and provider lifecycle still require Android-device acceptance; a
successful compile or unit test does not prove those interactions.
