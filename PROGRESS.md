# FirePlayer 2.0 — implementation progress

Working checklist kept current as increments land. Branch: `claude/blissful-mayer-2nf8z9`.

Legend: [x] done and verified · [~] partly done, or verified only with fixture data / in the emulator · [ ] not started.
"Emulator" means an Android TV API 30 x86 emulator running **without hardware acceleration** in the cloud container:
behaviour and layout are real, but speed is not (video plays at roughly 1/6 speed; a remote key press takes up to a
minute to arrive). Section 3b records the first tests on the user's Fire TV.

## 0. Environment and baseline (November 17)

- [x] Design handoff read: CLAUDE-HANDOFF.md, design-layout.md, design-brief.md, tivimate-feature-report.md,
      Design-References 01–06, Wallpapers.
- [x] Build environment: Android SDK 36/35 + build-tools 35; JDK 17 (the project pins `jvmToolchain(17)`; JDK 21
      alone fails configuration). Maven Central rate-limited (HTTP 429) first downloads; retry succeeded.
- [x] Baseline of the old repo (`firestick-player` main @ #76): `assembleRelease` OK; 325 tests / 39 classes pass.
- [x] Baseline of this repo's snapshot (old repo #74 + env-var signing + `live/`): `assembleDebug` OK (live/ groundwork
      compiles); 301 tests / 36 classes pass. Release builds need the private signing environment variables.
- [x] Emulator available (software emulation, see above). Screenshots via the emulator console.

## 1. Inventory and groundwork fixes (handoff step 1)

- [x] Ported old-repo VOD changes #75 (Display size) and #76 (renamed titles / best copy) — user approved.
- [x] live/ known issues: channel identity (stream URL → provider id / tvg-id / name+group), favorites survive
      refresh (own table), epg_url no longer cleared, removed channels/groups reconciled, EPG retention enforced
      while importing, playlist sync now feeds EPG sync, account-wide connection budget (playback side). KeyStore
      wording: credentials are in ordinary private storage, documented as not encrypted.
- [ ] SmartPlayOrchestrator identity/fallback issues — belongs to step 4 (VOD catalogs / Smart Play), untouched.

## 2. Reusable playback foundation (handoff step 2)

- [x] `PlayerHost` + `LiveSession`: one in-process live ExoPlayer moved between the guide miniature and full screen
      (one surface, resized: no retune, no reconnect); lifecycle rules unit-tested; screen stays on while playing;
      dropped streams reconnect with back-off; a dead channel is retuned on OK.
- [x] `ConnectionBudget`: provider allowance enforced app-wide for live playback (tested). Multiview/probes will
      use the same budget.
- [x] VOD `PlayerActivity` unchanged (its migration onto PlayerHost is a later, separate step).
- [ ] Database ownership note: live.db is main-process only, single writer (LiveSync); titles.db stays with the
      title service. Cross-database ownership for catalogs is step 4.

## 3. Live TV vertical slice (handoff step 3)

- [x] Playlist setup through the real UI (Xtream; M3U path implemented, fixture-tested by unit tests only):
      add, edit server/login/output/EPG link, update interval, update, delete; account expiry and allowance shown
      read-only. Emulator: added via on-screen prompts, error path ("Couldn't sign in…") seen with a bad address.
- [x] Channel + group import from the fixture provider (18 channels, 6 groups, logos) — emulator.
- [x] EPG import (XMLTV, gzip-aware, staged import, 1 day past / 3 days ahead) — emulator + unit tests.
- [x] Compact guide: 10 rows × ~2 h visible at 1080p; duration-proportional cells; now line; past dimmed; focus
      white/blue; playing channel blue with ▶; expandable playlist/group column that collapses in the grid;
      miniature top left with details beside it — emulator (classic).
