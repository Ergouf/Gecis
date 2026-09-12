from PIL import Image
import os

src = r"D:\workspace\Gecis-worktrees\windows-tech\app\src\main\res\mipmap-xxxhdpi\ic_launcher.png"
round_src = r"D:\workspace\Gecis-worktrees\windows-tech\app\src\main\res\mipmap-xxxhdpi\ic_launcher_round.png"
out = r"D:\workspace\Gecis-worktrees\windows-tech\windows\src-tauri\icons"
os.makedirs(out, exist_ok=True)

path = round_src if os.path.exists(round_src) else src
base = Image.open(path).convert("RGBA")

base.resize((32, 32), Image.Resampling.LANCZOS).save(os.path.join(out, "32x32.png"))
base.resize((128, 128), Image.Resampling.LANCZOS).save(os.path.join(out, "128x128.png"))
base.resize((256, 256), Image.Resampling.LANCZOS).save(os.path.join(out, "icon.png"))
base.resize((256, 256), Image.Resampling.LANCZOS).save(
    os.path.join(out, "icon.ico"),
    sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)],
)
print("ok", sorted(os.listdir(out)))
