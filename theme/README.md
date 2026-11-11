# Infinimedia Default

These are the custom theme assets supplied in the Media checkout. The corrected 275 × 116 `main.iff` already merges the faceplate with the cycling logo and is selected directly by `theme.json`. Each layer uses the same canvas dimensions and pixel coordinates. The skin name in theme.json, skininfo.xml and readme.txt is Infinimedia Default. Original artwork authors and copyright remain credited; the archived upstream skin is retained unchanged in test-assets/skins.

`package-default-theme.py` packages these source assets deterministically into `skins/Infini.wsz`. Debug previews, conversion scripts and the empty main-base.bmp from the working theme directory are not production assets. The supplied indexed SVG source is retained at repository-root `infini.svg`; Canvas supports that metadata directly without conversion to an 8-bit image.

Image filenames can use any extension handled by linked Canvas decoders. Logical sprite names (main, titlebar, cbuttons, pledit, etc.) are independent of encoding. A `theme.json` images value is a filename or an ordered array of up to four same-size layers. Without a mapping, traditional role.bmp wins; otherwise a matching role in another format is selected deterministically. Unknown/malformed codecs use the existing visual fallback.

The visual media pane uses the VIDEO title from video.bmp with side edges and mirrored bottom corners from gen.bmp. The renderer assembles a complete frame at the player’s scale, leaving the inner media stage at 16:9. Clicking its title still collapses the pane.
