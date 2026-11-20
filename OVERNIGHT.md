# Overnight run — 2026-11-20 (the user is asleep; work autonomously, in this order)

The user's instruction (about 00:55 their time, UTC-5): do all of this autonomously, in order, efficiently, no
mistakes; they check it tomorrow. Usage windows reset at 07:00 and 12:00 UTC (2:00 and 7:00 their time). If a window
runs out, agents must have saved their work (WIP commits) and the work continues at the reset.

If you are resuming after a restart or a summary: read this file, `git log --oneline -15`, the branches named below,
and continue from the first unfinished step. Update the status lines here as steps finish (commit + push).

## Standing rules

- Kotlin, Android Views, no Compose, no coroutines. Lean and smooth on the Fire TV Stick 4K Max (1.7 GB).
- One shared place for look values; shared components; Classic mode intact; nothing works while hidden.
- Never print, log or store stream / playlist / add-on URLs, logins or keys. Never repeat the TV's serial or Wi-Fi.
- Push only to `claude/blissful-mayer-2nf8z9` (each repo) except the release in step 5, which the user asked for.
- Build + all unit tests before every merge; verify on the TV (adb via the Tailscale tunnel) before calling a step done.
- Agents: build agents on Opus to save usage, except where the user said Fable (step 1). WIP commits every ~20 min.
- The TV tunnel and port forward stop after 2 hours: restart both before installing.

- **Hard rule (the user, 01:05 their time): "I want to keep glass home's quick return and quick functionality
  though".** Glass Home must come back as instantly as today and stay as quick: nothing its screen needs on return
  may be released. A Glass Home change that cannot be shown to keep that is not shipped.

## Steps

1. **Playback audit (Fable) + guide loading + playback settings.** Audit Live TV and film playback and make watching
   better (memory on the device first: tonight FirePlayer 230-400 MB, Glass Home 158 MB in the background, swap
   exhausted; Glass Home may be edited). See whether App Settings > Playback deserves a few more preferences (not
   many; none in the 3-line windows of the film player or Live TV unless warranted; organised like the others). The
   best source stays the top option for watching films. EPG, channel groups, channels and programmes in the guide
   must appear quickly.
   Branches: `audit/vod`, `audit/live` (Fable), Glass Home memory in `/home/user/glass-home-firetv`.
   Status: started.
1b. **Sources order (the user, 02:10 their time): "I want 4k imax (open matte) ratio files to be at the top of the
   sources list."** After `audit/vod` is merged (same files: `vod/sources/*`): a 4K (2160p) release marked IMAX or
   open matte (release-name words such as IMAX, IMX (the user added: "IMX as well"), "Open Matte", OpenMatte, Open.Matte) ranks above every other source,
   in the Sources list and therefore as the best source, as long as it is one this TV can play and is not marked
   troubled; among themselves, and for everything else, today's order stays. Parser words and the rule as pure,
   unit-tested logic. Status: not started.
1c. **A third add-on (the user, 02:45 their time): StremThru "Torz"** ("starts with
   https://stremthru.13377001.xyz/stremio/torz/ ... ends with manifest.json"; the user enters the whole link on the
   TV: never ask for it, print it, log it or store it anywhere but encrypted on the TV). Today the app has two fixed
   add-ons (`Addon.TORRENTIO`, `Addon.MEDIAFUSION` in `vod/SourcesContract.kt`, parsed in `vod/sources/*`). Add a
   third the same way: Settings > Add-ons row with its hint and note, link check, its stream format parsed (read the
   format from StremThru's public source / an unconfigured manifest, never from the user's link), failures, limits,
   merged and ranked with the others, unit tests. Build with 1b after `audit/vod` is merged (same files).
   Also asked: "is there a way for you to check if my Debrid is working and protects my ip": the app plays only
   HTTPS links handed over by the add-ons (it has no torrent client); add one FirePlayerDiag line per film start
   naming only the HOST the stream comes from and whether the source was a ready (cached) link, so it can be confirmed
   on the TV without showing a link. Status: not started.
