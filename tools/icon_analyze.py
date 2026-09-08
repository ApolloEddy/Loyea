from PIL import Image
import os

RES = r"D:\CodingProjects\Android\Loyea\app\src\main\res"

for dpi in ["mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"]:
    p = os.path.join(RES, f"mipmap-{dpi}", "ic_launcher_foreground.png")
    img = Image.open(p).convert("RGBA")
    w, h = img.size
    bbox = img.getchannel("A").getbbox()
    bw, bh = bbox[2] - bbox[0], bbox[3] - bbox[1]
    canvas_ratio = bw / w
    # adaptive icon: mask shows central 66.67% (72/108dp)
    safe = w * 2 / 3
    mask_ratio = bw / safe
    print(f"{dpi}: canvas={w}x{h} bbox={bbox} content={bw}x{bh}")
    print(f"    content/canvas={canvas_ratio:.1%}  content/visible-mask={mask_ratio:.1%}")

# legacy icons
for name in ["ic_launcher", "ic_launcher_round"]:
    p = os.path.join(RES, "mipmap-xxxhdpi", f"{name}.png")
    img = Image.open(p).convert("RGBA")
    w, h = img.size
    bbox = img.getchannel("A").getbbox()
    print(f"legacy {name}: size={w}x{h} opaque-bbox={bbox}")
