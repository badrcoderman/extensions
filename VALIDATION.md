# Validation — 2026-10-25

The repository contains ten requested sources. Nine use extension API 1.4 and Java 8 classes. ProComic retains upstream's API 1.6 and Java 11 classes, source ID and URL format, with a targeted Tachimanga compatibility patch.

## Checks passed

```bash
RUN_LIVE_TESTS=1 ./gradlew verifyExtensions buildExtensions --console=plain
python scripts/package_repository.py
```

- 73 executed JUnit cases passed: 64 regression cases and nine live tests. Shared tests run in each applicable module; 12 cases for other modules were skipped. No failures or errors.
- Release Android lint passed for all ten modules.
- All ten Android APKs and JVM JAR class archives built successfully.
- Packaging verifies the APK and JAR signatures against this fork's certificate, manifests, entry classes, APK DEX entry points, and class bytecode versions.
- The gzip protobuf index round-trips and contains exactly the allowlisted sources in `sources.json`, each with APK and JAR URLs. Legacy JSON indexes list the same sources.

| Source | Sample chapter list | Sample reader | Image checks |
| --- | --- | --- | --- |
| MangaRabi | 48 chapters, paginated API | Boruto: Two Blue Vortex 38 | All 8 images |
| HikayaTeam | 88 chapters, paginated API | Devil Returns to School Days 88 | All 3 images |
| MangaSid | 12 chapters | Mage Again 12 | 17 sections; first/middle/last downloaded |
| Assassin Scans | 11 chapters | My Sin 11 | 17 sections; first/middle/last downloaded |
| KadrHub | 243 chapters | The Long Way Of The Warrior 243 | 40 sections; first/middle/last downloaded |
| ManhwaLek | 945 chapters including decimals | Demonic Emperor 927 | 5 sections; first/middle/last downloaded |
| Mangawy | 170 chapters | Dungeon Odyssey 170 | All 15 vertical sections extracted; first/middle/last downloaded |
| Man9a | 77 available chapters | Becoming A Family With The Duke and His Dear Son 81 | 35 sections; first/middle/last downloaded |
| MangaWeekly | All 246 chapters through AJAX pagination | The Ember Knight 246 | 12 sections; first/middle/last downloaded |
| ProComic | Upstream reader retained | Reported chapter 213362, number 319 | 3 initial images and 16 mapped pages extracted; all 10 pieces of its first mapped page decoded and reconstructed |

All nine live tests checked catalogs, catalog pagination, search, details, cover downloads, chapter lists and reader images. Latest updates were checked where exposed; MangaWeekly provides catalog and search browsing. Sample images from the seven new sites were also independently decoded with Pillow. Mangawy's vertical strips remain in their original order as separate pages.

MangaRabi's preparation flow, split Flight payloads, partial readers and placeholders remain covered by regression cases. HikayaTeam's access checks and tokenized image endpoint remain covered. New fixtures exercise the actual catalog, chapter and reader formats, Nuxt reference-table decoding, lazy image attributes, decimal chapters, chapter ordering and text-chapter errors.

## ProComic's reported error

The supplied `NoSuchMethodError: android/graphics/Canvas.drawColor(I)V` identifies an unsupported call in upstream's page stitcher. The build removes exactly one invocation and verifies that none remain. Three real map geometries, including the reported chapter, cover every pixel without that background fill. Its ten downloaded pieces had no transparent pixels. See [PROCOMIC_FIX.md](PROCOMIC_FIX.md) for the pinned input, patch and evidence.

These checks validate source logic, live network paths, image data and package structure. Installation and reading inside Mihon on Android and Tachimanga on iOS were not performed; no test devices were available in this session. The ProComic fix specifically addresses the supplied missing-method error.

References: [Mihon extension loader](https://github.com/mihonapp/mihon/blob/main/app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionLoader.kt), [Tachimanga extension guide](https://tachimanga.app/help/guides/extensions_guide.html), [ProComic upstream source](https://github.com/keiyoushi/extensions-source/tree/8f504748a2f6/src/ar/procomic).
