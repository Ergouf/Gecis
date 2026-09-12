from PIL import Image, ImageDraw
import os

out = r"D:\workspace\Gecis-worktrees\windows-tech\windows\src-tauri\icons"
os.makedirs(out, exist_ok=True)

def make(size, path):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    m = max(1, size // 16)
    d.rounded_rectangle([m, m, size - m - 1, size - m - 1], radius=size // 5, fill=(23, 23, 23, 255))
    cx, cy, r = size // 2, size // 2, size // 4
    d.arc([cx - r, cy - r, cx + r, cy + r], start=-40, end=300, fill=(247, 247, 245, 255), width=max(2, size // 12))
    d.rectangle([cx, cy - r // 3, cx + r, cy + r // 8], fill=(23, 23, 23, 255))
    d.rectangle([cx + r // 2, cy - r // 8, cx + r, cy + r // 8], fill=(247, 247, 245, 255))
    img.save(path)

make(32, os.path.join(out, "32x32.png"))
make(128, os.path.join(out, "128x128.png"))
make(256, os.path.join(out, "icon.png"))
img = Image.open(os.path.join(out, "128x128.png"))
img.save(os.path.join(out, "icon.ico"), sizes=[(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
print("icons:", sorted(os.listdir(out)))
