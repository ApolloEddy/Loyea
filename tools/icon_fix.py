from PIL import Image, ImageDraw, ImageChops, ImageFont
import os

RES = r"D:\CodingProjects\Android\Loyea\app\src\main\res"
BG = (249, 246, 240, 255)  # #F9F6F0 ic_launcher_background
TARGET_W = 200             # glyph width on 432 canvas: 46.3% canvas, ~69% of visible mask
CANVAS = 432

# ---- 1. master glyph from highest-res foreground ----
src_path = os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher_foreground.png")
src = Image.open(src_path).convert("RGBA")
old_src = src.copy()
bbox = src.getchannel("A").getbbox()
glyph = src.crop(bbox)
new_h = round(glyph.height * TARGET_W / glyph.width)
glyph = glyph.resize((TARGET_W, new_h), Image.LANCZOS)
print(f"glyph: {bbox[2]-bbox[0]}x{bbox[3]-bbox[1]} -> {glyph.size}, centered on {CANVAS}px canvas")

# ---- 2. regenerate adaptive foregrounds (in memory first) ----
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
new_foregrounds = {}
for dpi, size in DENSITIES.items():
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    g = glyph.resize((round(TARGET_W * size / CANVAS), round(new_h * size / CANVAS)), Image.LANCZOS)
    canvas.paste(g, ((size - g.width) // 2, (size - g.height) // 2), g)
    new_foregrounds[dpi] = canvas

# ---- 3. legacy icons: keep original background shape, wipe old glyph, paste new ----
def glyph_bbox(img):
    """bbox of pixels that differ from BG among fully-opaque pixels only
    (transparent area outside the rounded/circle shape must not count)."""
    diff = ImageChops.difference(img, Image.new("RGBA", img.size, BG)).convert("L")
    dmask = diff.point(lambda v: 255 if v > 12 else 0)
    solid = img.getchannel("A").point(lambda a: 255 if a >= 250 else 0)
    return Image.composite(dmask, Image.new("L", img.size, 0), solid).getbbox()

LEGACY_SIZES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
new_legacy = {}
for name in ["ic_launcher", "ic_launcher_round"]:
    p = os.path.join(RES, "mipmap-xxxhdpi", f"{name}.png")
    img = Image.open(p).convert("RGBA")
    print(f"{name} center pixel: {img.getpixel((216, 40))}")
    old_bbox = glyph_bbox(img)
    pad = 8
    wipe = (max(0, old_bbox[0] - pad), max(0, old_bbox[1] - pad),
            min(img.width, old_bbox[2] + pad), min(img.height, old_bbox[3] + pad))
    # safety: wipe rect corners must stay inside the original opaque shape
    alpha = img.getchannel("A")
    corners_ok = all(alpha.getpixel((x, y)) == 255
                     for x in (wipe[0], wipe[2] - 1) for y in (wipe[1], wipe[3] - 1))
    print(f"{name}: old_glyph_bbox={old_bbox} wipe={wipe} corners_inside={corners_ok}")
    assert corners_ok, f"wipe rect would cut into the {name} background shape"
    d = ImageDraw.Draw(img)
    d.rectangle(wipe, fill=BG)
    img.paste(glyph, ((img.width - glyph.width) // 2, (img.height - glyph.height) // 2), glyph)
    new_legacy[(name, "xxxhdpi")] = img
    for dpi, size in LEGACY_SIZES.items():
        if dpi == "xxxhdpi":
            continue
        src_legacy = Image.open(os.path.join(RES, f"mipmap-{dpi}", f"{name}.png")).convert("RGBA")
        ob = glyph_bbox(src_legacy)
        pad2 = max(2, round(8 * size / CANVAS))
        wipe2 = (max(0, ob[0] - pad2), max(0, ob[1] - pad2),
                 min(src_legacy.width, ob[2] + pad2), min(src_legacy.height, ob[3] + pad2))
        la = src_legacy.getchannel("A")
        ok2 = all(la.getpixel((x, y)) == 255
                  for x in (wipe2[0], wipe2[2] - 1) for y in (wipe2[1], wipe2[3] - 1))
        assert ok2, f"{name} {dpi}: wipe rect {wipe2} cuts background"
        ImageDraw.Draw(src_legacy).rectangle(wipe2, fill=BG)
        g = glyph.resize((round(TARGET_W * size / CANVAS), round(new_h * size / CANVAS)), Image.LANCZOS)
        src_legacy.paste(g, ((src_legacy.width - g.width) // 2, (src_legacy.height - g.height) // 2), g)
        new_legacy[(name, dpi)] = src_legacy

# ---- 4. save everything ----
for dpi in DENSITIES:
    new_foregrounds[dpi].save(os.path.join(RES, f"mipmap-{dpi}", "ic_launcher_foreground.png"))
for (name, dpi), img in new_legacy.items():
    img.save(os.path.join(RES, f"mipmap-{dpi}", f"{name}.png"))
print("saved all densities")

# ---- 5. preview sheet: OLD vs NEW, circular-mask sim + legacy square ----
def circle_masked(fg, size):
    base = Image.new("RGBA", (size, size), BG)
    base.paste(fg.resize((size, size), Image.LANCZOS), (0, 0), fg.resize((size, size), Image.LANCZOS))
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).ellipse([0, 0, size - 1, size - 1], fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(base, (0, 0), m)
    return out

CELL, GAP, LABEL_H = 380, 46, 64
sheet_w = CELL * 4 + GAP * 5
sheet = Image.new("RGBA", (sheet_w, CELL + LABEL_H * 2 + GAP * 3), (43, 43, 43, 255))
draw = ImageDraw.Draw(sheet)
try:
    font = ImageFont.truetype(r"C:\Windows\Fonts\arialbd.ttf", 34)
except OSError:
    font = ImageFont.load_default()

old_fg = Image.open(src_path)  # already overwritten? no - we saved after reading. use old_src
old_fg = old_src

def cell_paste(im, col, label, row=0):
    x = GAP + col * (CELL + GAP)
    y = GAP + row * (CELL + LABEL_H + GAP)
    sheet.paste(im, (x, y), im)
    tw = draw.textlength(label, font=font)
    draw.text((x + (CELL - tw) / 2, y + CELL + 10), label, fill=(220, 220, 220, 255), font=font)

cell_paste(circle_masked(old_fg, CELL), 0, "OLD  (glyph 91% of mask)")
cell_paste(circle_masked(new_foregrounds["xxxhdpi"], CELL), 1, "NEW  (glyph ~69% of mask)")
cell_paste(new_legacy[("ic_launcher", "xxxhdpi")], 2, "NEW legacy square")
cell_paste(new_legacy[("ic_launcher_round", "xxxhdpi")], 3, "NEW legacy round")

preview_path = r"D:\CodingProjects\Android\Loyea\tools\icon_preview.png"
sheet.save(preview_path)
print(f"preview: {preview_path}")
