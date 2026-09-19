#!/usr/bin/env python3
"""
Prove the firmware layout is the layout that was designed in the bench.

This exists because it once was not. Every position in `theme_dash.h` was
transcribed from `hud-editor.html` correctly, but the *sizes* were not -- they
could not be, because the built-in fonts only came in four heights -- and
nothing anywhere noticed the difference. The bench and the firmware drifted
apart silently and the first report of it was somebody looking at the panel.

So: parse both, compare, and fail loudly. Positions must match exactly. Sizes
must match the generated `.vlw` capital heights, which is what the firmware now
actually renders at.

    python3 tools/check_layout.py
"""

import os
import re
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
BENCH = os.path.join(ROOT, "hud-editor.html")
THEME = os.path.join(ROOT, "arduino", "NavHud", "theme_dash.h")
FONTS = os.path.join(ROOT, "arduino", "NavHud", "fonts")

# bench key -> (x define, y define, size define or None, font file or None)
# The font file is what the element is drawn with, and its capital height is
# what has to equal the bench's size.
MAP = {
    "batt":      ("DASH_VOLT_X",  "DASH_VOLT_Y",  "DashMid"),
    "ps":        ("DASH_PS_X",    "DASH_PS_Y",    "DashPower"),
    "psUnit":    ("DASH_PSLAB_X", "DASH_PSLAB_Y", "DashUnit"),
    "peakLab":   ("DASH_PKLAB_X", "DASH_PKLAB_Y", "DashTiny"),
    "peak":      ("DASH_PEAK_X",  "DASH_PEAK_Y",  "DashMid"),
    "arrow":     ("DASH_ARROW_X", "DASH_ARROW_Y", None),
    "dist":      ("DASH_DIST_X",  "DASH_DIST_Y",  "DashDist"),
    "distUnit":  ("DASH_DUNIT_X", "DASH_DUNIT_Y", "DashSmall"),
    "speed":     ("DASH_SPD_X",   "DASH_SPD_Y",   "DashSpeed"),
    "speedU":    ("DASH_SUNIT_X", None,           "DashUnit"),
    "speedBig":  ("DASH_BIGSPD_X","DASH_BIGSPD_Y",  "DashBig"),
    "speedBigU": ("DASH_BUNIT_X", None,           "DashUnit"),
    "limit":     ("DASH_LIM_X",   "DASH_LIM_Y",   "DashPower"),
    "street":    ("DASH_ST_X",    "DASH_ST_Y",    "DashStreet"),
}

# Positions must be exact. Sizes get a small allowance, for two reasons that
# are both real and neither of which is sloppiness:
#
#  1. TrueType hinting quantises stem and cap heights at integer pixel sizes,
#     so the rendered capital jumps 44 -> 47 with nothing in between. 45 px is
#     not a size this face can be rendered at, at any point size.
#  2. Several elements deliberately share one .vlw. The two km/h units were
#     designed a pixel apart and the speed limit a pixel off the PS number;
#     giving each its own face would cost about 15 KB of flash to move type by
#     one pixel.
#
# Two pixels is the line. Below it, note and continue; at or above it,
# something has drifted and the build fails -- which is the whole point, since
# the failure this guards against was a street name rendering at 17 px when it
# was designed at 31.
SIZE_SLACK = 1

SHARED_FACE = {
    "limit": "shares DashPower with the PS number",
    "speedU": "shares DashUnit with the other km/h",
    "speedBigU": "shares DashUnit with the other km/h",
}


def bench_layout(path):
    src = open(path, encoding="utf-8").read()
    m = re.search(r"var DEFAULT\s*=\s*\{(.*?)\n\s*\};", src, re.S)
    if not m:
        sys.exit("could not find `var DEFAULT = {` in " + path)
    body = m.group(1)
    out = {}
    for key, props in re.findall(r"(\w+)\s*:\s*\{([^}]*)\}", body):
        d = {}
        for k, v in re.findall(r"(\w+)\s*:\s*(-?\d+)", props):
            d[k] = int(v)
        out[key] = d
    return out


