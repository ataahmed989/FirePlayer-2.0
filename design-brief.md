# All-in-one Fire TV app — Module Boundary & Loading-Strategy Brief

> Design update — November 17, 2026: This architecture baseline is supplemented by [design-layout.md](design-layout.md), which records the evolving Home/Live UI, remote navigation, PiP, visual system and settings outline. Settings are still being designed; generated concepts are illustrations, not implemented or final-approved screens. Later explicit user decisions recorded there supersede conflicting earlier assumptions here. In particular, EPG source ownership, retention controls, catalog ownership, provider-wide connection coordination and persistence ownership still need resolution. No app implementation was performed as part of this documentation update.

> VOD update — November 18, 2026: the user decided how Home, Movies and TV Shows get their titles, the title page and how films and episodes play. Those decisions are in §6 and supersede §1.2, §1.3 and the VOD parts of §2–§3 where they conflict. FirePlayer stays on Android Views; the Stremio/Compose observations in §2 are a behaviour reference only.

**Repo model (locked):** fork/evolve the FirePlayer repo. One repo, one APK, package **`app.fireplayer`** retained. FirePlayer's VOD engine is the trunk; **Live TV** and **Multiview** are new modules merged into the same repo and shipped as an update.

---

## 0. Locked architecture decisions

| Decision | Value |
|---|---|
| Repo / APK / package | One repo, one APK, `app.fireplayer` (fork of FirePlayer) |
| Player | Internal **`PlayerHost`** — in-process Media3. No cross-app Intents, no `setPackage`/`NEW_TASK`, no `PlayerBridge`. |
| Multiview | In-app, inside the Live player, TiviMate workflow. Decoder cap N=3, player pool, audio focus on focused tile, Smart Play serialized per tile, respect provider `max_connections`. |
| Glass Home | **Zero changes.** Package `app.fireplayer` and authority `app.fireplayer.continue` unchanged; existing `history/ContinueProvider` + `ContinueWatching` stay as-is. |
| Removed | The read-only cross-app Continue-Watching content-provider *client* (previously proposed for a separate APK) and any `app.fireplayer.action.MULTIVIEW` batch handoff. Not needed in a single app. |

The existing `ContinueProvider` (authority `app.fireplayer.continue`) is **kept** — it is the Glass Home bridge and must not change.

---

## 1. Module boundaries

### 1.0 Shared core (trunk pieces + two new pieces)

| Piece | Responsibility | Source |
|---|---|---|
| **Metadata cache** | Single SQLite persistence layer (WAL, single-writer). | Existing `titles/TitleBook` (`titles.db`: `item/category/tmdb/episode/seen/login`) stays for VOD + Smart Play. **New** `LiveDb` tables: `playlist(id, kind, url_or_creds, last_sync)`, `channel(playlist_id, id, name, logo, group_id, position, favorite, blocked)`, `group(...)`, `program(...)` (1-day retention), `channel_epg_map(playlist_id, channel_id, epg_ref)` (shared-EPG mapping). |
| **Image loader** | One loader, one memory + one disk cache, shared by all modules. | Existing `ui/ImageLoader` (LRU + `inSampleSize` + `RGB_565` + view-tag guard) extended with a disk cache and ±2-tile prefetch (see §3). |
| **Focus engine** | D-pad focus, long-press, page-scroll, seek steps, focus-memory. | **New** `FocusEngine`/`LongPressHandler`. Builds on trunk `Rows.verticalNav` + `CenterScrollView` ("glide to center"). Adds long-press (Long OK/Left/Right/Back; hold Back → full-screen player from EPG scroll), page scroll, seek-step config, persisted focus anchor (channel id + time cursor). |
| **PlayerHost** | Internal playback host. | **New**, wraps Media3/ExoPlayer used today by `PlayerActivity`/`PlayerFactory` into a reusable in-process host. One API: `play(uri, startPositionMs, headers, subs, options)`, `stop`, `seek`, `listeners`. Decoder/buffer work runs on Media3's playback threads — never the UI thread. |
| **Persistence** | Settings + secrets. | Existing `data/Prefs` + `data/KeyStore` (Xtream creds, TMDB key). |

### 1.1 Live TV module (new)

**Responsibility:** playlists, EPG, channel/group/favorite models, EPG grid + overlay guide, info/control panels, Multiview, catch-up UI states, PIN/blocking, search.

