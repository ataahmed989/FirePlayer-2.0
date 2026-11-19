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

## Final build of the pass (commit 88dbf42), same film, played from its title page

| | Before (232fadd) | Final (88dbf42) |
|---|---|---|
| Repeated refreshes in ~60 s | 88 of 1,250 (7.0 %) | **0 of 1,431** |
| UI frames drawn in ~60 s, controls hidden | 1,233 | **6** |
| FirePlayer 2.0 TOTAL PSS | 570–646 MB | **264 MB** (Dalvik 156 MB, GL mtrack 14 MB) |
| MemAvailable / SwapFree | 64 MB / 76 kB | 223 MB / 185 MB |
| OK on Play → first frame | not logged | **3.95 s** (screen +0.39 s, link +0.42 s, player +0.45 s) |

A 1080p 59.94 episode (The Legend of Korra) from Continue Watching: 1 of 1,502 refreshes repeated, 165 MB;
leaving the player: `leave stop +719ms · player let go in 5ms`.

Found on the device and fixed (88dbf42): title pages opened after a film had started from Continue Watching were
dead (no details, sources or Play), because the title stack was only resumed when a title page was already open.

Live TV on the device: guide listed 0.27 s after opening; the buffering ring shows over the miniature while a channel
loads; full-screen controls show the larger TV Guide / Channel History buttons, the full-size Captions button when
Off, and the dulled blue line with "5:00 AM … 5:30 AM · No programme information". Not yet seen on the device: the
logo plate (the test channel had no logo), reconnecting after a network drop, the playlist settings grouping,
skipping with previews, skip buttons, display sizes, the Episodes sheet and next episode.