- [x] Miniature playback: OK tunes; second OK on the playing channel opens full screen — emulator.
- [x] Full-screen controls (top info, pods, tracker, TV Guide / History / Captions / Favorite, chevron) — emulator,
      classic and glass, full width. In glass, the controls now blur the live picture (film glass, copied only while
      the controls show) — built and committed after the glass screenshot, not yet re-shot.
- [~] Channel History, Captions and More open FireDialog lists — implemented, not yet seen on the emulator.
- [x] Hold Back → full screen; Back from full screen → guide with the same channel/programme focus, scope and time,
      and the stream not retuned (its clock kept running) — emulator. Idle 30 s → full screen — emulator.
- [~] Channel Up/Down and CH+/− zapping when controls are hidden — implemented, not yet exercised on the emulator.
- [x] Glass and classic: same layout; screenshots of both (guide with miniature, full-screen controls). Remaining
      geometry difference: glass panel corners are rounder than classic (existing glass renderer radius table).
- [x] Test APK (debug-signed, ARM) delivered from 26ddfdf. It can't install over a release-signed FirePlayer.
- [x] Independent review (4 dimensions, each finding verified): 35 confirmed findings fixed; 355 unit tests pass.

## 3b. First Fire TV test (Fire TV Stick 4K Max, Fire OS 8 / Android 11, user's real provider)

Connected over Tailscale + ADB with the user's permission; the test app (`app.fireplayer.next`) sits beside the
user's FirePlayer. Provider size: 32,686 channels, 353 groups, ~279,000 programmes (12,249 channels have an EPG id).

- [x] Tab rail: Down from Live TV reached the page instead of Settings → the rail moves between its own tabs;
      OK/Right opens a tab; Left from a page returns to the selected tab — verified on the Max.
- [x] Groups column: folding a playlist closed the column (focus fell into the guide) → only that playlist's rows
      change, focus stays — verified on the Max (OK and Left fold, OK opens).
- [x] Captions: "This channel has no captions" on channels with TV captions → CEA-608/708 now found in .ts streams
      and drawn over full screen — verified on the Max (CC1 on a movie channel).
- [x] Favorites slow → star and guide change at once, write on its own thread — verified on the Max.
- [x] ▶ playing marker overlapped five-digit numbers → number column sized to the longest number — verified.
- [x] "EPG not working": the guide was right once the import finished; the first import took ~17 minutes with
      no feedback. Now "Loading the guide…" shows in empty rows, and a full update measured on the Max (88 MB XMLTV,
      276,074 programmes for 7,016 EPG ids) went from 282 s to 76 s for the EPG (+15 s for the channel lists):
      XMLTV times read digit by digit (a date formatter per time cost ~3 minutes), imports switch generations in
      1 ms instead of renaming every row (37 s with the database held), the old generation is deleted in short steps
      (16 s, not blocking), unused index dropped, no auto-vacuum for new databases. Storing is now the largest part
      (42 s of 60 s).
- [x] Update buttons (all playlists, one playlist, EPG) dim and ignore presses while updating; otherwise they show
      when the last update was (user request) — dimming verified on the Max.
- [~] "All playlists" (32,686 channels) took 2–4 s to list → per-playlist reads in index order, one cursor window;
      entering the grid for the scope already shown no longer reloads it. Built, not yet re-measured on the Max.

## 3c. Contextual player settings (user spec, November 17)

- [x] VOD FirePlayer Settings (This episode/film, Remote, Look, Skip intro and credits, Skipping, Defaults,
      Playback, Statuses, App) — on the Max, classic and glass.
- [x] Live FirePlayer Settings (Playlists and EPG, Remote with channel changing buttons, Look, Defaults, Statuses,
      App) from Menu and the full-screen Settings button — on the Max, classic and glass (over full screen it blurs
      the live picture).
- [x] Shared settings: one function per setting, one saved value; App settings keeps every setting (VOD panel's
      missing rows preserved there and linked from both player windows).
- [x] Statuses with saved key checks (configured vs working), linked to App settings sub-windows; Back returns.
- [x] Individual playlist settings incl. Include VOD (off, locked), account expiry/allowance, active connections
      ("1 of 2" from the provider) — on the Max.
