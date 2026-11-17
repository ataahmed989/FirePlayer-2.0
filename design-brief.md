# All-in-one Fire TV app — Module Boundary & Loading-Strategy Brief

> Design update — November 17, 2026: This architecture baseline is supplemented by [design-layout.md](design-layout.md), which records the evolving Home/Live UI, remote navigation, PiP, visual system and settings outline. Settings are still being designed; generated concepts are illustrations, not implemented or final-approved screens. Later explicit user decisions recorded there supersede conflicting earlier assumptions here. In particular, EPG source ownership, retention controls, catalog ownership, provider-wide connection coordination and persistence ownership still need resolution. No app implementation was performed as part of this documentation update.

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
