"""Resample the shared fox artwork into Android and Windows launcher assets.

Usage: python windows/scripts/make_icons.py (requires Pillow).
"""
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
BRANDING = ROOT / "assets/branding"
RES = ROOT / "app/src/main/res"
WINDOWS = ROOT / "windows/src-tauri/icons"
SIZE = 1024


def foreground(art, coverage):
    layer = Image.new("RGBA", (SIZE, SIZE))
    mark = art.copy()
    mark.thumbnail((round(SIZE * coverage), round(SIZE * coverage)), Image.Resampling.LANCZOS)
    layer.alpha_composite(mark, ((SIZE - mark.width) // 2, (SIZE - mark.height) // 2))
    return layer


def transparent(art, coverage=.76):
    result = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    result.alpha_composite(foreground(art, coverage))
    return result


def save_at(image, path, size):
    path.parent.mkdir(parents=True, exist_ok=True)
    image.resize((size, size), Image.Resampling.LANCZOS).save(path)


def main():
    source = Image.open(BRANDING / "fox-master.png").convert("RGBA")
    alpha = source.getchannel("A")
    if alpha.getextrema()[0] == 255:
        raise ValueError("Fox master must have a transparent background")
    art = source.crop(alpha.getbbox())
    square = transparent(art)
    round_icon = transparent(art)
    windows_square = transparent(art, coverage=.86)
    adaptive = foreground(art, .59)
    monochrome = Image.new("RGBA", adaptive.size, "white")
    monochrome.putalpha(adaptive.getchannel("A"))
    square.save(BRANDING / "app-icon.png")
    save_at(square, ROOT / "app/src/main/ic_launcher-web.png", 512)
    for density, size, adaptive_size in [
        ("mdpi", 48, 108), ("hdpi", 72, 162), ("xhdpi", 96, 216),
        ("xxhdpi", 144, 324), ("xxxhdpi", 192, 432),
    ]:
        folder = RES / f"mipmap-{density}"
        save_at(square, folder / "ic_launcher.png", size)
        save_at(round_icon, folder / "ic_launcher_round.png", size)
        save_at(adaptive, folder / "ic_launcher_foreground.png", adaptive_size)
        save_at(monochrome, folder / "ic_launcher_monochrome.png", adaptive_size)
    for filename, size in [("32x32.png", 32), ("128x128.png", 128), ("icon.png", 256)]:
        save_at(windows_square, WINDOWS / filename, size)
    windows_square.save(WINDOWS / "icon.ico", sizes=[(s, s) for s in (16, 24, 32, 48, 64, 128, 256)])
    print("Generated unified fox icons for Android and Windows")


if __name__ == "__main__":
    main()
