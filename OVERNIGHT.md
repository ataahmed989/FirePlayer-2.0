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
2. **Movies & TV Shows search:** the row holding the best match comes first (a show: TV Shows first; a film: Movies).
   Branch `feat/home-header` (with step 3). Status: started.
3. **Home banner:** slightly larger and wider, using the free space between it and the rail, and taller if it reads
   well; Recent channels may move right and need not match its height; the dark gradient of the Movies / TV Shows
   banner is allowed; buttons further to the bottom left, title further to the top left, with pleasing spacing. It
   idles (rotates) whenever focus is not on a Continue Watching title. New "Home Header" category in the all-settings
   page: turn off "match the focused Continue Watching title" so it always idles while in view.
   The user's message ended with "For the genre row," and nothing after it: unknown, nothing done for it; ask.
   Status: started.
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
