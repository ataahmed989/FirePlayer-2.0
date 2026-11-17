# TiviMate 5.3.3 — Feature Report (observable surface only)

**Target:** `tivimate.apk` — `ar.tvplayer.tv`, v`5.3.3` (code `1000005332`), 20.14 MiB, SHA256 `BEAEBB25…EE6C`. minSdk 23 / targetSdk 34, compiled SDK 37. 6545 classes, 1046 resources.

Source of the Live TV feature surface used by `design-brief.md`. Everything here is **observable** (manifest, bundled library/native stack, resource strings, packaging). The implementation code is encrypted and was not recovered — see §0.

---

## 0. Critical finding: the app is protected — implementation code is not statically analyzable

TiviMate's own feature code is packed with **DexProtector**. The APK ships `libdexprotector.so` (all 4 ABIs) and a 1.79 MB encrypted blob `assets/assets.dat`. The three DEX files hold ~6545 **third-party library** classes plus three `ar.tvplayer.tv.*` classes — `ProtectedTvPlayerApplication`, `LibTvPlayerApplication`, and `R`. The manifest-declared `ar.tvplayer.tv.ui.MainActivity` and every feature class are **absent from the DEX**.

The two surviving bootstrap classes are the protector's loader:
- `ProtectedTvPlayerApplication` exposes `a(byte[]): byte[]` (decrypt), `a(Object,String): InputStream` (asset read), `attachBaseContext`, `onCreate`, and hooks `b()`/`c()`/`s(String)` — it decrypts the real DEX from `assets.dat` in `attachBaseContext()` and installs a custom ClassLoader before any Activity resolves.
- `LibTvPlayerApplication` is a native trampoline with ~790 methods named `a(int,…)`/`m691a…m791a` — a JNI dispatch table bound to `libdexprotector.so`.

