# ProComic — Tachimanga Canvas compatibility

The reported runtime error is:

```text
HttpException: canceled due to java.lang.NoSuchMethodError:
android/graphics/Canvas.drawColor(I)V
```

Upstream's image stitcher calls `Canvas.drawColor(Color.WHITE)` before placing the page's image pieces. Tachimanga's runtime lacks that method in the reported installation. The `http://procomic.pieces/` addresses are internal requests intercepted by the extension to reconstruct an image; they are not external website addresses.

## Patch

[scripts/PatchProComic.java](scripts/PatchProComic.java) replaces the pinned stitch method with a delegate that overlaps bounded piece transfers, then decodes and draws the pieces in the original rectangle order. The delegate omits the unsupported `Canvas.drawColor` call and passes an explicit full-image source rectangle to `Canvas.drawBitmap`; stock Suwayomi throws on a null source rectangle. Upstream's image decoder fallbacks and JPEG quality of 90 remain in use. The build verifies that exactly one stitch method and client builder were patched and that no `Canvas.drawColor` invocation remains.

Three real plans, including the reported chapter `213362`, a shuffled ten-piece grid and a two-piece vertical page, are stored as geometry-only fixtures. [scripts/verify_procomic_plans.py](scripts/verify_procomic_plans.py) checks every horizontal band for gaps, overlaps and out-of-bounds rectangles. All three plans cover every destination pixel, making the background fill redundant for those pages. Previous live checks downloaded and decoded all pieces for these sample pages and reconstructed them successfully outside the apps.

## Reproducible input

- Upstream source: [ProComic at commit 8f504748a2f6](https://github.com/keiyoushi/extensions-source/tree/8f504748a2f6/src/ar/procomic).
- Original JAR: [ProComic 1.6.0, release 7363e42](https://github.com/keiyoushi/extensions/releases/download/7363e42/tachiyomi-ar.procomic-v1.6.0.jar).
- SHA-256: `377b1046c3f58dc10cb74c338c6ae6a90980e0925f0ba55103d3a15304c4cf49`.
- Fork version: **1.6.5**, code **106005**, API **1.6**.
- Source ID: **8891921907305143070**, unchanged.

The same patched classes are packaged as a signed JAR and compiled to Android DEX for the signed APK. They use this fork's signing certificate, so replacing an upstream installation requires uninstalling its extension package first.

## Testing limits

The user-provided error confirms the missing method. Automated checks verify the patch and package structure; earlier network checks validated catalogs, chapters, reader data, CDN signing and image pieces. An iOS device is unavailable here, so this document does not claim a successful reading session inside Tachimanga. The separately reported missing `R` class is covered by the hotfix below.

## Required-class hotfix (1.6.2)

The 1.6.1 JAR accidentally omitted `eu/kanade/tachiyomi/a/a/a/R.class`: a generic resource exclusion matched an obfuscated implementation class. Version 1.6.2 excludes resource classes only inside the application's own resource package. Packaging now verifies that all 125 upstream implementation classes remain in the signed JAR. The reported `NoClassDefFoundError` identifies this missing class.

## Reliability update (1.6.3)

The pinned reader receives a network wrapper through one verified `OkHttpClient.Builder.build()` patch. Temporary GET failures retry at most twice. Expired mapped-page sessions reload the public reader and deferred maps once, preserve the original page index and CDN path, and share a short snapshot between page requests. Signed-image sessions renew once. Regression tests verify renewal and retry bounds. Unsupported decoders produce a clear Arabic error without repeatedly downloading the image.

## Suwayomi desktop result

The supplied Suwayomi 2.4.2366 AppImage successfully installed the signed JAR, loaded the source and filters, searched, loaded 307 available chapters for the reported title, and extracted all 19 pages for chapter 213362. Its first direct AVIF image downloaded successfully. Reconstructing page 4 failed in stock Suwayomi: the Android compatibility layer's `BitmapFactory` uses Java ImageIO and this package has no AVIF decoder. The two optional Android decoder classes are also absent. This is a separate host decoder limitation; removing a Canvas call or restoring `R.class` does not supply AVIF decoding. No iOS device test is claimed.

## Transfer update (1.6.4)

The stitcher now fetches up to three pieces ahead for each page. At most five pieces transfer across the source, with a lower host limit respected. Ahead-of-time reads stop after about 2 MiB per piece; larger images continue streaming when consumed. Shared slots bound that buffer to about 10 MiB plus stream buffers. Requests reuse the host client and connection pool. Cancellation closes in-flight responses, and one retry can recover an interrupted piece body. Worker threads expire after idle time.

Session renewal uses the site's actual `/chapter-deferred-media/{id}` endpoint; the previous update incorrectly added `/api/public`. A live guest-reader check verified the corrected endpoint. Chapter refreshes share same-chapter work without holding a lock during network requests, so a slow chapter does not delay a different chapter.

A shuffled six-piece PNG page was assembled successfully using the actual Suwayomi graphics and upstream decoder fallback. Its dimensions, destination positions and colors passed pixel checks. The separate AVIF decoder limitation remains; see [PERFORMANCE.md](PERFORMANCE.md).

## Runtime audit update (1.6.5)

Map geometry must now cover the canvas exactly without gaps or overlaps. Map response, piece-body, piece-count and canvas-allocation limits prevent unchecked growth in these extension-controlled paths. A cancellable shared canvas budget limits concurrent allocations. The upstream decoder remains in use; its internal allocations are outside that canvas budget. Host connection-limit changes apply to new transfers on the same client. See [AUDIT.md](AUDIT.md) for the findings, limits, regression cases and separate Tachimanga laboratory package.
