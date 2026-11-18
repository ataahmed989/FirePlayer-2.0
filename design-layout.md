# FirePlayer design and settings draft

Updated: November 17, 2026. Working specification from the design conversation; no app implementation is implied. User is still designing settings. Generated concepts illustrate direction, not approved final layouts or functional prototypes.

## Visual system

- Preserve original VOD Figtree typography, off-white text, blue #3D8BFF accent, rounded controls/panels, information pods, and focus motion.
- Classic and optional glass share geometry. Glass uses blurred imagery, glaze, bright rims and soft shadows.
- Focused items use white backgrounds and blue text/icons. Playing-channel identity stays blue when unfocused; focus and playing state are distinct.
- All tabs share a stationary blue/fog background independent of the featured title. Latest direction: house-free forest/lake with bluish rather than green trees, heavily blurred for the concept. Background blur should be an appearance option; final artwork selection remains open.
- Preserve the approved Home concept as the base; change only requested elements. Do not generate further revisions without a user request.

## Home and app navigation

- Navigation destinations: Home, Movies, TV Shows, Live TV, My List, Search, Settings.
- Left tab rail collapses when moving right into content. Exact expansion/return gestures remain open.
- Featured header rotates through several titles when idle. Timing, pause-on-focus and transition remain open.
- Horizontal shelves keep the selected, enlarged tile anchored at the left, with a small sliver of its predecessor visible. Titles scroll smoothly, do not wrap indefinitely; final tile also reaches this anchor.
- Keep the original hero, Continue Watching, portrait shelves and reserved top-right window structure.

## Top-right window outside Live TV

- When a live channel continues across tabs, it occupies the floating top-right player.
- Long Back selects the window and reveals channel/program details plus sound, full-screen and close controls. D-pad can also reach it.
- Remove the sentence beneath the original PiP window.
- Without active PiP, the same reserved window shows scrollable recent-channel history and program details.
- Long Back takes focus to the top/window; Center enters its internal list; Back leaves internal navigation and selects the outer window, permitting page navigation.
- Starting VOD stops live playback. Returning to Live TV removes the floating overlay and restores live playback in the guide miniature; it does not stop that channel.
- Full-screen action opens the Live TV player; Close stops the floating playback.
- External/system PiP is a separate additional-controls action for leaving the app. Device support and unavailable-state presentation remain open.

## Live TV guide and playback

