# Look redesign — the user's brief (2026-11-19)

The current FirePlayer 2.0 layout is the base. The concept images in this folder are **guides, not perfect: the
actual app should be much cleaner.** Built on the Max-only test build; the user verifies on the TV. Classic mode's
solid surfaces stay intact.

Images here: `concept-1-home-app-settings-overlay.png` (Home with the App settings window over it, floating rail),
`concept-2-home-top.png` (Home: taller banner, Recent channels, larger cards), `concept-3-show-title-page.png` (a
show's title page — with one correction below), `concept-4-movies-rail-expanded.png` (the expanded rail as a rounded
floating window), `concept-5-live-tv-groups.png` (Live TV: edge-attached rail strip, larger logo, cleaner guide).
`current-NN-*.jpg` are the app today (glass look) on the Max for comparison.

## The user's words

### First: correct the glass surfaces

The tab rail, Recent channels, glass buttons, and App settings overlay should look like dark translucent glass showing
a softly blurred version of what is actually behind them.

- Inspect the existing glass implementation before changing it. Full-screen VOD and Live TV glass already look right;
  preserve that behavior.
- Check for opaque panel fills, stacked tint layers, or incorrect background sampling that make the browsing panels
  look solid gray.
- Blur the content behind a panel, then draw a restrained dark tint, subtle highlights, and the panel's sharp
  foreground text/icons.
- Keep underlying poster colors and shapes faintly visible through overlays. Underlying text should be sufficiently
  blurred that it doesn't compete with menu labels.
- Let most color variation come from the backdrop. Avoid a strong fixed blue wash.
- Don't lower the opacity of the entire panel view: its text, icons, and focused controls must remain crisp and opaque.
- Use a shared glass style for tint, blur strength, border, and depth. Allow small adjustments for readability where
  necessary.
- Avoid multiple overlapping glass fills in the settings window that accumulate into an opaque gray block.

**Left navigation rail**

- Turn the full-height edge-attached strip into a narrow floating window, slightly inset from the left, top, and
  bottom. It should animate into this when leaving the live tv page.
- Give it rounded corners, a fine translucent outline, and a restrained soft shadow.
- Preserve the current icon order, shapes, sizing, and spacing, with the gear at the bottom.
- Keep the compact rail icon-only. Preserve its existing expansion and navigation behavior.
- Clip the backdrop and tint cleanly to the rounded shape. Avoid square corners, doubled outlines, or a bright border.

**Borders and depth**

- Header and window outlines should be thin, muted, and consistent — approximately a one-pixel visual hairline at the
  target resolution.
- Use a subtle upper-edge highlight and soft outer shadow to create separation. Avoid thick bevels, metallic rims, and
  heavy glow.
- Unfocused title cards need only a very subtle edge.
- Preserve a clearly stronger white focus outline on the selected card. Keep it evenly rounded and ensure scaling
  doesn't clip it.
- Define shared corner-radius and border styles rather than adjusting each component independently.

### Then: enlarge and refine the header

- Match the attached concept's larger cinematic banner and stronger title hierarchy.
- Keep artwork sharp. Add a separate dark gradient over the image: strongest behind the text on the left, fading
  smoothly toward the artwork on the right, with additional shading near the bottom.
- Avoid a flat dark rectangle over the whole image or a visible seam where the shading ends.
- Keep the title prominent, metadata compact, and the description readable within a controlled line count.
- Use matching rounded glass Watch now and Details buttons, with carousel indicators near the bottom.
- Preserve idle rotation and existing remote behavior.
- Add buttons for glass buttons Watch now and Details.

**Title cards**

- Enlarge Continue Watching so the available width shows roughly three complete landscape cards and part of a fourth.
- Enlarge portrait cards proportionally while retaining their portrait aspect ratio.
- Keep the selected card enlarged and anchored toward the left, with smooth horizontal scrolling.
- Use a bottom gradient behind card titles, episode information, and progress bars; keep the artwork above it clear.
- Maintain consistent gaps, text padding, corner radii, and slim progress bars.
- Allow lower rows to continue naturally below the viewport instead of shrinking everything to fit.

**Recent channels**

- Match the rail and settings overlay's dark translucent glass.
- Preserve the existing history icon exactly.
- Use aligned channel-logo tiles, clear channel names, smaller program details, subtle separators, and a restrained
  scrollbar.
- Align the panel with the featured banner and match its height, as shown in the concept.
- Preserve its channel-history and PiP behavior.

**Performance and verification**

- Reuse the existing Android Views and glass components.
- Prefer a shared, downsampled backdrop with cached blur over separate expensive blur work for every surface. Or if
  you could use/implement what we already have for the playback glass look, try that.
- Refresh when the window appears and when the underlying content changes, using the existing conservative refresh
  policy. Avoid continuous full-screen capture and blur while idle.
- Exclude the glass panel itself from backdrop sampling to prevent recursive blur.
- Keep Classic mode's solid surfaces intact.
- Verify on the target Fire TV: settings over bright posters, carousel transitions, fast scrolling, focus scaling, and
  PiP playback.
- Upgrade hold button windows to be transparent also.

### The show title page

