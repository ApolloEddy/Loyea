import subprocess, os
from PIL import Image, ImageDraw, ImageFont

ROOT = r"D:\CodingProjects\Android\Loyea"
RES = os.path.join(ROOT, "app", "src", "main", "res")
TMP = os.path.join(ROOT, "tools", "_old_icon_tmp")
os.makedirs(TMP, exist_ok=True)
BG = (249, 246, 240, 255)

def git_export(rel, out):
    data = subprocess.run(["git", "-C", ROOT, "show", f"HEAD:{rel}"],
                          capture_output=True, check=True).stdout
    with open(out, "wb") as f:
        f.write(data)

old_fg = os.path.join(TMP, "old_fg.png")
git_export("app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png", old_fg)

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
W = CELL * 4 + GAP * 5
H = CELL + LABEL_H + GAP * 2
sheet = Image.new("RGBA", (W, H), (40, 40, 42, 255))
draw = ImageDraw.Draw(sheet)
font = ImageFont.truetype(r"C:\Windows\Fonts\arialbd.ttf", 30)

def cell(im, col, label):
    im = im.resize((CELL, CELL), Image.LANCZOS)
    x = GAP + col * (CELL + GAP)
    y = GAP
    sheet.paste(im, (x, y), im)
    tw = draw.textlength(label, font=font)
    draw.text((x + (CELL - tw) / 2, y + CELL + 14), label,
              fill=(225, 225, 225, 255), font=font)

new_fg = Image.open(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher_foreground.png")).convert("RGBA")
new_sq = Image.open(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher.png")).convert("RGBA")
new_rd = Image.open(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher_round.png")).convert("RGBA")

cell(circle_masked(Image.open(old_fg).convert("RGBA"), 432), 0, "OLD")
cell(circle_masked(new_fg, 432), 1, "NEW")
cell(new_sq, 2, "NEW SQUARE")
cell(new_rd, 3, "NEW ROUND")

out = os.path.join(ROOT, "tools", "icon_preview.png")
sheet.save(out)
print("preview:", out)
