#!/usr/bin/env python3
"""Reproduce the Sound Field polar mapping and compare the old/new angle map.

This does not read the player or the plugin's FFT. It feeds synthetic L/R
samples through the same formulas used by SoundFieldPanel.drawPolarCloud.
Requires Pillow (PIL).
"""
from __future__ import annotations

import math
import random
import sys
from pathlib import Path

from PIL import Image, ImageDraw

W, H = 800, 800
CX, CY = W * 0.5, H * 0.84
R = min(W * 0.40, H * 0.72)


def map_point(l: float, r: float, normalized: bool) -> list[tuple[float, float]]:
    mid = (l + r) * 0.5
    side = (l - r) * 0.5
    energy = min(1.0, abs(mid) + abs(side))
    if normalized:
        pan = (side / energy) if energy > 1e-6 else 0.0
        pan = max(-1.0, min(1.0, pan))
    else:
        pan = side
    angle = math.pi / 2.0 - pan * math.pi * 0.48
    distance = R * (0.025 + 0.975 * energy)
    out: list[tuple[float, float]] = []
    if 0.018 + abs(l) * 0.58 > 0.008:
        out.append((CX + math.cos(angle + 0.018) * distance,
                    CY - math.sin(angle + 0.018) * distance))
    if 0.018 + abs(r) * 0.58 > 0.008:
        out.append((CX + math.cos(angle - 0.018) * distance,
                    CY - math.sin(angle - 0.018) * distance))
    return out


def panel(title: str, points: list[tuple[float, float]]) -> Image.Image:
    img = Image.new("RGB", (W, H), (13, 18, 26))
    d = ImageDraw.Draw(img)
    for k in range(1, 5):
        rr = R * k / 4
        d.arc([CX - rr, CY - rr, CX + rr, CY + rr], 180, 360,
              fill=(40, 55, 72), width=1)
    for x, y in points:
        if 0 <= x < W and 0 <= y < H:
            d.ellipse([x - 1, y - 1, x + 1, y + 1], fill=(120, 220, 240))
    d.text((20, 20), title, fill=(230, 240, 250))
    return img


def main() -> None:
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "soundfield-mapping-fix-test.png")
    random.seed(1)
    old: list[tuple[float, float]] = []
    new: list[tuple[float, float]] = []
    # Uniform coverage of the L/R square makes the reachable-region shape
    # obvious instead of hiding it behind a real waveform's distribution.
    for _ in range(30000):
        l = random.uniform(-1.0, 1.0)
        r = random.uniform(-1.0, 1.0)
        old.extend(map_point(l, r, normalized=False))
        new.extend(map_point(l, r, normalized=True))
    image = Image.new("RGB", (W * 2, H), (0, 0, 0))
    image.paste(panel("raw side (old)", old), (0, 0))
    image.paste(panel("normalized pan (fix)", new), (W, 0))
    image.save(out)

    def max_radius(points):
        return max((math.hypot(x - CX, y - CY) for x, y in points), default=0.0) / R

    print(f"old max radius / R = {max_radius(old):.4f}")
    print(f"new max radius / R = {max_radius(new):.4f}")

    # A -3 dBFS signal can only reach 0.708 R with the linear amplitude map.
    # This is headroom, not clipping: the mapping itself still reaches R at 1.0.
    scaled = []
    for _ in range(20000):
        l = random.uniform(-0.708, 0.708)
        r = random.uniform(-0.708, 0.708)
        scaled.extend(map_point(l, r, normalized=True))
    print(f"0.708 full-scale max radius / R = {max_radius(scaled):.4f}")
    print(out)


if __name__ == "__main__":
    main()
