# Runtime audit — 2026-10-26

This audit covers the shared request recovery code, ProComic's map parsing and piece downloads, and package boundaries. It is a finite review and regression suite, not a claim that every source or host is free of bugs.

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

[Build 40305881879](https://github.com/badrcoderman/extensions/actions/runs/40305881879) passed all 226 production JUnit cases, release lint, all ten JAR/APK builds and signature/package verification. The twenty published package hashes match the manifest. The published ProComic 1.6.5 JAR retains all 125 upstream classes and matches all 83 locally tested helper classes. Its shuffled PNG fixture passed in the actual Suwayomi graphics runtime. [Structured release evidence](validation/runtime-audit-2026-10-26.json) records these results.

This is not a fresh all-sites live pass. Assassin Scans failed its live HTTP check on GitHub twice and is tracked in [issue #2](https://github.com/badrcoderman/extensions/issues/2). The interrupted local live run encountered DNS failures for Man9a and MangaRabi. ProComic's public catalog returned HTTP 200 on 2026-10-26, but the reader required Turnstile, preventing completion of the deferred-media/map check from this connection.
