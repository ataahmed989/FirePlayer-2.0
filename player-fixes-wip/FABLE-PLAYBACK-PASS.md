# Playback pass (Fable) — brief

FirePlayer 2.0 (Fire TV, Kotlin, Android Views, no Compose, no coroutines; minSdk 22). Device: Fire TV Stick 4K Max
(MT8696, Fire OS 8 / API 30, about 1.7 GB usable, UI 960×540 dp). TV: Samsung TU7000 (HDR10, HDR10+, HLG; no Dolby
Vision). Gradle project `firestick-player-main/`; build and all unit tests:
`cd firestick-player-main && export JAVA_HOME=$(ls -d /usr/lib/jvm/*17* | head -1) ANDROID_HOME=/opt/android-sdk && ./gradlew -q assembleDebug testDebugUnitTest --offline`
(813 tests pass at the start).

Read first: `design-brief.md` §6.5 (playback stages, switching, Sources) and §6.6 (the approved player fixes and film
mode memory), `design-layout.md` (look and the decisions log at the end), `PROGRESS.md` §3f and §6. Earlier
measurements and analysis (don't redo them, build on them): the scratchpad reports `choppy_r_code-paths.md`,
`choppy_r_hdr-decoder.md`, `r3_choppy-test.md`, `rev_fireplayer-playback.md` in
`/tmp/claude-0/-home-user/a41c2390-98bb-50f9-a8b8-51037a408bc6/scratchpad/`.

Rules for the whole pass:
- **Keep FirePlayer 1.0's VOD player design and capabilities.** Nothing visible changes in the VOD player except what
  the fixes below need.
- **Shared look:** any new or changed control uses the shared components and the one shared place for look values
  (Tokens / Glass / VodSizes and the shared button components), so the later look pass edits one place.
- **Lean and smooth on the Stick:** no per-frame allocation or layout during playback; translation/alpha only for
  animation; measure, don't guess, wherever a unit test or a log line can show it.
- **Privacy:** never log, store or show add-on URLs, keys or resolved links.
- **Surviving restarts:** commit work in progress to your branch at least every ~20 minutes; if your branch already
  exists from an interrupted run, continue from it.
- No adb or device access: the device test is done afterwards with the user. Say exactly what to check on the TV.

## Part 1 — VOD player: review, then fix and optimise

1. **Review** the VOD playback path end to end as it is now (after Films & Shows step 3): the title page / Continue
   Watching handoff (`vod/play/*`, `vod/sources/*`), `PlayerActivity`, `PlayerHost`, FramePacer, buffering
   (LoadControl), the decoder/HDR path, subtitles (libass), skip previews, the Glass look in the player, and memory.
   List what is wrong or slow, with file:line and how you know.
2. **Apply the eight approved fixes** (§6.6). They exist as patches in `player-fixes-wip/` written before step 3
   changed `PlayerActivity`: adapt them to the current code rather than forcing them in, and re-check each against the
   review:
   1. count every lost frame (held twice, same refresh, never decoded) with memory and temperature, in Picture & sound
      and the log;
   2. steadier FramePacer: move ±1 refresh only on sustained drift (EMA), skip instead of queueing a same-refresh frame;
   3. film buffer sized to device RAM (128 MiB + 6 s back buffer below 1.8 GiB; 64 / 176 / 256 MiB tiers), stepping
      down on memory pressure;
   4. keep the Glass look, but during 4K HDR on low-memory Sticks lower its refresh automatically (e.g. 8 → 2 a second)
      and probe less; find what holds the ~100 MB of graphics memory and release it if it is the Glass look;
   5. no second 4K decoder for skip previews on this Stick; release the preview after 3 s;
   6. mask HDR10+ data before decoding when the display lacks HDR10+;
   7. lost frames count in CopyHealth (over 2 % for 10 minutes = troubled); honest "Range" row on SDR screens;
   8. build the libass renderer only for films with an SSA/ASS track.
3. **Finish step 3's gap:** while Play is waiting for the add-ons, pressing OK again plays the best source so far
   (today it still waits for the other add-on).
4. **Speed:** make starting a film faster (from OK to first frame) and returning to the page faster, without
   breaking the agreed waits and checks: e.g. overlap stage 2's link check with player preparation where safe, avoid
   duplicate work between the title page and the player, warm what can be warmed. Report before/after timings from
   logs you add (the user will read them on the device).
5. **Second download connection (fix 12):** don't build it; add what's needed to measure whether Real-Debrid limits
   speed per connection (a log of sustained download rate during playback), so it can be decided on the device.
6. **Fewer stalls, keeps going (user):** tune buffering for fewer stalls with the RAM-sized buffer (rebuffer and
   start thresholds); on a stall or a recoverable error, recover automatically — re-open the same source at the same
   position, refresh an expired link once, then follow the agreed switching rules (§6.5) — so the user is never left
   on a frozen picture or an error screen when a retry or the next source would work. Log each recovery.
7. Unit tests for the pure logic (pacer decisions, buffer tiers, lost-frame counting, the OK-again rule, timers,
   the recovery rules).

## Part 2 — Live TV player (after Part 1)

1. **Buffering symbol:** a buffering indicator (spinner) over the live picture while it buffers — full screen and the
   guide's miniature — appearing only after a short delay (about 300 ms) so quick zaps don't flash it, and gone the
   moment frames play. Smooth (animated with a cheap rotation; nothing that forces layout every frame).
2. **TV Guide and Channel History buttons larger** (the round buttons at the bottom left of the full-screen live
   controls): larger than today, matching the size of the VOD player's main round controls.
3. **Captions button:** the same size as the VOD player's Subtitles button by default, even when captions are Off.
4. **Programme line when there is no programme:** the live progress line becomes a **fully filled blue line, dulled**
   (lower opacity) when there is no programme data, with a short "No programme information" text placed with the line
   (not in the info pods). Its start and end times are the current **30-minute slot** (e.g. 9:00–9:30), or end at the
   next programme's start if one starts before the slot ends (e.g. 9:00–9:20). Normal programmes keep today's line.
5. **Speed:** make channel changes and opening Live TV faster (time from key press to first frame, and to the guide
   showing), e.g. reuse the decoder/surface between zaps, start loading the next stream before tearing down the old
   one where safe, cache what the guide needs. Report before/after timings from logs.
6. **Keeps retrying, fewer stalls (user):** on a stall or error (network drop, provider hiccup, stream ended)
   reconnect automatically with backoff (about 1, 2, 4, 8 s, then every 30 s while the user stays on the channel),
   keeping the last frame with the buffering symbol and showing a small "Reconnecting…" only after a few seconds;
   tune live buffering for fewer stalls while respecting the user's Live TV buffer setting. Log each recovery.
7. **Larger channel logos with a better background on full screen (user):** in the full-screen live controls, show the
   channel logo larger, on a better plate that keeps both white and dark logos readable (e.g. a subtle rounded
   neutral/glass plate sized to the logo, logo fitted with padding and never stretched), as one shared component with
   its look values in the shared place, so the guide can use it later.
8. Unit tests for the pure logic (the 30-minute slot rule, the spinner delay rule, the reconnect backoff).

## Part 3 — Settings (small, after Part 2)

1. **Playlists & EPG:** "Manage playlists" and "Update playlists" (and the other playlist entries, "Add playlist" and
   "Reorder playlists", if that is how "EPG sources" groups its own) become indented entries under a "Playlists" row
   with the dropdown arrow, exactly like "Manage EPG sources" sits indented under "EPG sources" — the same component
   and behaviour (arrow, expand/collapse, indentation), nothing copied.

## Output

Commit on your branch with clear messages (end each with the two attribution lines you are given). Report: what you
found, what you changed (file list), the build/test result, the timings logged, risks, and a short **device checklist**
for the user's TV (what to play and what to look for).
