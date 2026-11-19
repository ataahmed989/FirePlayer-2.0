#!/usr/bin/env python3
"""Builds the glass look's wallpaper (res/drawable-nodpi/wallpaper_glass.png) from the user's 4K picture.

The picture is very dark and very smooth: each colour channel uses only about 20-40 of the 256 levels. Stored as
it is, a faint glow becomes a flat patch one level lighter than what is around it and a smooth fade becomes steps;
a TV that lifts dark tones shows them as light dashes and bands. So the levels are first turned back into a smooth
picture: the smoothest one that still rounds to the user's picture (every pixel stays within half a level of it),
and only then stored in 8 bits with a fine blue-noise dither, which reads as an even surface instead of steps.

Run from the repo root:  python3 -I Wallpapers/build_wallpaper_glass.py   (needs numpy, scipy, Pillow)
"""
import sys

import numpy as np
from PIL import Image
from scipy import ndimage as ndi

SRC = "Wallpapers/Foggy-Blue-Beach-Light-Spiral-Tint-4K.png"
OUT = "firestick-player-main/app/src/main/res/drawable-nodpi/wallpaper_glass.png"
# Smoothing passes (sigma in output pixels), widest first; after each the picture is pulled back to within half a
# level of the user's.
SIGMAS = [48, 48, 32, 32, 24, 24, 16, 16, 12, 12, 8, 8, 6, 6, 4, 4, 3, 2]
TILE = 256


def smooth_within_half_a_level(q: np.ndarray) -> np.ndarray:
    lo, hi = q - 0.5, q + 0.5
    f = q.copy()
    for s in SIGMAS:
        f = np.clip(ndi.gaussian_filter(f, s, mode="nearest"), lo, hi)
    return f


def blue_noise(seed: int) -> np.ndarray:
    """A tile of thresholds in [0, 1), evenly spread, with the low frequencies taken out so it shows no clumps."""
    rng = np.random.default_rng(seed)
    n = rng.random((TILE, TILE))
    for _ in range(4):
        n = n - ndi.gaussian_filter(n, 1.6, mode="wrap")
        ranks = np.argsort(np.argsort(n, axis=None)).reshape(n.shape)
        n = ranks / float(n.size)
    return n.astype(np.float32)


def main() -> int:
    src = np.asarray(Image.open(SRC).convert("RGB")).astype(np.float32)
    h, w, _ = src.shape
    # Exact 2:1: each output pixel is the mean of its four (3840x2160 -> 1920x1080).
    half = src.reshape(h // 2, 2, w // 2, 2, 3).mean(axis=(1, 3))
    out = np.empty(half.shape, np.uint8)
    for c in range(3):
        f = smooth_within_half_a_level(half[..., c])
        t = np.tile(blue_noise(1000 + c), (half.shape[0] // TILE + 1, half.shape[1] // TILE + 1))
        t = np.roll(t, (37 * c, 91 * c), axis=(0, 1))[: half.shape[0], : half.shape[1]]
        out[..., c] = np.clip(np.floor(f + t), 0, 255).astype(np.uint8)
        print(f"channel {c}: furthest from the user's picture {np.abs(f - half[..., c]).max():.3f} levels")
    Image.fromarray(out, "RGB").save(OUT, optimize=True)
    print("wrote", OUT, out.shape)
    return 0


if __name__ == "__main__":
    sys.exit(main())
