# Infinimedia Default

These are the custom theme assets supplied in the Media checkout. The default main faceplate is `main.ora`, an OpenRaster document with the InfiniMedia palette-cycle XML extension. The build packages this file unchanged. Canvas decodes the ORA directly, scales the complete canvas into the Winamp faceplate, and animates its palette ranges from the embedded metadata. No PNG/IFF breakdown assets are generated or required.

The skin name in theme.json, skininfo.xml and readme.txt is Infinimedia Default. Original artwork authors and copyright remain credited; the archived upstream skin is retained unchanged in test-assets/skins.

`package-default-theme.py` packages these source assets deterministically into `skins/Infini.wsz`. Debug previews and backups are not production assets. The repository-root `infini.svg` remains a separate static artwork source; the default skin's color cycling is carried by the embedded ORA metadata.

Image filenames can use any extension handled by linked Canvas decoders. Logical sprite names (main, titlebar, cbuttons, pledit, etc.) are independent of encoding. A `theme.json` images value is a filename or an ordered array of up to four same-size layers. Without a mapping, traditional role.bmp wins; otherwise a matching role in another format is selected deterministically. Unknown/malformed codecs use the existing visual fallback.

The visual media pane uses the VIDEO title from video.bmp with side edges and mirrored bottom corners from gen.bmp. The renderer assembles a complete frame at the player’s scale, leaving the inner media stage at 16:9. Clicking its title still collapses the pane.
