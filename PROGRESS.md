# FirePlayer 2.0 — implementation progress

Working checklist kept current as increments land. Branch: `claude/blissful-mayer-2nf8z9`.

Legend: [x] done and verified · [~] partly done, or verified only with fixture data / in the emulator · [ ] not started.
"Emulator" means an Android TV API 30 x86 emulator running **without hardware acceleration** in the cloud container:
behaviour and layout are real, but speed is not (video plays at roughly 1/6 speed; a remote key press takes up to a
minute to arrive). Nothing here has been run on a Fire TV yet.

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
- [~] Full-screen controls (top info, pods, tracker, TV Guide / History / Captions / Favorite, chevron) — emulator
      classic; glass pending. Channel History, Captions and More open FireDialog lists (not yet screenshotted).
- [~] Back from full screen → guide with focus/scope/time retained; hold Back → full screen; idle → full screen
      (seen: 30 s idle switched to full screen) — partly verified on the emulator.
- [~] Channel Up/Down and CH+/− zapping when controls are hidden — implemented, not yet exercised on the emulator.
- [~] Glass and classic: same layout code; classic screenshots taken; glass screenshots pending.
- [x] Independent review (4 dimensions, each finding verified): 35 confirmed findings fixed; 355 unit tests pass.

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
