#!/usr/bin/env python3
"""The HUD pictures in docs/img/ (shown in README.md), cut from `make render`.

    make docs-images        (python3 with Pillow: pip install pillow)

Every picture is the firmware's own framebuffer (render_screens.cpp), scaled
2x without smoothing. Re-run after changing what the HUD draws, and commit
docs/img/.
"""
import glob
import os

from PIL import Image, ImageDraw, ImageFont

OUT = 'out'
DOCS = os.path.join('..', '..', 'docs', 'img')
SCALE = 2
GAP, GAP_RGB, BG_RGB, LABEL_RGB = 8, (45, 48, 56), (28, 28, 28), (190, 190, 190)

# README's four moments: a roundabout, over the limit, over a remembered
# limit (the dashed ring), arrival.
DRIVE = ['rab_1_oclock', 'over_limit_merge_6km', 'over_limit_low_conf_ramp', 'arrived']

# The roundabout gallery: the dash theme's turn zone.
RAB_BOX = (0, 66, 163, 184)
RABS = [
    ("exit at 9 o'clock", 'rab_9_oclock'),
    ("exit at 10 o'clock", 'rab_10_oclock'),
    ("exit at 12 o'clock", 'rab_12_oclock'),
    ("exit at 1 o'clock", 'rab_1_oclock'),
    ("exit at 4 o'clock", 'rab_4_oclock'),
    ('U-turn', 'rab_uturn'),
    ("left-hand traffic, 9 o'clock", 'rab_left_traffic_9'),
    ("left-hand traffic, 3 o'clock", 'rab_left_traffic_3'),
    ('left-hand traffic, U-turn', 'rab_left_traffic_uturn'),
    ('far away: dimmed', 'rab_far_dimmed'),
    ('no angle: exit-number guess', 'rab_no_angle_table'),
    ('exit 9, nothing known', 'rab_no_angle_bare_ring'),
]


def shot(theme, name):
    found = glob.glob(os.path.join(OUT, f'{theme}_*_{name}.png'))
    if len(found) != 1:
        raise SystemExit(f'{theme} {name}: {len(found)} match(es) in {OUT}/ -- run make render')
    return Image.open(found[0]).convert('RGB')


def big(im):
    return im.resize((im.width * SCALE, im.height * SCALE), Image.NEAREST)


def stack(ims):
    sheet = Image.new('RGB', (ims[0].width, sum(i.height for i in ims) + GAP * (len(ims) - 1)), GAP_RGB)
    y = 0
    for im in ims:
        sheet.paste(im, (0, y))
        y += im.height + GAP
    return sheet


def font(size):
    for name in ('DejaVuSans.ttf', 'arial.ttf'):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            pass
    return ImageFont.load_default()


def gallery(cases, box, cols=4, pad=16, label_h=24):
    tiles = [big(shot('dash', f).crop(box)) for _, f in cases]
    w, h = tiles[0].size
    rows = (len(tiles) + cols - 1) // cols
    sheet = Image.new('RGB', (pad + cols * (w + pad), pad + rows * (h + label_h + pad)), BG_RGB)
    d, f = ImageDraw.Draw(sheet), font(17)
    for i, ((label, _), tile) in enumerate(zip(cases, tiles)):
        x, y = pad + (i % cols) * (w + pad), pad + (i // cols) * (h + label_h + pad)
        d.text((x, y), label, font=f, fill=LABEL_RGB)
        sheet.paste(tile, (x, y + label_h))
    return sheet


def save(im, name):
    path = os.path.join(DOCS, name)
    im.save(path, optimize=True)
    print(f'  {path}  {im.width}x{im.height}')


save(stack([big(shot('e60', n)) for n in DRIVE]), 'hud-e60.png')
save(stack([big(shot('dash', n)) for n in DRIVE]), 'hud-states.png')
save(stack([big(shot(t, DRIVE[0])) for t in ('dash', 'e60')]), 'hud-themes.png')
save(gallery(RABS, RAB_BOX), 'hud-roundabouts.png')
