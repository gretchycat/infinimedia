# Infinimedia Default

These are the custom theme assets supplied in the Media checkout. The default main faceplate is `main.ora`, an OpenRaster document whose visible artwork layer is `data/rainbow-logo.iff`; the static shadow/base remains a PNG layer. The build packages this file unchanged. Canvas renders and cycles the IFF layer from its native palette ranges, then composites it over the base and scales the complete canvas into the Winamp faceplate. Animation comes from the IFF palette ranges; the PNG base remains static.

The skin name in theme.json, skininfo.xml and readme.txt is Infinimedia Default. Original artwork authors and copyright remain credited; the archived upstream skin is retained unchanged in test-assets/skins.

`package-default-theme.py` packages these source assets deterministically into `skins/Infini.wsz`. Debug previews and backups are not production assets. The repository-root `infini.svg` remains a separate static artwork source; the default skin's color cycling comes from the embedded IFF palette.

Image filenames can use any extension handled by linked Canvas decoders. Logical sprite names (main, titlebar, cbuttons, pledit, etc.) are independent of encoding. A `theme.json` images value is a filename or an ordered array of up to four same-size layers. Without a mapping, traditional role.bmp wins; otherwise a matching role in another format is selected deterministically. Unknown/malformed codecs use the existing visual fallback.

The visual media pane uses the VIDEO title from video.bmp with side edges and mirrored bottom corners from gen.bmp. The renderer assembles a complete frame at the player’s scale, leaving the inner media stage at 16:9. Clicking its title still collapses the pane.

`playlist-titlebar.bmp` is the unpacked 275×20 playlist title strip. The app draws this image directly from the skin; it does not assemble or alter the title bar at runtime. It is included in `Infini.wsz`.

The equalizer uses the title artwork embedded in `eqmain.bmp` directly. It preserves the rails and graduated marks, clears only the baked slider thumbs for live Android controls, and draws live controls over the skin art.
