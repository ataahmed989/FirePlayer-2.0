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
- Later app pass with Fable (PROGRESS.md §6): glass windows and small/hold windows, colours, larger FirePlayer 2.0
  logo in the rail, larger and better channel logos, faster loading, fluid motion (groups window shifts when the rail
  opens instead of a layer on top; Live TV buffering animation), navigation tweaks in the guide and title rows.
  Also from the concept: its premium glass windows, the tab window's glass (not too glazed), its icons and logo; keep
  the slim rail strip on Live TV but let the rail become an edged glass "bubble" window on the other pages.

## Architecture questions carried forward

- Resolved 2026-11-18: films and shows come from Stremio add-ons (Cinemeta, TMDB for browsing; Torrentio and
  MediaFusion for playback); see design-brief.md §6. IPTV films and shows are excluded.
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
- 2026-11-18 · User: the glass look's background is a blurred version of "Foggy Blue Beach with Light Spiral" — the
  user's own `Wallpapers/Foggy-Blue-Beach-Light-Spiral-Blurred.png` (`wallpaper_glass`); the blue fog is retired.
  Glass shapes blur it once more ([StillGlass]); nothing is blurred per frame.

- 2026-11-18 · User: Live TV page redesign after the user's four concepts (direction, not exact layouts; the
  user's backgrounds and font stay). More compact, less free space; a larger miniature than in the concepts
  (368 × 207 dp, the details beside it); the playlist name header slightly larger than the groups (16.5 sp vs
  14.5 sp). No panels: the column, details and guide sit on the look's wallpaper. Grid (guide focused): the tab rail
  and groups column slide away and the content takes the full width; column open: rail, column, then the content.
  Guide: header 26 dp, rows 29 dp, channel column 256 dp; flat rounded cells (classic dark grey; glass a faint white
  with a rim); a ▶ after the playing channel's name; the focus highlight glides from cell to cell.
- 2026-11-18 · User: smoother motion ("more fluid … from a miniature player to a larger player … better sliding").
  One motion for the column, the rail and the content (220 ms, fast out, slow in), with the miniature moving in step;
  the picture grows to full screen (280 ms) and shrinks back (240 ms) while the guide fades. Measured on the Max and
  fixed:
  - Opening the column rebuilt the app's window (44–193 ms stall): Android picked the keyboard-resize mode again
    whenever a scrolling list showed or hid. HomeActivity now states adjustPan (text boxes pan above the keyboard).
  - The slide no longer lays the screen out every frame: the content moves as a whole and is laid out once (the
    details take their open width as the column starts to open, the guide its open width when the slide ends).
  - The guide and the column fade as layers drawn once, not into a new screen-sized layer every frame.
  - The wallpaper around the live picture is four strips and a rounded outline for the corners (was the whole screen
    drawn through a rounded cut-out, then a shape the graphics re-drew into a texture every frame).
  - Result (warm, under the TV's memory pressure): column open/close about 4–13 ms per frame after the first (was
    20–55 ms, and a 193 ms first frame); shrink about 10–15 ms (was ~25 ms every frame); grow about 10–14 ms.
  - A slow first frame (focus moving, a layout, something drawn the first time) made the slide jump ahead on some
    runs (screen recording). Motions now start on the frame after the key press, so they play from the beginning;
    full screen's channel and programme fade in halfway through the grow instead of appearing over the guide.
  - Assumption: with the column open, the details' progress bar is shorter (44 dp) so "… min left" fits. Fixed in
    passing: the time and "in …" ran together when the bar was hidden (a programme not on now).

- 2026-11-18 · User: App settings organised into categories on the Settings tab — Playlists & EPG, Live TV,
  Playback, Remote & skipping, Subtitles & audio, Look, Accounts & services, TiviMate titles, App — each its own
  window; "Open a link" stays on the Settings tab (a tool, last). The player windows' "App settings" row and Menu open
  the same category list. Assumption: two columns of five (ten rows don't fit one column on a 540 dp screen).
- 2026-11-18 · User: Manage groups opens its own window from a playlist's window — hide/show (a switch per group,
  Show all, Hide all, Hide new groups) and the order — "easy and smooth"; with an "Assign favorites" button: the
  playlist's categories, then a category's channels; pressing a channel turns it blue with a filled star. The playlist
  and Manage groups windows are wider (about 44% of the screen) so full names show. Long lists are recycled (353 groups
  open in 64 ms on the Max, was ~1 s). Assumptions: hidden groups listed last (faded) in Assign favorites; each group
  shows its count of favorites.
