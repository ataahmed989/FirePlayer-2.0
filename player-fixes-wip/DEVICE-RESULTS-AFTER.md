# Device results after the VOD stage's first changes (commit f7ba088) — same film, same Stick, 2026-11-19

The Odyssey (2026), 4K 23.976, ~80 Mb/s, resumed from Continue Watching in FirePlayer 2.0, Glass look on.

| | Before (232fadd) | After (f7ba088) |
|---|---|---|
| Repeated refreshes in ~60 s of plain playback | 88 of 1,250 (7.0 %), one 1.4 s freeze | **0 of 1,436 (0.0 %)** |
| UI frames drawn in ~62 s with controls hidden | 1,233 (RenderThread 15–20 % CPU, FirePlayerGlass 6–9 %) | **34** (RenderThread 0 %) |
| FirePlayer 2.0 TOTAL PSS | 570–646 MB | **254 MB** |
| Dalvik heap (film buffer) | 252–264 MB | 158 MB (buffer 128 MB cap) |
| GL mtrack | 121–198 MB | **14 MB** |
| MemAvailable / SwapFree | 64 MB / 76 kB | 177 MB / 107 MB |
| ExoPlayer:Loader CPU | 37–40 % | 8.5 % |
| Title in the player's Settings | "Not matched" | **"Matched"** |

The player's own log (FirePlayerDiag, every 10 s): shown ≈ 241, dropped 0, twice 0–1, same 0–1, never 0;
buffer 125–129 MB of 128 MB = 10–12 s ahead; download 67–103 Mb/s (best 122.7). Glass refresh shows
"4 a second · 2 in 4K".

Still open / to watch:
- Only ~10–12 s of film fits in 128 MB at this bitrate, and the download rate (67–85 Mb/s typical) is close to the
  film's bitrate: a dip in the network will stall it. Recovery after a stall must be quick and automatic.
- Not yet checked on the device: skipping with previews, the skip buttons, subtitles (ASS), display size options,
  controls showing/hiding (Glass must appear at once), returning to the page, a show's episodes and next episode.