1d. **TorBox (the user, 03:00 their time): they switched from Real-Debrid to TorBox and asked: "check if streams
   from each add on work how they are supposed using my torbox debrid ... once".** (i) In 1c's work, make the sources
   code debrid-neutral where it assumes Real-Debrid: cached / instant markers in the add-ons' names (Torrentio's
   "[RD+]" becomes "[TB+]" with TorBox, MediaFusion's and Torz's own marks), failure names, link-host checks, the
   settings wording ("with your Real-Debrid account"), with tests. (ii) Then ONE check on the TV, only while it is
   idle (never while `PlayerActivity` or a channel is in front: at 03:00 a film was playing and blind key presses
   interrupted it for a few seconds and rewound it - ALWAYS read `dumpsys window | grep mCurrentFocus` and take a
   screenshot before sending any key): for each add-on that has a link set, open a film not in Continue Watching,
   see its sources listed under that add-on's name (the user: use The Odyssey (2026); its resume point does not matter. Play one add-on's source, then switch to the other add-on's source from the player's Sources, and so on for each add-on set; the user offered to switch for us if needed), play one for under 30 s (no resume point is left), read the log
   (first frame, the host line from 1c, no error), Back. Never capture the Add-ons settings window. Report per add-on.
   Status: not started.
2. **Movies & TV Shows search:** the row holding the best match comes first (a show: TV Shows first; a film: Movies).
   Branch `feat/home-header` (with step 3). Status: MERGED (2850d18), tests pass; NOT yet tried on the TV (search a
   show, e.g. "breaking bad": TV Shows first; a film, e.g. "dune": Movies first).
3. **Home banner:** slightly larger and wider, using the free space between it and the rail, and taller if it reads
   well; Recent channels may move right and need not match its height; the dark gradient of the Movies / TV Shows
   banner is allowed; buttons further to the bottom left, title further to the top left, with pleasing spacing. It
   idles (rotates) whenever focus is not on a Continue Watching title. New "Home Header" category in the all-settings
   page: turn off "match the focused Continue Watching title" so it always idles while in view.
   The user's message ended with "For the genre row," and nothing after it: unknown, nothing done for it; ask.
   Status: MERGED (2850d18) and on the TV as fp-next-57. Seen on the TV: the wider, taller banner (x 70-630, 228
   tall, the Movies look), Recent channels narrower at the right, it idles with focus in the rail, the Home Header
   category and its switch. Not yet tried: the switch turned Off; Up from a Continue card to the buttons.
3b. **Two additions from the user (01:50 their time), to build at the 07:00 reset with one Opus agent, branch
   `feat/header-timer` from the head:** "Add an idle title timer (idk if that's worded right) to header settings.
   And pressing d-pad up from header button focus/selected scrolls the header."
   Read as: (i) a row in Settings > Home Header choosing how long the banner stays on each title while it idles
   (for example 5 s, 8 s as today, 12 s, 20 s), applied at once, to every tab's banner (one rhythm across Home,
   Movies and TV Shows; say so in the row's note); (ii) with focus on Watch now or Details, D-pad Up moves the banner
   on to its next title at once (the usual fade), and the idle wait starts again; Left / Right / Down as today.
   If (ii) was instead a report that Up wrongly moves the banner today, the user will say: the reading was told to
   them. Status: MERGED (8626c8e), 1,137 tests, on the TV as fp-next-58.
