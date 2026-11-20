# Throughput: is the app holding the download back?

Branch `audit/throughput`, from `4cbf05d`. Nothing here was run on the TV. The code was read, Media3 1.8.0's
own classes were checked where it mattered (with `javap`), and the new figures are unit-tested. What to look for
on the TV is at the end.

## The short answer

Most likely not. Nothing in FirePlayer's path holds one film download near 65-82 Mb/s. Every step is cheap at
10 MB/s, loading picks up again within about 10 ms of there being room, and Wi-Fi is kept at full power while
the film plays.

The two figures being compared measure different things:

- **122.7 Mb/s** is the Stick's speed test. Speed tests (Speedtest by Ookla, fast.com, Analiti) download over
  several connections at once, to servers chosen to be close.
- **65-82 Mb/s** is one HTTP connection to TorBox's server, the only kind a film uses. One TCP connection's speed
  depends on the distance to the server (round-trip time), packet loss on Wi-Fi, and whatever the server allows
  per connection. Getting 55-65% of a multi-connection test over a single connection is normal.

The proof has to come from the TV, so the app now logs the loader's real speed while it is loading. It also logs
whether a VPN carries the film and what else on the TV was downloading at the same time. The log line is
described at the end.

## What was checked in the app's path, and the verdict

| Suspect | What the code does | Verdict |
|---|---|---|
| HTTP stack | Media3's `DefaultHttpDataSource` on Android's `HttpURLConnection`. Since Android 4.4 that is OkHttp inside: the same Okio 8 KiB buffers and the same TLS (Conscrypt / BoringSSL) the OkHttp data source would use. | Not a limit. Switching would add a library and change nothing per connection. It also can't be built offline here (OkHttp isn't in the Gradle cache). |
| Connection reuse | One long `Range: bytes=N-` request per film position. A new connection only at the start, on a jump, on a retry, or on the provider-server move (`ServerSwitch`: at most 3 per film, at most once a minute). Redirects are remembered (`RedirectMemoryDataSource`). | Not a limit for steady loading. Each jump pays for a TCP and TLS handshake plus TCP slow start: a fraction of a second. |
| TLS cost | Decryption runs in BoringSSL on the loader thread. At 10 MB/s that is a few percent of an A55 core with AES instructions, and still well under a core without them. | Not a limit. It now shows in the log's `cpu=`. |
| Read sizes and copies | Each read returns at most 8 KiB (Okio's segment). That's about 1,200 reads a second at 80 Mb/s, each going through a few thin wrappers, then straight into the sample queue's 64 KiB pieces. Byte-by-byte reads happen only at the MKV index at the end of the file, and `BatchedInput` already batches those. | Not a limit: microseconds per read. |
| `DefaultAllocator` segment | 64 KiB (`C.DEFAULT_BUFFER_SEGMENT_SIZE`). Pieces are reused as the film plays, so steady loading allocates nothing. | Not a limit. |
| `continueLoadingCheckIntervalBytes` | 1 MiB (Media3's default). After each MiB the loader waits for the playback thread to say "go on". That thread runs every 10 ms while playing, at audio priority. Meanwhile the socket keeps filling (Android's Wi-Fi TCP buffer is typically a few MB). | Not a limit. The wait now counts against the app in `net-wait=`, so it would show. |
| Loader thread priority | Media3's `ExoPlayer:Loader` runs at normal priority, like the app's UI. The playback thread is above both. Decoding is in hardware. Background apps get a small CPU share on Android. | Not changed. The thread used 37-40% of a core even with memory exhausted, so it was not short of CPU. Raising it could take time from the menus. |
| GC from loading | No allocation per read. One case churns: while the TV is short of memory, the buffer is squeezed and the pieces already played are freed once a second (`FilmLoadControl.freeLetGo`), so loading allocates new ones. Under memory pressure each new 64 KiB array costs page faults and can wait for the kernel to reclaim memory. Part of the 37-40% is likely that. | Kept as it is. It happens only while memory is short and little of the film is ahead, and keeping those pieces would hold on to memory Fire OS is asking back for. |
| Loading pauses at the buffer target | Media3's rule with 120 s min = max: loading stops at the byte target and resumes as soon as one piece is free (no gap in bytes). While loading is off, the playback thread asks again every doSomeWork (10 ms while playing; checked in Media3 1.8.0's `ExoPlayerImplInternal.maybeUpdateLoadingPeriod`). | Not a limit: it never restarts late. |
| Wi-Fi power | `WAKE_MODE_NETWORK` holds a Wi-Fi lock in mode 3 (`WIFI_MODE_FULL_HIGH_PERF`, checked in Media3's `WifiLockManager`) while the film plays or waits. | Not a limit. |
| The measured `down=` | `down=` is bytes over every second, including seconds when the buffer was full and loading only kept pace with the film. `free=` and `sustained=` leave full seconds out, but they are whole seconds sampled on the main thread. A second that was only partly held still counts. | A real gap in the measurement, now closed by the loader line below. `down=`, `free=` and `sustained=` are unchanged (the source offer uses `sustained=`). |

What this rules out is the app. What it doesn't rule out: TorBox's server or CDN path for one connection, Wi-Fi
loss, a VPN carrying the film, or another download on the same line. The last two are answered below and in
the new log line.

## What changed

1. **The loader line** (`LoaderRate`, `PlaybackDiag`). The film's HTTP connection is wrapped (`LoaderRate.Timed`,
   in `PlayerFactory.dataSourceFactory`) so every read is timed. `FilmLoadControl` reports each time it holds
   loading back. Only "runs" are counted: reads one after another with no hold and no gap over 250 ms. A run's
   first 6 MiB are left out, because after a pause they arrive at once from what the network stack queued, and the
   server's TCP starts slow again (the same rule `RedirectMemoryDataSource` already uses for the sources row).
