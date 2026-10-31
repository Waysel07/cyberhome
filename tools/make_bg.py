"""Draw the static background (assets/bg.jpg, 1920x1080) and the app icon/banner with Pillow.

python make_bg.py <project dir>

The background follows the Cyberpunk 2077 pause menu: a red-black screen with a warm haze in the upper
middle, scanlines, tick marks and tiny binary down both edges. Everything that moves or can be focused is
drawn by the app on top of it.
"""
import os
import random
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

root = sys.argv[1]
W, H = 1920, 1080
RED = (230, 60, 60)
BG = (12, 6, 9)
random.seed(77)

# Vertical fall-off: a dark red haze across the top third, near-black below it (as in the pause menu).
top, bottom = (40, 13, 17), (10, 10, 14)
img = Image.new("RGB", (W, H))
gd = ImageDraw.Draw(img)
for y in range(H):
    t = min(1.0, max(0.0, (y - 60) / 560.0)) ** 0.8
    gd.line([(0, y), (W, y)], fill=tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
glow = Image.new("RGB", (W, H), (0, 0, 0))
ImageDraw.Draw(glow).ellipse([380, -300, W - 380, 420], fill=(14, 4, 6))
glow = glow.filter(ImageFilter.GaussianBlur(170))
img = ImageChops.add(img, glow)
d = ImageDraw.Draw(img, "RGBA")

for y in range(0, H, 3):
    d.line([(0, y), (W, y)], fill=(0, 0, 0, 44))
for _ in range(26):
    y = random.randint(0, H)
    x0 = random.randint(0, W // 2)
    d.rectangle([x0, y, x0 + random.randint(200, 900), y + random.choice((1, 1, 2))], fill=RED + (6,))

# Edge ticks and binary, left and right.
try:
    tiny = ImageFont.truetype(os.path.join(root, "assets", "Rajdhani-Medium.ttf"), 11)
except OSError:
    tiny = ImageFont.load_default()
for y in range(90, H - 90, 12):
    long_tick = (y // 12) % 5 == 0
    d.line([(10, y), (10 + (10 if long_tick else 4), y)], fill=RED + (85,), width=1)
    d.line([(W - 10, y), (W - 10 - (10 if long_tick else 4), y)], fill=RED + (85,), width=1)
for i in range(34):
    y = 110 + i * 13
    d.text((W - 38, y), "".join(random.choice("01") for _ in range(random.choice((3, 4, 5)))),
           font=tiny, fill=RED + (70,))
for i in range(22):
    y = 330 + i * 13
    d.text((26, y), "".join(random.choice("0123456789ABCDEF") for _ in range(random.choice((3, 4)))),
           font=tiny, fill=RED + (55,))

shade = Image.new("L", (W, H), 0)
ImageDraw.Draw(shade).rectangle([0, 0, W, H], fill=0)
img.save(os.path.join(root, "assets", "bg.jpg"), quality=90)

# Launcher icon and banner: cyan hexagon on red-black.
CYAN = (52, 226, 238)


def hexagon(d, cx, cy, r, color, width):
    import math
    pts = [(cx + r * math.cos(math.radians(60 * i + 30)), cy + r * math.sin(math.radians(60 * i + 30))) for i in range(6)]
    d.line(pts + [pts[0]], fill=color, width=width)


def tile(w, h):
    t = Image.new("RGB", (w, h), (20, 7, 11))
    td = ImageDraw.Draw(t, "RGBA")
    for y in range(0, h, 3):
        td.line([(0, y), (w, y)], fill=(0, 0, 0, 60))
    td.rectangle([2, 2, w - 3, h - 3], outline=RED + (200,), width=max(2, h // 70))
    hexagon(td, w / 2, h / 2, h * 0.30, CYAN, max(3, h // 40))
    hexagon(td, w / 2, h / 2, h * 0.17, CYAN + (140,), max(2, h // 70))
    return t


os.makedirs(os.path.join(root, "res", "drawable"), exist_ok=True)
tile(320, 180).save(os.path.join(root, "res", "drawable", "banner.png"))
tile(192, 192).save(os.path.join(root, "res", "drawable", "ic_launcher.png"))
print("ok")
