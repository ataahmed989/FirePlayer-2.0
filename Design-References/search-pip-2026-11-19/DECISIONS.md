# Search (both kinds) and the live picture — agreed design (the user, 2026-11-19)

Mock-ups shown to the user: `mockups.html` in this folder (published privately as an artifact). The frames here are
the chosen ones, each the real app on the TV with the new parts drawn in:

- `A1-search-first-view-two-pills-CHOSEN.jpg` — Search, first view: two pills above the bar, history under it.
- `A2-search-results-movies-tv.jpg` — Movies & TV Shows results.
- `B1-live-search-first-view.jpg` — Live TV search, first view with its own history.
- `B2-live-results-channel-focused.jpg`, `B3-live-results-programme-focused.jpg` — Live TV results.
- `C1-home-live-picture-strip-shown.jpg`, `C2-home-live-picture-button-focused.jpg` — the live picture on Home.
- `C3-home-after-close-recent-channels.jpg` — after Close.
- `D1-search-with-live-picture.jpg` — the live picture on Search.

The behaviour of search itself was agreed earlier the same day: `design-layout.md`, decisions log, "User: Search,
both kinds, agreed" and "the rail's Search tab is for films and shows only".

## The user's answers to the mock-ups, in their words

> 1. Option 2. Two pills
> 2. Option 1: Slim strip (different look for glass and non glass) that appears when the live picture is selected
>    (smooth animation). Full screen
> 3. Lands on Volume
> 4. Ok just turns the pictures sound off and on
> 5. Return goes where you were in the page
> 6. First recent channel
> 7. Doesn't appear on Movies, TV Shows, My List or Settings
> 8. Keep how you laid it out it looks good.
> 9. If PIP is playing (volume on or off) then holding return button launches full screen.
> 10. Keep the glass search bar the same as the mock ups (softer corners) but the history pods should be have rounded
>     ends

## What that decides

1. **Kind switch on Search:** two pills ("Movies & TV Shows", "Live TV") on a line above the search bar.
2. **The live picture on Home:** it takes the Recent channels window's place (top right, aligned with the banner),
   the picture as large as that box allows. The slim strip under it (channel, programme, and the Volume, Full screen
   and Close buttons) is **hidden until the picture is selected**: it eases in when focus moves to the picture and
   eases out when focus leaves. The strip has its own look in each mode: glass in the glass look, solid in Classic.
   *Read by me, to confirm:* "Full screen" at the end of answer 2 is taken as the picture using the whole box while
   the strip is hidden.
3. **Focus arriving at the picture lands on Volume.**
4. **Volume:** OK turns the picture's sound off and on (no level).
5. **Back from the picture's buttons** returns focus to where it was on the page.
6. **After Close:** the channel stops, Recent channels takes the picture's place, focus on the first recent channel.
7. **Only Home and Search** show the picture. On Movies, TV Shows, My List and Settings it does not appear.
   *Read by me, to confirm:* going to one of those tabs stops the channel (as Close does, without moving focus);
   going back to Live TV carries on with the last channel as today.
8. **Wording on Live TV search stays:** hint "Search channels and programmes", headings "Channels" and "Programmes".
9. **Holding Back while the picture plays** (sound on or off) opens that channel full screen on Live TV.
10. **Corners:** the search bar keeps the mock-ups' softer corners (the programme pods' radius) with their glass; the
    history pods have fully rounded ends (pills), in the same glass.

Also from the mock-ups, unchanged: the picture on Search sits in the top right corner beside the page's name, a size
smaller (about a quarter of the screen wide), with the bar and history shortened to clear it; results keep their
place. The Search button at the top of the "Search and App Settings" window, and of the Live TV settings window, opens
the matching kind of search (agreed earlier).

## Later the same evening (the user)

> Option 1: Slim strip (different look for glass and non glass) that appears when the live picture is
> selected/focused (smooth animation when it appears). No slip should appear if the Picture is not focused/selected.
> We might scrap the whole PIP if its not conventional, but at least we will keep the recent channels list. I honestly
> want to fix any Live TV channel playback issues and build the multiview options

- Answer 2 is confirmed as read: **no strip unless the picture is focused**; it eases in on focus.
- **The live picture is on hold** and may be dropped. It is not built until the user says so. The Recent channels
  list stays either way.
- **Order now:** finish tuning round 1, then Search (both kinds, with the Search button in the settings windows),
  then Live TV playback fixes and Multiview (design-brief.md: inside the Live player, up to three channels, sound
  from the focused one, within the provider's connection limit), its options to be agreed first.