def theme_defines(path):
    src = open(path, encoding="utf-8").read()
    out = {}
    for name, value in re.findall(r"^#define\s+((?:DASH|TACHO)_\w+)\s+(-?\d+)\s*(?://.*)?$",
                                  src, re.M):
        out[name] = int(value)
    return out


def font_cap(name):
    """The capital height the .vlw actually renders, read from the file."""
    path = os.path.join(FONTS, name + ".vlw")
    if not os.path.exists(path):
        sys.exit(f"missing {path} -- run tools/make_vlw.py --all")
    blob = open(path, "rb").read()
    count = struct.unpack_from(">i", blob, 0)[0]
    # Capital height is the tallest ascent among the digits, or among A-Z if
    # the font carries no digits. Both are flat-topped, so this is the lit
    # height the bench was measuring.
    # Measured the same way tools/make_vlw.py measures it: the bitmap height
    # of "8", or of "E" for a font with no digits. Using the glyph's ascent
    # instead would read one pixel short, because round digits overshoot the
    # baseline slightly -- and a one-pixel disagreement between the generator
    # and the checker is a false alarm that trains you to ignore the checker.
    for want in (0x38, 0x45):
        for i in range(count):
            u, h, w, adv, dY, dX, _ = struct.unpack_from(">7i", blob, 24 + i * 28)
            if u == want:
                return h
    return 0


def main():
    bench = bench_layout(BENCH)
    theme = theme_defines(THEME)
    problems = []
    notes = []
    checked = 0

    for key, (xd, yd, font) in MAP.items():
        if key not in bench:
            problems.append(f"{key}: in the firmware map but not in the bench")
            continue
        b = bench[key]

        for axis, define in (("x", xd), ("y", yd)):
            if define is None:
                continue
            if define not in theme:
                problems.append(f"{key}.{axis}: {define} not defined in theme_dash.h")
                continue
            checked += 1
            if theme[define] != b[axis]:
                problems.append(
                    f"{key}.{axis}: bench {b[axis]}, firmware {define}={theme[define]}")

        if font and "size" in b:
            checked += 1
            cap = font_cap(font)
            off = abs(cap - b["size"])
            if off > SIZE_SLACK:
                problems.append(
                    f"{key}.size: bench {b['size']}, {font}.vlw renders {cap} "
                    f"({off} px out)")
            elif off:
                why = SHARED_FACE.get(key, "nearest size this face rasterises to")
                notes.append(f"{key}: designed {b['size']}, renders {cap} -- {why}")

    # The tachometer and the disc are geometry, not text, and have their own
    # defines.
    for key, pairs in (
        ("tacho", (("segW", "TACHO_SEG_W"), ("segH", "TACHO_SEG_H"),
                   ("pitch", "TACHO_PITCH"), ("x", "TACHO_X"),
                   ("y", "TACHO_Y"))),
        ("limit", (("r", "DASH_LIM_R"), ("ring", "DASH_LIM_RING"))),
        ("arrow", (("size", "DASH_ARROW_SZ"),)),
        ("rule",  (("y", "DASH_RULE_Y"),)),
    ):
        if key not in bench:
            continue
        for prop, define in pairs:
            if prop not in bench[key]:
                continue
            if define not in theme:
                problems.append(f"{key}.{prop}: {define} not defined")
                continue
            checked += 1
            if theme[define] != bench[key][prop]:
                problems.append(
                    f"{key}.{prop}: bench {bench[key][prop]}, "
                    f"firmware {define}={theme[define]}")

    print(f"{checked} layout values checked against {os.path.basename(BENCH)}")
    if problems:
        print("\nthe firmware does not match the bench:")
        for p in problems:
            print("  " + p)
        return 1
    for n in notes:
        print("  within a pixel: " + n)
    print("every position exact, every size within a pixel")
    return 0


if __name__ == "__main__":
    sys.exit(main())
