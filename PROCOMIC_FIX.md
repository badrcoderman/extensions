# ProComic — Tachimanga Canvas compatibility

The reported runtime error is:

```text
HttpException: canceled due to java.lang.NoSuchMethodError:
android/graphics/Canvas.drawColor(I)V
```

Upstream's image stitcher calls `Canvas.drawColor(Color.WHITE)` before placing the page's image pieces. Tachimanga's runtime lacks that method in the reported installation. The `http://procomic.pieces/` addresses are internal requests intercepted by the extension to reconstruct an image; they are not external website addresses.

## Patch

[scripts/PatchProComic.java](scripts/PatchProComic.java) removes exactly that invocation, replacing it with a stack-balanced `POP2`. It rebuilds the class constant pools, checks that exactly one invocation was changed and verifies that no `Canvas.drawColor` call remains. The rest of upstream's image decoder, piece ordering, drawing, signing and access logic remains intact.

Three real plans, including the reported chapter `213362`, a shuffled ten-piece grid and a two-piece vertical page, are stored as geometry-only fixtures. [scripts/verify_procomic_plans.py](scripts/verify_procomic_plans.py) checks every horizontal band for gaps, overlaps and out-of-bounds rectangles. All three plans cover every destination pixel, making the background fill redundant for those pages. Previous live checks downloaded and decoded all pieces for these sample pages and reconstructed them successfully outside the apps.

## Reproducible input

- Upstream source: [ProComic at commit 8f504748a2f6](https://github.com/keiyoushi/extensions-source/tree/8f504748a2f6/src/ar/procomic).
- Original JAR: [ProComic 1.6.0, release 7363e42](https://github.com/keiyoushi/extensions/releases/download/7363e42/tachiyomi-ar.procomic-v1.6.0.jar).
- SHA-256: `377b1046c3f58dc10cb74c338c6ae6a90980e0925f0ba55103d3a15304c4cf49`.
- Fork version: **1.6.1**, code **106001**, API **1.6**.
- Source ID: **8891921907305143070**, unchanged.

The same patched classes are packaged as a signed JAR and compiled to Android DEX for the signed APK. They use this fork's signing certificate, so replacing an upstream installation requires uninstalling its extension package first.

## Testing limits

The user-provided error confirms the missing method. Automated checks verify the patch and package structure; earlier network checks validated catalogs, chapters, reader data, CDN signing and image pieces. An iOS device is unavailable here, so this document does not claim a successful reading session inside Tachimanga. Any second error requires its complete message for a separate diagnosis.
