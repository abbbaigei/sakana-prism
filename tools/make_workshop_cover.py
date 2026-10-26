"""Renders the Steam Workshop cover for Sakana Prism.

Usage: python make_workshop_cover.py <out.png>
"""

import random
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 1920, 1080
AURORA = [(0x4D, 0xD8, 0xC4), (0x5B, 0x8C, 0xFF), (0xA7, 0x7B, 0xFF), (0xFF, 0x7A, 0xB4), (0xFF, 0xC4, 0x6B)]
INK = (0xE8, 0xF1, 0xFF)
MUTED = (0x8E, 0xA3, 0xC0)


def lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def ramp(t):
    """Sample the aurora gradient at 0..1."""
    t = max(0.0, min(1.0, t)) * (len(AURORA) - 1)
    index = min(int(t), len(AURORA) - 2)
    return lerp(AURORA[index], AURORA[index + 1], t - index)


def font(name, size):
    for candidate in (name, "C:/Windows/Fonts/" + name):
        path = Path(candidate)
        if path.is_file():
            return ImageFont.truetype(str(path), size)
    raise SystemExit(f"font not found: {name}")


def background():
    image = Image.new("RGB", (W, H), (5, 7, 11))
    draw = ImageDraw.Draw(image)
    for y in range(H):
        t = y / (H - 1)
        draw.line([(0, y), (W, y)], fill=lerp((4, 6, 10), (11, 18, 32), t ** 0.85))
    grid = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    g = ImageDraw.Draw(grid)
    for x in range(0, W, 120):
        g.line([(x, 0), (x, H)], fill=(120, 160, 220, 10))
    for y in range(H - 100, 0, -90):
        g.line([(0, y), (W, y)], fill=(120, 160, 220, 10))
    return Image.alpha_composite(image.convert("RGBA"), grid)


def prism(layer):
    """A glass prism on the right: one white beam in, a fan of colour out."""
    draw = ImageDraw.Draw(layer)
    cx, cy, size = 1470, 300, 180
    apex = (cx, cy - size)
    left = (cx - int(size * 0.92), cy + int(size * 0.62))
    right = (cx + int(size * 0.92), cy + int(size * 0.62))

    def along(a, b, t):
        return (round(a[0] + (b[0] - a[0]) * t), round(a[1] + (b[1] - a[1]) * t))

    entry = along(apex, left, 0.58)
    exit_ = along(apex, right, 0.64)
    beam_from = (1085, 150)
    steps = 40
    for step in range(steps):
        t0, t1 = step / steps, (step + 1) / steps
        a = (beam_from[0] + (entry[0] - beam_from[0]) * t0, beam_from[1] + (entry[1] - beam_from[1]) * t0)
        b = (beam_from[0] + (entry[0] - beam_from[0]) * t1, beam_from[1] + (entry[1] - beam_from[1]) * t1)
        alpha = round(6 + 150 * t0 ** 1.5)
        draw.line([a, b], fill=(226, 238, 255, alpha), width=3)
    draw.line([entry, exit_], fill=(232, 242, 255, 175), width=2)
    for point in (entry, exit_):
        draw.ellipse(
            [point[0] - 7, point[1] - 7, point[0] + 7, point[1] + 7],
            fill=(236, 245, 255, 90),
        )
    for index in range(len(AURORA)):
        end = (W - 30, entry[1] - 60 + index * 72)
        draw.line([exit_, end], fill=AURORA[index] + (140,), width=3)
    draw.polygon([apex, left, right], outline=(210, 230, 255, 120))
    draw.line([apex, left], fill=(238, 247, 255, 215), width=4)
    draw.line([left, right], fill=(238, 247, 255, 190), width=4)
    draw.line([right, apex], fill=(238, 247, 255, 215), width=4)
    return layer


def spectrum():
    """Log-spaced bars with roughly the shape the plugin draws."""
    rng = random.Random(20771026)
    bars = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    draw = ImageDraw.Draw(bars)
    left, right, base, max_height = 120, W - 120, H - 84, 350
    count = 192
    gap = 2
    width = (right - left - count * gap) / count

    def bump(t, centre, spread, height):
        return height * pow(2.718281828, -((t - centre) / spread) ** 2)

    for i in range(count):
        t = i / (count - 1)
        envelope = 0.42 - 0.24 * t
        envelope += bump(t, 0.05, 0.055, 0.42)
        envelope += bump(t, 0.27, 0.065, 0.20)
        envelope += bump(t, 0.55, 0.085, 0.13)
        envelope *= 0.94 + 0.12 * rng.random()
        height = max(8.0, min(1.0, envelope) * max_height)
        x = left + i * (width + gap)
        colour = ramp(0.15 + 0.78 * t)
        draw.rectangle([x, base - height, x + width, base], fill=colour + (255,))
    glow = bars.filter(ImageFilter.GaussianBlur(18))
    canvas = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    canvas = Image.alpha_composite(canvas, glow)
    canvas = Image.alpha_composite(canvas, bars)
    draw = ImageDraw.Draw(canvas)
    draw.line([(left - 24, base + 2), (right + 24, base + 2)], fill=(150, 180, 220, 70), width=2)
    return canvas


def title(layer):
    draw = ImageDraw.Draw(layer)
    big = font("seguisb.ttf", 118)
    small = font("msyh.ttc", 46)
    note = font("msyh.ttc", 30)
    latin = font("seguisb.ttf", 26)

    x, y = 120, 210
    for char in "SAKANA PRISM":
        draw.text((x, y), char, font=big, fill=INK)
        x += draw.textlength(char, font=big) + 6
    draw.text((124, y + 150), "鱼  棱  镜", font=small, fill=(0x7F, 0xC8, 0xD8))
    draw.text((126, y + 236), "实时频谱  ·  立体声声场  ·  封面取色", font=note, fill=MUTED)
    draw.text((126, H - 62), "Salt Player for Windows", font=latin, fill=(0x6D, 0x80, 0x9C))
    return layer


def main():
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "cover.png")
    image = background()
    image = prism(image)
    image = Image.alpha_composite(image, spectrum())
    image = title(image)
    out.parent.mkdir(parents=True, exist_ok=True)
    image.convert("RGB").save(out, optimize=True)
    size = out.stat().st_size
    print(f"{out}  {image.size[0]}x{image.size[1]}  {size / 1024:.1f} KB")
    if size >= 1_000_000:
        raise SystemExit("cover exceeds the 1 MB workshop limit")


if __name__ == "__main__":
    main()