2. **Which way the film goes** (`NetPath`). This is the VPN state of the TV's network as this app sees it, and
   what the rest of the TV received meanwhile (`TrafficStats`: the whole TV minus this app). No addresses are
   logged, only the kind of path.

Logged under `FirePlayerDiag`, 20 s after the film's player is built and every minute after, playing or not:

```
loader flat=78.3Mb/s/41s net-wait=93% cpu=31% loading=70%/60s got=512.0MB others=0.4Mb/s vpn=none
```

- `flat=` Mb/s over the seconds the loader loaded flat out, and how many seconds that was. `flat=none` means it
  never did: the buffer stayed full, so the line wasn't put to the test that minute.
- `net-wait=` the share of those seconds spent inside the connection's read, waiting for bytes (TLS decryption
  included). The rest is the app's own work: the MKV extractor, the copy into the buffer, and the hand-off to
  the playback thread.
- `cpu=` the loader thread's CPU over those seconds, as a share of one core.
- `loading=` the share of the minute the loader was loading at all.
- `got=` everything that came on the film's connection.
- `others=` what every other app on the TV downloaded in the same minute.
- `vpn=` `none`, `split` (a VPN is on but carries only its own addresses), `all` (a VPN carries all traffic: an
  exit node), or `not-this-app`. ` dns=vpn` is added when the VPN also answers name lookups.

How to read it:

- **`net-wait=` 85% or more, `cpu=` well under 100%**: the loader is waiting on the network. The app is not the
  limit, and `flat=` is what TorBox and the line give one connection.
- **`net-wait=` low and `cpu=` near 100%**: the app is the limit (its CPU). Not expected. If seen, the next step is
  the loader's priority.
- **`net-wait=` low and `cpu=` low**: the loader waits on something else, most likely the playback thread's
  hand-off, or other threads taking the CPU.
- **`flat=` near `sustained=`**: the play line's figure was right. `flat=` well above it: the play line was averaging
  in pauses.

## Can Tailscale on the TV slow a TorBox download?

Only if it carries the download, and it does that only with an **exit node**. On Android, Tailscale is a VPN
that, without an exit node, routes only its own addresses (100.64.0.0/10 and its IPv6 range, plus subnets you
accepted). TorBox traffic goes straight out over Wi-Fi, untouched. With an exit node, every byte of the film is
encrypted and decrypted by Tailscale's WireGuard on the Stick's CPU. It then makes a detour through the exit
node's own upload. That can cost a lot.

**"Use Tailscale DNS"** sends only name lookups through Tailscale, not the download. Two side effects: lookups take
a little longer, so a connection starts a little later. And if your tailnet's DNS answers from somewhere else,
a CDN that picks its server by where the lookup came from may give you a farther server. A farther server
means a slower single connection. TorBox's own CDN choice (TorBox › Settings › CDN, see FILM-STALLS.md) is the
surer lever.

How to tell:

- In the Tailscale app on the Stick: **Exit node** should say **None**.
- In the log: `vpn=split` (or `vpn=none`) means the film goes straight out. `vpn=all` means it goes through
  Tailscale. With `vpn=all`, `others=` also comes out near the film's own rate: the same bytes are counted once
  in the tunnel and once more on Wi-Fi.
- To be sure, play the same film with Tailscale switched off and compare `flat=`. Newer Tailscale versions can
  also leave chosen apps out of the VPN (app-based split tunneling), if yours has it.

Idle, Tailscale costs some memory and almost no CPU or network (keep-alives).

## Can background apps slow it?

- **Network: yes, if they download.** Every download on the line shares it with the film. On a Fire TV the usual
  ones are Appstore auto-updates, Fire OS updates, and the launcher's video previews and ads. The log's `others=`
  shows it: under about 1 Mb/s, nothing else was downloading.
- **CPU: hardly.** Android gives apps in the background a small share of the CPU, and the film's loader is not
  short of CPU (37-40% of one core even at its worst tonight).
- **Memory: yes, indirectly, and on a 1.7 GB Stick this is the one that matters.** It doesn't slow the download.
  It shrinks how much of the film fits ahead (the buffer is sized by the memory free when the film starts), and
  when Fire OS runs short during the film the buffer is squeezed. Then there's less room for a slow scene, so
  more stalls at the same speed. Loading also costs more CPU then (fresh memory for each piece).

What you can do:

1. Fire TV **Settings › Preferences › Featured Content**: turn **Allow Video Autoplay** off (the launcher's
   previews download and use memory).
2. **Settings › Applications › Appstore › Automatic Updates**: off, or leave the Stick on overnight for updates.
3. Before a 4K remux, restart the Stick (**Settings › My Fire TV › Restart**). It frees the most memory, and the
   buffer is sized at the film's start.
4. Force-stop apps you've used and won't need (**Settings › Applications › Manage Installed Applications**).
5. Tailscale: no exit node while watching, or switch it off. Leave "Use Tailscale DNS" off unless you need
   tailnet names on the TV.

## On the TV

1. Install from this branch, play the 4K remux, and leave it 3-4 minutes.
2. `adb logcat -s FirePlayerDiag | grep -E "loader|play pos"`
3. Read the `loader` lines as above. Expected: `net-wait=` high, `cpu=` well under 100%, `flat=` in the same
   65-82 Mb/s range, `others=` near 0, `vpn=split` or `vpn=none`. If so, the app isn't the limit, and the levers
   are TorBox's CDN choice and a lighter copy (FILM-STALLS.md).
4. To see what one connection gets outside the app: download the same TorBox file on a computer on the same
   Wi-Fi with a plain browser download (usually one connection), then compare it with `flat=`.
