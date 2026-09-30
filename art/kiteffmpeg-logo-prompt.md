# Default KiteFFmpeg artwork

Approved on 2026-09-30. The default logo is a green kite built from a folded
zigzag ribbon, with six alternating maze-like cuts and stepped ends.

## Assets

- `kiteffmpeg-logo.svg`: editable vector source, with a 1024 by 1024 view box.
- `kiteffmpeg-logo.png`: approved 1024 by 1024 PNG with a transparent background.
- `../docs/assets/kiteffmpeg-logo.svg` and `kiteffmpeg-icon.svg`: identical
  copies of the vector source for the documentation site.
- `../docs/assets/favicon.png`: 64 by 64 export of the same artwork.

The repository README uses the SVG. The yuroyami profile repository carries
matching SVG and PNG copies under `assets/logos/` and uses the SVG in its README.

## Design constraints

Keep the pointed kite outline, aligned top and bottom tips, green palette,
parallel slanted bands, deep alternating cuts, short end turns, and consistent
fold depth. The maze-like zigzag is the identity of the artwork. Preserve the
transparent background and the current framing when making exports.

The final artwork is constructed as SVG geometry and rendered directly to PNG.
It does not embed a bitmap or an unchanged FFmpeg emblem. Earlier generated
concepts and the violet sail with a white inlay are superseded.

See [Artwork credits](CREDITS.md) for the FFmpeg references and attribution.

## Recreate the exports

Run from the repository root with `rsvg-convert` available:

```sh
rsvg-convert -w 1024 -h 1024 -o art/kiteffmpeg-logo.png art/kiteffmpeg-logo.svg
rsvg-convert -w 64 -h 64 -o docs/assets/favicon.png art/kiteffmpeg-logo.svg
cp art/kiteffmpeg-logo.svg docs/assets/kiteffmpeg-logo.svg
cp art/kiteffmpeg-logo.svg docs/assets/kiteffmpeg-icon.svg
```
