"""Contact sheet of a folder of screenshots: python tools/contact_sheet.py <dir> <out.png> [cols] [cell_px]"""
import os
import sys

from PIL import Image, ImageDraw

src, out = sys.argv[1], sys.argv[2]
cols = int(sys.argv[3]) if len(sys.argv) > 3 else 4
cell = int(sys.argv[4]) if len(sys.argv) > 4 else 540
names = sorted(n for n in os.listdir(src) if n.lower().endswith(".png"))
rows = (len(names) + cols - 1) // cols
sheet = Image.new("RGB", (cols * cell, rows * (cell + 28)), (20, 20, 20))
draw = ImageDraw.Draw(sheet)
for i, n in enumerate(names):
    im = Image.open(os.path.join(src, n)).convert("RGB").resize((cell, cell))
    x, y = (i % cols) * cell, (i // cols) * (cell + 28)
    sheet.paste(im, (x, y + 28))
    draw.text((x + 6, y + 6), n[:-4], fill=(255, 255, 255))
sheet.save(out)
print(out, len(names))
