# Film stalls: "the download is faster than the film, why does it keep buffering?"

Branch `fix/film-stalls`, from `a86c69d`. Nothing here was run on the TV: the rules are unit-tested, the
measurements are the ones taken on the Fire TV Stick 4K Max before this work. What to look for on the TV is at
the end.

## The short answer

The download was not faster than the film. It was about equal to it, and "about equal" is not enough.

- The file: The Odyssey, 4K Dolby Vision remux, 89.1 GB over 2 h 53 min. That is **about 69 Mb/s on average**,
  and a remux runs well above its average in busy scenes, for minutes at a time.
- The download, from the log's `down=` while the buffer had room: **65.6 to 82.2 Mb/s**. The Wi-Fi link was
  576 Mb/s, so the line into the Stick was not the limit; what TorBox's server sent over one connection was.
- What the Stick can hold ahead: the film's buffer was 53 to 128 MiB (the Stick has 1.7 GB; the 4K decoder
  takes up to 200 MB of the same memory, and Fire OS asked for memory back during the film). At this film's
  rate that is 6 to 15 s in all, part of it kept behind for going back. The log showed **1 to 5 s ahead**.

A film that arrives at 0.95 to 1.15 times the speed it plays, with 5 s of room, runs dry every time a scene
needs more than the line brings for longer than a few seconds. No buffer rule changes that; only a faster
supply or a lighter file does. Dropped frames were 0 throughout: decoding was never the problem.

Two things in the app made it worse than it had to be:

1. **Each stall played on with half of what fits.** `FilmMemory.waitUs` capped the wait at half the room
   (2.5 s of 5 s), at every stall, however many there had been. Hence "rebuffer end after 2.4-3.0 s" and the
   next stall 6 to 16 s later. The old rule's 4, 10, 20 s steps never applied: the cap came first.
2. **The automatic switch went to the same film at the same size.** "recovery stalls past the limit: trying
   the next source" picked the next row in the list, which was the same file from StremThru Torz (also
   TorBox). It stalled the same, as it had to.

## Research

What I could establish, and what I could not.

**TorBox and per-connection speed.** Not documented either way that I could find.
- TorBox's changelog (February 2024) says downloads "can use up to 16 connections", so several connections to
  one file are allowed. This is from the search result's summary of
  `torbox.featurebase.app/en/changelog/v35`; the page itself could not be opened from this session
  (`torbox.featurebase.app` is outside its allowed network), so I have not read the wording in context.
- A request on TorBox's board, "Clear limit on simultaneous connections", was answered (again per the search
  summary) with "no real explicit limit, but it's all fair share". Same caveat.
- TorBox's October 2025 changelog mentions CDN changes for download speed, and a moderator reply on
  r/TorBoxApp (April 2025) advises running TorBox's speed test and switching the CDN between EU, US and auto
  in TorBox's settings, and using Ethernet. That is the one thing the user can try today without any code:
  **TorBox › Settings › CDN, and its speed test from a computer on the same network.**
- Nothing I found says whether TorBox holds one connection to a speed. 65 to 82 Mb/s on a 576 Mb/s link looks
  like a server-side or path limit on a single TCP connection, which is the case where a second connection
  helps; but that is an inference from one evening's numbers, not a finding.

