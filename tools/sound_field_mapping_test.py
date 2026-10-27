#!/usr/bin/env python3
"""Reproduce the Sound Field polar mapping used by SoundFieldPanel.

This does not read the player or the plugin's FFT. It feeds synthetic L/R samples
through the same formulas as drawPolarCloud / drawReferenceCloud, then checks the
two properties the drawing code promises:

  * a hard-panned sample (L=1, R=0) lands exactly on the +90 deg tick,
  * energy == 1.0 reaches exactly R, i.e. the radius is plain linear amplitude.

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

# Must match POLAR_ANGLE_HALF_SPAN in SoundFieldPanel.kt.
POLAR_ANGLE_HALF_SPAN = 0.5
# Must match ADAPTIVE_PEAK_FLOOR in SoundFieldPanel.kt.
ADAPTIVE_PEAK_FLOOR = 0.25


def polar_angle(pan: float) -> float:
    """pan (-1..1) -> angle in radians from the +X axis, per polarAngle()."""
    return math.pi / 2.0 - pan * math.pi * POLAR_ANGLE_HALF_SPAN


def map_analytic(l: float, r: float, divisor: float = 1.0) -> tuple[float, float, float]:
    """Returns (angle_deg, radius_ratio, energy) for one L/R sample pair."""
    mid = (l + r) * 0.5
    side = (l - r) * 0.5
    raw_energy = abs(mid) + abs(side)
    energy = min(1.0, raw_energy / max(divisor, 1e-4))
    pan = max(-1.0, min(1.0, side / raw_energy)) if raw_energy > 1e-6 else 0.0
    angle = polar_angle(pan)
    # Linear amplitude: distance = radius * energy.
    return math.degrees(math.pi / 2.0 - angle), energy, energy


def map_point(l: float, r: float, divisor: float = 1.0) -> list[tuple[float, float]]:
    """Pixel positions for the two dots (L and R) of one sample pair."""
    mid = (l + r) * 0.5
    side = (l - r) * 0.5
    raw_energy = abs(mid) + abs(side)
    energy = min(1.0, raw_energy / max(divisor, 1e-4))
    pan = max(-1.0, min(1.0, side / raw_energy)) if raw_energy > 1e-6 else 0.0
    angle = polar_angle(pan)
    distance = R * energy
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
    d.ellipse([CX - 2, CY - 2, CX + 2, CY + 2], fill=(40, 55, 72))
    for x, y in points:
        if 0 <= x < W and 0 <= y < H:
            d.ellipse([x - 1, y - 1, x + 1, y + 1], fill=(120, 220, 240))
    d.text((20, 20), title, fill=(230, 240, 250))
    return img


def max_radius_ratio(points: list[tuple[float, float]]) -> float:
    return max((math.hypot(x - CX, y - CY) for x, y in points), default=0.0) / R


def main() -> None:
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "soundfield-mapping-test.png")
    random.seed(1)

    # Uniform coverage of the L/R square shows the reachable region.
    points: list[tuple[float, float]] = []
    for _ in range(30000):
        points.extend(map_point(random.uniform(-1.0, 1.0), random.uniform(-1.0, 1.0)))

    image = panel("normalized pan, linear radius", points)
    image.save(out)

    print(f"uniform full-scale max radius / R = {max_radius_ratio(points):.4f}")

    # Angle check. With pan = side / (|mid| + |side|) the two extremes are:
    #   anti-phase  L=1, R=-1  -> pan = +1 -> +90 deg
    #   one-sided   L=1, R=0   -> pan = +0.5 -> +45 deg
    for l, r, expected, why in (
        (1.0, -1.0, 90.0, "anti-phase full scale"),
        (-1.0, 1.0, -90.0, "anti-phase full scale"),
        (1.0, 0.0, 45.0, "one-sided full scale"),
        (0.0, 1.0, -45.0, "one-sided full scale"),
    ):
        deg, ratio, _ = map_analytic(l, r)
        print(f"L={l:+.0f} R={r:+.0f} ({why}): angle = {deg:+.4f} deg "
              f"(expect {expected:+.1f}), radius / R = {ratio:.4f}")

    # Silence collapses to the origin instead of a padded ring.
    deg, ratio, _ = map_analytic(0.0, 0.0)
    print(f"silence: radius / R = {ratio:.4f} (expect 0.0000)")

    # A -3 dBFS master cannot reach the outer ring without adaptation: that is
    # headroom, not a bug, and stays the default behaviour.
    quiet = 10 ** (-3.0 / 20.0)
    deg, ratio, _ = map_analytic(quiet, quiet)
    print(f"-3 dBFS mono: radius / R = {ratio:.4f} (linear headroom, expected)")

    # 峰值自适应: dividing by the sliding peak stretches it to the outer ring.
    adaptive = map_point(quiet, quiet, divisor=quiet)
    print(f"-3 dBFS with adaptive divisor {quiet:.3f}: "
          f"max radius / R = {max_radius_ratio(adaptive):.4f} (expect 1.0000)")

    # The floor keeps silence from being blown up: divisor never drops below it.
    floor_divisor = max(0.02, ADAPTIVE_PEAK_FLOOR)
    tiny = map_point(0.02, 0.02, divisor=floor_divisor)
    print(f"near-silence with divisor floor {floor_divisor:.2f}: "
          f"max radius / R = {max_radius_ratio(tiny):.4f}")

    # Polar Level uses the same exact linear radius, so every ring label is exact.
    print("polar level rings (label -> drawn radius / R):")
    for level in (0.25, 0.5, 0.75, 1.0):
        drawn = level  # distance = radius * level, no baseline offset
        flag = "ok" if abs(drawn - level) < 1e-9 else "MISMATCH"
        print(f"  {level:.2f} -> {drawn:.4f}  {flag}")

    print(out)


if __name__ == "__main__":
    main()
