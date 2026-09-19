#!/usr/bin/env python3
"""
Drive the NavHUD display from a PC -- no phone, no car, no API key.

Plug the board into your computer and run:

    pip install pyserial
    python3 hud_sim.py --port /dev/ttyUSB0

It replays the same synthetic drive the Android app's demo mode uses, so you
can get the sketch, the fonts and the wiring right before any of the Android
side exists. Ctrl-C to stop.

    --stdout      print frames instead of opening a port (for piping into tests)
    --speed 4     run the drive at 4x
    --frames N    stop after N frames
"""
import argparse
import math
import sys
import time

EARTH_R = 6371008.8
DEG = math.pi / 180.0

# maneuver codes, mirroring hud_protocol.h
(NONE, LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, SHARP_LEFT, SHARP_RIGHT, UTURN,
 STRAIGHT, MERGE_LEFT, MERGE_RIGHT, FORK_LEFT, FORK_RIGHT, ROUNDABOUT, DEPART,
 ARRIVE, RAMP_LEFT, RAMP_RIGHT, KEEP_LEFT, KEEP_RIGHT) = range(20)

F_OVER, F_OFF, F_GPS, F_ARRIVED, F_LOWCONF, F_NIGHT = 1, 2, 4, 8, 16, 32

# (metres of this section, limit, maneuver entering it, street, roundabout exit)
SECTIONS = [
    (260,   30, DEPART,       "Rue du Depart",       0),
    (740,   50, RIGHT,        "Rue de la Loi",       0),
    (60,    50, ROUNDABOUT,   "Avenue de Tervueren", 2),
    (1150,  70, SLIGHT_LEFT,  "Avenue de Tervueren", 0),
    (420,   90, RAMP_RIGHT,   "E40 slip road",       0),
    (6200, 120, MERGE_LEFT,   "E40",                 0),
    (600,   70, RAMP_RIGHT,   "Exit 22",             0),
    (480,   50, LEFT,         "Chaussee de Louvain", 0),
    (90,    30, ARRIVE,       "Destination",         0),
]

# A stretch of the motorway with no speed-limit data, to exercise hold-over.
GAP_START, GAP_END = 4000, 4300


def checksum(body):
    cs = 0
    for ch in body.encode("ascii"):
        cs ^= ch
    return cs


def wrap(body):
    return "$%s*%02X\r\n" % (body, checksum(body))


def build():
    """Returns (total_metres, list of (start, end, limit, man, street, exit))."""
    out, pos = [], 0
    for length, limit, man, street, ex in SECTIONS:
        out.append((pos, pos + length, limit, man, street, ex))
        pos += length
    return pos, out


def state_at(along, total, sections):
    limit, cur_i = 0, 0
    for i, (s, e, lim, man, street, ex) in enumerate(sections):
        if s <= along < e:
            limit, cur_i = lim, i
            break
    else:
        cur_i = len(sections) - 1
        limit = sections[-1][2]

    low_conf = False
    if GAP_START <= along < GAP_END:
        limit, low_conf = sections[cur_i][2], True   # held over, flagged

    # next maneuver = start of the next section
    if cur_i + 1 < len(sections):
        nxt = sections[cur_i + 1]
        dist = int(nxt[0] - along)
        man, street, ex = nxt[3], nxt[4], nxt[5]
    else:
        dist, man, street, ex = int(total - along), ARRIVE, "Destination", 0

    # a speed that respects the limit but eases off for turns
    factor = 1.0
    if dist < 25:
        factor = 0.35
    elif dist < 80:
        factor = 0.35 + 0.65 * ((dist - 25) / 55.0)
    speed = limit * factor + (8 if limit >= 100 else 0)
    speed *= min(1.0, along / 80.0 + 0.05)

    remaining = max(0, total - along)
    eta = int(remaining / 16.0)
    arrived = remaining < 25

    flags = F_GPS
    if limit > 0 and speed > limit + 2:
        flags |= F_OVER
    if low_conf:
        flags |= F_LOWCONF
    if arrived:
        flags |= F_ARRIVED

    return dict(speed=int(round(speed)), limit=limit,
                man=ARRIVE if arrived else man, ex=ex, dist=max(0, dist),
                eta=eta, rem=int(remaining), flags=flags, street=street)


def frame(s):
    return wrap("HUD,%d,%d,%d,%d,%d,%d,%d,%d,%s" % (
        s["speed"], s["limit"], s["man"], s["ex"], s["dist"],
        s["eta"], s["rem"], s["flags"], s["street"][:20]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", help="serial port, e.g. /dev/ttyUSB0 or COM3")
    ap.add_argument("--baud", type=int, default=115200)
    ap.add_argument("--stdout", action="store_true", help="print instead of writing a port")
    ap.add_argument("--speed", type=float, default=1.0, help="playback rate multiplier")
    ap.add_argument("--hz", type=float, default=4.0)
    ap.add_argument("--frames", type=int, default=0, help="stop after N frames (0 = loop)")
    ap.add_argument("--loop", action="store_true", help="restart at the end")
    args = ap.parse_args()

    if not args.stdout and not args.port:
        ap.error("give --port or --stdout")

    ser = None
    if args.port:
        try:
            import serial
        except ImportError:
            sys.exit("pyserial is not installed:  pip install pyserial")
        ser = serial.Serial(args.port, args.baud, timeout=0.2)
        time.sleep(2.0)          # boards that auto-reset on DTR need a moment
        print("opened %s at %d baud" % (args.port, args.baud), file=sys.stderr)

    total, sections = build()
    dt = 1.0 / args.hz
    along, n = 0.0, 0

    try:
        while True:
            st = state_at(along, total, sections)
            line = frame(st)
            if ser:
                ser.write(line.encode("ascii"))
                while ser.in_waiting:                      # echo the $HELLO back
                    sys.stderr.write(ser.readline().decode("ascii", "replace"))
            if args.stdout:
                sys.stdout.write(line)
                sys.stdout.flush()

            n += 1
            if args.frames and n >= args.frames:
                break
            along += (st["speed"] / 3.6) * dt * args.speed
            if along >= total:
                if args.loop or not args.frames:
                    along = 0.0
                else:
                    break
            if not args.stdout or args.port:
                time.sleep(dt / args.speed)
    except KeyboardInterrupt:
        pass
    finally:
        if ser:
            ser.write(wrap("HUD,-1,0,0,0,0,0,0,0,").encode("ascii"))
            ser.close()
        print("\n%d frames sent" % n, file=sys.stderr)


if __name__ == "__main__":
    main()