**Consequence:** the EPG grid renderer, playlist parser, DB schema, player controller, and Multiview engine cannot be read from this APK, and were not recovered (runtime unpacking is both prohibited by the app's license — *"You may not … reverse-engineer, or attempt to extract the source code of the app"* — and out of scope). The sections below are the architecture/tech-stack and complete feature surface extracted from the manifest, the decodable library stack, the native libraries, and the decodable resource strings.

---

## 1. EPG / TV Guide grid UI

**Observed feature surface** (resource strings, decoded via JADX):

- **Three guide presentation modes:** full-screen guide, **overlay mode**, and **preview mode** — for both the channel list and the guide groups (`"Show TV guide in overlay mode"`, `"Show TV guide in preview mode"`, `"Show TV guide groups in overlay/preview mode"`, `"Show channels list in preview mode"`, `"Show channels list groups in overlay/preview mode"`).
- **View hierarchy (inferred):** horizontally-scrolling **channel axis** and a **time axis** (program blocks sized by duration), a channels/groups sidebar, and overlaid **info panel** + **control panel** (`"Show info panel"`, `"Show info panel and control panel"`, `"Show info panel at the bottom when switching channels"`, `"Show info panel for next/previous channel"`). Panels have **transparency** (`"User interface transparency, %"`, `"Transparency"`, `"Background color"`) and **timeout** (`"Panels timeout, sec"`, `"Popup timeout, sec"`).
- **Program block rendering:** `"Highlight current programs"` / `"Highlight current programs in color"`, `"Dim past programs"`, `"Two-line program titles"`, `"Show program description"`, `"Store program descriptions"`, and a `"Clock"` positioned `"Top left/right / Bottom left/right"` with `"Show date on clock"`.
- **Timeline behavior:** `"Past days to keep EPG"`, `"Show current programs"`, `"Show past programs without catch-up"`, `"Scroll to next programs"` / `"Scroll to previous programs"`, `"Return to current programs"` / `"Use Back to return to current programs"`.

**D-pad focus movement / focus-memory / fast-scroll** — the richest observable signal, from the remote-control mapping strings:

| Binding | Action |
|---|---|
| `OK` | open menu / toggle selection / change status / enter PIN |
| **Long OK** | open menu |
| `Left` | show groups / reset all / selection options |
| **Long Left** | navigate to past programs |
| `Right` | set all / copy-move / move to bottom |
| `Up` / `Down` | move selection |
| **Long Up/Down/Right/Left/Back** | dedicated long-press actions |
| **Page scroll channels up/down** | fast-scroll by page |
| `Skip step for FF/RW`, `Skip step for time bar (backward)` | seek granularity |
| `Search Action`, `Navigate up`, `Confirm exit by second press Back`, `Long press Back button to return to the player` | navigation |
| **`Reorder buttons`, `Visible buttons`, `Hidden buttons`, `button path`** | fully **user-remappable remote buttons** |

The app is TV-remote-first and explicitly touch-agnostic: `"TiviMate app is intended for using on TV boxes with a remote control. It is not optimized for devices with a touch screen."` Manifest: `android.software.leanback` (required=false), `android.hardware.touchscreen` (required=false), `MainActivity` `screenOrientation=landscape`.

**Unknowns (encrypted):** the concrete RecyclerView/custom-view hierarchy, the focus-memory implementation, and the exact program-block layout math.

---

## 2. Data handling

**Playlist ingestion (observed):**
- Types: `"M3U playlist"` (local `*.m3u`/`*.m3u8`), **`"Xtream Codes"`** with `"Xtream Codes parameters"` and `"Server address"`/`"Server name"`/`"Paste playlist URL"`, `"TV playlist"` vs `"VOD playlist (no TV channels)"`.
- Multi-playlist: `"Add playlist"`, `"Enable playlist"`, `"Delete playlist"`, `"Reorder playlists"`, `"Parent playlist"`, `"Update all playlists"`, `"Update playlist"`, `"Update on app start"`, `"Update on playlists change"`, `"Update interval, hours"`.

**EPG storage/caching (observed + inferred):**
- Format is strictly **XMLTV** (`"XMLTV format is only supported"`), per-playlist `"EPG URL"` with `"Assign EPG"`, `"EPG sources"`, `"EPG sources priority"`, `"EPG time offset"`.
- **Refresh strategy:** `"Update EPG"`, `"Update interval, hours"`, `"Update on app start"`, `"Update on playlists change"`, and a **partial-vs-full scan** policy: *"By default the app performs partial scanning of EPG source to get the list of channels to ensure maximum performance… try a full scan."*
- **Retention/memory limits:** `"Past days to keep EPG"` and `"Store program descriptions"` (opt-in).

**Persistence layer (observed, inferred):**
- **Native SQLite** `libsqliteJni.so` + **Room** ORM (manifest `androidx.room` `MultiInstanceInvalidationService`) + **Jetpack DataStore** `libdatastore_shared_counter.so`. The DB schema itself is encrypted/unknown.
- Logos: Glide + `"Logos folder"`, `"Logos priority"` (`"Prefer logos from EPG/folder/playlist"`), `"Inexact matching for logos files"`, `"Clear logos cache"`.

**Channel grouping & favorites (observed):**
- `"Groups"`, `"Create group"`, `"Group name"`, `"Group options"`, `"Reorder groups"`, `"Hide/Show/Block/Unblock group"`, `"Group channels by playlists in All playlists"`, `"Group All movies/shows by categories"`, `"Show newly added groups"`, `"Show All playlists category"`, `"Show Favorites category"`.
- Favorites: `"Add to/Remove from Favorites"`, `"Manage Favorites"`, `"Change Favorite status"`, `"Show favorite channels first"`, `"Show Favorites only"`.
- Sorting: global `"Channels sorting"`/`"Groups sorting"`/`"Playlists sorting"` (`"By order in playlist"`) with per-group/playlist override and **`"Manual channels sorting"`** (Premium). `"Reorder channels"`, `"Restore group name"`.

**Lean behavior on low-RAM (observed):** hard cap + delegation — `"Displaying first %d channels. Use Groups or Search to find more."` (and `%d movies`/`%d shows`). Manifest `android:largeHeap="true"` shows the app assumes a large heap for the in-memory EPG cache (TV-box assumption).

---

## 3. Playback

**Engine (observed):** **Media3 ExoPlayer** — 46 top-level `androidx.media3.exoplayer` classes including `ExoPlayer`, `HlsMediaSource`, `DashMediaSource`, `SsMediaSource` (SmoothStreaming), `RtspMediaSource`, `MediaCodecRenderer`, DRM `DefaultDrmSessionManager`, `offline.DownloadHelper`, `ads.AdsMediaSource`, `preload`, `source.MergingMediaSource`. Plus **FFmpeg native** (`libffmpegJNI.so` + `libavutil.so`).

**Buffering (observed):** `"Buffer size"` — *"The buffer size determines duration of video that must be downloaded to start playback. Try increasing this size if you frequently see buffering… This can increase the channel switching time."*

**Surface / decoding (observed):** `"Video decoder"` / `"Audio decoder"` with explicit HW↔SW fallback — *"Try the software video/audio decoder if your device has issues when decoding using the hardware decoder."* `"Tunneled playback"` (Android tunneled-video mode), `"Audio passthrough"`, `"Audio offset"`, `"Select surround audio track by default"`, `"Audio tracks"`/`"Video tracks"`.

**Resolution / frame-rate (observed):** `"Auto frame rate (AFR)"` — *"switch TV refresh rate to match the video frame rate… not all devices support auto frame rate"*; `"Switch rate for 50/60 FPS only"`, `"Switch screen refresh rate"`, `"Switch screen resolution"`, `"Show video resolution instead of labels"`.

**Fire TV / leanback optimizations (observed):** explicit **`"Amlogic fix"`** — *"Some devices based on Amlogic chipset can have bad picture quality… Enabling this setting can improve video playback"*; `"Show black screen when switching channels"`; `"Auto start app on wake up from sleep mode"` + `ScreenOnService` + `BootReceiver` (priority 999, disabled by default); `"Switch to picture-in-picture mode on press Home"` + `supportsPictureInPicture="true"`.

---

## 4. Multiscreen / multi-provider (Multiview)

**Observed:** branded **`"Multiview"`** with `"Add screen"`, `"Remove screen"`, `"Enlarge screen"`, `"Reduce screen"`, `"Turn on next channel"`, `"Turn on previous channel"`, `"Select groups"`, `"Select the playlists you want to see in the current group"` — a grid of simultaneously playing channels, each from an independently selected playlist/group (multi-provider).

**Resource/performance guards (observed):** `"Your provider may limit the number of concurrent connections"`, and `"Recording may not work on all channels and may require more than one server connection."`

**Audio focus:** not exposed as a string (only the focused tile audible vs. all-muted vs. primary audio is encrypted/unknown).

---

## 5. Other notable features (observed)

- **Recording / DVR:** `"Record"`, `"New recording"`, `"Custom recording"`, `"Schedule recording"`, `"Scheduled/Ongoing/All recordings"`, `"Start recording before program start, min"`, `"Stop recording after program end, min"`, `"Recordings folder"` (configurable, incl. SMB), `RecordingAlarmReceiver`. FFmpeg (`libffmpegJNI.so`) is the likely muxer.
- **Catch-up / timeshift:** `"Catch-up"`, `"No catch-up available"`, `"Show catch-up icon"`, `"Show past programs without catch-up"`, seek bindings: `"Use Down/Left to rewind live stream with catch-up"`, `"Use Left/Right for seeking"`, `"Use RW/FF/Pause for seeking/pause"`.
- **Parental controls / blocking:** `"Parental controls"`, PIN flow (`"Change PIN"`, `"Confirm PIN"`, `"Enter PIN"`, `"Press OK to enter PIN"`, `"PIN input method"`, lockout `"You entered the wrong PIN 5 times. Try again in %d sec."`), `"Require PIN for"`, `"Don't require PIN after unlocking"`, channel/group-level `"Block/Unblock channel"` / `"Block/Unblock group"`.
- **Reminders:** `"Reminders"`, `"My reminders"`, `"Remind before program start, min"`, `ReminderAlarmReceiver`, `ReminderPopupActivity`.
- **Search:** `"Search"`, `"Search history"`, `"Voice search"` / `"Speak to search"` / `"Prefer voice search"`, `"Search and add"`.
- **Backup/restore:** `"Back up data"`, local file **and cloud URL** (`"Enter link to a backup file, e.g. on Dropbox or Google Drive"`).
- **Self-update:** `"Update the app"`, `"Auto update app"`, `UpdateAppActivity` (uses `REQUEST_INSTALL_PACKAGES`).
- **SMB:** `SmbActivity`, `"Setup SMB"`.
- **Account / Premium gating:** `"Account"`, `"Create account"`, `"Unlock Premium"`, Parse SDK, **Firebase Realtime DB `https://tivimate-233306.firebaseio.com`**, Billing 9.1.0, **5-device limit**. Free-tier limits enumerated: *"Support for multiple playlists / Favorites management / Catch-up / Customizable EPG update intervals / Customizable panels transparency and timeout / Manual channels sorting / Turning on last channel on app start / Auto frame rate (AFR)"* are Premium.

---

## 6. Tech-stack appendix (observed)

| Concern | Component | Evidence |
|---|---|---|
| Playback | Media3 ExoPlayer (HLS/DASH/SS/RTSP, DRM, offline, ads, preload) | DEX class inventory |
| Decode/mux | FFmpeg `libffmpegJNI.so` + `libavutil.so` | APK lib inventory |
| Storage | Native SQLite `libsqliteJni.so` + Room + DataStore | lib inventory + manifest Room services |
| Networking | Retrofit2 + OkHttp (startup provider) | DEX + manifest |
| Images | Glide (OkHttpGlideModule) | manifest meta-data |
| Background | WorkManager (`androidx.work` SystemForegroundService/JobService) | manifest |
| Auth/premium | Parse SDK + Firebase RTDB + Play Billing 9.1.0 + Firebase Crashlytics/RemoteConfig/Analytics | manifest |
| Protection | DexProtector `libdexprotector.so` + `assets.dat` | APK lib/assets inventory |

---

## 7. Confidence ledger & remaining unknowns

- **Observed (high confidence):** package identity, manifest components/permissions/FGS types/leanback, the DexProtector packaging, the entire library/native stack, and the full feature/UI surface from resource strings.
- **Inferred (medium):** architecture implied by the stack (e.g., FFmpeg↔recording, Room↔EPG cache, ExoPlayer↔multiview tiles).
- **Unknown (blocked — encrypted):** all `ar.tvplayer.tv.*` feature implementations — EPG grid rendering & focus-memory, M3U/Xtream parsers, DB schema & refresh internals, buffering values & surface/decoder selection, Multiview layout/audio-focus, and the precise player wiring.

**Use this report as a feature checklist and behavior reference only** — the design decisions derived from it live in `design-brief.md` and `design-layout.md`, and the implementation is clean-room (standard Media3/SQLite/RecyclerView patterns), not a copy of TiviMate.
