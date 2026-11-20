# Multiview: notes for the engineer who builds it

## 1. The user's design (their words)

> "For multiview i would want 2-4 side by side, pressing one makes one larger and puts the others underneath and
> holding a selected picture gives me the options to remove, add/search, change/search, or go full screen."

Agreed earlier: it lives inside the Live TV player; sound only from the focused picture; never more streams than the
provider allows; the build starts only after Search and these Live TV updates are in.

## 2. What in FirePlayer's live path assumes one player (checked in the code)

- **PlayerHost** (`playback/PlayerHost.kt`) is a process-wide singleton (`PlayerHost.get`) holding one `ExoPlayer`,
  one `SurfaceView` (`attach`/`detach`), one `LiveLoadControl`, one `LiveReconnect`, one `LiveRefusal`, one stall
  watch, one `LiveTiming`. Every listener gets one `Status` (one key, one buffering flag, one error). Multiview needs
  a per-tile engine (player + surface + reconnect/refusal/stall state + status) and PlayerHost becomes the owner of
  1 to 4 of them, with "the focused one" for everything the controls touch.
- **LiveSession** (`playback/LiveSession.kt`): "One channel plays at a time"; `tune` *replaces* what plays; one
  `Engine.open/close`; one lease `LEASE` per session. Tiles need one session (or one lease holder name) each.
- **ConnectionBudget** (`PlayerHost.budget`, per provider account, `limit` = playlist `maxConnections`, 0 means 1)
  is already shared and counts holders, so it is the right place to refuse a 3rd/4th tile. Its own doc already names
  Multiview tiles. The film player must also count (today a film stops live first: `FilmMode.stopLive`).
- **Audio focus**: `PlayerFactory.buildLive` sets `handleAudioFocus = true`. Two players asking for focus take it
  from each other (the loser pauses). Tiles must be built with `handleAudioFocus = false` and volume 0, and only the
  focused tile owns focus and volume (ChuchPlayer does exactly this: `isMuted = !isFocused`, volume 0 on the rest).
  `PlayerHost.setMuted` is single.
- **Single fields** in PlayerHost: `captionsOn`/`captionsMime`/`cues`, `audioDelayUs` (+ `delayKey`), `paused`,
  `behind` (Go live), `pickAgainAudio/Caption`, `memoryStep`, `waitReason`, `error`. All become per tile; captions,
  audio delay, pause and Go live apply to the focused tile only.
- **HomeActivity** places one `surface`, one `BusyOverlay`, one `captions` view, one hole in the wallpaper
  (`wallpaper.setHole`), one `LivePicture` motion. Tiles need their own surface, busy sign and hole each, laid out
  once per layout change (never per frame).
- **LiveRoom / memory** (Part C) and `LiveCushion.capBytes` assume one buffer: with 4 tiles use the Small Live TV
  buffer per tile and a shared byte ceiling (e.g. 64 MiB split by tile count).
- **Decoders on the Fire TV Stick 4K Max**: expect one 4K decode at most; several 1080p hardware decodes usually work
  but each holds its own output buffers (graphics memory, already 208 MB measured with one channel). Plan: tiles cap
  video to their size (`setMaxVideoSize` on the tile's track selection, which picks a lower HLS variant), one tile at
  a time may be 4K (the large/focused one), and a decoder failure on a tile is shown on that tile, not retried hard
  (`CANT_DECODE` logic exists, reuse it).
- **Surfaces**: several `SurfaceView`s overlap badly with glass; keep tiles as SurfaceViews without glass over them,
  and keep the controls' glass over the focused tile only.

## 3. How ChuchPlayer does several pictures (read only, licence: none found, ideas only)

- `MultiExoPlayerView.kt`: each tile is its own native view owning its own `ExoPlayer` + `PlayerView`, created
  lazily and released in `onDetachedFromWindow`. Buffer 1 / 30 / 0.5 / 1 s per tile, default track selection.
- `useMultiScreenStore.ts`: up to `maxScreens` (4); layouts `grid` and `split` (one featured, the rest under it, which
  matches the user's "one larger, others underneath"); `fullscreenScreenId` for one tile full screen;
  `setFocusedScreen` mutes every tile but the focused one; remove/add/change channel per tile; next-tile cycling.
- Worth taking: the state shape (tiles list, focused id, featured id, full-screen id), "focused = only one with
  sound", release-on-detach. Not worth taking: unbounded per-tile buffers, no connection limit check, no audio focus
  handling, per-frame frame-rate callbacks.

## 4. Proposed build order

1. Split PlayerHost into a per-tile `LiveTile` (player, surface, reconnect/refusal/stall, status) and a host that
   owns 1 tile; no behaviour change, all live tests still green.
2. Lease per tile through `ConnectionBudget`; host refuses a tile the provider wouldn't allow and says so.
3. Audio: tiles built without audio focus and muted; the focused tile owns focus and volume; captions, audio delay,
   pause and Go live follow focus.
4. Layout in the Live TV player: 2-4 side by side; OK makes one larger with the others underneath; hold opens
   remove / add (search) / change (search) / full screen. Small buffer and size-capped video per small tile.
5. Memory: one shared byte ceiling, `LiveRoom` and onTrimMemory across tiles; check on the Stick with 4 tiles.

## 5. What was built (branch feat/multiview)

- Entry: full screen's extra row, Multiview (no longer dimmed). The channel playing becomes the first picture and the
  channel picker (Live TV search's bar and results, `live/ui/ChannelPicker.kt`) opens for the second; Back from it
  leaves full screen as it was. The one live player (`PlayerHost`) is stopped only once the second channel is picked,
  and is used unchanged otherwise.
- Layouts and moves are pure and unit-tested (`live/ui/MultiviewRules.kt`): 2 side by side, 3-4 in a 2 x 2 grid (with 3
  the free cell is "Add a channel"), OK makes a picture large on top with the others in a row under it, OK on it again
  goes back. Pictures move by translation and scale, laid out once at the end (`MultiviewBoard`).
- Held OK (or Menu) on a picture: Remove, Add channel, Change channel, Full screen (`HoldPopup`). Back, Full screen, or
  removing down to one picture: full screen on that channel in the one live player.
- Players: one `MultiviewPlayer` per picture (Small buffer, memory step from the start, sound decoded and muted unless
  selected, no audio focus of its own; Multiview holds the focus once), its own `SurfaceView`, one `ConnectionBudget`
  lease each (`MultiviewLeases`); a channel the allowance has no room for is said so and not opened. A picture the TV
  can't decode says so after two tries and lets go of its player. Everything is released when Multiview closes or the
  screen stops; the pictures come back with the screen. Leaving the Live TV tab, a film starting or the sleep timer
  ends Multiview.
- Settings › Live TV › Multiview: "Multiview pictures" (up to 2, 3 or 4; default 4) and "Multiview with 3 or 4" (in a
  grid, default, or one large on top).
- Still to check on the Stick: 4 pictures at once (decoders, memory, `dumpsys meminfo`), the move's smoothness, the
  keyboard over the picker.
