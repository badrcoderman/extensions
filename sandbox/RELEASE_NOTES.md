Sandbox Diagnostics 1.4.1 is a separate opt-in laboratory extension. It is excluded from the normal manga source index and uses a distinct signing certificate.

- Import the **JAR** into your Tachimanga sandbox; the matching **APK** supports Mihon on Android 8+.
- Find **Sandbox Diagnostics (LOCAL TEST)** under English sources. Choose a test title and open **Run this probe (manual)**.
- Fixed tests cover the extension's own JVM canary, a new temporary canary file, a private-IP HTTP endpoint, a fixed `printf` child process and an intentional recoverable source error.
- Browsing and metadata refreshes do not run probes. Reader-page requests and chapter downloads do, so keep automatic downloads disabled for these test titles.
- Return to the title and refresh details for the last local result. Cached chapter pages do not perform another run.

No arbitrary commands, remote code loading, private-file reads, persistence or automatic uploads are included. An ALLOWED result describes a capability and does not prove a sandbox escape. Missing or denied APIs become explicit results.

The signed JAR passed real Suwayomi desktop host checks, including result-image decoding and continued browsing after the intentional source error. iOS and Android devices were not attached: actual Tachimanga sandbox outcomes need your iOS run.

For iPhone-to-PC networking, run the fixed canary fixture on your controlled LAN and search the lab source for the PC's private IPv4 address. [Full instructions and build details](https://github.com/badrcoderman/extensions/blob/repo/sandbox/README.md).
