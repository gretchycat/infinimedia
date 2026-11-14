# Infinimedia Default

These are the custom theme assets supplied in the Media checkout. The default main faceplate comes from `main.ora`, an OpenRaster document with the InfiniMedia palette-cycle XML extension. `ora-to-skin.py` converts it into a 275 × 116 `main-base.png` and transparent `main-cycle.iff` overlay. The converter scales both layers on the same aspect-preserving canvas and maps each XML palette range to standard IFF CRNG chunks, which the existing renderer animates. The media build regenerates the runtime layers directly from the ORA, so editing the authoring image is reflected in the packaged skin. To update the checked-in previews manually, run `python3 theme/ora-to-skin.py`. The skin package contains only the generated runtime layers, not the larger authoring file. The converter requires the pinned Pillow dependency in `scripts/requirements-theme.txt`.

The skin name in theme.json, skininfo.xml and readme.txt is Infinimedia Default. Original artwork authors and copyright remain credited; the archived upstream skin is retained unchanged in test-assets/skins.

`package-default-theme.py` packages these source assets deterministically into `skins/Infini.wsz`. Debug previews, conversion scripts and backups are not production assets. The repository-root `infini.svg` remains a separate static artwork source; color cycling for the default skin is carried by the ORA-to-IFF conversion above.

Image filenames can use any extension handled by linked Canvas decoders. Logical sprite names (main, titlebar, cbuttons, pledit, etc.) are independent of encoding. A `theme.json` images value is a filename or an ordered array of up to four same-size layers. Without a mapping, traditional role.bmp wins; otherwise a matching role in another format is selected deterministically. Unknown/malformed codecs use the existing visual fallback.

The visual media pane uses the VIDEO title from video.bmp with side edges and mirrored bottom corners from gen.bmp. The renderer assembles a complete frame at the player’s scale, leaving the inner media stage at 16:9. Clicking its title still collapses the pane.