- [x] Manage groups (switches, Show/Hide all, Hide new groups, reorder with pick-up/move/drop and Move to top),
      Reorder playlists — on the Max (user's group order and visibility left as they were after testing).
- [x] Live extra row: Multiview/PiP dimmed, Audio, Audio delay, Display size, Screen dimmer, Sleep timer,
      Settings — row seen on the Max; individual windows built and unit-tested (display geometry, sleep timer),
      not each exercised on the Max yet.
- [x] Code review of the first Fire TV fixes: 15 confirmed findings fixed. Second review (settings work): 26 confirmed
      findings fixed (window close callbacks from sub-windows, live language defaults, key-check errors, group
      choices in their own table — live.db v6 — and others); upgrade checked on the Max (groups and order kept).
- [x] Backgrounds (user's): classic — dark blue-grey fog; glass — the foggy beach with its light spiral, blurred.
      Both seen on the Max.
- [ ] Opening Manage groups takes ~1 s with 353 groups (side window isn't recycled); switches/moves are instant.

## 3d. Live TV page redesign (user concepts, November 18)

- [x] Layout: compact page, 368 × 207 dp miniature with details beside it, flat guide on the wallpaper; rail and
      groups column slide away in the grid; playlist headers larger than groups — on the Max, classic.
- [x] Guide look (26 dp header, 29 dp rows, flat cells, ▶ on the playing channel) and a gliding focus — on the Max.
- [x] Smoothness, measured on the Max with frame stats and system traces: no window rebuild when the column opens
      (adjustPan), no per-frame layout in the slide, layer fades, cheaper wallpaper around the picture. Column
      open/close ~4–13 ms per frame (was 20–55 ms plus a 193 ms first frame); shrink ~10–15 ms (was ~25 ms).
- [x] Column-open details: the time and "… min left" fit; time and "in …" spaced when the bar is hidden.
- [~] Motions start on the frame after the key press (no jump ahead over a slow first frame); full screen's info fades
      in during the grow. Built and unit-tested; not yet watched on the Max (the TV connection ended).
- [ ] Glass look of the redesign: not yet checked on the Max.
- [ ] Going full screen sometimes re-binds the groups column's rows (~10 ms on one frame); cause not found yet.
- Note: the Max had little free memory during these tests (swap full, the kernel's reclaimer busy), and the test app
  is a debug build (slower than a release build), so timings vary from run to run.

## 4. Catalogs, Home, navigation (handoff step 4) — not started

- [ ] Independent VOD catalogs from playlists; Home rail/hero/shelves (Home becomes the launcher destination when it
      works); title details; Smart Play; Continue Watching in-process (ContinueProvider untouched).

## 5. Across-tab live window, PiP, Multiview, settings (handoff step 5) — not started

- [ ] Top-right live window / recent channels (until then, leaving Live TV stops the stream); external PiP;
      Multiview (cap 3); remaining settings outline; Manage groups; Reorder playlists; past programmes (long Left);
      parental PIN UI (ParentalGate groundwork exists); catch-up.

## Contracts that must keep working (checked at every increment)

- Package `app.fireplayer`; ContinueProvider authority `app.fireplayer.continue` and its columns/URIs (untouched).
- PlayerActivity: ACTION_VIEW filters, MX Player extras/result, RESUME / PLAY_EPISODE, Glass Home WARM broadcast,
  task attributes (untouched).
- TitleService `:titles`, `TitleService$Starter`, `app.fireplayer.titles.START` (untouched; still started from Home).
- Every existing setting and pref key (Settings windows reachable from the Settings tab and the Menu button).
- `HomeActivity` stays the only launcher component.
- Release signing is supplied privately; debug/test APKs use the development key and cannot install over a
  release-signed FirePlayer. Debug builds don't offer the self-update (it couldn't install over them).
