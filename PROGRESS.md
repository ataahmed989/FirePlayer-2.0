# FirePlayer 2.0 — implementation progress

Working checklist kept current as increments land. "Implemented" means built and exercised; "mock" means only
verified against fixture data; anything not ticked is not done. Branch: `claude/blissful-mayer-2nf8z9`.

Legend: [x] done and verified · [~] partly done / mock-verified only · [ ] not started

## 0. Environment and baseline (November 17)

- [x] Design handoff read: CLAUDE-HANDOFF.md, design-layout.md, design-brief.md, tivimate-feature-report.md,
      Design-References 01–06, Wallpapers.
- [x] Cloud build environment: Android SDK 36/35 + build-tools 35 installed; JDK 17 installed (project pins
      `jvmToolchain(17)`; JDK 21 alone fails configuration). Maven Central rate-limited (HTTP 429) the first
      dependency downloads; a retry succeeded. None of these were code failures.
- [x] Baseline of the old repo (`firestick-player` main @ #76): `assembleRelease` OK, signed CN=FirePlayer;
      `testReleaseUnitTest` 325 tests / 39 classes, 0 failures.
- [~] Baseline of this repo's snapshot (`firestick-player-main/` = old repo #74 + env-var signing + `live/`):
      `assembleDebug` OK (live/ groundwork compiles); unit tests: see below once finished.
- [ ] Emulator: the cloud container has no KVM. Android TV API 30 x86 emulator is being tried with software
      acceleration (slow); GitHub Actions with KVM is the fallback.

## 1. Inventory and groundwork fixes (handoff step 1)

- [ ] Port old-repo VOD changes #75 (Display size) and #76 (renamed titles / best copy) — user approved.
- [ ] live/ known issues (handoff list): EPG retention during import; PlaylistManager → EPG sync; epg_url
      cleared by playlist REPLACE; removed channels not reconciled; channel identity keyed by stream URL;
      SmartPlay identity/fallback; provider-wide connection limits; KeyStore wording (not encrypted).

## 2. Reusable playback foundation (handoff step 2)

- [ ] `PlayerHost`: one in-process live session (one ExoPlayer) that moves between the guide miniature and
      full screen without reconnecting; lifecycle state machine with unit tests; VOD stops live.
- [ ] Provider-wide connection coordination (account-level, not per orchestrator).
- [ ] PlayerActivity (VOD) unchanged in behavior; migration onto PlayerHost happens in later small steps.

## 3. Live TV vertical slice (handoff step 3)

- [ ] Playlist setup (Xtream + M3U), multiple playlists, update/delete.
- [ ] Channel + group import; favorites/order survive refresh; removed channels reconciled.
- [ ] EPG import (per-playlist XMLTV), 1 day past history, retention enforced during import.
- [ ] Compact guide: expandable playlists sidebar (All playlists; per playlist Favorites / All channels /
      groups), duration-proportional grid, preview top-left + details beside it.
- [ ] Miniature playback: first Center tunes, second Center on the playing channel opens full screen.
- [ ] Full-screen controls: top info, pods, non-seekable tracker, TV Guide / History / Captions / Favorite,
      more chevron.
- [ ] Back returns to the guide with focus, group and time position retained. Hold Back → full screen.
- [ ] Channel Up/Down and CH+/− when controls are hidden.
- [ ] Glass and classic: same layout, actual screenshots of both.

## 4. Catalogs, Home, navigation (handoff step 4)

- [ ] Independent VOD catalogs from configured playlists; Home rail/hero/shelves; title details; Smart Play;
      Continue Watching in-process (ContinueProvider untouched).

## 5. Across-tab live window, PiP, Multiview, settings (handoff step 5)

- [ ] Top-right live window / recent channels; external PiP (capability-gated); Multiview (cap 3); remaining
      settings.

## Contracts that must keep working (checked at every increment)

- Package `app.fireplayer`; ContinueProvider authority `app.fireplayer.continue` and its columns/URIs.
- PlayerActivity: exported ACTION_VIEW filters, MX Player extras and `com.mxtech.intent.result.VIEW` result,
  `app.fireplayer.action.RESUME` / `PLAY_EPISODE`, Glass Home WARM broadcast, task attributes.
- TitleService `:titles` process, `TitleService$Starter` and `app.fireplayer.titles.START`.
- Pref keys (`glass_look`, `glass_refresh`, `pos:<uri>`, …) and every existing setting.
- Release signing identity is supplied privately; debug builds use the standard development key and cannot
  install over a release-signed FirePlayer.