Use `concept-3-show-title-page.png` as the reference, with one correction: **remove the Episodes action button.**
Episodes should already be visible on this page; no separate Episodes screen is needed.

- Title and backdrop: retain the large show logo/title, compact metadata, and short synopsis. Keep the backdrop sharp
  on the right, fading into a dark gradient behind text and controls.
- Left actions: stack Play/Resume S# E# and Sources vertically. Place the circular + (My List) and … (More) buttons
  side by side beneath them. Close the gap left by removing Episodes.
- Inline episode section: keep the season dropdown and Episodes heading above the cards, to the right of the action
  column. The heading is not a button.
- Episode cards: retain the large horizontal pods shown in the reference — approximately two complete cards and part
  of the next in this space. Each pod contains a sharp thumbnail and a glass information base with episode number,
  title, runtime, and release date. Preserve progress and watched indicators where available.
- Glass: use the same dark translucent treatment as the updated Home page. Background colors should show softly
  through blurred glass, while text and icons remain sharp. Use fine outlines, rounded corners, subtle highlights, and
  restrained shadows; avoid opaque gray fills and heavy bevels.
- Focus and navigation: retain the white-and-blue focused action style. Episode cards should have a clear focus
  outline and modest enlargement without clipping. D-pad navigation must connect the action column, season selector,
  and horizontal episode row naturally.
- Navigation rail: retain the narrow, inset rounded glass window, existing icons, and bottom Settings gear.

### Expanded navigation rail

- The expanded rail draws over the page, preserving the page's position and card sizes underneath. Opening it must not
  push, resize, or reflow the content.
- Use the same dark translucent glass as the updated concepts: softly blurred underlying artwork, restrained tint,
  sharp foreground icons/text, and a fine edge highlight.
- Make the FirePlayer logo and name larger, with balanced spacing and sufficient padding.
- Preserve navigation order, white focused backgrounds with blue text/icons, and the Settings gear at the bottom.
- The earlier expanded-window concept uses rounded corners and small outer margins. The new Live TV concept instead
  shows a full-height, edge-attached glass rail, without a floating outer window. Preserve the compact rail width in
  this state.
- When browsing Live TV channels, the navigation rail should disappear completely as previously agreed.

### Home and Movies banners

- Make the featured banner taller, following the updated Home concept.
- Preserve sharp artwork, a prominent title, compact metadata, and readable description.
- Fade the artwork smoothly into dark shading behind the text and along the bottom.
- Use fine outlines and subtle shadows instead of thick gray borders or bevels.

### Live TV details and guide

- Enlarge the selected channel's logo in the details header while preserving its aspect ratio. Keep the channel name
  beside it and program details below.
- Refine channel-logo backgrounds into consistent rounded glass tiles. Keep enough contrast behind dark logos without
  making every tile a solid gray block.
- Give program cells a cleaner, restrained glass finish: subtle transparency, softly blurred background, thin borders,
  and gentle highlights.
- Avoid inflated "bubble" bevels, doubled outlines, and excessive shine.
- Keep logos, program text, focus indicators, and the current-time marker sharp. Preserve guide density and existing
  navigation.
- Reuse the shared cached glass backdrop rather than running an independent blur for every guide cell.

## How it is read where the brief is silent (told to the user; theirs to correct)

1. **Rail states.** Home, Movies, TV Shows, My List, Search, Settings: the compact rail is the narrow floating inset
   rounded glass window; expanded, it is a rounded floating glass window drawn over the page with the larger logo and
   name. Live TV: a full-height edge-attached glass strip at the compact width, which hides completely while browsing
   channels (as today). The rail animates between attached and floating when Live TV is entered or left.
2. **Banner buttons.** Up from the first row focuses Watch now; Left/Right move between Watch now and Details; Down
   returns to the row; the banner stops rotating while one of its buttons has focus. Watch now plays the shown title
   (as Play on its title page: resumes when there is a resume point); Details opens its title page. A page still opens
   with focus on its first row. On Home, Right from Details goes to Recent channels.
3. **Card sizes.** Continue Watching about 3.3 across; posters enlarged by the same factor (from 6.5 to about 4.8
   across), ranked numbers with them. This replaces the sizes chosen on 2026-11-18.
4. **Title page.** With a resume point the column is Resume · Play from beginning · Sources, then + and ⋯. A film's
   page has the same column, with More Like This to its right where a show has episodes; a show's More Like This stays
   below the episodes. The cast line moves to ⋯ › Details; the callout stays with the facts; the source line stays as
   one compact line (it carries the wait for the add-ons). The season dropdown opens a small glass list, Specials last.
5. **Search** (both kinds, agreed the same day) is built after this redesign, in this look.

## Rules for the work

Kotlin, Android Views, no Compose, no coroutines. Lean and smooth on the Fire TV Stick 4K Max (1.7 GB): no per-frame
allocation or layout in motion, translation/alpha/scale only, nothing captured or blurred while idle. Every look value
(tint, blur, borders, radii, shadows, sizes, type) lives in the one shared place (Tokens / Glass / GlassDrawable kinds /
VodSizes and the shared components) so later tuning is one edit. Keep every feature, setting and behaviour not named
above. The playback pass's rule stays: glass does no work out of sight.
