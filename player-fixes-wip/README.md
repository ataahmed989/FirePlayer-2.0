# Player fixes, paused work in progress

The eight player fixes approved on 2026-11-18 (design-brief.md §6.6) were built by three engineers and paused before
merging, so that a later, in-depth playback pass can start from them. Each patch applies with `git am` on the base
commit named inside it:

- `fix_frames-1-2-7b.patch` — fixes 1, 2, 7: count every lost frame (FirePlayerDiag log, Picture & sound row),
  steadier FramePacer (EMA), lost frames in CopyHealth/CopyRank, honest Range row on SDR screens.
- `fix_memory-3-5-8b.patch` — fixes 3, 5, 8: film buffer sized to device RAM with step-down, no 4K skip preview on
  small devices, libass overlay only when an ASS track is used.
- `fix_glass-hdr-4-6.patch` — fixes 4, 6: lighter Glass look during 4K HDR on small devices, HDR10+ masked before
  decoding when the TV lacks HDR10+.

Not yet merged together, reviewed or tested on the device. Also see the film-mode memory plan in design-brief.md §6.6.
