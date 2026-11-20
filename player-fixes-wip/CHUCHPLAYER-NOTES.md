# ChuchPlayer as a reference for lean live playback (Live TV pass 2)

Reference: ChuchPlayer (React Native / Expo IPTV player with native Android Kotlin modules), read only.
**Licence: none found** (no LICENSE file, no `license` field in package.json), so all rights reserved by its
author: ideas and settings were compared, no code was copied.

Its live player is in `native/android/.../player/` (ExoPlayerModule.kt, ExoPlayerHolder.kt, MultiExoPlayerView.kt).
It is plain Media3 with almost everything at defaults.

| Area | ChuchPlayer | FirePlayer (live) | Applied? |
|---|---|---|---|
| Player | One process-wide ExoPlayer (singleton holder), `setMediaItem` + `prepare` per channel | Same idea: one PlayerHost player reused across channels, rebuilt only for other headers or another Live TV buffer | Nothing to do |
| Buffer (min / max / start / after stall) | 1 s / 30 s / 0.5 s / 1 s, time not prioritised over size | Medium 15 / 30 / 1.5 / 3 s, cushion grows after each stall (pass 1), time prioritised | **No**: its 0.5 s start is what makes a shaky channel stall again and again; pass 1's growing cushion is the fix for the user's mid-channel buffering |
| Memory ceiling of the buffer | Media3's default byte target (because time isn't prioritised, the byte target stops loading) | Seconds only, bytes capped only after onTrimMemory | **Yes**: a 64 MiB ceiling at all times (`LiveCushion.NORMAL_BYTES`), with pass 1's floor so it never causes a stall. 30 s of a 4K channel would be 90 MB+ on a 1.7 GB Stick; a 1080p channel never reaches it |
| Back buffer | Default (0) | Default (0) | Nothing to do |
| Track selection / resolution cap | Defaults (viewport = display size) | Defaults (viewport = display size), audio/caption language from Settings | Nothing to do |
| Decoder and surface | PlayerView's SurfaceView, kept between channels | One SurfaceView, kept between channels; player released (not just stopped) when leaving live so the film player gets the decoders | Nothing to do |
| Tunnelling / passthrough | Neither | Passthrough setting; no tunnelling | **No**: tunnelling on Fire TV has A/V-sync quirks and gives no memory gain |
| Timeouts | Connect 30 s, read 120 s | Connect 10 s, read 10 s, plus pass 1's stall watchdog (12 s with nothing arriving) | **No**: ours already fails faster on a dead connection |
| HTTP errors | Default load-error policy (internal retries on everything), then 5 source retries 1, 2, 3, 4, 5 s apart, then an error event with the code and cause | Media3 retried every load error 3 more times inside each try (a 404 cost four requests per try), then 1, 2, 4, 8 s, then every 30 s forever, shown only as buffering | **Yes**: no internal retries for 4xx answers (`PlayerFactory.LiveErrorPolicy`); a refused channel is said in plain words (Part B, `LiveRefusal`) and tried again 1, 5, 15, then every 30 s. Taken from it: say the HTTP status, walk the error's cause chain for it. Not taken: giving up after 5 tries (the user wants it to keep trying while they stay) and logging the cause's message (it can hold the stream address) |
| Keep-alive / pooling | DefaultHttpDataSource (HttpURLConnection keep-alive) | Same | Nothing to do |
| Audio focus | Not handled (`handleAudioFocus` false) | Handled | **No**: we keep it for single view; for Multiview see MULTIVIEW-NOTES.md |
| Frame-rate matching | `setVideoFrameMetadataListener` called for every frame + `Surface.setFrameRate` | None on live | **No**: a callback per frame is the per-frame work we avoid |
| Memory release | Releases the player when the React context dies; nothing on onTrimMemory | Released when leaving live; buffer squeezed on onTrimMemory (pass 1) | Part C adds: the pages' cached pictures, page glass copies and unused graphics let go while Live TV is in front (`LiveRoom`) |
| Channel logos | Native list decodes logos at the exact drawn size (LogoBitmapUtils) | Live TV keeps its own logo cache, dropped when the page stops | Nothing to do |
