# Download speed and connections — 2026-10-25

ProComic overlaps up to three image-piece downloads per page. At most five transfers run across the source, and a lower connection limit configured by the host is preserved. Piece order, rectangle placement, the upstream image decoder and JPEG quality (90) stay the same. The existing OkHttp client and connection pool are reused.

| Check | Sequential | Parallel | Result |
| --- | --- | --- | --- |
| Six PNG pieces, 200 ms response delay | 1,448 ms | 630 ms | 2.30× faster in this controlled fixture |
| Ten real ProComic CDN pieces, first pair | 3,474 ms | 2,160 ms | All 123,964 bytes matched |
| Same live pieces, reversed test order | 2,358 ms | 1,469 ms | All bytes matched again |

The two live pairs average about 1.61× faster for piece transfer, or about 38% less transfer time. All measurements above use the signed ProComic 1.6.4 JAR. They cover network transfers, not a whole chapter or a phone reading session. CDN load, network latency, bandwidth and decoding affect actual results. The [recorded measurements](validation/speed-2026-10-25.json) contain no reader URLs or session tokens.

The fixture ran against the graphics and decoder classes from the supplied Suwayomi 2.4.2366 AppImage. A shuffled six-piece page assembled successfully; dimensions, destination positions and decoded colors matched. An explicit full-image source rectangle also fixes Suwayomi's null-source-rectangle crash. Stock Suwayomi still lacks an AVIF piece decoder; that separate limitation remains in [issue #1](https://github.com/badrcoderman/extensions/issues/1).

Ahead-of-time reads stop near 2 MiB per piece. Larger pieces continue streaming when needed, without truncation. Shared slots bound ahead buffering to roughly 10 MiB plus stream buffers. Canceling a page closes its HTTP requests and responses; failed or canceled work releases those slots. A partially interrupted piece body can restart once. Idle worker threads expire, and server rate limits retain the existing retry policy.

Each page acquires transfer slots in piece order. An interrupted body's retry retains its slot, so ahead buffers cannot block the next required piece. Regression checks include four pages competing for one host slot and a large-body retry with the same limit.

All nine API 1.4 sources and ProComic now share refresh work only for the same chapter. Different chapters can refresh independently, and cache hits do not wait behind a slow request. Successful refreshes remain cached for ten seconds; errors are not cached. ProComic's renewal URL was corrected to the site's `/chapter-deferred-media/{id}` endpoint, verified with the public reader and now included in daily health checks alongside the map endpoint.

## Reproduce the desktop fixture

Build the ProComic release classes, extract the provided Suwayomi AppImage, and use Python with Pillow plus a JDK 17+ compiler:

```bash
python scripts/desktop/check_graphics_and_speed.py \
  --server-jar /path/to/squashfs-root/bin/Suwayomi-Server.jar \
  --extension-jar build/repository/jar/tachiyomi-ar.procomic-v1.6.4.jar \
  --java /path/to/squashfs-root/jre/bin/java \
  --output /tmp/procomic-speed-check
```

Network regressions cover cross-chapter refresh isolation, shared refreshes, expiry/eviction, global transfer limits, lower host limits, image ordering, large chunked pieces, cancellation, partial-body recovery and rate-limit behavior.
