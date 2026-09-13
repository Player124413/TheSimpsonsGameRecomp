#!/usr/bin/env python3
"""Renders the app launcher icon (an original pink-glazed donut on sky blue).

Outputs:
  android/app/src/main/res/mipmap-{mdpi..xxxhdpi}/ic_launcher.png  (legacy raster)
  android/app/src/main/res/drawable-nodpi/ic_launcher_foreground.png (adaptive)

Run from anywhere; paths resolve relative to the repository root.
"""

import math
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__))))

RES = os.path.join(ROOT, "android", "app", "src", "main", "res")

SKY = (48, 143, 221, 255)          # Springfield sky blue
DOUGH = (232, 169, 61, 255)        # golden dough
DOUGH_SHADE = (206, 143, 40, 255)  # dough shadow
GLAZE = (244, 143, 177, 255)       # pink frosting
GLAZE_DARK = (226, 110, 152, 255)  # frosting shadow edge
SPRINKLES = [
    (255, 238, 88, 255),   # yellow
    (129, 199, 132, 255),  # green
    (79, 195, 247, 255),   # light blue
    (255, 112, 67, 255),   # orange
    (240, 98, 146, 255),   # extra pink
    (255, 255, 255, 255),  # white
]


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))


def draw_donut(size, tilt_deg=-14, scale=1.0):
    """Draws the donut onto a transparent RGBA image of size x size."""
    ss = 4  # supersampling
    S = size * ss
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    cx, cy = S * 0.5, S * 0.5
    r_outer = S * 0.30 * scale
    r_hole = S * 0.115 * scale

    # Work in unrotated space, rotate at the end.
    donut = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    dd = ImageDraw.Draw(donut)

    # Dough ring (slight 3D via two-tone: shade on the bottom half).
    dd.ellipse([cx - r_outer, cy - r_outer, cx + r_outer, cy + r_outer], fill=DOUGH)
    dd.pieslice([cx - r_outer, cy - r_outer, cx + r_outer, cy + r_outer],
                15, 165, fill=DOUGH_SHADE)

    # Glaze: blob over the ring with a wavy bottom edge below the middle.
    # Built as a polygon with many points; covers the hole region too, then
    # the hole is punched again after.
    wave_r = r_outer * 1.02
    pts = []
    # Top arc from 200deg to -20deg (over the top).
    steps = 96
    for i in range(steps + 1):
        a = math.radians(180 + 200 * (i / steps))  # 180..380 (i.e. -180..20)
        pts.append((cx + wave_r * math.cos(a), cy + wave_r * math.sin(a) * 0.99))
    # Wavy bottom edge: two sine bumps going right-to-left.
    n = 36
    for i in range(n + 1):
        t = i / n
        # x from right side back to left side along y = cy + 0.18*r_outer
        x = cx + wave_r * (1 - 2 * t)
        y = cy + r_outer * (0.16 + 0.13 * math.sin(t * math.pi * 2.4 + 0.6))
        pts.append((x, y))
    dd.polygon(pts, fill=GLAZE)
    # Slight darker rim under the glaze edge.
    dd.line(pts[int(len(pts) * 0.52):], fill=GLAZE_DARK, width=S // 90)

    # Punch the hole (draw hole as background-colored → use mask approach).
    hole = Image.new("L", (S, S), 0)
    hd = ImageDraw.Draw(hole)
    hd.ellipse([cx - r_hole, cy - r_hole, cx + r_hole, cy + r_hole], fill=255)
    hole = hole.filter(ImageFilter.GaussianBlur(S // 260))
    donut.putalpha(Image.composite(
        Image.new("L", (S, S), 0), donut.getchannel("A"), hole))

    # Hole inner shadow ring (dough shading around the hole).
    ring = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    rd = ImageDraw.Draw(ring)
    rd.ellipse([cx - r_hole, cy - r_hole, cx + r_hole, cy + r_hole],
               outline=DOUGH_SHADE, width=S // 140)
    ring = ring.filter(ImageFilter.GaussianBlur(S // 300))
    # Keep the ring only outside the hole (multiply by inverse hole mask).
    inv_hole = hole.point(lambda v: 255 - v)
    ring.putalpha(Image.composite(ring.getchannel("A"),
                                  Image.new("L", (S, S), 0), inv_hole))
    donut = Image.alpha_composite(donut, ring)

    # Sprinkles on the glaze (upper 2/3 of the donut).
    sd = ImageDraw.Draw(donut)
    rng = _Rng(7)
    placed = []
    for _ in range(46):
        for _try in range(24):
            a = rng.next() * math.tau
            rr = r_hole * 1.25 + rng.next() * (r_outer * 0.82 - r_hole * 1.3)
            # Bias to the top: reject bottom-heavy angles.
            y = cy + rr * math.sin(a)
            if y > cy + r_outer * 0.16:
                continue
            x = cx + rr * math.cos(a)
            if y > cy + r_outer * 0.02 and rng.next() < 0.75:
                continue
            ok = True
            for (px, py) in placed:
                if (px - x) ** 2 + (py - y) ** 2 < (r_outer * 0.135) ** 2:
                    ok = False
                    break
            if ok:
                placed.append((x, y))
                color = SPRINKLES[int(rng.next() * len(SPRINKLES))]
                ang = rng.next() * math.tau
                ln = r_outer * 0.11
                wd = max(3, int(r_outer * 0.035))
                sd.line([(x - ln * math.cos(ang), y - ln * math.sin(ang)),
                         (x + ln * math.cos(ang), y + ln * math.sin(ang))],
                        fill=color, width=wd)
                break

    donut = donut.rotate(tilt_deg, resample=Image.BICUBIC)

    # Ground shadow under the donut.
    shadow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    shd = ImageDraw.Draw(shadow)
    shd.ellipse([cx - r_outer * 0.9, cy + r_outer * 0.92,
                 cx + r_outer * 0.9, cy + r_outer * 1.22], fill=(0, 0, 0, 70))
    shadow = shadow.filter(ImageFilter.GaussianBlur(S // 80))
    img = Image.alpha_composite(img, shadow)
    img = Image.alpha_composite(img, donut)

    return img.resize((size, size), Image.LANCZOS)


class _Rng:
    """Tiny deterministic LCG (stable icons across runs)."""

    def __init__(self, seed):
        self.state = seed & 0xFFFFFFFF

    def next(self):
        self.state = (1103515245 * self.state + 12345) & 0x7FFFFFFF
        return self.state / 0x7FFFFFFF


def render_legacy(size):
    """Full square icon (sky background + donut) for legacy launchers."""
    img = Image.new("RGBA", (size, size), SKY)
    # Legacy icons are full-bleed squares; the donut sits centered.
    donut = draw_donut(size, tilt_deg=-14, scale=0.94)
    img = Image.alpha_composite(img, donut)
    return img


def render_foreground(size):
    """Adaptive-icon foreground: transparent canvas, donut scaled into the
    66/108 safe zone (~61%)."""
    return draw_donut(size, tilt_deg=-14, scale=0.60)


def main():
    for dpi, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96,
                    "xxhdpi": 144, "xxxhdpi": 192}.items():
        out_dir = os.path.join(RES, f"mipmap-{dpi}")
        os.makedirs(out_dir, exist_ok=True)
        render_legacy(px).save(os.path.join(out_dir, "ic_launcher.png"))
        print(f"wrote mipmap-{dpi}/ic_launcher.png ({px}x{px})")

    nodpi = os.path.join(RES, "drawable-nodpi")
    os.makedirs(nodpi, exist_ok=True)
    render_foreground(432).save(os.path.join(nodpi, "ic_launcher_foreground.png"))
    print("wrote drawable-nodpi/ic_launcher_foreground.png (432x432)")


if __name__ == "__main__":
    sys.exit(main())
