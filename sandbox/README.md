# Sandbox Diagnostics — local laboratory extension

This separate extension contains visible, fixed capability canaries for an owned sandbox. Its JAR targets Tachimanga's extension API 1.4; the matching APK targets Mihon on Android 8+. Source language is **English**. The package and signing certificate differ from the normal manga extensions. It is excluded from `sources.json` and the published source indexes.

Installing, browsing, searching and refreshing details do not run a probe. A probe runs when the host requests its chapter's pages, including an explicit chapter download. Keep automatic chapter downloading disabled for these test titles.

| Test title | Operation | What a result establishes |
| --- | --- | --- |
| JVM execution | Reflectively invoke this extension's own fixed canary | Its extension bytecode and the reflection call ran; this is expected plugin behavior |
| Temporary file | Create one uniquely named temporary file, write/read the fixed marker, delete it | Access to a new temporary canary; no existing user files are read |
| Private-network HTTP | GET a fixed canary path on a selected private IPv4 address, port 4570 | That controlled endpoint is reachable; redirects are disabled |
| OS process, opt in | Run only `/bin/sh -c 'printf extension-sandbox-canary-v1'` or Android's `/system/bin/sh` equivalent | Whether that fixed child process can run with the host's current privileges |
| Recoverable source error | Throw one constant `IOException` from the reader request | The app should display a source error and remain usable afterward |

**ALLOWED does not establish a sandbox escape.** The probes do not attempt privilege escalation, persistence, arbitrary commands, remote code loading, credential access or automatic uploads. The process canary starts with an empty environment. A denied operation reports BLOCKED when a `SecurityException` identifies denial. Missing host APIs, IO failures and unsupported operations report UNAVAILABLE; this does not prove a security boundary. Raw exceptions and private file paths are omitted. Only the last sixteen local results remain in memory and disappear when the source unloads.

## Use in your Tachimanga iOS sandbox

1. Import the signed `sandbox-diagnostics-v1.4.1.jar` through Tachimanga's extension import screen. Find **Sandbox Diagnostics (LOCAL TEST)** under English sources.
2. Browse the source and select one test title. Read its description, then open **Run this probe (manual)**. Each title has one chapter, so moving between chapters does not trigger a different probe.
3. The reader shows a static outcome card. Return to the title and refresh its details to read the last local result. Rerunning requires the app to refetch the chapter pages; a cached page does not execute a fresh probe. Do not interpret cached results as a new run.
4. For the intentional-error test, expect a source error. Then browse the source and run the JVM test again to check that Tachimanga remains responsive.

For an iPhone-to-PC network test, put the devices on your controlled LAN and run this on the PC:

```bash
python3 scripts/sandbox_fixture.py --bind 0.0.0.0
```

Search the diagnostics source for the PC's literal private IPv4 address, such as `192.168.1.10`, and open the resulting network test. The extension only permits RFC 1918 or loopback IPv4 addresses, uses the fixed `/canary` path and port 4570, reads at most 129 response bytes, and sends no cookies or authorization. iOS local-network permission and sandbox policy may deny the connection. A loopback test on the iPhone requires a fixture running on the iPhone itself. Stop the PC fixture with Ctrl+C after testing.

The APK exposes the same source in Mihon. It has no activity, service, receiver or provider and does not execute anything as a standalone installed Android app. Extension execution takes place inside the reader host, subject to that host's privileges and compatibility layer.

## Build the test packages

Use JDK 17 and the Android SDK configured for this repository:

```bash
./gradlew -PincludeSandbox verifySandbox buildSandbox
```

Create a separate PKCS12 laboratory key with `keytool`, retain it for consistent updates, and put its password in `SANDBOX_KEYSTORE_PASSWORD`. Do not reuse the normal extension signing key. Then run:

```bash
python3 scripts/package_sandbox.py --keystore /private/path/sandbox-lab.p12
```

Signed JAR/APK files, public signer metadata and SHA-256 hashes are staged in `build/sandbox-packages/`. The packager refuses the production signer and production package directories. It verifies both signatures, JVM class versions, entry classes and the APK's DEX entry point. Laboratory binaries are available locally and as explicitly labeled GitHub pre-release assets; they are not added to the normal extension store.

Desktop verification can check the same JAR's source loading, fixture images, fixed canaries and recoverable failures. Actual iOS capability results require running it in your Tachimanga sandbox; desktop results cannot establish iOS isolation or API support.
