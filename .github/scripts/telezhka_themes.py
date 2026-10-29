#!/usr/bin/env python3
"""Makes the colour variants of the Тележка theme from telezhka.attheme.

Orange accents get the variant's accent hue, the graphite greys get the variant's tint;
everything else (reds, greens, alpha) stays as it is. Run after changing telezhka.attheme:
    python3 .github/scripts/telezhka_themes.py TMessagesProj/src/main/assets
"""
import colorsys
import os
import sys

# name: (accent rgb, grey hue 0..1, grey saturation factor, darken factor for near-black)
VARIANTS = {
    "midnight": ((0x6C, 0x8C, 0xFF), 0.63, 1.6, 1.0),
    "mint": ((0x3D, 0xDC, 0x97), 0.43, 0.9, 1.0),
    "lavender": ((0xB6, 0x9C, 0xFF), 0.72, 1.3, 1.0),
    "amoled": ((0xFF, 0x6B, 0x3D), None, 0.0, 0.5),
}
ORANGE = colorsys.rgb_to_hls(0xFF / 255, 0x6B / 255, 0x3D / 255)


def convert(value, accent, grey_hue, grey_sat, darken):
    v = value & 0xFFFFFFFF
    a, r, g, b = v >> 24, (v >> 16) & 255, (v >> 8) & 255, v & 255
    h, l, s = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
    ah, al, as_ = colorsys.rgb_to_hls(*(c / 255 for c in accent))
    if s > 0.35 and 0.02 <= h <= 0.095 and l > 0.2:
        # orange family: same distance from the brand orange, around the new accent
        h = ah
        s = max(0.0, min(1.0, s * as_ / ORANGE[2]))
        l = max(0.0, min(1.0, l + (al - ORANGE[1])))
    elif s < 0.3:
        if grey_hue is not None:
            h = grey_hue
        s = min(1.0, s * grey_sat)
        if darken < 1:
            # AMOLED: the window background goes black, panels and bubbles stay a bit lighter
            if l < 0.07:
                l = 0.0
            elif l < 0.2:
                l = l * 0.75
    else:
        return value
    r, g, b = (round(c * 255) for c in colorsys.hls_to_rgb(h, l, s))
    out = (a << 24) | (r << 16) | (g << 8) | b
    return out - (1 << 32) if out >= 1 << 31 else out


def main():
    assets = sys.argv[1]
    lines = open(os.path.join(assets, "telezhka.attheme"), encoding="utf-8").read().split("\n")
    for name, (accent, grey_hue, grey_sat, darken) in VARIANTS.items():
        out = []
        for line in lines:
            key, sep, val = line.partition("=")
            if sep and val.lstrip("-").isdigit():
                line = "%s=%d" % (key, convert(int(val), accent, grey_hue, grey_sat, darken))
            out.append(line)
        with open(os.path.join(assets, "telezhka_%s.attheme" % name), "w", encoding="utf-8") as f:
            f.write("\n".join(out))
        print(name)


if __name__ == "__main__":
    main()
