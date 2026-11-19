# Look redesign — tuning round 1 (the user, 2026-11-19, after seeing the first pass on the TV)

Read `BRIEF.md` in this folder first: its rules still hold (concepts are guides, the app must be much cleaner; Classic
mode intact; every look value in the one shared place; nothing captured, blurred or laid out per frame).

## The user's words

> I want the tabs/rail window animation more fluid and smoother especially when it collapses/extends and reduces from
> a top to bottom panel to a window with rounded edges.
>
> Make the buttons on title pages smaller in length at least and just make them look better overall. Make the Make
> title's description smaller source details smaller
>
> Home Screen banner, reorganize the text so that the title is a bit smaller to see the poster more and uses the
> Film/Movies Title treatment/logo and shift that and the details towards the top left, make the buttons more towards
> the bottom left. When the navigation focus is on one of the banner's buttons, then the titles should rotate on idle
> mode.
>
> Sorry if these are bad descriptions but try your best to make it visually more appealing and premium
>
> Change channel buttons (only if the change arrows button is set in settings) should always work, even if naviagting
> guide, or controls are on full screen or when the PIP is in use.
>
> Update the search bar glass and history pods can be the same glass material as the channel program pods
>
> Start designing the search bar for stuff and the Live TV PIP on home and search in the top right with the volume
> full screen, and x buttons which closes it and replaces with the channel history list.

Earlier the same day (agreed, kept for later, now done with the channel buttons): "when using the channel button, it
should only change channel down the list of the same group the channel currently playing".

## How it is read (told to the user; theirs to correct)

### 1. Rail motion (`shell/NavRail.kt`, `RailShape`, `HomeActivity.kt`)

Two motions must be fluid on the Fire TV Stick 4K Max:

- **Widening and collapsing** (focus into / out of the rail). Today the width is set in one step. It becomes one
  continuous motion: the window's drawn right edge grows and shrinks, the labels and the larger mark and name ease in
  and out with it (fade plus a few dp of slide), the glass and outline follow the moving edge. The page under it never
  moves or lays out. Nothing is laid out per frame: the view takes its widened bounds once and the drawing is clipped
  or translated inside it.
- **Live TV ↔ the other tabs** (edge strip, full height, square ↔ floating inset window with rounded corners). A real
  morph: the bounds move, the corner radius grows from 0, the outline and soft shadow ease in; no stretched picture, no
  visible crossfade between two shapes, no pop at the end.

Both: an eased curve (fast out, soft landing), about 240–320 ms, interruptible (reversing starts from where it is,
never jumps), in step with the page change, and reaching the final state exactly. Per frame: only this view redrawn;
no allocation; glass captured once before or after the motion, never during it. The rail still hides completely
while browsing Live TV channels.

### 2. Title page (`vod/ui/TitlePage.kt`, `TitleButtons.kt`, `TitleHeader.kt`, the title section of `VodSizes.kt`)

- The action column's buttons (Resume / Play, Play from beginning, Sources) are **shorter in length**: as wide as the
  longest label needs with balanced padding, clearly narrower than today (about 216 dp now), never clipping a label.
- They **look better**: a premium pill with balanced height, icon and label; the primary action reads as primary even
  unfocused; quieter secondary actions; the count in "Sources · 18" quieter than the word; the round + and ⋯ aligned
  with the column and the same family. The white-and-blue focused style stays.
- The **description is smaller** and the **source details line is smaller** (and quieter), with spacing rebalanced so
  the header reads logo → facts → description → source line without crowding.
- With the narrower column, what is to its right (season dropdown, Episodes, pods; More Like This on a film) starts
  further left and shows more.

### 3. Banner (`vod/ui/VodHeader.kt`, `HeaderRotation.kt`, `HeaderArt.kt`, `VodPage.kt`, the header section of `VodSizes.kt`)

Applied to the one shared banner, so Home, Movies, TV Shows (and My List's header, which has no buttons) stay alike:

- The title is shown as the **title's logo** (the same treatment as the title page: `TitleLogo`, drawn by its visible
  part), **smaller** than today's text title so more of the picture shows. A title without a logo (or whose logo
  fails) shows its name in text, at a smaller size than today. No flash of text before a logo arrives.
- The logo, the facts line and the description move to the **top left** of the card; the description stays compact
  (two lines) and narrow enough to keep the picture's subject clear.
- The **Watch now** and **Details** buttons sit at the **bottom left**; the dots stay centred near the bottom.
- The dark shading is reshaped for this: enough behind the logo and words at the top left and behind the buttons, the
  rest of the picture clearer than today, no flat rectangle and no seam.
- **Rotation with focus on the banner's buttons:** it no longer stops. After the usual idle time with no key pressed
  it moves to the next title and keeps going; any key press starts the idle time again; the buttons always act on the
  title shown at that moment. (This replaces "the banner stops rotating while one of its buttons has focus".)

### 4. Channel buttons (`live/ui/LivePage.kt`, `GuideView.kt`, `HomeActivity.kt`, `RemoteRows.kt`, `LivePrefs`)

The setting is Remote › "Channel ▲▼" (`LivePrefs.channelButtonsOnly`). When it is set to **Channel buttons only**,
the remote's Channel ▲ / ▼ always change the playing channel:

- while moving around the guide (today they page the guide there),
- in full screen while the controls are showing (the controls stay up and show the new channel),
- while live TV is playing in a small picture outside the Live TV page (the picture-in-picture on Home, and wherever
  else a live picture is playing),
- and with a window open over it, except where a key press must not be stolen (typing, a PIN prompt).

They move **within the playing channel's group** (the group it was started from), in that group's order. With
"D-pad + channel buttons" everything stays as it is today, except that the group rule applies there too.

### 5. Search glass (`vod/ui/SearchBar.kt`, `SearchHistoryList.kt`, the glass kinds)

The search bar and the history pods use the **same glass material as the guide's programme cells** (one shared kind,
not copied values), with the focused state the app uses elsewhere.

### 6. Designs to agree before building (mock-ups, not code)

- The **search bar and page** in both kinds (Movies & TV Shows / Live TV), in the new look.
- The **live picture-in-picture** on Home and Search: top right, with **volume**, **full screen** and **✕** buttons;
  ✕ closes it and the Recent channels list takes its place.
