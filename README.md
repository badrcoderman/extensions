# Requested Manga Extensions

A focused fork of [keiyoushi/extensions](https://github.com/keiyoushi/extensions) containing only the sources requested for this repository, including a ProComic compatibility fix.

| Source | Website | Language | Tachimanga | Mihon |
| --- | --- | --- | --- | --- |
| MangaRabi | [mangarabi.com](https://mangarabi.com) | ar | [JAR](https://raw.githubusercontent.com/badrcoderman/extensions/repo/jar/tachiyomi-ar.mangarabi-v1.4.2.jar) | [APK](https://raw.githubusercontent.com/badrcoderman/extensions/repo/apk/tachiyomi-ar.mangarabi-v1.4.2.apk) |
| HikayaTeam | [hikayateam.com](https://hikayateam.com) | ar | [JAR](https://raw.githubusercontent.com/badrcoderman/extensions/repo/jar/tachiyomi-ar.hikayateam-v1.4.2.jar) | [APK](https://raw.githubusercontent.com/badrcoderman/extensions/repo/apk/tachiyomi-ar.hikayateam-v1.4.2.apk) |
| MangaSid | [mangasid.com](https://mangasid.com) | ar | [JAR](https://raw.githubusercontent.com/badrcoderman/extensions/repo/jar/tachiyomi-ar.mangasid-v1.4.1.jar) | [APK](https://raw.githubusercontent.com/badrcoderman/extensions/repo/apk/tachiyomi-ar.mangasid-v1.4.1.apk) |
| Assassin Scans | [as-scans.net](https://as-scans.net) | ar | [JAR](https://raw.githubusercontent.com/badrcoderman/extensions/repo/jar/tachiyomi-ar.assassinscans-v1.4.1.jar) | [APK](https://raw.githubusercontent.com/badrcoderman/extensions/repo/apk/tachiyomi-ar.assassinscans-v1.4.1.apk) |
| KadrHub | [kadrhub.com](https://kadrhub.com) | ar | [JAR](https://raw.githubusercontent.com/badrcoderman/extensions/repo/jar/tachiyomi-ar.kadrhub-v1.4.1.jar) | [APK](https://raw.githubusercontent.com/badrcoderman/extensions/repo/apk/tachiyomi-ar.kadrhub-v1.4.1.apk) |
| ManhwaLek | [manhwalek.com](https://manhwalek.com) | ar | [JAR](https://raw.githubusercontent.com/badrcoderman/extensions/repo/jar/tachiyomi-ar.manhwalek-v1.4.1.jar) | [APK](https://raw.githubusercontent.com/badrcoderman/extensions/repo/apk/tachiyomi-ar.manhwalek-v1.4.1.apk) |
| Mangawy | [mangawy.org](https://mangawy.org) | ar | [JAR](https://raw.githubusercontent.com/badrcoderman/extensions/repo/jar/tachiyomi-ar.mangawy-v1.4.1.jar) | [APK](https://raw.githubusercontent.com/badrcoderman/extensions/repo/apk/tachiyomi-ar.mangawy-v1.4.1.apk) |
| Man9a | [man9a.net](https://man9a.net) | ar | [JAR](https://raw.githubusercontent.com/badrcoderman/extensions/repo/jar/tachiyomi-ar.man9a-v1.4.1.jar) | [APK](https://raw.githubusercontent.com/badrcoderman/extensions/repo/apk/tachiyomi-ar.man9a-v1.4.1.apk) |
| MangaWeekly | [www.mangaweekly.com](https://www.mangaweekly.com) | en | [JAR](https://raw.githubusercontent.com/badrcoderman/extensions/repo/jar/tachiyomi-ar.mangaweekly-v1.4.1.jar) | [APK](https://raw.githubusercontent.com/badrcoderman/extensions/repo/apk/tachiyomi-ar.mangaweekly-v1.4.1.apk) |
| ProComic | [procomic.pro](https://procomic.pro) | ar | [JAR](https://raw.githubusercontent.com/badrcoderman/extensions/repo/jar/tachiyomi-ar.procomic-v1.6.2.jar) | [APK](https://raw.githubusercontent.com/badrcoderman/extensions/repo/apk/tachiyomi-ar.procomic-v1.6.2.apk) |

## Install

Add this repository URL in the app's extension repositories or extension stores screen:

```text
https://raw.githubusercontent.com/badrcoderman/extensions/repo/index.pb
```

The index supplies signed JARs for Tachimanga and signed APKs for Mihon. For manual Tachimanga installation, download a JAR from the table and open **Browser → Extensions → +**. On Android, install its APK for Mihon. The [legacy JSON index](index.min.json) is also available.

This fork uses its own signing certificate. To replace a ProComic installation from Keiyoushi, uninstall that extension package before installing this fork's version, then trust the new extension when prompted. The ProComic source ID is preserved for existing library entries.

All sources use extension API 1.4 and Java 8 classes except ProComic, which retains upstream's API 1.6 and Java 11 classes. Use an app version with API 1.6 support for ProComic. MangaWeekly currently redirects to its English catalog, provides catalog and search browsing, and appears under **English / en**; the other sources appear under **Arabic / ar**. Mixed-content labels are retained, so client settings may hide these extensions.

## Readers and checks

- MangaRabi follows the site's chapter preparation flow and checks the full page count.
- HikayaTeam obtains fresh media tokens through its chapter image endpoint.
- MangaSid, Assassin Scans, KadrHub, ManhwaLek, Mangawy, Man9a and MangaWeekly support catalogs, search, details and chapter images.
- Chapter pagination is followed where required. Mangawy's full-width vertical tiles remain in reading order as separate images to avoid allocating giant bitmaps on iOS.
- KadrHub text chapters produce an explanatory message. Locked chapters retain the website's access requirements.
- [ProComic fix](PROCOMIC_FIX.md): removes the `Canvas.drawColor(int)` call reported as missing in Tachimanga, while preserving upstream readers, source ID and URLs.

[VALIDATION.md](VALIDATION.md) records parser, live website, image and package checks. Device installation and reading inside Mihon and Tachimanga still require confirmation; no iOS or Android test device is attached to this build environment.

## Build and update

Requires JDK 17, Android SDK 34 / build-tools 34.0.0, curl, and Python with `protobuf>=7.35.1,<8`.

```bash
./gradlew verifyExtensions buildExtensions
# Optional live website checks:
RUN_LIVE_TESTS=1 ./gradlew verifyExtensions
```

`sources.json` is the repository allowlist and package metadata. Increment the source's `extVersionCode` when changing its implementation, and update `sources.json` to match. ProComic's build downloads a pinned upstream JAR, verifies its SHA-256 hash, applies the reviewed bytecode patch, and rebuilds its Android APK from the same patched classes.

Set `EXTENSION_KEYSTORE`, `EXTENSION_KEYSTORE_PASSWORD`, and optionally `EXTENSION_KEY_ALIAS` (default `extensions`), then run:

```bash
python scripts/package_repository.py
```

Packages, both indexes, icons, and checksums are staged under `build/repository/`. GitHub Actions runs the checks, signs using repository secrets, and publishes verified files. Keep the signing key private and preserve it for updates. Published hashes are in [SHA256SUMS](SHA256SUMS).

## Attribution

Repository lineage and extension conventions come from [Keiyoushi](https://github.com/keiyoushi/extensions-source). The API library is [keiyoushi/extensions-lib](https://github.com/keiyoushi/extensions-lib). ProComic is derived from the upstream implementation and pinned release listed in [PROCOMIC_FIX.md](PROCOMIC_FIX.md). The protobuf schema and generated binding in `scripts/` come from Keiyoushi's publisher and the Mihon extension index, including the JAR URL field. Site icons belong to their websites. Source code is licensed under [Apache-2.0](LICENSE).