- 2026-11-18 · User: holding OK on a channel opens a small window — a star (focused), the full channel name and
  "Assign favorites" (opens Assign favorites at that channel). OK on the star adds or removes the favorite at once;
  Back or the D-pad past the options closes it. Assumption: the window closes by itself ~0.45 s after the star changes,
  so the viewer sees it fill.
- 2026-11-18 · User: holding a channel group acts like holding a channel — a small window with the group's name,
  "Move to top" and "Hide category" (supersedes "holding a group opens Manage groups"). Assumption: after Hide, a short
  note says it can be shown again in Manage groups.
- 2026-11-18 · User: with the groups column and rail slid away (the grid) the channel-names column is wider
  (320 dp; 256 dp with the column open), changing in step with the slide.
- 2026-11-18 · User: "as smooth but lean as possible without over using things like the graphics card" — a standing
  rule: no new blur, shadows or extra layers; short transform/alpha motions; nothing animates while idle.
- 2026-11-18 · User (concept art): darker backgrounds — glass a dark grey-blue (not bright blue), classic darker; the
  channel's logo beside its name in the details; a highlighted plate behind every channel logo (guide, details, full
  screen); one more row so the guide reaches the bottom; Down past the last item goes to the first and Up past the first
  to the last in the tab rail, the groups column and the channel list (a held key stops at the end); the TV Guide button
  returns to the guide on the playing channel; the Settings icon is a rounded gear like the other line icons; in full
  screen, Up / Channel + goes to the next channel down the category's list and Down / Channel − to the previous one.
  Assumptions: two plates (soft white, or light grey for dark ink on a see-through logo), chosen once per logo; the
  10th row is partly cut at the screen's edge on purpose.
