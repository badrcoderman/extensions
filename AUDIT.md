# Runtime audit and sandbox checks — 2026-10-25

This audit covers the shared request recovery code, ProComic's map parsing and piece downloads, package boundaries, and a separate laboratory extension. It is a finite review and regression suite, not a claim that every source or host is free of bugs.

| Finding | Evidence | Change |
| --- | --- | --- |
| Failed nonce-body inspection left the HTTP response open | A synthetic response source throws while `peekBody` reads; the new cleanup assertion failed on the previous implementation | Close the response on inspection failure, preserve cancellation/fatal errors and translate supported failures |
| A session tag could replay an ordinary POST | A 403 followed by success caused an unmarked POST to be renewed; the no-replay regression failed before the fix | Session renewal and image refresh require GET/HEAD or an explicit read-only marker |
| Overlapping map rectangles were accepted | A 2×2 map with two overlapping left-hand rectangles passed the old checks; the regression failed without allocating a bitmap | Require exact full coverage, with no gaps or overlaps, in every horizontal band |
| Map dimensions and piece bodies could request excessive allocations | The old parser accepted a 50,000×50,000 canvas plan; body reads had no upper bound | Limit map bodies to 2 MiB, image pieces to 16 MiB, plans to 256 pieces/rectangles, dimensions to 65,536 per axis and canvases to 16 megapixels |
| Cached client quotas ignored later connection-limit changes | The quota was computed once for each client; a new regression changes the same client's limit to one | Re-read the live host limit on each transfer admission |
| Empty or HTML piece responses reached the decoder and could be mislabeled as decoder incompatibility | A new fixture serves an empty body and a login HTML response with HTTP 200 | Reject empty readers and textual access responses before invoking the decoder |

Canvas memory also has a shared pixel budget: at most one quarter of the reported Java heap, capped at 128 MiB of ARGB canvases across concurrent mapped pages. Unsupported or zero heap introspection falls back to an 8-megapixel shared budget. Budget waits respect cancellation, and success, failure and interruption release acquired credits. A new Arabic error directs readers to the website when an image exceeds the extension's bounds.

The three stored real ProComic map geometries remain regression fixtures. The upstream decoder and JPEG quality remain unchanged. These bounds cover compressed piece buffers and destination canvases; the host's decoder allocations are separate. They cannot guarantee that every malformed compressed image is harmless. Stock Suwayomi's missing AVIF decoder remains tracked in [issue #1](https://github.com/badrcoderman/extensions/issues/1).

## Published verification

[Build 40305881879](https://github.com/badrcoderman/extensions/actions/runs/40305881879) passed 236 executed JUnit cases, release lint, all ten JAR/APK builds and signature/package verification. The twenty published package hashes match the manifest. The published ProComic 1.6.5 JAR retains all 125 upstream classes and matches all 83 locally tested helper classes. Its shuffled PNG fixture passed in the actual Suwayomi graphics runtime. [Structured release evidence](validation/runtime-audit-2026-10-26.json) records these results.

This is not a fresh all-sites live pass. Assassin Scans failed its live HTTP check on GitHub twice and is tracked in [issue #2](https://github.com/badrcoderman/extensions/issues/2). The interrupted local live run encountered DNS failures for Man9a and MangaRabi. ProComic's public catalog returned HTTP 200 on 2026-10-26, but the reader required Turnstile, preventing completion of the deferred-media/map check from this connection.

## Separate laboratory extension

[Sandbox Diagnostics](sandbox/README.md) is an explicitly selected API 1.4 source with its own package and signing certificate. It is excluded from the ten-source production index. The signed JAR and APK contain fixed JVM, temporary-file, private-network HTTP, process and recoverable-error canaries. They do not accept arbitrary commands or load remote code. Requests to reader pages, including chapter downloads, trigger a probe; installation, browsing and metadata/chapter-list refreshes do not.

The actual provided Suwayomi 2.4.2366 runtime installed the signed laboratory JAR in a fresh, isolated localhost server. Catalog and chapter-list requests left all five tests unrun. The JVM canary passed; temporary-file, private-network and fixed-process canaries reported ALLOWED. All four static result PNGs decoded correctly. The intentional source error returned an error while subsequent source browsing remained responsive. [Structured desktop evidence](validation/sandbox-2026-10-25.json) records these results without private paths, reader tokens or device identifiers.

An ALLOWED result records a capability, not a sandbox escape. No iOS or Android device was attached for independent testing. Missing or denied APIs are reported as unavailable or blocked rather than silently treated as successful.

On 2026-10-26, the user reported the laboratory JAR running in Tachimanga on iOS: JVM PASS, temporary file ALLOWED, network UNAVAILABLE, process UNAVAILABLE, and the expected intentional source error. The last-result summaries for the two unavailable capabilities and continued host responsiveness after the error have not been supplied. These are [user-reported results](validation/tachimanga-user-report-2026-10-26.json), separate from the observed desktop checks. In particular, UNAVAILABLE does not establish a sandbox denial, and the default loopback HTTP target requires a fixture on the phone itself.
