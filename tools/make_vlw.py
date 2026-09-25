#!/usr/bin/env python3
"""
Turn a TTF into TFT_eSPI `.vlw` smooth fonts, and into the PROGMEM C arrays
the sketch actually uses.

Why this exists
---------------
The layout was designed in the browser bench with Barlow Condensed at specific
pixel sizes. The firmware could not reproduce those sizes, because TFT_eSPI's
built-in bitmap fonts come in exactly four heights (10, 17, 36 and 70 px of lit
glyph) and `setTextSize` only multiplies them by whole numbers. Worse, the
built-in faces are far wider per unit of height than a condensed face, so the
sizes that *were* reachable collided with their neighbours -- the battery
reading at 34 px ran into the PS number.

Smooth fonts fix both. Each `.vlw` is one face at one size, anti-aliased, in
whatever typeface you like.

The one thing to know before adding sizes
-----------------------------------------
`setTextSize` does nothing to a smooth font -- TFT_eSPI's `textWidth()` and
`fontHeight()` both skip the multiply when `fontLoaded` is set. So every
distinct pixel size on the panel needs its own file, and the flash bill is
`sum(width*height)` over the glyphs. That is why the charsets below are cut to
the bone: an element that only ever shows numbers gets twelve glyphs, not
ninety-five, which is roughly a sevenfold saving.

Format
------
Big-endian int32 throughout. Header is 24 bytes:

    glyphCount, version(11), fontSize, 0, ascent, descent

then 28 bytes per glyph:

    unicode, height, width, xAdvance, topExtent(dY), leftExtent(dX), 0

then every glyph bitmap concatenated in metrics order, one byte of alpha per
pixel, row-major, top row first, no padding between rows or glyphs.

`height`, `width` and `xAdvance` are read back through a `uint8_t` cast in
TFT_eSPI's `loadMetrics()`, so anything over 255 truncates silently and
desynchronises the whole bitmap stream. We refuse to emit that.

Usage
-----
    python3 tools/make_vlw.py --all          # everything theme_dash.h needs
    python3 tools/make_vlw.py --size 75 --chars digits --name Speed
"""

import argparse
import os
import struct
import sys

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    sys.exit("needs Pillow:  pip install pillow --break-system-packages")

HERE = os.path.dirname(os.path.abspath(__file__))
TTF = os.path.join(HERE, "fonts", "BarlowCondensed-Medium.ttf")
OUT = os.path.join(HERE, "..", "arduino", "NavHud", "fonts")

# TFT_eSPI reads these back into uint8_t. Over this and the file is garbage.
MAX_DIM = 255