- November 17 refinement: prioritize information density, uncluttered presentation and fluid TiviMate-like navigation. Earlier generated concepts are too spacious to serve as final guide proportions.
- Show only one FirePlayer brand mark in the navigation rail; do not repeat a large FirePlayer logo above the guide. Channel logos remain separate functional identifiers.
- Glass and non-glass guide versions use identical structure and navigation. Non-glass uses original FirePlayer dark surfaces (panel #0B0E12 with original translucency, dark rows #1A1E24 and subtle fills), visually aligned with the supplied dark guide reference; it must not depend on video blur. Glass changes surface rendering, not information density.
- Playlist/group column supports multiple expandable playlists, not a single flat group list. Include an All playlists entry; each playlist expands to Favorites, All channels and its own groups. Keep playlist scope visible and channel identity keyed by playlist plus channel ID. Global favorites/all-channels aggregation details remain open.
- Guide information hierarchy: compact top-left miniature with selected-program details beside it; time header and dense channel grid below. Keep long descriptions in the details area rather than repeating them in program cells.
- Proposed density targets, pending visual review: about 10–12 visible channel rows with preview/details, and more when using overlay/full-height guide; 2–3 hours visible depending on sidebar state and text size. These are design targets, not confirmed emulator measurements.
- Program cells use duration-proportional widths, small rounded corners, narrow gaps, single-line ellipsized titles and restrained separators. Channel number, small logo and name share one compact column; avoid oversized pills and thick glowing borders on every cell.
- Collapse playlist/group column when moving into the grid to gain time-axis width; provide a clear return path. Exact focus boundary gestures remain to be specified.
- Smoothness requirements for later implementation: retain focus and scroll position through updates, load only visible guide regions plus a small buffer, avoid retuning playback on mere highlight changes, keep guide/network work off the input path, and avoid continuous decorative animations. Verify on target Fire TV hardware; static art cannot demonstrate fluidity.
- Live TV tab and player form one experience, inspired by supplied TiviMate reference photos but using FirePlayer styling.
- Guide supports playlist/groups sidebar, channel/program grid, top-left miniature player, and selected-program details. Sidebar-collapse guide state and transparent/glass full-screen overlay guide are required views.
- Center on another channel/current program tunes the miniature; Center again on the playing channel opens full screen. No timed double-click.
- Hold Back while browsing the guide returns playing channel to full screen.
- Inactivity expands miniature playback to full screen; timeout is undecided (30 seconds was a suggestion only).
- Back from unobstructed full screen returns to the guide with miniature playback and retained group/channel/time position.
- Back closes the topmost panel first, then playback controls.
- Default channel changing: both D-pad Up/Down and Channel +/−, where available. Center must reveal controls.
- Alternative setting, suggested label **Channel changing buttons**: **D-pad + channel buttons** / **Channel buttons only**. In the latter mode Up/Down can also reveal controls. Reveal key must not also navigate on the same press.
- When controls/guide are open, D-pad navigates that interface. Exact Channel +/− behavior in those states remains open.

### Full-screen primary controls

- Top: channel logo/name, current program title and schedule, next program, clock.
- Bottom: original picture/sound/source pods above a minimal program tracker.
- Tracker: thin blue scheduled-program progress, start/end and remaining time; not selectable or seekable for ordinary live playback. No guide data: omit misleading progress and show an unavailable state.
- Left primary buttons: TV Guide, Channel History. Right: Captions, Favorite. Rounded FirePlayer circles/pills; additional-options chevron centered below.
- Channel History contains last ten previously visited channels; deduplication and minimum dwell time remain open.
- Additional controls include Multiview, external PiP and applicable existing VOD controls (audio, subtitle delay/style, audio delay, display size, dimmer, sleep, settings). Final order remains open. Favorite is a primary toggle; no duplicate is required.
- Pause, restart, catch-up and seekable timeshift are conditional, not assumed available on every live stream.
- Multiview: initial cap three, active tile owns sound; add/replace/enlarge/remove workflow. Equal-tile versus large-active-tile layouts, entry state and remote focus transitions remain open.

## Settings ownership — proposed, not final

- App settings own shared appearance, remote defaults, language/playback defaults, accounts and updates. VOD/Live panels expose applicable controls without divergent duplicate values.
- Player settings also contain current-title/channel actions. A session adjustment and a persistent default must be visibly distinguishable.
- API credential entry belongs in App settings; player Statuses shows status and a route to the corresponding App setting. Never show saved secrets in status rows.
- User asks to retain substantial original FirePlayer settings; omission from an unfinished list is not authorization to remove a feature.

## VOD player settings — user outline

| Section | Entries |
| --- | --- |
| This Episode/Film | Matched TMDB Title or New Title; Show Details; Picture & Sound; Fix Match; Source |
| Remote | Menu button opens |
| Look | Glass look On/Off; Glass refresh |
| Skip Intro and Credits | Skip buttons; Next episode / Exit countdown |
| Skipping | Preview while skipping |
| Defaults | Subtitle language; Audio language; Auto sync subtitles; Resume where I left off |
| Playback | Play next episodes; Match frame rate; Surround passthrough |
| Statuses | OpenSubtitles API; TMDB API key |
| App | Check for updates |

User requests OpenSubtitles API key, username and password all active in App settings. Clarify whether “active” means saved or successfully validated; existing code treats username/password as optional. Do not silently impose a new playback restriction. TMDB saved/configured and verified states should also remain distinct.

Current-title label behavior for unmatched titles is unfinished. “New Title” is the user's provisional wording.

## Live settings — user outline, incomplete

### Playlists

- Playlist list. Opening a playlist shows Name, EPG sources, Xtream Code parameters, Manage groups, Update interval, Update playlist, Delete playlist.
- Xtream parameters: Server, Username, Password, Output format, Include TV channels, Include VOD, expiration details, number of simultaneous streams.
- Expiration and provider-reported connection allowance should be read-only account information; distinguish allowance from actual active streams and device Multiview capacity. Exact UI is not final.
- Add playlist; Update all playlists; Reorder playlists.
- M3U-specific detail fields remain to be designed; do not present Xtream-only fields as universal.

### EPG

- EPG sources; Past days to keep EPG (one day); Update interval; Update EPG; Clear EPG; Last status.
- EPG primarily comes from each playlist's own source. When a playlist is missing EPG, support using another playlist's EPG as a fallback where its channels can be mapped reliably. Preserve playlist-specific channel identity and do not assume equal display names identify the same channel.
- Multiple playlist-provided sources feed the app-wide guide through channel-to-EPG mappings. This supersedes the original single-input-source constraint; a unified guide does not require a single source URL.
- Open details: automatic versus manual fallback selection, whether fallback also fills individual missing channels/programs, and handling ambiguous matches. These are not yet approved behaviors.
- One day refers to past history. Future horizon and whether the one-day value is fixed or adjustable remain open.

### Appearance

- Glass look. Remaining settings forthcoming from user.

### App settings

User has opened this category but has not completed its outline. Shared appearance, accounts and defaults above are organizational proposals, not a completed approved menu.

## Original settings to preserve or explicitly resolve

Source checked: app/src/main/java/app/fireplayer/ui/SettingsPanels.kt.

- Info pods visibility controls.
- D-pad Left/Right skip interval; Rewind/Fast-forward interval; Hold to speed up.
- Custom subtitle font import and restoration of built-in font.
- Glass refresh currently offers 2, 4 or 8 updates per second and appears only with glass enabled.
- Existing TiviMate title-proxy settings need an explicit retained/relocated decision as standalone catalogs arrive; Glass Home compatibility remains protected.
- Original subtitle styling and per-playback audio/subtitle adjustments remain part of the product.

## Open design work

- Live player settings hierarchy and current-channel section; shared-versus-specific setting ownership.
- Guide density, logos/numbers, scroll/focus restoration, time navigation and return-to-now.
- Catch-up/restart capability presentation; future/past program actions.
- Favorites, group management, search, parental PIN/blocking and lockout interfaces.
- Channel switching feedback, buffering, unavailable stream, missing EPG, offline/expired playlist and update failures.
- Multiview controls, sound ownership indication, resource/provider-limit states and exit behavior.
- PiP unsupported-device behavior and Back/long-Back precedence across page/window/player contexts.
- Accessibility: text size, contrast/transparency, motion and long-press discoverability.
- Settings change scope, reset behavior, destructive-action handling and credential validation status.
- Generated images cannot prove timing, motion or remote behavior; those need an interactive review later.

## Architecture questions carried forward

- Define independent VOD catalog import/refresh; do not rely on TiviMate warming TitleBook. Addon scope is unresolved.
- Define correct-title/episode verification, startup fallback and failure-during-playback rules.
- Provider-wide playback/probe coordination is needed in addition to per-tile serialization.
- Resolve shared persistence ownership across titles.db and live.db and the separate title-service process.
- Define target-device performance budgets and acceptance checks before claiming three-screen reliability.

## Implementation decisions log

Decisions made while implementing. "User" entries are the user's answers; "Assumption" entries are reversible
choices made to keep going and are open to change. Progress is tracked in PROGRESS.md.

- 2026-11-17 · User: FirePlayer-2.0 is the trunk. Old-repo VOD changes #75 and #76 are ported into
  `firestick-player-main/` so VOD matches the shipped app.
- 2026-11-17 · User: the launcher icon opens the new shell directly on the Live TV guide. Unfinished tabs are
  hidden (no placeholders). "Open a link" and "Subtitle style" move under Settings. When Home is functional it
  becomes the default launcher destination. `HomeActivity` stays the single LAUNCHER component.
- 2026-11-17 · User: Android Views, reusing GlassDrawable/GlassBackdrop, fonts, FireDialog, SidePanel and focus
  behavior. Recycled Views for shelves and channel lists; the EPG is a canvas-drawn View that draws only the
  visible area and handles D-pad focus, scrolling and accessibility. Selective glass; blur low-resolution, cached
  and refreshed conservatively; glass capture stops when controls are hidden; no per-cell or per-tile blur.
  Network, image decoding and EPG processing stay off the UI thread; bounded caches and prefetch. Validate on the
  target Fire TV before increasing effects.
- 2026-11-17 · User: classic shows the same stationary blue wallpaper behind its original dark surfaces.
- 2026-11-17 · Assumption: the live miniature and full-screen live player are one ExoPlayer in one Activity
  (the shell), resized between the two rectangles, so switching never reconnects or rebuffers and uses one
  provider connection. VOD keeps using `PlayerActivity` unchanged; starting VOD stops live.
- 2026-11-17 · Assumption: glass on Home/guide blurs the stationary wallpaper once (cached), not live video.
- 2026-11-17 · Assumption: test APKs are debug-signed (user: the original key is only needed for an update that
  installs over the existing FirePlayer).
- 2026-11-17 · User: brand mark is the solid blue rounded play triangle (no lighter facet), colour #0068FC sampled
  from the Home references; "FirePlayer" in bold white beside it when the rail is expanded. One mark only.
- 2026-11-17 · Assumption (from the TiviMate checklist, "Panels timeout"): Live TV › Hide controls after 4 / 6 / 10 s /
  Never, default 6 s. The quick info shown after a channel change hides after 4 s.
- 2026-11-17 · Assumption: guide inactivity → full screen defaults to 30 s (the suggested value), adjustable in
  Live TV › Full screen after no key presses (Never / 30 s / 1 / 2 / 5 min).
- 2026-11-17 · Assumption: opening Live TV with nothing playing resumes the last channel in the miniature (like
  TiviMate's "turn on last channel on app start"). Reversible; could become a setting.
- 2026-11-17 · Assumption: until the top-right live window exists, leaving the Live TV tab stops live playback
  (the stream is released and the channel remembered for when Live TV is opened again).
- 2026-11-17 · Assumption: EPG keeps programmes from 1 day back to 3 days ahead (future horizon was open).
- 2026-11-17 · Assumption: Left from the programme on air returns to the playlist/group column; browsing past
  programmes (TiviMate's long Left) is not implemented yet.
- 2026-11-17 · Assumption: the miniature has no transport controls (the generated concept's play/pause and
  full-screen buttons were noted as not agreed); without a channel playing it shows a hint panel.

- 2026-11-17 · User: the brand mark is a **hollow** blue play triangle with rounded corners and an even ring thickness
  (drawn as one 5.6-unit stroke along a rounded triangle, round joins). Supersedes the solid version.
- 2026-11-17 · User: full-screen Live controls follow the VOD player's sizes: pods are the VOD Chip pods (same size,
  same Info pods setting), round buttons are the VOD medium 39dp circles (TV Guide, Channel History, Favorite), the
  Captions pill is the VOD 39dp pill (CC icon kept), and button names show underneath only while selected. New icons
  for TV Guide (channel blocks and programme bars) and Channel History (clock in a circling arrow) from the user's
  pictures.
- 2026-11-17 · User: pausing live shows Play/Pause (51dp, as VOD's play) and Go live (39dp) at the start of the row,
  moving TV Guide and Channel History along; they stay until playback is back at live (Go live, or the stream
  reconnecting at live). No recording feature. Implementation: remote play/pause keys or the Play button; controls stay
  up while paused; Down opens more options as in VOD.
- 2026-11-17 · Assumption: Live controls use blue icons/text on the white selected button (written focus rule); the
  VOD player uses dark icons on its selected buttons. Open: should VOD switch to blue too?
- 2026-11-17 · User: **films and shows will not come from IPTV playlists**; VOD comes from Stremio add-ons with a debrid
  service. Organisation of titles, covers and rows is still open. Supersedes the handoff's "independent catalogs from
  configured playlists" and makes add-on integration agreed scope. The playlist "Include VOD" switch is hidden.
- 2026-11-17 · Assumption (user asked about a second app): debug/test builds are a separate app beside the user's
  FirePlayer — package `app.fireplayer.next`, name "FirePlayer 2.0", the hollow-logo icon and banner, Continue Watching
  at `app.fireplayer.next.continue` (Glass Home only reads `app.fireplayer.continue`, so it never sees the test app),
  and no TiviMate title server (port 8000 belongs to the installed FirePlayer). Release builds are unchanged.
- 2026-11-17 · From the user's first Fire TV test (real provider, 32,686 channels, 353 groups):
  - Tab rail: Up/Down move between tabs only (one press from Live TV reaches Settings); OK or Right opens the tab's
    page and goes into it; Left on the rail does nothing. From a page, Left/Back returns to the selected tab, never
    to whichever tab is nearest on screen. The widened classic rail is opaque (it covered the groups column with the
    column's names showing through).
  - Groups column: folding or opening a playlist keeps focus on it and the column open; updates arriving while the
    viewer is in the column keep their place. Left in an empty guide (empty group, no favorites) returns to the column.
  - Favorites: the star and the guide row change the moment the button is pressed; the database write follows.
  - Captions: live .ts channels offer the TV closed captions carried in the picture ("CC1", "Digital captions").
    Assumptions: shown over the full-screen picture only (too small in the miniature), in the Subtitle style setting;
    once turned on they stay on from channel to channel until turned off.
  - Guide: channel numbers up to five digits get a wider number column so the ▶ playing marker sits before the
    number. While a playlist's first guide is loading, its empty rows read "Loading the guide…".
  - Update buttons (Update all playlists, Update playlist, Update EPG) dim and ignore presses while an update runs;
    otherwise they show when the last update was (user request).
- 2026-11-17 · User spec: two contextual player settings panels (VOD FirePlayer Settings, Live FirePlayer Settings)
  and Individual Playlist Settings, in the existing panel design (rounded panels/rows, Figtree, blue accents, white
  focused rows with blue text/icons, optional glass; classic keeps the original dark colours). Shared settings keep
  one saved value wherever they appear. Settings not listed in the panels (skip intervals, Hold to speed up,
  subtitle font options, …) are preserved and stay reachable from the app's main Settings page; their final
  placement there is still to be planned. "This Channel / Program" (details, Picture & Sound, Source, Fix EPG Match)
  is a proposal awaiting approval: not built. Automatic versus manual EPG fallback remains undecided (the current
  automatic exact-id fallback stays provisional).
- 2026-11-17 · User answers for that spec:
  - "Include VOD" is shown in Xtream parameters, Off, and can't be turned on (not a feature yet; no playlist VOD
    loading is designed).
  - Multiview and external PiP buttons are shown dimmed until they're built.
  - Manage groups: Hide/Show — a switch per group, "Hide all", "Show all", and a "Hide new groups" switch (groups a
    later update adds start hidden). Reorder — hidden groups aren't listed; press a group to pick it up, move it
    anywhere with Up/Down, press again to drop it; each group also has a "Move to top" button beside it.
- 2026-11-17 · How the spec was built (assumptions marked):
  - One function draws each setting (ui/SettingsPanels sections), so the VOD window, the Live window and App
    settings read and write the same saved value. The full window is now called **App settings** (the home screen's
    Settings tab row, and a row under App in both player windows — assumption, so every setting the player windows
    leave out stays reachable from the player too). Its order is unchanged until the main Settings page is planned.
  - Statuses ▾ = a "Services and font" row with a summary badge (All working / n of 2 working / Needs a look / Not
    set up) that opens to OpenSubtitles, TMDB API and Subtitle font from phone. Each opens its App settings section
    as a sub-window; Back returns to the row it came from. Key states: Not set, Set · not checked, Checking…,
    Working, Not accepted, Couldn't check. A check runs after a key is saved, from "Check the keys now", and when the
    Statuses list is opened for keys not yet checked. Results are saved with a one-way fingerprint of the
    credentials (a new key is "not checked" again); keys are never shown.
  - Live FirePlayer Settings opens with Menu on the Live TV tab (when "Menu button opens" is Settings) and from the
    Settings button in full screen; the guide dims behind it. The Settings tab keeps its Playlists, EPG and Live TV
    windows (Live TV also holds Hide controls after / Full screen after no key presses, not in the spec).
  - Assumption: the Live window's "Update interval" is the EPG update interval (EPGs older than it are read again at
    start-up); each playlist keeps its own update interval in its settings.
  - Assumption: Live Defaults leaves out Auto sync subtitles (live channels don't support it: "only where supported").
  - Channel changing buttons: the two ways as rows (the chosen one ticked) with what it does underneath.
  - Playlist settings: the M3U link is never shown in full (it often carries the sign-in), only its host. Active
    connections is asked from the provider when the window opens ("1 of 2"). "Own EPG status" shows the last import.
  - Manage groups: hidden groups and their channels leave the groups column, All channels and All playlists;
    favorites keep them. The order changes the groups column only (assumption: All channels keeps the provider's
    order). Every move is saved as it happens. Reorder playlists works the same way.
  - Live full-screen extra row (Down from the buttons), VOD style: Multiview and External PiP dimmed (user), Audio,
    Audio delay (per channel, back to 0 ms on another channel), Display size (the VOD setting, full screen only),
    Screen dimmer (the VOD customs), Sleep timer (adds "End of this programme" from the guide), Settings. Under each
    button is its current value, as on VOD's row.
  - Measured on the Max: redrawing a window of 350+ rows takes 0.7–1.7 s, so group switches and moves change their
    rows in place; opening Manage groups still takes about a second (the side window isn't a recycled list).

- 2026-11-18 · User: the classic (non-glass) look has its own background — the user's dark blue-grey fog
  (`wallpaper_classic`). Supersedes "Classic shows the same blue wallpaper behind its dark panels". The glass look
  keeps the blue fog, and only glass builds the blurred wallpaper. The user will say which wallpapers to use elsewhere.

## Concept references (local preview artifacts)

- Original approved Home base: C:/Users/ataah/.codex/generated_images/01a1e677-d453-78f1-87f1-88b8a51d61ad/exec-44794608-40c2-4a3d-9f9e-7e5c7ea59b4e.png
- Latest Home illustration: C:/Users/ataah/.codex/generated_images/01a1e677-d453-78f1-87f1-88b8a51d61ad/exec-56a052a7-15b4-436e-ac0c-bc7fa3300f72.png
- Blue house-free sharp artwork: C:/Users/ataah/.codex/generated_images/01a1e677-d453-78f1-87f1-88b8a51d61ad/exec-8c58bc49-eb10-43d9-ac61-f93b8e60767d.png
- Strongly blurred background: C:/Users/ataah/.codex/generated_images/01a1e677-d453-78f1-87f1-88b8a51d61ad/exec-d0843ccf-d734-4699-9298-f2a019cec44a.png
- Live guide: C:/Users/ataah/.codex/generated_images/01a1e677-d453-78f1-87f1-88b8a51d61ad/exec-87bf2151-f567-489a-8805-e38189b87e12.png
- Full-screen Live controls: C:/Users/ataah/.codex/generated_images/01a1e677-d453-78f1-87f1-88b8a51d61ad/exec-7ed51ce9-1aa9-4704-a99b-f619dc06f09a.png
- Overlay guide: C:/Users/ataah/.codex/generated_images/01a1e677-d453-78f1-87f1-88b8a51d61ad/exec-81a719be-5a58-43e6-8591-5473cf45fa43.png

Concept discrepancies are not specifications: generated miniature shows transport controls not agreed for guide preview; group focus differs from white/blue rule; timeline cells and clip sizes are approximate. Written interaction requirements take precedence. Generated artwork requested as 4K was returned at 1672x941; do not label it native 4K.