- 2026-11-18 · User: Playlists and EPG, better organised — the playlists; "Manage playlists" (Add playlist, Reorder
  playlists); "Update playlists" with when it last updated; "EPG sources" (N of M loaded, closed by default) opening to
  "Manage EPG sources" (each playlist's EPG and its programmes, Days to keep EPG, Update interval, Update EPG, Clear EPG).
  Days to keep EPG stays a fixed 1 day for now.
- 2026-11-18 · User: a "Live TV buffer" setting (Small, Medium, Large) in Live TV settings and in the main settings'
  Playback — one saved value. Medium is the buffer live channels always had (15 s / 30 s, start 1.5 s, 3 s after a
  stall); Small 5 s / 10 s / 1 s / 2 s; Large 30 s / 45 s / 2.5 s / 5 s (kept modest: the Max is short of memory). A
  change reloads the playing channel once. Films keep their memory-based buffer.
- 2026-11-18 · User: the idle timer may be longer (owner's choice). Default becomes 1 minute (the user's TV is
  already set to 1 minute).
- 2026-11-18 · Found on the Max: a paused Stremio film played behind FirePlayer 2.0 — neither player claimed Android's
  audio focus, and Play/Pause pressed over the guide went unused, so Android handed it to Stremio's media session. Both
  players now claim the sound (other apps pause; Alexa pauses or lowers them for a moment); HomeActivity keeps every
  media button it doesn't use; in the guide Play/Pause pauses or resumes the miniature's channel ("Paused"/"Playing").

- 2026-11-18 · User: Remote is "Menu button opens", then one "Channel ▲▼" row that opens the same small list of
  choices (D-pad + channel buttons / Channel buttons only) — no ticked rows or note. Under every Remote header that is
  about TV (Live FirePlayer Settings, Settings › Live TV, Settings › Remote & skipping). The rule for every window: a
  setting with a few options is one row showing its value, opening the small choice window.
- 2026-11-18 · User: Live TV page after TiviMate (the user's photos): a slim flat rail strip (58 dp) flush with the left
  edge; the playlist in use in blue in the groups column; bigger, calmer lists (groups 16 sp, rows 39 dp, column 224 dp;
  channel names 15.5 sp, programmes 15 sp), exactly 8 channel rows (35 dp) under the unchanged 368 x 207 miniature.
  Glass background darker again (same grey-blue).
- 2026-11-18 · User: the group's held window has "Manage groups" under Move to top and Hide category (Down reaches it);
  it opens Manage groups for that playlist on the held group. "Hide category" stays the user's wording.
- 2026-11-18 · Settings consistency pass (user: headers with icons everywhere, consistent option layouts) and a review of
  the round: 15 confirmed bugs fixed (e.g. a paused live channel no longer goes full screen on a frozen frame; a hidden
  group can't come back through TV Guide; Play/Pause reaches live while a window has focus).

- 2026-11-18 · User (films and shows, after three proposal rounds): titles from Cinemeta + TMDB; playback from the
  user's Torrentio and MediaFusion add-ons only when needed; one poster per IMDb id; IPTV films and shows excluded;
  Local Files not used. Full list in design-brief.md §6.
- 2026-11-18 · User: wait for both add-ons before starting; at the first-loading limit ask "Keep waiting / Try next
  source"; switching numbers approved (30 s settling; 3 stalls in 2 min, one over 20 s or 25% of 2 min; 3 tries,
  2 switches, 3 min apart); sources not on Real-Debrid yet never automatic, ask once.
- 2026-11-18 · User: no syncing of any kind (Stremio sync judged too fragile: private API, a bad write can empty a
  Stremio library). FirePlayer keeps its own history; Glass Home shows it.
- 2026-11-18 · User: posters open a title page with Play, Play from beginning (once started; restarts the current
  episode on shows), Episodes, More Like This, Sources; layout A (rows on the page). Continue rows go straight to
  playback on the same file (Continue Watching on Home mixes films and series; Movies has films only; TV Shows has
  Continue Watching · Series), like Glass Home. Sources are looked up when a title page opens.
- 2026-11-18 · User: Movies/TV Shows header rotates the page's Top 10 while idle and shows the focused title only
  along the Continue row (same on Home). 15 posters per row; hold OK: My List, Mark watched; MediaFusion rows off.
- 2026-11-18 · User (sizing page): posters 6.5 across at 3:4 (121 × 162 dp), Continue Watching wide cards 4.5 across
  (182 × 102 dp), header 195 dp, medium text, chosen card grows 16%, and Home's header slides away once you scroll
  past Continue Watching. Details in design-brief.md §6.3.
- 2026-11-18 · User: glass look background keeps the blurred foggy beach with the spiral, recoloured to the approved Home
  concept's background (deep teal-navy, lighter at the top, darker towards the bottom: about RGB 18,36,49 at the top to
  8,14,21 at the bottom, with almost no red). Made from Wallpapers/Foggy-Blue-Beach-Light-Spiral-Blurred.png by mapping
  each row's brightness and tint to the concept's page edges while keeping the image's own detail.
- 2026-11-18 · User: keep the Samsung TU7000's picture settings (Standard colour tone, gamma BT.1886 +3, Color 27, Contrast
  35, Brightness 50) and tune the background on FirePlayer's side to look like the concept on the iPad. Measured from a
  photo with both in the same shot, the TV roughly doubles blue and crushes red in dark tones, so the glass background
  now sends a warmer grey (about RGB 33,27,22 at the top to 18,18,11 at the edge) that lands on the concept's teal-slate
  on this TV. On an accurate screen it looks brownish-grey; a per-TV choice (tuned for this TV / accurate) belongs on
  the This TV page later. Awaiting the user's side-by-side photo to refine.
