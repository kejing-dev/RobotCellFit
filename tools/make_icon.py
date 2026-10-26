"""Robot Cell Fit icon: the 512 export for the factory gallery and the legacy launcher PNG, drawn from the
same shapes as the adaptive icon (`res/drawable/ic_launcher_foreground.xml` on `@color/ic_launcher_background`).

    python tools/make_icon.py <export-512.png>

Warning orange tile, white flat UR5e side silhouette (base line, three-segment arm, small gripper) and a
quarter arc for the reach (design appIcon). Coordinates are the vector's 108-unit viewport.
"""
import os
import sys
from PIL import Image, ImageDraw

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S = 1080
K = S / 108.0
ORANGE = (217, 72, 15, 255)
WHITE = (255, 255, 255, 255)


def line(d, pts, w):
    pts = [(x * K, y * K) for x, y in pts]
    d.line(pts, fill=WHITE, width=int(w * K), joint="curve")
    r = w * K / 2
    for x, y in pts:
        d.ellipse((x - r, y - r, x + r, y + r), fill=WHITE)


def draw(size, rounded):
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    if rounded:
        d.rounded_rectangle((0, 0, S, S), radius=int(S * 0.22), fill=ORANGE)
    else:
        d.rectangle((0, 0, S, S), fill=ORANGE)
    line(d, [(28, 83), (58, 83)], 7)
    line(d, [(43, 83), (43, 61), (58, 44), (72, 50)], 7)
    line(d, [(72, 50), (78, 45)], 5)
    line(d, [(72, 50), (79, 54)], 5)
    cx, cy, r = 43 * K, 61 * K, 6 * K
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=WHITE)
    # quarter arc of radius 43 about (43, 61): from the top (43, 18) to the right (86, 61)
    bbox = ((43 - 43) * K, (61 - 43) * K, (43 + 43) * K, (61 + 43) * K)
    d.arc(bbox, start=270, end=360, fill=WHITE, width=int(4 * K))
    return im.resize((size, size), Image.LANCZOS)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "icon-512.png")
    draw(512, rounded=True).save(out)
    draw(192, rounded=False).save(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher.png"))
    print("wrote", out)


if __name__ == "__main__":
    main()