**Guide design refinement (November 17):** support a multi-playlist expandable sidebar (All playlists, then each playlist's Favorites / All channels / groups), one FirePlayer brand logo, and a compact information-dense guide following the supplied dark reference. Glass and classic share layout and remote behavior; classic retains original FirePlayer dark colors. Prior generated guide spacing is illustrative, not final. See design-layout.md for density targets and remaining navigation choices.

**Key classes:** `PlaylistManager` (M3U/M3U8 + Xtream Codes; Xtream calls reuse `XtreamAccount`), `EpgStore` (merged XMLTV), `ChannelRepository`, `GroupRepository`, `EpgGridAdapter`, `OverlayGuideView`, `InfoPanelController`, `MultiviewController`, `ParentalGate`, `LiveSearch`.

**Data flow:** `PlaylistManager.sync()` (one WorkManager cycle for all playlists) → parse → upsert `channel`/`group`/`channel_epg_map` → `EpgStore.ingest()` (one merged XMLTV source, partial scan, 1-day retention, unmapped channels show no EPG) → grid reads `channel` ∪ `program` via the mapping table. Play a channel → `PlayerHost.play(uri, …)` (catch-up → timeshift URI).

**Shared EPG (updated November 17, 2026):** each playlist's own EPG is its primary source. Where a playlist lacks EPG, another playlist's EPG can supply fallback coverage through reliable channel mappings. Multiple source inputs feed one app-wide guide; retain the `channel_epg_map` mapping concept, source provenance and a coordinated refresh cycle. One day of past history remains the current baseline. Unmatched channels show no EPG. Automatic versus manual fallback and per-channel/program gap filling remain undecided. This replaces the earlier single-source-URL constraint in this brief, including earlier references to one merged XMLTV input.

**Consumes from core:** `FocusEngine` (grid nav, long-press, page-scroll, focus-memory, hold-Back), metadata cache (`LiveDb`), `ImageLoader` (logos; playlist→EPG→folder priority), `PlayerHost`, persistence.

**Scope specifics honored:** channel cap + Groups/Search escape hatch (`Displaying first %d channels…`); groups/favorites/sorting; channel names auto-refresh on playlist update (no rename UI); PIN setup + require-for rules + block/unblock channel/group + lockout timer; Multiview (below); search text/history/voice intent.

### 1.2 VOD module (trunk + redesigned rows)

**Responsibility:** Netflix-style home rows (row design left to you), lazy metadata on focus, pagination, detail sheet, Continue Watching, subtitles.

**Key classes:** `HomeActivity` (trunk, re-skinned), `CatalogAdapter` (`LazyColumn`/`LazyRow`), `DetailSheet`, `VodRepository`.

**Data flow:** home reads catalogs from `TitleBook`/addons; row visibility triggers page load (skip-based); focus a tile → fetch meta + episodes (`meta` first, streams on-demand); Play → Smart Play → `PlayerHost`. Continue Watching reads `ContinueWatching`/`WatchHistory` **in-process** (same app); `ContinueProvider` continues to answer Glass Home unchanged.

**Consumes from core:** `FocusEngine`, `ImageLoader`, metadata cache (`TitleBook`), `PlayerHost`, persistence, and the trunk subtitle engine (`subs/`).

**Scope specifics honored:** no Trakt; no separate player; subtitles from the trunk `subs/` engine.

### 1.3 Smart Play module (new; reuses trunk primitives directly)

**Responsibility:** given a title/episode → (1) verify the correct file, (2) discover + rank sources, (3) health-check the top candidates, (4) auto-fallback, (5) hand one confirmed source to `PlayerHost`.

**Key classes:** `SourceResolver`, `SourceCatalog`, `SourceRanker`, `SourceProber`, `SmartPlayOrchestrator` — thin orchestration over existing trunk classes (same repo, no porting): `Copies` (discovery), `CopyRank` (ranking), `CopyHealth` (health), `NextPick` (provider episode numbering), `MediaSpec` (copy metadata + provenance), `TitleBook`/`TitleMatcher` (TMDB verify), `XtreamAccount` (probe/`get_vod_info`, `screens()`).

**Data flow:** `SmartPlayOrchestrator.play(selection)` → **verify** (movies: TMDB `(name,year)`; shows: TMDB `(show,season,episode)` via `NextPick.Real`) → **discover** (`Copies.ofFilm/ofEpisode`) → **rank** (`CopyRank`: `videoBps × codec-efficiency` → width → bit depth → HDR floor 0.7 → `soundRank` → language → subs) → **health-check top N** (`CopyHealth.troubled` + `get_vod_info`/range probe to reject dead sources) → **fallback** (next-best, no user input) → **handoff** `PlayerHost.play(uri, …)`.

**Consumes from core:** `PlayerHost`, metadata cache (`TitleBook`, health/position), persistence.

### 1.4 Multiview (new, in-app, inside the Live player)

**Workflow (TiviMate-style, locked):** extra/repurposed remote button → `Add screen` / `Select groups` / `Select playlists` / `Next-Prev channel` / `Enlarge-Reduce` / `Remove`.

**Resource plan (§4):** decoder cap **N=3** default, `PlayerHost` pool of N Media3 instances, audio focus on the focused tile only, Smart Play serialized per tile, provider `max_connections` (`XtreamAccount.screens()`) respected before launching a new tile.

---

## 2. Title layout + fast-loading reference (observed / inferred / unknown)

| Concern | TiviMate (observable only — code encrypted) | Stremio (local repos — observed) |
|---|---|---|
| Layout | **Inferred:** full-screen EPG grid (channel × time axis), overlay/preview guide modes, info+control panels (transparency + timeout), program blocks sized by duration; single `MainActivity`, landscape, leanback. | **Observed:** Compose `LazyColumn` home → `FeaturedHeroPager` (top-5, 5 s auto-advance) → Continue Watching shelf → board shelves; each shelf a `LazyRow` of `PosterTile` (112 dp, 2:3, gradient overlay, Continue/Cinema badges). No RecyclerView/Glide/Fragment. |
| Virtualization | **Inferred:** custom grid recycling program/channel cells; page-scroll fast nav; focus-memory anchor. | **Observed:** `LazyRow`/`LazyColumn` (windowed composition); keys `{"${type}-${id}"}`; skeleton tiles while loading. |
| Lazy detail | **Inferred:** program description fetched/stored on demand ("Store program descriptions" toggle). | **Observed:** meta + streams are click/focus-on-demand (`openDetails`→meta, `openStreams`→streams); series enumerates with `videoId=null` then per-episode; 15 s/30 s timeouts. |
| Pagination / caps | **Observed:** channel cap `"Displaying first %d channels. Use Groups or Search…"`; `"Past days to keep EPG"`. | **Observed:** `CATALOG_PAGE_SIZE=100`, `CATALOG_PREVIEW_SIZE=100`, `LIBRARY_RECENT_COUNT=200`; load-more = `skip=<cumulative count>` (`LoadRange`/`LoadNextPage`); preload first 5 catalogs, then `onShelfVisible` loads `start..start+2` (dedup set); search debounced 300 ms, preload 14. |
| Image cache / prefetch | **Observed:** logo priority (playlist/EPG/folder), `"Clear logos cache"`, `"Inexact matching for logos files"`. | **Observed:** Coil 3 + OkHttp, memory 25 %, disk 250 MB, IPv4 DNS, `size()+crossfade`; **no prefetch**. |
| Persistence | **Inferred:** Room/native-SQLite EPG cache (`libsqliteJni.so` + Room). | **Observed:** Rust core storage via SharedPreferences; in-memory request-dedup only (`ResourceLoadable` refetches only when request changes / content `None`, unless `force`). |

**Use Stremio's mechanism** (lazy windowed rows + visibility-triggered pages + skip pagination + disk-cached images), **add the one thing Stremio omits** — focused-tile prefetch — and **do not copy** TiviMate's grid (encrypted) or Glass Home's canvas `ShelfPage` (too bespoke).

---

## 3. Loading & speed strategy (in-process playback)

1. **Virtualize everything.** Compose `LazyRow`/`LazyColumn` for VOD; a two-axis lazy grid (`LazyRow` of columns) for the EPG. Only the composed window allocates views/bitmaps.
2. **Lazy fetch on focus, not eager.** Preload only the first 5 catalogs' first page; each row loads its next page on visibility; meta/streams/episodes load on focus/click; search debounced 300 ms.
3. **Caps.** 100 items/page; EPG channel list capped at `Displaying first %d channels` (Groups/Search = escape hatch); 1-day EPG retention; VOD load-more via `skip`.
4. **Shared image cache + focused prefetch.** One `ImageLoader` (memory LRU + disk cache). On focus, prefetch the focused tile's neighbors (±2) and the focused row's posters; EPG prefetches the current time window. Downsample to render size (`inSampleSize`, `RGB_565`). Logos use playlist→EPG→folder priority.
5. **Playback never blocks the UI thread.** `PlayerHost` is in-process but all decode/network/buffer work lives on Media3's playback threads; the UI thread only receives rate-limited state callbacks (position/surface/buffering events posted to the main looper, coalesced). Multiview's N Media3 instances each run on their own playback threads; surfaces are recycled, never held on the UI thread.
6. **Single-writer persistence.** All EPG/metadata writes go through the shared core SQLite (WAL) on one worker; reads via DAOs/`Flow`. Atomic-file writes for watch position (trunk `AtomicFile`).
7. **Background sync via WorkManager** (unmetered + idle), one cycle, gated by `last_sync`/`seen_at` (trunk `TitleBook.gone()` precedent).

---

## 4. Multiview resource plan (in-app)

- **Decoder cap N=3 default** (persisted, device-profile aware). MediaCodec instance limits are the binding constraint; cap is raised/lowered by the device profile, never by provider count.
- **Player pool.** `PlayerHost` maintains a pool of N Media3 instances. `Add screen` acquires one; `Remove` releases it and recycles its surface. `Enlarge-Reduce` swaps the focused tile's surface between the grid cell and the main surface without re-initializing the decoder where possible.
- **Audio focus.** Only the focused tile holds audio focus; all others play muted. Focus change transfers audio focus atomically.
- **Smart Play × tiles.** Smart Play runs **serially per tile** (single-flight probes to respect provider `max_connections` via `XtreamAccount.screens()`). A new tile resolves its source through Smart Play before acquiring a decoder; fallback stays within that tile and does not starve other tiles.
- **Workflow.** Extra/repurposed button → `Add screen` / `Select groups` / `Select playlists` / `Next-Prev` / `Enlarge-Reduce` / `Remove`, all handled by `MultiviewController` over the `PlayerHost` pool + `FocusEngine`.

---

## 5. Deferred (unchanged)

- Final VOD and Live TV screen layouts (row design is yours).
- Reminder UX.
- Trakt integration (explicitly out of scope).

---

**Glass Home: zero changes** — package `app.fireplayer` and authority `app.fireplayer.continue` are retained, so the existing `ContinueProvider`/`ContinueWatching` contract is untouched and Glass Home keeps working as-is.

---

## 6. VOD decisions — November 18, 2026 (user)

Agreed with the user over several review rounds (proposal page "FirePlayer Films & Shows", revision 4). "Proposed" marks
details behind a decision that the user has seen but not individually approved. Card and header sizes are still open.

### 6.1 Where titles come from

- **Cinemeta** (Stremio's metadata add-on, v3): rows (Popular, New by year, Featured), genres, search, details,
  seasons and episodes. The IMDb id (`tt…`) is the title key; one poster per IMDb id.
- **TMDB** (the user's key): Top 10 This Week (weekly trending), age rating, cast photos, artwork. More Like This uses
  TMDB recommendations appended to the details call (proposed); without a key, Cinemeta: films by the same director
  then same-genre Popular, shows by genre. Without a key the Top 10 row is hidden and headers rotate Cinemeta
  Popular's first 10.
- **Playback sources:** the user's Torrentio (Real-Debrid) and MediaFusion (public ElfHosted instance, Real-Debrid)
  add-ons, entered in Settings › Add-ons. They never supply posters, and a catalog listing never implies a title will
  play. Add-on URLs carry the Real-Debrid key or config: store encrypted, never log, never show in full, never store
  resolved links. MediaFusion's own rows exist but are off by default.
- **Not used:** Local Files (returns nothing on Android; USB playback would be a separate feature), YouTube, Public
  Domain Movies. IPTV playlist films and shows are excluded.
- **Ids into the player:** IMDb id, TMDB id, season and episode go to subtitles (OpenSubtitles by IMDb id), Skip
  Intro and history. Missing ids fall back to today's name matching.

### 6.2 Library and history

- FirePlayer keeps Continue Watching, My List (a saved-title list, independent of Real-Debrid), Next Episodes,
  Continue Series, watched marks and resume points keyed by title/episode instead of by URL. Proposed: the last release
  used per film or episode (info hash + file index, never the URL).
- **No syncing of any kind** — no Stremio sync, no FirePlayer-to-FirePlayer sync. Each TV keeps its own history.
  Store it so sync could be added later.
- Glass Home shows FirePlayer 2.0's Continue Watching once 2.0 replaces 1.0. Obsolete 1.0 entries (IPTV links) may be
  cleared during the move without touching FirePlayer's own history.

### 6.3 Pages and rows

- **Home:** idle-rotating header with the PiP/history window, Continue Watching (films and series), My List directly
  below, then Next Episodes, Popular Movies, Popular Shows; MediaFusion rows optional, off.
- **Movies:** Continue Watching · Films (only when a film is in progress), Top 10 This Week, Popular, New by year
  (year picker), Featured, genres. **TV Shows:** Continue Series, Next Episodes, Top 10, Popular, New by year,
  Featured, genres.
- **Header:** rotates the page's Top 10 (Home: featured) while idle; while focus is in the page's Continue row it shows
  the focused title; other rows don't change it.
- **Rows:** 15 titles plus "See all". Hold OK: My List, Mark watched; on Continue rows also Open title page, Play from
  beginning, Remove from Continue Watching (proposed). Rows per page editable in Settings › Rows like Manage groups.
- **Scrolling** works like the Episodes sheet (`ui/EpisodesSheet.kt`): the chosen tile stays at the left edge and
  grows about 12% from its left edge with a fading ring, followers make room, and two critically damped `Spring`s
  drive position and growth so a held D-pad glides. Translation and scale only, no per-frame layout. Each row lays out
  its 15 tiles once; the page's rows are recycled.
- **Sizes:** open. To be set with the user, starting from the approved Home concept (Design-References 01/02).

### 6.4 Pressing titles and the title page

- Posters open the title page. **Continue rows go straight to playback** (Home's Continue Watching, Movies' Continue
  Watching · Films, TV Shows' Continue Series, Glass Home): no title page, no resume question, and the **same file** —
  FirePlayer asks only the add-on that supplied that release for the title's list and picks it (no wait for the other
  add-on). If it's gone: normal search, same release from the other add-on first, then the closest match, with
  "Resumed near …" (proposed).
- **Title page, layout A:** header (backdrop at half size with its fade baked in, no blur; logo if ready within about
  0.3 s, otherwise text; one facts line plus at most one callout; three lines of story; one cast line; a source line
  only after an add-on answers, labelled with where its facts came from), then one row of buttons, then rows on the
  page: a show's season pills and episode cards (EpisodesSheet look, Specials last) and More Like This; a film's More
  Like This. Back from a row returns to the buttons; Back from the buttons leaves the page.
- **Buttons:** Play/Resume (the label carries the state, e.g. "Resume S2 E5 · 12m left") · Play from beginning (only
  with a saved position; on a show it restarts the current episode) · Episodes (shows) · Sources (count once
  answered). No More Like This button: More Like This is only a row on the page (user, 2026-11-18). Proposed: (+) My List and (⋯) Details / Mark watched / Remove from Continue Watching. No
  trailer.
- **Sources are looked up when a title page opens:** after 1.5 s (0.5 s with a resume point, or from Next Episodes or
  a Continue card's hold menu), only for what Play would start, from both add-ons; kept in memory 10 minutes (6
  titles); never from rows; links are never resolved ahead of Play. MediaFusion (ElfHosted allows 100 look-ups per
  home connection, refilling 1 a minute): at most 20 early look-ups an hour, no automatic MediaFusion look-ups after 40
  in an hour, back off when it answers "busy". Listing never adds anything to Real-Debrid.

### 6.5 Playback

FirePlayer 1.0's VOD player design and capabilities are kept, with best-source analysis and **Next source** (one press,
at the current position) in Picture & sound.

| Stage | Rule |
|---|---|
| 1 · Finding sources | Wait for both add-ons: Torrentio ≤ 10 s, MediaFusion ≤ 25 s, counted from the first ask; a strip shows who is still looking. Proposed: OK again plays the best so far. |
| 2 · Checking the link | ≤ 20 s. FirePlayer resolves the add-on URL itself (redirects off) and classifies the redirect target: Torrentio's `/videos/*` notices, MediaFusion/ElfHosted slates. A timeout on a source not confirmed ready counts as possibly being fetched. |
| 3 · First loading | 30 / 45 / 60 s for sources under 20, 20–50 and over 50 Mb/s. At the limit FirePlayer asks **Keep waiting / Try next source**. |
| 4 · While playing | After 30 s: switch on 3 stalls in 2 minutes, one stall over 20 s, or over 25% of 2 minutes buffering. Stalls within 10 s of a seek, source change or resume don't count. At most 3 start tries and 2 switches per film or episode, at least 3 minutes apart; an attempted list; afterwards a note points to Next source. |

- **Not on Real-Debrid yet:** never picked automatically; shown in their own group. When no ready source works,
  FirePlayer asks once whether Real-Debrid should fetch one. Proposed details: the most-seeded source, a single episode
  rather than a season pack, not after an account or limit problem, once per title, the same for the next episode;
  two wrong "ready" guesses for a title stop automatic tries.
- **Failures (proposed):** file-specific → skip it and the same release from the other add-on; not ready → skip;
  format the device can't play → try another format; audio refused → decode on the Stick; expired link → refresh
  once; add-on down → use the other; Real-Debrid account, limits, rate limits, outage or no internet → stop and tell
  the user. A "copyright" refusal just after a new add: wait about 30 s and retry once.
- **Next episode:** prefers the same kind of source. Proposed order: same release (Torrentio binge group), then the
  same add-on with the same resolution, HDR type, codec, audio and size class, then the best compatible source; looked
  up in the last ~4 minutes.
- **Position after a switch (proposed):** 5 s early, "Resumed near …".
- **Sources panel (proposed):** one merged list in the wide SidePanel — Best for this TV (+ Last used), 4K, 1080p,
  720p and lower, Other, Cinema recordings, Not on Real-Debrid yet (the last four closed; 5 rows plus "Show N more").
  Two-line text rows; duplicates merged ("Both add-ons", "×3"); facts labelled "from Torrentio/MediaFusion" until
  FirePlayer measures them (then dated); add-on "ready" marks shown as "probably on Real-Debrid".
- **Best source per TV:** each Stick reads its own TV (HDR types including Dolby Vision, resolution, passthrough
  formats). A **This TV** page in Settings shows the result with "Check again", Automatic or a custom plan (HDR order,
  top resolution, sound formats, optional data-rate limit). The user's Samsung TU7000: HDR10, HDR10+ and HLG, no Dolby
  Vision (HDR needs Input Signal Plus on its HDMI input); HDR10 and HDR10+ equal until an HDR10+ film is verified
  clean; Dolby Vision profile 8 fine; profiles 5 and 7 last; SDR first if HDR is off.
- **No file-size limit** for now. If ever needed: a data-rate limit (Mb/s) per Stick, never GB.

### 6.6 Player fixes approved after the November 18 playback test

On the user's 4K Max a 4K Dolby Vision + HDR10+ film (26 Mb/s, 29 GB, MediaFusion) lost about 1 frame in 6 in one
session, while a 10-minute replay was smooth and, after HDR was enabled on the TV, so was further viewing (1 of 367).
That session had the Stick out of memory (lowmemorykiller looping, swap at 9 MB), FirePlayer at 448 MB (about 230 MB
film buffer, ~100 MB graphics held after the menu opened with the Glass look on) and the TV reporting no HDR. Likely
but unproven cause: FramePacer flip-flopping under timing jitter (simulation 13–18%). Approved:

1. Count every lost frame (held twice, same refresh, never decoded) with memory and temperature, in Picture & sound
   and the log.
2. Steadier FramePacer: move ±1 refresh only on sustained drift (EMA), skip instead of queueing a same-refresh frame.
3. Film buffer sized to device RAM: 128 MiB with a 6 s back buffer below 1.8 GiB (64 / 176 / 256 MiB tiers),
   stepping down on memory pressure.
4. Keep the Glass look; during 4K HDR films on low-memory Sticks lower its refresh automatically (e.g. 8 → 2 a second)
   and probe less often; find what holds the ~100 MB and release it if it's the Glass look.
5. No second 4K decoder for skip previews on this Stick; release the preview after 3 s.
6. Mask HDR10+ data before decoding when the display lacks HDR10+.
7. Lost frames count in CopyHealth (over 2% for 10 minutes = troubled); honest "Range" row on SDR screens.
8. Build the styled-subtitle (libass) renderer only for films with an SSA/ASS track.