**Whether several range connections raise throughput.** In general, yes when the limit is per connection (a
CDN's per-request cap, or one TCP stream on a long or lossy path), no when the limit is the account, the
server's uplink or the home line. Gcore's CDN documentation states its limit is per request, so two
connections give twice the speed; that is Gcore, not TorBox. Emby and Plex forum threads report both
outcomes. So: possible, unproven for TorBox.

**Media3 (1.8.0, the version in this app; read from the library's own class).**
`DefaultLoadControl.shouldStartPlayback` plays on when the film ahead reaches `bufferForPlaybackMs` (after a
stall: `bufferForPlaybackAfterRebufferMs`), **or** when the memory held reaches the byte target and time is
not put before size. Its defaults: 50 s min and max, 1 s to start, **2 s after a rebuffer**, no back buffer,
and a byte target of 131 MB video + 13 MB audio (144 MB). `shouldContinueLoading` stops at the byte target
whatever the time ahead. What is kept behind counts toward the same bytes. So in any Media3 player a file
this size is limited by bytes, not seconds, and plays on after 2 s unless the app says otherwise. FirePlayer
already replaces these rules (`FilmLoadControl`); the fault was in its own cap, above.

**Other players on 1 to 2 GB devices.**
- **Just Player** is a plain Media3 player. I did not read its source in this session; if it keeps Media3's
  defaults, as I believe it does, that is about 144 MB all ahead, nothing behind, 2 s after a stall. On the
  Stick that would be about 15 s of this film ahead, more than FirePlayer keeps, at the price FirePlayer
  measured earlier (a 230 MB buffer had Fire OS's low-memory killer looping and the film losing frames).
- **Kodi** caches by bytes in memory, not seconds: Settings › Services › Caching since v21 (before:
  `advancedsettings.xml`, `memorysize` and `readfactor`). Community guides for Fire TV set about 133 MB, and
  one Kodi 21 user reports 192 MB with read factor 7 for heavy Dolby Vision files on a bigger device. (From
  memory of the wiki, not re-read here: Kodi needs about three times the cache size in RAM.) Its cure for
  remuxes on slow lines is the same as anyone's: a bigger cache where there is memory, a lighter file where
  there is not.
- **Stremio**: not researched beyond what is generally known (its Android TV app plays debrid links in its
  own player and leaves the choice of file to the viewer). I found nothing saying it opens a second
  connection for such links, and did not look further.

None of them does anything FirePlayer could copy to play a 69 Mb/s film over a 72 Mb/s connection in 128 MiB.

Sources: [TorBox changelog, February 2024](https://torbox.featurebase.app/en/changelog/v35) ·
[TorBox: Clear limit on simultaneous connections](https://torbox.featurebase.app/p/clear-limit-on-simultaneous-connections) ·
[TorBox changelog, October 2025](https://torbox.featurebase.app/en/changelog/v81) ·
[r/TorBoxApp thread, April 2025](https://redlib.cypherpunktools.com/r/TorBoxApp/comments/1k6s87t/lately_torbox_is_working_great) ·
[Gcore: network limits](https://gcore.com/docs/cdn/cdn-resource-options/network-limits-set-the-content-delivery-speed-to-end-users.md) ·
[Emby: single or multiple TCP streaming](https://emby.media/community/topic/112090-single-or-multiple-tcp-streaming) ·
[Kodi wiki: Settings/Services/Caching](https://kodi.wiki/view/Settings/Services/Caching) ·
[Kodi wiki: Modify the video cache](https://kodi.wiki/view/HOW-TO:Modify%20the%20video%20cache) ·
Media3 1.8.0 `androidx.media3.exoplayer.DefaultLoadControl` (constants and `shouldStartPlayback`, read from
the library in the Gradle cache).

## What changed

### 1. The wait after a stall grows with each stall in the same film

`player/FilmStalls.kt` (`StallCount`, `StallCushion`), used by `FilmLoadControl` in `PlayerFactory.kt`.

| stall in this film | wanted | of what fits in memory |
|---|---|---|
| 1st | 4 s | half (as before) |
| 2nd | 10 s | three quarters |
| 3rd and on | 20 s | all of it |

"Wanted" is never less than the film's own shortfall calls for (the download's speed over the film's,
measured as it loads: at 0.8x, 12 s). "What fits" is this film's seconds in this TV's memory, measured when
the buffer was last full; a full buffer always plays on, so the wait cannot hang. Stalls count up while they
keep coming and are forgotten after 10 quiet minutes (before: a sliding 5 minutes). The first start, starts
after a jump and the first stall are unchanged.

With 5 s of room this turns a 2.5 s wait into a 5 s one from the third stall: about twice as long, about half
as often. It does not stop them.

### 2. Less is kept behind while the film stalls

`BackBuffer.keepUs(…, tight)` in `player/BackBufferCap.kt`. On TVs under 2.6 GiB what is kept behind is
already held to a size (24 MiB on the Stick: 2.3 s of an 84 Mb/s film). While the film has stalled lately,
and only where that cap already bites (over about 32 Mb/s on the Stick), half the cap is kept, down to 1 s.
That gives about 12 MiB, a second and more, to what is ahead. Lighter films and TVs with 2.6 GiB or more keep
what they kept.

### 3. A source the connection cannot keep up with is not kept on, and not swapped for its twin

`vod/play/FilmSupply.kt`, `vod/play/SourcePlayback.kt`, `vod/sources/LineRate.kt`, `SourceRank.fit`,
`DefaultSourceSession.lighter`.

- The player now measures what the line **sustains**: every second the film loaded flat out (room in the
  buffer, under a minute ahead), mostly the last three minutes (`DownloadRate.sustainedMbps`). Not the best
  three seconds, which is `best=`.
- When the stall rules call for a switch (3 stalls in 2 minutes, one over 20 s, or a quarter of the time
  waiting; unchanged) and the source needs more than **90 %** of that speed (and the film is not seen to
  arrive at 1.3x or more: both measurements must agree), the next source is **not** tried. Instead, once:

  > This source needs about 69 Mb/s and this connection is bringing about 72, so it keeps pausing. Switch to
  > 4K · HDR · BluRay · 24.1 GB · ≈26 Mb/s? It carries on from here.
  > **Switch to it** · **Keep this source**

  The source offered is the best by "Best for this TV" that needs at most **75 %** of the line (room for
  busy scenes). It starts 5 s before where the film was. With nothing lighter ready, a note says so and the
  film stays where it is.
- The answer is remembered for the film: after "Switch to it", a later heavy source of that film is left
  without asking; after "Keep this source", that source is never switched away from or asked about again
  (another source of the film is judged afresh). Back closes the question without remembering anything; it
  is not asked again in that play.
- The speed measured goes into the Sources list. "Best for this TV" is unchanged until a film has actually
  stalled past the limit on this TV for want of speed. From then on, for two weeks, a file needing over 90 %
  of that speed ranks as "likely not" and its row says **"Needs 69 Mb/s · this connection brought 72"**, so
  the title page's Play picks the best file the line sustains. A film later seen to load 25 % faster or more
  replaces the figure at once. Picking the heavy file by hand in Sources always works.
- A source the viewer picked by hand is no longer left silently for ordinary stalls either: asked once, "This
  source keeps pausing. Try the next source?". A source FirePlayer picked itself, on a connection fast
  enough for it, moves to the next source as before.

### 4. A second connection: not built

The research does not show that a second connection raises throughput from TorBox, which was the condition.
It may well (see above), but building it blind has real costs on this device: each connection's read-ahead
is memory the film's buffer does not have (two 4 MiB chunks are a sixth of a 53 MiB buffer), every chunk is a
new range request to a debrid CDN that may count or throttle them, and an ordering mistake in the bytes
handed to the extractor corrupts the film rather than merely stalling it. That is not something to ship
untested on the TV behind a switch.

What would settle it, in ten minutes, before any code: on a computer on the same network, download the same
TorBox link once with one connection and once with two (`aria2c -x1` against `aria2c -x2`, or two `curl -r`
ranges at once), and compare the totals. If two give clearly more than one, the connection is the limit and
"Faster downloads (2 connections)" is worth building; the log already has what it needs to show the rate
each way (`best=` and `sustained=`). If they give the same, the limit is TorBox's server or account, and no
player can do better: try TorBox's CDN setting, or the lighter file.

### 5. Settings › Playback › When a film keeps buffering

`Ask` (default) · `Switch to a lighter source` · `Keep trying`. The same picker row as Live TV buffer.

- **Ask**: as in 3.
- **Switch to a lighter source**: no question; a note says "Kept pausing at 72 Mb/s · switching to a lighter
  source". Also allows the ordinary next-source switch away from a source picked by hand.
- **Keep trying**: nothing is switched and nothing asked, whatever the reason for the stalls.

## What to read in the log on the TV

`adb logcat -s FirePlayerDiag FirePlayer`, the same remux, from the title page's Play.

1. Every 10 s, the `play` line now ends its download figures with `sustained=72.4Mb/s/41s` (what the line
   keeps up, over how many seconds), and its note has `stalls=3 stallWait=5000ms arriving=0.98x`.
   - `arriving` near 1.00x with `ahead=` of a few seconds is the cause, restated by the app itself.
   - `sustained` against the source's ≈ Mb/s in the Sources row is the comparison the offer is made on.
2. At each stall, one line: `stall 3 in this film: playing on with 5000ms ahead (wanted 20000ms, 5000ms fit,
   arriving at 0.98x)`. The first should read about half of what fits, the second three quarters, the third
   all of it. `rebuffer end after …ms` should grow accordingly (about 2.5 s, 3.7 s, 5 s with 5 s of room) and
   the gap to the next `rebuffer start` with it.
3. `kept=` in the note drops to about half (2300 ms to 1150 ms on this film) after the first stall, and
   `ahead=` gains about a second when the buffer is full.
4. When the stall rules trip: `recovery stalls past the limit and the connection is short: the source needs
   69 Mb/s, the download sustains 72 Mb/s, arriving at 0.98x; a lighter source is ready at 26 Mb/s`, and the
   question appears. `recovery stalls past the limit: trying the next source` should **not** appear for
   this film any more.
5. After "Switch to it": `recovery asked: switching to the lighter source`, `recovery another source starts
   at h:mm:ss`, then `play` lines with `ahead=` growing to what fits (about 40 s of a 26 Mb/s file in
   128 MiB) and no more `rebuffer start`.
   Open Sources: the remux row reads "Needs 69 Mb/s · this connection brought 72".
6. If instead `sustained=` reads far above the file's rate (say 150 Mb/s) and it still stalls, the line is
   not the cause and none of this applies: send that log.

## Not done, and risks

- Not run on a device. The buffer rules run on Media3's playback thread; the new code there is arithmetic on
  values that thread already had, one log line per stall, and one small object when the figures change.
- The offer depends on the add-on stating the file's size and the title having a runtime (true for this
  film). Without them it falls back to the arrival rate alone, and offers nothing lighter unless the line's
  speed was measured.
- "Keep this source" is remembered for that source of that film for good. It is undone by picking another
  row in Sources.
- The remembered line speed is one figure for the TV, not per debrid service or per time of day. Two weeks,
  or one faster film, clears it.
- A film that stalls for want of speed still stalls until the rules trip (three stalls). That is deliberate:
  one stall is not evidence.
