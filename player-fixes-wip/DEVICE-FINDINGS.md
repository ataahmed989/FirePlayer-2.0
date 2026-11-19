# Device findings — The Odyssey (2026), 4K 23.976, ~84 Mb/s, on the Fire TV Stick 4K Max (2026-11-19)

Measured on the user's TV while the film played in FirePlayer 2.0's PlayerActivity (build of commit 232fadd, i.e.
BEFORE any playback-pass fix). Display mode 3840×2160 @ 23.976, 10-bit. Wi-Fi RSSI −49, link 576 Mb/s; download
≈ 84 Mb/s sustained. CPU 62–68 °C, no thermal status. Both layers (video SurfaceView + UI) are DEVICE-composed.

## What was measured

1. **The Stick is out of memory during the film.** MemAvailable 64–152 MB, **SwapFree fell to 76 kB of 819 MB**;
   lowmemorykiller killed another app mid-film. FirePlayer 2.0: **TOTAL PSS 570–646 MB** =
   **Dalvik heap 252–264 MB** (the film buffer: ExoPlayer's allocator byte[]s; 269 MB allocated) +
   **"GL mtrack" 121–198 MB** + ~50 MB native + ~117 MB system; 108 MB of it was in swap.
2. **GL mtrack is the decoder's output frames, not the Glass look.** It was 121 MB with Glass on and 198 MB with
   Glass off; that is 5–8 frames of 3840×2160 10-bit (≈ 25 MB each). HWUI's own caches were only ~53 MB
   (textures 11.8 MB + scratch 41.3 MB, 359 entries) with Glass on. So fix 4's "what holds the ~100 MB" = video
   output buffers: the lever is how many decoded frames the renderer/pacer holds at once, not Glass.
3. **With Glass on, the UI redraws ~20 times a second during plain playback with nothing on screen** (controls and
   panel hidden): 1,233 UI frames in 62 s (31 % janky), RenderThread 15–20 % CPU, `FirePlayerGlass` thread 6–9 %.
   **With Glass off: 13 UI frames in 20 s**, RenderThread idle. So the Glass backdrop keeps capturing/blurring and
   invalidating while no glass shape is visible. With the Settings panel open it was 47 UI frames/s (54 % janky).
4. **Smoothness, 62 s of plain playback (Glass on, memory exhausted): 88 of 1,250 refreshes repeated (7.0 %)** —
   one 33-refresh freeze (1.4 s), one 6, three 3s, and a ~3 s burst of alternating holds
   (`22.2..2222.222.23..222...22...222.222`, the every-other-frame pattern). A 5 s sample earlier, with more memory
   free, was perfect (126/126). So the losses track memory exhaustion plus the pacer's flip-flop pattern.
5. `ExoPlayer:Loader` used 37–40 % of a core at 84 Mb/s while memory was exhausted, 12 % once memory recovered.
6. Audio is decoded on the Stick to 48 kHz 16-bit stereo PCM (audioserver ~25 % CPU): no passthrough in use.
7. **Title matching bug:** the player's Settings panel shows "The Odyssey (2026) — Not matched" for a film started
   from Films & Shows (the ids handed over are not reaching the player's match state).

## What this means for the fixes (priority order)

1. **Stop all Glass work when no glass is visible** (the biggest free win): when the controls and windows are hidden,
   the backdrop capture/blur thread and every invalidation must stop completely (0 UI frames/s), and start again
   instantly when controls show (keep it responsive: capture one frame at once on show, then refresh). While glass IS
   visible during 4K/HDR on this Stick, lower the refresh automatically (the user allows using the Glass refresh
   setting: e.g. 4 → 2 a second). Don't change the main or additional button layouts.
2. **Film buffer: 128 MiB (fix 3) is the ceiling on this device, and by memory, not only by tier:** with the decoder
   holding up to ~200 MB of frames, a 256 MiB buffer cannot fit. Size it from MemAvailable at start as well as RAM,
   keep the back buffer small (6 s ≈ 63 MB at 84 Mb/s is too much here: cap the back buffer in bytes, e.g. ≤ 24 MiB),
   and step down on onTrimMemory.
3. **Hold fewer decoded frames:** check how many output buffers the paced renderer keeps dequeued/queued ahead; at
   25 MB a frame each one matters (fix 2's "skip instead of queueing a same-refresh frame" helps here too).
4. **Pacer (fix 2):** the alternating-hold burst is the flip-flop; EMA with hysteresis.
5. **Film-mode memory before start** (stop Live TV, drop page images and caches, trim graphics) must really run for
   plays from Films & Shows; trim HWUI caches again when the controls hide (41 MB of scratch textures).
6. **Title matching:** fix the "Not matched" for add-on plays (use the tt/TMDB ids handed over).
7. Also asked by the user (2026-11-19): make sure the player's **display size features** (fit / zoom / stretch etc.)
   still work properly after these changes; keep Glass responsive; smooth, fluid transitions where possible
   (translation/alpha only); no change to the main and additional button layouts.

## How to re-measure on the device (for the checklist)

Frame timing: `dumpsys SurfaceFlinger --latency "SurfaceView - app.fireplayer.next/app.fireplayer.PlayerActivity#0"`
(gaps between shown times in refresh periods; anything > 1 is a repeat). UI redraws: `dumpsys gfxinfo <pid> reset`,
wait, `dumpsys gfxinfo <pid>` (Total frames rendered should stay ≈ 0 during plain playback). Memory:
`dumpsys meminfo <pid>` (Dalvik Heap, GL mtrack, TOTAL PSS) and `/proc/meminfo` (MemAvailable, SwapFree).