- 2026-11-18 · User: the TV-tuned grey looked clearly brown on the TV; drop TV tuning and the photo calibration. The
  glass background is now the user's own 4K blurred beach with the spiral, "matched to the concept" (the less blue of
  their two; `Wallpapers/Foggy-Blue-Beach-Light-Spiral-Tint-4K.png`, about RGB 19,30,41 at the top to 11,18,22 at the
  bottom). Shipped as `wallpaper_glass.png` at the screen's 1920 × 1080 (an exact 2:1 average, with fine grain like the
  original's so dark gradients don't band), lossless: JPEG, even at quality 100, moved pixels up to 4 levels and
  nudged the average red. Their bluer "TV look" version (`…-TV-Look-4K.png`) is the next to try if this one isn't blue
  enough.
- 2026-11-18 · User: the channel up/down buttons change channel only within the group of the channel that's playing
  (down that group's list), not the group the guide happens to show. Later (PROGRESS.md §6).
- 2026-11-18 · User: the title page uses the same UI parts as Home, Movies and TV Shows (rows, cards, header parts,
  buttons, hold and ⋯ windows, Details, notices), and every look value (colours, radii, glass, focus growth, type sizes,
  spacing) comes from one shared place, so a later look change to windows and glass applies to every page at once.
- 2026-11-19 · User: the rail's Search tab is for films and shows only; Live TV gets its own Search button on its page
  (channels and programmes), later (PROGRESS.md §6).
- 2026-11-18 · User (second playback check): no shrinking film buffer under memory pressure; FirePlayer 2.0 frees memory
  before VOD playback (Live TV, page images, caches) while keeping returns instant (snapshot); background release only
  after a delay; no Glass Home changes until integration; test a second download connection before building it.
- 2026-11-18 · User: Top 10 numbers use Glass Home's ranked-shelf design (solid white Inter ExtraBold beside each poster).
- 2026-11-18 · User (sizing page comments): no More Like This button on the title page (it stays a row); TV Shows'
  row is "Continue Watching · Series"; holding OK on a title opens a small window with "+ Add to list" (chosen first)
  and Mark watched; on Continue Watching cards also "Remove".
- 2026-11-18 · User: title rows scroll like the Episodes sheet (chosen tile at the left edge, grows, one per press,
  held D-pad glides).
- 2026-11-18 · User: best source depends on each TV (a This TV page in Settings); no file-size limit for now.
- 2026-11-18 · Playback test on the Max (Spider-Man: Brand New Day, 4K DV + HDR10+, 26 Mb/s, MediaFusion): about
  1 frame in 6 lost in the user's session, smooth in a replay and after HDR was enabled. Contributors: the Stick out
  of memory, FirePlayer's ~230 MB film buffer and ~100 MB graphics held after the menu opened with Glass look on,
  the TV reporting no HDR (Samsung Input Signal Plus was off; now on). User approved eight player fixes
  (design-brief.md §6.6), keeping the Glass look with an automatic lower refresh in 4K HDR.

- 2026-11-18 · Films & Shows step 1, pages (Home, Movies, TV Shows on the rail; the app still opens on Live TV).
  Assumptions, all reversible:
  - From the sizing page: a 3 dp white ring hugging the chosen card; 10 dp between a row's title and its cards and
    18 dp under each row. Scrolled down, the "half row" is the next row's title (two full rows of posters show).
  - Header: text only (no logo) and no buttons yet; Up from the first row does nothing. Its backdrop is a box at most
    2.6 header heights wide at the card's right, fading to clear over its left 45% and bottom 35% (baked once).
  - The hold window says "Mark unwatched" for a title already watched; holding OK on See all or a genre does nothing.
  - Top 10 rows have no See all card; the library's rows (My List, Continue Watching, Next Episodes) show it only past
    15 titles; Cinemeta rows always do.
  - See all opens in place of the rows (Back returns to the card it came from) and closes on a tab switch. Its grid
    is 6 posters across, the chosen one grows about its middle; at most 300 titles (each poster is a view of its
    own while the grid is open; reusing a few views would allow more).
  - Genre chips and See all cards are flat fills in both looks (glass in a moving row would show the wrong part of
    the wallpaper). The header and Home's window are glass panels; their frosted picture slides with them while the
    page glides (accepted, to check on the TV).
  - Home's top-right window shows the last three channels watched on Live TV with what's on now (read-only) until
    the live picture can follow the viewer there.
  - Top 10 numbers use Inter ExtraBold on Android 8 and later (bundled with its OFL licence); older Android has
    Figtree Bold.

- 2026-11-18 · Films & Shows step 1, data and the two halves joined (rows from Cinemeta and TMDB, library in vod.db).
  Assumptions, all reversible:
  - Mark watched also takes a title out of Continue Watching until it is played again; the rows drop it at once, as
    with Remove.
  - A TMDB key that TMDB refused counts as no key (Top 10 rows hide, the header uses Cinemeta Popular) until the key
    changes or a day passes.
  - Next Episodes stays empty in this step; a Genres row's chips come from Cinemeta's manifest (a built-in list when it
    can't be read).
  - Wide Continue Watching cards use the smaller backdrop (metahub background/small, TMDB w780); the header keeps the
    medium one.

- 2026-11-19 · Films & Shows step 2, the title page (layout A; design-brief.md §6.4). Built from the same parts as
  Home, Movies and TV Shows so one change to the look reaches every page: rows, cards and their motion (TitleRow,
  CardStrip, CardView, RowMotion), the column (PageColumn), the backdrop (HeaderArt) and the header's text styles, the
  small window (HoldWindow over HoldPopup), Details in the settings window (SidePanel), the notice, and buttons, round
  buttons and chips from ui/Buttons.kt over the Glass kinds and Tokens. Films & Shows' sizes, text styles, colours and
  motion values are in one place (VodSizes; app-wide ones in Tokens and Glass); step 1's own values moved there
  unchanged. Assumptions, all reversible:
  - The title pages belong to the tab's page: a tab switch closes them, like See all; the screen stopping keeps them.
  - Back while the rail has focus over a title page goes back into the page instead of leaving the app.
  - Season pills are step 1's genre chips (36 dp, 14 sp) with the Episodes sheet's "season shown" fill and blue ring as
    the selected state; they move only when they don't fit.
  - While the header follows an episode card, the callout chip hides too.
  - The story follows the details as they come (brief: TMDB's overview when there is one, else Cinemeta's, its cut
    word ended), its words fading out and in with the page headers' 120 ms text fade when they change; only the title
    (logo or name) never changes during a visit.
  - OK on an episode not aired yet says "Airs Dec 3"; holding OK on it does nothing.
  - Sources is left out for a film not out yet and a show with nothing aired (so focus opens on (+), as briefed). A
    show listed with Specials only has Play dimmed and no Sources (Play never starts a special); its cards play them.
  - Starting backdrop fade: clear → solid from 15% to 70% of the width (eased), solid → clear from 36% to 60% of the
    height; to check on the TV.
  - A show's page glides up so the buttons sit 14 dp from the top for More Like This (no cut line of text above them).
  - More Like This posters from TMDB are decoded no larger than their source (w185: 185 x 247 px).
  - The backdrop is the poster's own background when the poster has one (the picture the page header already showed,
    usually on disk), else the details' first; it is chosen once and never swapped during the visit.
  - At rest a show's episode row starts at the Play target's card when it is in the season shown (else E1), so Down
    from the buttons only grows that card instead of gliding along the row.
  - A page covered by one opened from its More Like This keeps that row as it was (the poster grown) for coming back.
  - On a show, the (⋯) window says "Mark unwatched" only while every aired regular episode shows ✓ (the show's own
    mark covers only the episodes aired when it was set), so after new episodes air it offers Mark watched again;
    before the episodes are known it follows the show's own mark. The callout chip comes from the data's one rule
    (Callouts.choose), the same the data tests check against the research's expected page.
- 2026-11-19 · Films & Shows step 4, My List and Search tabs (rail: Search first, above Home; My List after TV
  Shows). Both are the same VodPage as Home, Movies and TV Shows (chosen by VodTab), so rows, header, hold window,
  title pages and See all are the shared parts; all sizes in VodSizes.
  - My List: two poster rows, "My List · Movies" and "My List · Series", newest added first, 15 then See all; an empty
    row hides, both empty shows "Titles you add with + show up here". The header never slides away and follows the
    focused title (words at once, picture after 0.5 s, no rotation); the rows glide under it. + / − in the hold window
    or on a title page changes the rows at once.
  - Search (user's request): no header; a full-width bar (magnifier, "Search films and shows") beside the round trash
    button. The bar and the history rows are the Settings tab's row, now one shared PageRow (Settings uses it too).
    Results (Cinemeta search, 0.5 s after typing stops or at once on Done) are "Movies" and "TV Shows" rows, each only
    when it has titles. A search is kept when submitted or when a result is opened; OK on a history row searches it
    and puts focus on the bar to edit; holding OK offers Remove; the trash asks "Clear search history?". Up from the
    results goes to the bar (results stay); Back from the results or with words in the bar goes back to the empty bar
    and history, Back again to the rail; coming to Search from another tab always opens that starting view.
- 2026-11-19 · Settings › Rows: a Rows category with a window each for Home, Movies and TV Shows. Switch rows on or
  off, Reorder rows (the shared move list of Reorder playlists and Manage groups), Add a row (the kinds the pages
  already have, same RowSpec/RowStyle/TitleRow; Top 10 for films, series or both only with a TMDB key; a genre from
  Cinemeta's list), remove added rows, Reset to default (asks first). No row twice on a page. Stored per page in the
  app's settings file; nothing stored keeps the defaults. Pages rebuild at once, rows that stay keep their cards; with
  every row off the page says so.
- 2026-11-19 · User: short-film filter: declined by the user (short films stay in the rows and results).

## Concept references (local preview artifacts)

- Original approved Home base: C:/Users/ataah/.codex/generated_images/01a1e677-d453-78f1-87f1-88b8a51d61ad/exec-44794608-40c2-4a3d-9f9e-7e5c7ea59b4e.png
- Latest Home illustration: C:/Users/ataah/.codex/generated_images/01a1e677-d453-78f1-87f1-88b8a51d61ad/exec-56a052a7-15b4-436e-ac0c-bc7fa3300f72.png
- Blue house-free sharp artwork: C:/Users/ataah/.codex/generated_images/01a1e677-d453-78f1-87f1-88b8a51d61ad/exec-8c58bc49-eb10-43d9-ac61-f93b8e60767d.png
- Strongly blurred background: C:/Users/ataah/.codex/generated_images/01a1e677-d453-78f1-87f1-88b8a51d61ad/exec-d0843ccf-d734-4699-9298-f2a019cec44a.png
- Live guide: C:/Users/ataah/.codex/generated_images/01a1e677-d453-78f1-87f1-88b8a51d61ad/exec-87bf2151-f567-489a-8805-e38189b87e12.png
- Full-screen Live controls: C:/Users/ataah/.codex/generated_images/01a1e677-d453-78f1-87f1-88b8a51d61ad/exec-7ed51ce9-1aa9-4704-a99b-f619dc06f09a.png
- Overlay guide: C:/Users/ataah/.codex/generated_images/01a1e677-d453-78f1-87f1-88b8a51d61ad/exec-81a719be-5a58-43e6-8591-5473cf45fa43.png

Concept discrepancies are not specifications: generated miniature shows transport controls not agreed for guide preview; group focus differs from white/blue rule; timeline cells and clip sizes are approximate. Written interaction requirements take precedence. Generated artwork requested as 4K was returned at 1672x941; do not label it native 4K.
- 2026-11-19 · User: switch from FirePlayer 1.0 to 2.0. Glass Home shows and resumes FirePlayer 2.0's own Continue
  Watching (the Films & Shows library, through the same provider columns and RESUME / PLAY_EPISODE intents; a card
  plays straight away on the same file, Back returns to Glass Home), using FirePlayer 2.0 (`app.fireplayer.next`) when
  it is installed and FirePlayer otherwise. Holding the remote's Menu button opens FirePlayer 2.0 instead of TiviMate
  (Glass Home's Home service; same on/off setting). FirePlayer 1.0's data (watch history and settings) was cleared on
  the Max at the user's request; its settings were compared with 2.0's first and the user chose NOT to copy the five
  that differed (Sound info pod hidden, subtitle shadow Light, Preview while skipping on, English titles in TiviMate
  on, Glass refresh 8). Still the user's to do: English titles in TiviMate and TiviMate's external player now belong
  to FirePlayer 2.0.
- 2026-11-19 · User: release plan. After the visual design updates, FirePlayer 2.0 replaces the old FirePlayer's code
  completely and ships as a signed release from the old FirePlayer's repository, so the installed app updates itself
  and the extra test app is removed; the user re-enters their info; nothing permanent is built only for the
  transition. FirePlayer 2.0 keeps acting as an external player (TiviMate). Until the swap, design work continues on
  the Max-only test build. Details in PROGRESS.md §6.
- 2026-11-19 · User: Search, both kinds, agreed (to be built in the redesigned look):
  - One Search page with two modes and a two-way switch at the top ("Movies & TV Shows" / "Live TV", the active one
    highlighted; styled with the redesign). The rail's Search tab opens Movies & TV Shows.
  - The Menu (3-line) button's window on Home, Movies, TV Shows, My List and Settings becomes "Search and App
    Settings": a Search bubble at the top (search icon left, "Search" centred) opens the Search page in Movies & TV
    Shows mode. The VOD player's own settings window keeps its layout; only its "App Settings" button is renamed
    "Search and App Settings" (the Search bubble is in that window, not in the player's settings window); Search from
    there leaves the film (position saved).
  - On Live TV the Search bubble is at the top of the Live TV settings window and of "Search and App Settings" opened
    from there; both open the Search page in Live TV mode (channels and programmes, all playlists, its own history).
    Channel results: a row of small logo tiles with the name beneath; a name that doesn't fit starts scrolling after
    about 0.3 s of focus; hold OK shows the full name and the guide's channel options. Programme results: a vertical
    list underneath, a blue LIVE mark on ones on now. A section shows only when it has results.
  - OK: a channel, or a programme on now, plays full screen on Live TV; a later programme opens the guide at that
    channel and time. Back from Live search returns to Live TV.
  - For now the channel stops while searching. Wanted after the redesign: automatic picture-in-picture, the live
    channel keeps playing in the top-right mini player when going from a playing channel to Home or Search (its size
    and position are decided with the redesigned pages first).
- 2026-11-19 · User: look redesign, from their brief and five concept images (Design-References/redesign-2026-11-19/).
  Built and on the Max test build: true dark translucent glass on the browse pages (one shared style in
  `ui/GlassStyle.kt`; windows over a page show that page blurred from one shared downsampled copy, `ui/PageGlass.kt`,
  refreshed only when a window appears or the page under it changes); the rail as a floating rounded window on every
  tab but Live TV (edge strip there), widening over the page with a larger logo; hairline borders and soft shadows;
  a taller banner with Watch now and Details; larger cards (Continue Watching about 3.3 across, posters about 4.8);
  Recent channels as a list of logo tiles the banner's height; the title page with a stacked action column, a season
  dropdown, the Episodes heading and large episode pods (no Episodes button); Live TV's larger details logo, glass
  logo tiles and cleaner programme cells. Replaces the sizes of 2026-11-18. Tuned after the first look on the TV:
  window tint 72% to 58%, dark logos on the light tile again, the title's logo drawn by its visible part.
  The faint vertical line where the banner's artwork begins is fixed (the card left of the picture's box is backed by
  the same solid colour the picture is eased into); dark logo tiles and the title's logo size checked on the TV.
  Open: the user's own tuning notes.

- 2026-11-20 · Glass refresh over heavy films (shipped from the firestick-player repo, b64eada). The user: "get rid
  of the glass refresh limit for 4k films", then "keep the limit for files that have 70 and higher Mb/s". The glass's
  lighter load (at most 2 copies a second, cut probes every 500 ms, fades no oftener than 33 ms) now applies only while
  a film averaging 70 Mb/s or more (its size over its running time; not known counts as under) plays on a TV short of
  memory; under that, 4K included, Settings › Look › Glass refresh applies as chosen. The row reads
  "8 a second · 2 at 70+ Mb/s" while it applies. The black-bar watch keeps its own slowdown for any 4K film.