4. **Multiview and the live picture (PiP), without glitches**, plus a settings category to turn automatic PiP on or
   off and whatever else deserves a preference. Designs: `player-fixes-wip/MULTIVIEW-NOTES.md` (2-4 side by side; OK
   on one makes it larger with the others underneath; holding a picture: remove, add (search), change (search), full
   screen; sound from the focused one; never more streams than the provider allows) and
   `Design-References/search-pip-2026-11-19/DECISIONS.md` (the picture on Home and Search, top right; strip with
   Volume, Full screen, Close only while focused; focus lands on Volume; OK toggles sound; Back returns to where focus
   was; Close brings Recent channels back with focus on the first; holding Back opens full screen; not on other tabs).
   Status: not started (after step 1's live branch is merged: same files).
5. **Release and reset.** After the last checks: the exact app as a signed release of FirePlayer (the old repo
   `ataahmed989/firestick-player` builds and publishes a signed release on a push to `main`; the app's package goes
   back to `app.fireplayer` with a version above the TVs'; the Glass Home update was agreed to ship at the swap). Then
   on the Stick 4K Max delete all FirePlayer data of both versions (`app.fireplayer`, `app.fireplayer.next`) so the
   user sets up from scratch.
   Status: not started.

## Log

- 05:55 UTC: plan written; step 1-3 agents launched.
- 06:14 UTC: at 72% of the window with four agents running, the two Fable audits were paused (their work so far
  saved on `audit/vod` and `audit/live`) so they get an uninterrupted run from the 07:00 reset: relaunch them then
  with the script `overnight-1-playback-audit-wf_19260f83-8c4.js` (they continue their branches). The Home banner /
  search-order and Glass Home agents (Opus) keep running (workflow run wf_6bdb89dc-ee8, branch `feat/home-header`
  and Glass Home's `claude/blissful-mayer-2nf8z9`).
- 06:45 UTC: steps 2-3 merged and installed (fp-next-57), 1,131 tests. Glass Home memory: the agent changed
  NOTHING: Glass Home already stops its timers and lets go of what it can when another app is in front, and under the
  hard rule (quick return) nothing sizeable more can be released; its branch is unchanged at 7244638. Next: at the
  07:00 UTC reset relaunch the two Fable audits (step 1), then step 4.
- 07:35 UTC: on the TV (fp-next-58): search order right ("breaking bad": TV Shows first; "dune": Movies first);
  Up on a banner button moves to the next title; the Home Header window shows the switch and "Time on each title";
  with the switch Off (the user turned it off themselves: leave it) the banner keeps idling on a Continue card.
  Steps 2, 3 and 3b are done. Waiting for the two Fable audits (run wf_8061bd2f-d97), then 1b, then step 4.
- 08:07 UTC: the 07:00 window is used up (the user: 96%). The two Fable audits were stopped with their work saved:
  branches `audit/vod` and `audit/live` (local), and the same work as patches in `player-fixes-wip/overnight-wip/`
  (if the branches are gone after a restart: `git checkout -b audit/vod a1d8242 && git am < that patch`, same for
  live). AT THE 12:00 UTC RESET: relaunch the audit script (`overnight-1-playback-audit-wf_19260f83-8c4.js`; the
  agents continue their branches) with the added instruction to FINISH: complete what is half done, no new
  exploration, final build + tests + report within about 20 minutes. Then merge, check on the idle TV, and go on with
  1b, 1c, 1d, step 4, step 5. Budget: one window did not cover the audits, so steps 4-5 will not all fit before the
  user is up; do not rush or skip checks to make it fit, and never release unverified work.
- 12:35 UTC: step 1 audits finished and MERGED (1,178 tests): films (buffer grows back once the page lets go of
  memory) and Live TV (guide opens quicker: lists kept in memory, first screenful first, logos on disk, per-stage
  timing lines; a pause the player makes itself is shown; new setting Live TV > Playback > "Play the last channel on
  opening", On by default). The film audit only finished its memory part (steps 2-6 of its brief not started).
  Built as fp-next-59; install when the TV is idle. Next: 1b+1c+1d.
- 13:45 UTC: sources work merged (a86c69d: IMAX/IMX/open matte first, StremThru Torz, debrid-neutral, host log
  line; 1,196 tests) and INSTALLED on the TV as fp-next-60 while it was asleep (not woken). The TorBox check on The
  Odyssey waits until the TV is on and idle (it plays sound: do not wake the TV to do it). Step 4 running (workflow
  run wf_e0829f9c-b3b: Multiview -> PiP -> review; branches feat/multiview, feat/pip, feat/mvpip-review).
- 15:12 UTC: 1d TorBox check DONE on the TV (fp-next-60), The Odyssey: Torrentio 10 sources, MediaFusion 10;
  Torrentio source played ("Source: Torrentio · cached · streams from torbox.app (TorBox)", first frame 3.3 s);
  MediaFusion best (4K DV REMUX) played ("... MediaFusion · cached · streams from torbox.app (TorBox)", first frame
  3.3 s). StremThru Torz not checked (no link entered yet). Seen in the log: one EPG source fails to parse
  ("EPG for kytv.xyz failed: XmlPullParserException") - tell the user.