CHARSETS = {
    # Everything that can appear in a number field, and nothing else.
    "digits": "0123456789.-",
    # Digits plus the two letters the voltage and the derestricted sign need.
    "volts": "0123456789.-V",
    # Street names, "CAMERA 300 m", "km/h". ASCII plus only the accents that actually turn up on the roads this
    # thing drives on. The full Latin-1 set was 171 glyphs and 97 KB of flash;
    # this is 108 and 61 KB, and the difference was Icelandic thorns and
    # Scandinavian slashed O's. Belgian street signs need French and Dutch
    # accents, Romanian ones need the comma-below forms -- and the legacy
    # cedilla forms too, because half the data still uses them.
    "text": (
        " !\"#$%&'()*+,-./0123456789:;<=>?@"
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`"
        "abcdefghijklmnopqrstuvwxyz{|}~"
        "ÀÂÄÇÈÉÊËÎÏÔÖÙÛÜ"
        "àâäçèéêëîïôöùûü"
        "ĂăȘșȚțŞşŢţß"
    ),
    # The unit strings, plus digits and a minus. The digits are for the
    # roundabout exit number, which arrowArt draws in whatever face is loaded,
    # and the minus is for the derestricted speed-limit sign. A smooth font
    # draws a hollow rectangle for a glyph it does not carry, so a missing
    # character here is a box on the glass, not a fallback.
    "units": "PSEAKkm/h 0123456789-",
}

# The bench positions the *lit glyph*, not the em box. A digit in Barlow
# Condensed is 0.72 of the nominal size, so asking for a 130 px digit means
# rasterising at 180. Measured, not assumed -- see cap_ratio().
CAP_REF = "8"


def cap_ref(chars):
    """The glyph whose lit height *is* the size, for this character set.

    Digits where there are digits, "E" otherwise: the glyph the layout's
    sizes were measured on, so a size here and a size there mean the same.
    """
    return CAP_REF if CAP_REF in chars else "E"


def lit_height(path, nominal, ref):
    """Rows the rasteriser actually produces -- what goes into the file.

    Not getbbox(). The bounding box is the layout box and anti-aliasing can
    spill a row past it, so a size chosen by getbbox lands one pixel out about
    half the time. Measure the bitmap, since the bitmap is what ships.
    """
    return ImageFont.truetype(path, nominal).getmask(ref, mode="L").size[1]


def cap_ratio(path):
    """Lit height of a digit as a fraction of the nominal size."""
    return lit_height(path, 200, CAP_REF) / 200.0


def nominal_for(path, cap_px, ref=CAP_REF):
    """The point size whose rendered digit is exactly `cap_px` tall.

    A single `cap_px / ratio` division is off by a pixel or two once hinting
    and rounding have had their say, and a pixel or two is exactly the kind of
    drift this whole exercise is meant to stop. So start there and walk.
    """
    guess = max(1, int(round(cap_px / cap_ratio(path))))
    best, best_err = guess, None
    for n in range(max(1, guess - 8), guess + 9):
        err = abs(lit_height(path, n, ref) - cap_px)
        if best_err is None or err < best_err:
            best, best_err = n, err
        if err == 0:
            return n
    return best


def render(path, cap_px, chars):
    """Rasterise every glyph at the size that makes a digit `cap_px` tall."""
    nominal = nominal_for(path, cap_px, cap_ref(chars))
    font = ImageFont.truetype(path, nominal)
    ascent, descent = font.getmetrics()

    glyphs = []
    for ch in chars:
        mask = font.getmask(ch, mode="L")
        w, h = mask.size
        box = font.getbbox(ch)
        # PIL measures from the top of the ascender, TFT_eSPI from the
        # baseline and upwards, so this is a subtraction not a copy.
        top_extent = ascent - box[1]
        left_extent = box[0]
        advance = int(round(font.getlength(ch)))

        if w == 0 or h == 0:          # space and friends: metrics, no pixels
            w = h = 0
            bitmap = b""
        else:
            bitmap = bytes(mask)

        for name, value in (("height", h), ("width", w), ("xAdvance", advance)):
            if not 0 <= value <= MAX_DIM:
                sys.exit(f"{name} {value} for {ch!r} at {cap_px}px exceeds "
                         f"{MAX_DIM}; TFT_eSPI truncates it to uint8_t and the "
                         f"bitmap stream desynchronises. Use a smaller size.")
        if not -128 <= left_extent <= 127:
            sys.exit(f"leftExtent {left_extent} for {ch!r} is outside int8_t")

        glyphs.append({
            "unicode": ord(ch), "height": h, "width": w,
            "advance": advance, "dY": top_extent, "dX": left_extent,
            "bitmap": bitmap,
        })
    return nominal, ascent, descent, glyphs


def pack(nominal, ascent, descent, glyphs):
    """Serialise, writing the *ink* box as the header's ascent and descent.

    Not the face's own ascent and descent, which is what a Processing-generated
    .vlw carries and what PIL hands you. The reason is specific: TFT_eSPI sets
    `gFont.maxAscent = gFont.ascent` straight from this header, and the block
    in loadMetrics() that would raise it from the glyph table is commented out
    in the library ("this method can generate bad values for non-existent
    glyphs, so we will reply on processing for the value and disable this code
    for now"). maxDescent *is* raised from the glyphs.

    So the header ascent is the box the datum is measured against and the
    background is painted over. Barlow Condensed's face ascent is 181 where the
    digits are 129, which put a 218-pixel background box behind the big speed
    number -- it erased 244 px of the band rule and pushed the digits 7.5 px
    below where they were designed. Writing the ink metrics makes the box the
    glyphs, which is what every caller assumes.
    """
    ascent = max((g["dY"] for g in glyphs), default=ascent)
    descent = max((g["height"] - g["dY"] for g in glyphs), default=descent)
    out = bytearray()
    out += struct.pack(">6i", len(glyphs), 11, nominal, 0, ascent, descent)
    for g in glyphs:
        out += struct.pack(">7i", g["unicode"], g["height"], g["width"],
                           g["advance"], g["dY"], g["dX"], 0)
    for g in glyphs:
        out += g["bitmap"]
    return bytes(out)


def as_header(blob, name, cap_px, glyphs):
    # TFT_eSPI does not use the header's ascent/descent for placement: at the
    # end of loadMetrics() it sets yAdvance = maxAscent + maxDescent taken from
    # the glyph table, and drawString() then treats the datum as acting on a
    # box of that height with the baseline maxAscent down from its top.
    #
    # That box is taller than the lit capital, because it reserves room for
    # accents above and descenders below. The bench laid the design out against
    # the *lit* glyph, so a top-datum string placed at the bench's y sits lower
    # than intended and can run off the bottom of the panel -- which is exactly
    # what the layout test caught. CAPTOP is the gap between the box top and
    # the top of a capital, so `y - CAPTOP` puts the capital where the bench
    # put it. BOXH is the full box, for clear rectangles.
    # The same numbers pack() writes into the header, and therefore the same
    # box TFT_eSPI positions against.
    max_ascent = max((g["dY"] for g in glyphs), default=0)
    max_descent = max((g["height"] - g["dY"] for g in glyphs), default=0)
    lines = [
        "// Generated by tools/make_vlw.py -- do not edit by hand.",
        "// Barlow Condensed Medium, SIL Open Font License 1.1 "
        "(tools/fonts/OFL.txt).",
        "#pragma once",
        "#include <pgmspace.h>",
        "",
        f"// {cap_px} px capitals. Box {max_ascent + max_descent} px "
        f"(ascent {max_ascent}, descent {max_descent}).",
        f"#define {name}_CAP    {cap_px}",
        f"#define {name}_CAPTOP {max_ascent - cap_px}",
        f"#define {name}_BOXH   {max_ascent + max_descent}",
        "",
        f"const uint8_t {name}[] PROGMEM = {{",
    ]
    for i in range(0, len(blob), 16):
        chunk = blob[i:i + 16]
        lines.append("  " + "".join(f"0x{b:02X}," for b in chunk))
    lines.append("};")
    lines.append("")
    return "\n".join(lines)


def verify(blob, glyphs):
    """Parse what we just wrote the way TFT_eSPI does, and check it agrees.

    Cheap, and it is the only check available without hardware: a file that
    parses back to the same metrics and the same bitmap offsets is a file the
    library will read correctly.
    """
    count, version, _, _, _, _ = struct.unpack_from(">6i", blob, 0)
    assert count == len(glyphs), f"glyph count {count} != {len(glyphs)}"
    assert version == 11, f"version {version} != 11"
    ptr = 24 + count * 28
    for i, g in enumerate(glyphs):
        u, h, w, adv, dy, dx, pad = struct.unpack_from(">7i", blob, 24 + i * 28)
        assert (u, h, w) == (g["unicode"], g["height"], g["width"]), \
            f"glyph {i} metrics differ"
        assert pad == 0
        assert blob[ptr:ptr + w * h] == g["bitmap"], f"glyph {i} bitmap differs"
        ptr += w * h
    assert ptr == len(blob), f"trailing bytes: {len(blob) - ptr}"
    return True


def proof(path, cap_px, chars, sample, out_png):
    """Render a sample line to a PNG so the size can be checked by eye."""
    nominal, ascent, _, glyphs = render(path, cap_px, chars)
    font = ImageFont.truetype(path, nominal)
    w = int(font.getlength(sample)) + 8
    img = Image.new("L", (max(w, 8), cap_px * 2), 0)
    ImageDraw.Draw(img).text((4, 4), sample, font=font, fill=255)
    img.save(out_png)
    lit = img.crop(img.getbbox()) if img.getbbox() else img
    return lit.size


# name -> (lit digit height in px, charset). Sizes are the bench layout's.
# Several bench elements share a size on purpose: 44 and 45 became one file,
# and 19 and 20 became one, because a separate file per element would have
# cost flash for a difference nobody can see.
FONTS = [
    ("DashBig",    130, "digits"),   # big speed
    ("DashSpeed",   75, "digits"),   # speed
    ("DashPower",   45, "digits"),   # PS, and the limit disc at 44
    ("DashDist",    33, "digits"),   # distance to the manoeuvre
    ("DashStreet",  31, "text"),     # street name and camera warning
    ("DashMid",     28, "volts"),    # battery volts, peak PS
    ("DashUnit",    20, "units"),    # PS, km/h
    ("DashSmall",   17, "units"),    # distance unit
    ("DashTiny",    13, "units"),    # PEAK label
]


def build(name, cap_px, charset, ttf, outdir, quiet=False):
    chars = CHARSETS[charset]
    nominal, ascent, descent, glyphs = render(ttf, cap_px, chars)
    blob = pack(nominal, ascent, descent, glyphs)
    verify(blob, glyphs)

    os.makedirs(outdir, exist_ok=True)
    with open(os.path.join(outdir, name + ".vlw"), "wb") as fh:
        fh.write(blob)
    with open(os.path.join(outdir, name + ".h"), "w") as fh:
        fh.write(as_header(blob, name, cap_px, glyphs))
    if not quiet:
        print(f"  {name:<11} {cap_px:>3}px  {charset:<7} "
              f"{len(glyphs):>3} glyphs  {len(blob):>7,} B  (nominal {nominal})")
    return len(blob)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ttf", default=TTF)
    ap.add_argument("--out", default=OUT)
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--size", type=int)
    ap.add_argument("--chars", default="digits", choices=sorted(CHARSETS))
    ap.add_argument("--name")
    ap.add_argument("--proof", help="write a sample PNG here and stop")
    args = ap.parse_args()

    if not os.path.exists(args.ttf):
        sys.exit(f"no font at {args.ttf}")

    if args.proof:
        size = proof(args.ttf, args.size or 130, CHARSETS[args.chars],
                     "128", args.proof)
        print(f"lit size {size} for a requested {args.size or 130}px")
        return

    if args.all:
        print(f"cap ratio {cap_ratio(args.ttf):.4f}  ({os.path.basename(args.ttf)})")
        total = sum(build(n, s, c, args.ttf, args.out) for n, s, c in FONTS)
        print(f"  {'total':<11} {total:>28,} B "
              f"({total / 1024:.0f} KB of the ~1020 KB code+PROGMEM ceiling)")
        return

    if not args.size:
        ap.error("--all or --size")
    build(args.name or f"Dash{args.size}", args.size, args.chars,
          args.ttf, args.out)


if __name__ == "__main__":
    main()
