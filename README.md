# Arabic Manga Extensions

A focused fork of [keiyoushi/extensions](https://github.com/keiyoushi/extensions) containing **only two Arabic sources**:

| Source | Website | Tachimanga | Mihon |
| --- | --- | --- | --- |
| MangaRabi | [mangarabi.com](https://mangarabi.com) | [Signed JAR](https://raw.githubusercontent.com/badrcoderman/extensions/repo/jar/tachiyomi-ar.mangarabi-v1.4.1.jar) | [Signed APK](https://raw.githubusercontent.com/badrcoderman/extensions/repo/apk/tachiyomi-ar.mangarabi-v1.4.1.apk) |
| HikayaTeam | [hikayateam.com](https://hikayateam.com) | [Signed JAR](https://raw.githubusercontent.com/badrcoderman/extensions/repo/jar/tachiyomi-ar.hikayateam-v1.4.1.jar) | [Signed APK](https://raw.githubusercontent.com/badrcoderman/extensions/repo/apk/tachiyomi-ar.hikayateam-v1.4.1.apk) |

## Install

Add this URL in the app's extension repositories or extension stores screen:

```text
https://raw.githubusercontent.com/badrcoderman/extensions/repo/index.pb
```

The index supplies a JAR URL for Tachimanga and an APK URL for Mihon. JARs contain Java 8 classes, an XML Android manifest, resources, and a signature from the same certificate as the APKs. Both sources use extension API 1.4, supported by current Mihon and Tachimanga.

For manual Tachimanga installation, download the JAR above, open **Browser → Extensions → +**, and select the file. On Android, install the APK for Mihon.

Older clients can use [index.min.json](https://raw.githubusercontent.com/badrcoderman/extensions/repo/index.min.json). If you previously added this fork with Keiyoushi's metadata, remove that old repository entry and add this URL again: this fork now has its own signing certificate.

Both sources appear under **Arabic / ar**. Their catalogues contain mixed content, so clients that hide adult-labelled extensions may hide them too.

## Features and validation

- Popular titles, latest updates, search, covers, and manga details.
- Complete chapter lists with pagination, decimal chapter numbers, and upload dates.
- All reader pages in order. MangaRabi follows the website's chapter preparation flow and checks the full page count. HikayaTeam obtains fresh media tokens through its image endpoint.
- Normal website access rules apply to locked or paid chapters.

Checks include parser regression tests, release Android lint, and opt-in live tests for catalogue pagination, search, details, chapter pagination, cover downloads, and every image in a sample chapter from each site. Package checks verify signatures, entry classes, and Java 8 bytecode.

Automated checks do not replace testing inside the iOS and Android apps. Device installation and reading still need confirmation on the user's app versions.

## Build and update

Requires JDK 17, Android SDK 34 / build-tools 34.0.0, and Python with `protobuf>=7.35.1,<8`.

```bash
./gradlew verifyExtensions buildExtensions
# Optional live website checks:
RUN_LIVE_TESTS=1 ./gradlew :src:ar:mangarabi:testReleaseUnitTest :src:ar:hikayateam:testReleaseUnitTest
```

Increment `extVersionCode` in the source's `build.gradle` when changing its implementation. Configure `EXTENSION_KEYSTORE`, `EXTENSION_KEYSTORE_PASSWORD`, and optionally `EXTENSION_KEY_ALIAS` (default: `extensions`), then run:

```bash
python scripts/package_repository.py
```

This stages signed packages, both index formats, icons, and checksums under `build/repository/`. It never replaces published files directly. GitHub Actions runs the checks, signs using repository secrets, and publishes the verified files. The workflow can also be run manually with live checks enabled.

Keep the signing key private and preserve it for future updates. Published file hashes are in [SHA256SUMS](SHA256SUMS).

## Attribution

Repository lineage and extension conventions come from [Keiyoushi](https://github.com/keiyoushi/extensions-source). The API library is [keiyoushi/extensions-lib](https://github.com/keiyoushi/extensions-lib). The protobuf schema and generated binding in `scripts/` come from Keiyoushi's publisher and the Mihon extension index, including the JAR URL field. Site icons belong to their respective websites. Source code is licensed under [Apache-2.0](LICENSE).
