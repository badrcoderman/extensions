# Validation — 2026-10-25

The repository contains ten requested sources. Nine use extension API 1.4 and Java 8 classes. ProComic retains upstream's API 1.6 and Java 11 classes, source ID and URL format, with a targeted Tachimanga compatibility patch.

## Checks passed

```bash
RUN_LIVE_TESTS=1 ./gradlew verifyExtensions buildExtensions --console=plain
python scripts/package_repository.py
```

- 175 executed JUnit cases passed (187 declared, 12 inapplicable cases skipped), including nine live source tests, eleven recovery regressions repeated in the nine API 1.4 modules, and three ProComic protocol regressions. No failures or errors remained after rerunning one KadrHub network timeout. The additional ProComic public API/reader-protocol script and two report privacy/deduplication tests also passed.
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

## Real desktop host verification

The user supplied `Suwayomi-Server-v2.4.2366-appimage.AppImage`. It ran with its bundled Java runtime, a separate data directory and a server bound to localhost. The signed published JARs were installed, followed by the signed update packages using the app's extension installation endpoint. The checks used Suwayomi's actual source, manga, chapter and image handlers.

| Source | Browse/search | Complete sampled chapter list | Reader pages | Decoded images |
| --- | --- | --- | --- | --- |
| MangaRabi | 24 / 10 results | 48 | 8 | First, middle, last passed |
| HikayaTeam | 24 / 1 results | 88 | 3 | First, middle, last passed |
| MangaSid | 24 / 24 results | 12 | 17 | First, middle, last passed |
| Assassin Scans | 24 / 1 results | 11 | 17 | First, middle, last passed |
| KadrHub | 30 / 60 results | 243 | 40 | First, middle, last passed |
| ManhwaLek | 24 / 14 results | 945 | 5 | First, middle, last passed |
| Mangawy | 36 / 6 results | 170 | 15 | First, middle, last passed |
| Man9a | 20 / 15 results | 67 | 20 | First, middle, last passed |
| MangaWeekly | 20 / 1 results | 246 | 12 | First, middle, last passed |

ProComic installed and loaded its filters and the reported chapter 213362 with all 19 pages. Its direct AVIF page downloaded, but the first mapped page failed in the supplied stock app because Java ImageIO had no AVIF reader. The trace confirms neither the missing Canvas method nor the missing `R` class occurred at this stage. This does not establish a successful complete ProComic reading session, nor an iOS/Android device test.

## Runtime audit fixes and remaining limits

- Resource exclusions match only each extension's resource namespace; every upstream ProComic class is checked before publication.
- Read retries are bounded, response bodies close before retries, cancellation remains cancellation, and long Retry-After values suppress immediate retries. Ordinary POSTs are not replayed; verified read-only POST endpoints are marked explicitly.
- Failed image links refresh a chapter once; repeated invalid HTML responses stop. ProComic's expired deferred maps and signed-image sessions have separate one-renewal bounds.
- Chapter pagination rejects repeated data and has a page bound. MangaRabi's placeholder-only pages use raw progress keys so hidden placeholders do not cause a false failure.
- Nuxt decoding validates indexes and depth, memoizes shared references, and reads only the data subtree. Missing required fields become explanatory Arabic errors at the source boundary.
- Man9a uses the website's `updated` sort and briefly polls preparing readers. MangaWeekly renews its public AJAX nonce and carries it into later pages. KadrHub searches the full website results page.
- Dependency resolution excludes API-stub transitive dependencies so compile and test hosts consistently use the declared compatible libraries. Network regression tests exercise the real OkHttp interceptor.
- Daily GitHub Actions checks produce deduplicated, privacy-safe issues. Reporting tests verify that chapter text, URLs and tokens from failures are omitted. Phone runtime telemetry is not enabled.

Websites may change or return a challenge, remove content or require access. Locked content is still subject to the website's access rules. A source snapshot and finite tests cannot guarantee that every future chapter will work. Stock Suwayomi's missing AVIF piece decoder remains a host limitation; no Android or iOS device is attached.

The signed updates were reinstalled into the same Suwayomi test library. All nine readers passed again, all ten source IDs matched the repository index, and type/status searches passed through the host for HikayaTeam, Assassin Scans, ManhwaLek, Mangawy and Man9a. KadrHub returned 60 full-page search results for the sample query, compared with eight from the old quick-search endpoint. ProComic 1.6.3 returned its Arabic unsupported-decoder error for the stock host limitation. Structured evidence is in [validation/desktop-2026-10-25.json](validation/desktop-2026-10-25.json).
