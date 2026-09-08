import subprocess, os
from PIL import Image, ImageDraw, ImageChops

ROOT = r"D:\CodingProjects\Android\Loyea"
RES = os.path.join(ROOT, "app", "src", "main", "res")
BG = (249, 246, 240, 255)  # #F9F6F0
TARGET_W = 250             # glyph width on 432 canvas: 57.9%, legacy has no mask so it can be larger
CANVAS = 432
TMP = os.path.join(ROOT, "tools", "_orig_tmp")
os.makedirs(TMP, exist_ok=True)

def git_export(rel, out):
    data = subprocess.run(["git", "-C", ROOT, "show", f"HEAD:{rel}"],
                          capture_output=True, check=True).stdout
    with open(out, "wb") as f:
        f.write(data)

# master glyph from ORIGINAL git HEAD foreground (full-res 263px, no resampling chain)
orig_fg = os.path.join(TMP, "orig_fg.png")
git_export("app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png", orig_fg)
src = Image.open(orig_fg).convert("RGBA")
bbox = src.getchannel("A").getbbox()
glyph = src.crop(bbox)
new_h = round(glyph.height * TARGET_W / glyph.width)
glyph = glyph.resize((TARGET_W, new_h), Image.LANCZOS)
print(f"glyph: {bbox[2]-bbox[0]}x{bbox[3]-bbox[1]} -> {glyph.size} ({TARGET_W/CANVAS:.1%} of canvas)")

def glyph_bbox(img):
    diff = ImageChops.difference(img, Image.new("RGBA", img.size, BG)).convert("L")
    dmask = diff.point(lambda v: 255 if v > 12 else 0)
    solid = img.getchannel("A").point(lambda a: 255 if a >= 250 else 0)
    return Image.composite(dmask, Image.new("L", img.size, 0), solid).getbbox()

SIZES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
round_master = None
for name in ["ic_launcher", "ic_launcher_round"]:
    for dpi, size in SIZES.items():
        rel = f"app/src/main/res/mipmap-{dpi}/{name}.png"
        orig = os.path.join(TMP, f"{name}_{dpi}.png")
        git_export(rel, orig)  # always work from pristine git version
        img = Image.open(orig).convert("RGBA")
        old_bbox = glyph_bbox(img)
        pad = max(2, round(8 * size / CANVAS))
        wipe = (max(0, old_bbox[0] - pad), max(0, old_bbox[1] - pad),
                min(img.width, old_bbox[2] + pad), min(img.height, old_bbox[3] + pad))
        alpha = img.getchannel("A")
        ok = all(alpha.getpixel((x, y)) == 255
                 for x in (wipe[0], wipe[2] - 1) for y in (wipe[1], wipe[3] - 1))
        assert ok, f"{name} {dpi}: wipe rect {wipe} cuts background"
        ImageDraw.Draw(img).rectangle(wipe, fill=BG)
        g = glyph.resize((round(TARGET_W * size / CANVAS), round(new_h * size / CANVAS)), Image.LANCZOS)
        img.paste(g, ((img.width - g.width) // 2, (img.height - g.height) // 2), g)
        img.save(os.path.join(RES, f"mipmap-{dpi}", f"{name}.png"))
        if name == "ic_launcher_round" and dpi == "xxxhdpi":
            round_master = img.copy()
print("legacy icons regenerated at 57.9% glyph")

# in-app avatar resource from the round master (circular cream base + glyph)
AV = {"mdpi": 40, "hdpi": 60, "xhdpi": 80, "xxhdpi": 120, "xxxhdpi": 160}
for dpi, size in AV.items():
    round_master.resize((size, size), Image.LANCZOS).save(
        os.path.join(RES, f"mipmap-{dpi}", "ic_avatar.png"))
print("avatar resource written:", AV)

# fresh preview sheet
from PIL import ImageFont
def circle_masked(fg_img, size):
    g = fg_img.resize((size, size), Image.LANCZOS)
    base = Image.new("RGBA", (size, size), BG)
    base.paste(g, (0, 0), g)
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).ellipse([0, 0, size - 1, size - 1], fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(base, (0, 0), m)
    return out

CELL, GAP, LABEL_H = 340, 60, 70
sheet = Image.new("RGBA", (CELL * 5 + GAP * 6, CELL + LABEL_H + GAP * 2), (40, 40, 42, 255))
draw = ImageDraw.Draw(sheet)
font = ImageFont.truetype(r"C:\Windows\Fonts\arialbd.ttf", 30)

def cell(im, col, label):
    im = im.resize((CELL, CELL), Image.LANCZOS)
    x = GAP + col * (CELL + GAP)
    sheet.paste(im, (x, GAP), im)
    tw = draw.textlength(label, font=font)
    draw.text((x + (CELL - tw) / 2, GAP + CELL + 14), label, fill=(225, 225, 225, 255), font=font)

new_fg = Image.open(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher_foreground.png")).convert("RGBA")
cell(circle_masked(Image.open(orig_fg).convert("RGBA"), 432), 0, "OLD")
cell(circle_masked(new_fg, 432), 1, "NEW ADAPTIVE")
cell(Image.open(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher.png")).convert("RGBA"), 2, "NEW SQUARE")
cell(Image.open(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher_round.png")).convert("RGBA"), 3, "NEW ROUND")
cell(Image.open(os.path.join(RES, "mipmap-xxxhdpi", "ic_avatar.png")).convert("RGBA"), 4, "IN-APP AVATAR")

out = os.path.join(ROOT, "tools", "icon_preview.png")
sheet.save(out)
print("preview:", out)
