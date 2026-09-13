#!/usr/bin/env python3
"""Rebuild the launcher icons from the approved mark.

The frog is drawn at MARK_WIDTH of the canvas and centred on it. Two files per density:

  ic_launcher_foreground.png  the adaptive icon's foreground layer, transparent behind the frog
  ic_launcher.png             the same frog flattened onto the app's background, for Android 7

Both are the adaptive canvas size, which is where the sizes below come from: a 108dp square of
which the middle 72dp is the part no launcher mask can crop.

Run from the repository root:  python3 tools/branding/make-launcher-icons.py
"""

from pathlib import Path

from PIL import Image

SOURCE = Path("tools/branding/kaeru-frog-foreground.png")
RES = Path("app/src/main/res")

# The adaptive canvas at each density: 108dp.
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

# How wide the frog is drawn, as a share of the canvas.
#
# It was 0.617, which put the mark right up against the 0.667 the mask is guaranteed to keep: on a
# round launcher the feet and the crown of the head sat on the edge of the circle. Eight per cent
# off gives it air on every side and costs nothing that can be seen at 48dp.
MARK_WIDTH = 0.5678

# `@color/kaeru_background`, which is also what the adaptive icon's background layer is filled with.
BACKGROUND = (0x0B, 0x0C, 0x10, 0xFF)


def build(mark: Image.Image, canvas: int) -> Image.Image:
    width = round(canvas * MARK_WIDTH)
    height = round(width * mark.height / mark.width)
    scaled = mark.resize((width, height), Image.LANCZOS)
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    out.paste(scaled, ((canvas - width) // 2, (canvas - height) // 2), scaled)
    return out


def main() -> None:
    mark = Image.open(SOURCE).convert("RGBA")
    for density, canvas in DENSITIES.items():
        folder = RES / f"mipmap-{density}"
        foreground = build(mark, canvas)
        foreground.save(folder / "ic_launcher_foreground.png")

        legacy = Image.new("RGBA", (canvas, canvas), BACKGROUND)
        legacy.alpha_composite(foreground)
        legacy.save(folder / "ic_launcher.png")
        print(f"{density}: {canvas}x{canvas}, mark {round(canvas * MARK_WIDTH)}px wide")


if __name__ == "__main__":
    main()
