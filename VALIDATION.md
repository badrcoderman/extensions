# Validation — 2026-10-24

Both extensions use the API 1.4 ABI, which current Mihon supports. Tachimanga provides the same extension API and accepts JVM JAR packages. Each JAR contains Java 8 classes, the merged XML Android manifest, packaged resources, and a v1 signature from the same certificate as its Android APK.

## Passed locally

```bash
./gradlew verifyExtensions buildExtensions
RUN_LIVE_TESTS=1 ./gradlew :src:ar:mangarabi:testReleaseUnitTest :src:ar:hikayateam:testReleaseUnitTest
python scripts/package_repository.py
```

- 21 parser regression tests; both additional live tests passed with no skips when enabled.
- Release Android lint passed for both modules.
- APK builds, JVM class builds, JAR packaging, and signature verification passed for both sources.
- Every packaged class targets Java 8 or earlier. No host API or runtime libraries are bundled in the extensions.
- The gzip protobuf repository index round-trips successfully and contains exactly the two requested sources, with JAR and APK URLs. The legacy JSON indexes list the same sources.

| Live check | MangaRabi | HikayaTeam |
| --- | --- | --- |
| Popular catalogue and pagination | Passed | Passed |
| Latest catalogue | Passed | Passed |
| Search | Passed | Passed |
| Details and cover download | Passed | Passed |
| Full sample chapter list | 48 chapters across 5 API pages | 88 chapters across 2 API pages |
| Sample reader | Boruto: Two Blue Vortex, chapter 38 | Devil Returns to School Days, chapter 88 |
| Image downloads and format checks | All 8 images passed | All 3 images passed |

MangaRabi reader status and access server actions were also checked against the live website. Regression cases cover partial readers, split Next.js Flight chunks, escaped JSON strings, placeholders, decimal chapter numbers, pagination, dates, and access-denied chapters.

These checks validate the source logic, network paths, and package structure. Installation and reading inside Mihon on Android and Tachimanga on iOS were not performed; no test devices were available in this session.

References: [Mihon extension loader](https://github.com/mihonapp/mihon/blob/main/source-api/src/main/kotlin/eu/kanade/tachiyomi/source/online/HttpSource.kt), [Mihon supported API versions](https://github.com/mihonapp/mihon/blob/main/app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionLoader.kt), [Tachimanga extension guide](https://tachimanga.app/help/guides/extensions_guide.html).
