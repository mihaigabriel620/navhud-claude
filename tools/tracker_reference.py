#!/usr/bin/env python3
"""
Python port of RouteTracker.kt + DemoDrive.kt + HudFrame.encode().

The point of this file is verification, not production: it lets the actual
navigation logic -- projection onto the polyline, speed-limit hold-over,
maneuver advancement, off-route detection -- be exercised and checked on a PC
before any of it runs on a phone in a moving car.

    python3 tracker_reference.py --check      # assertions about the drive
    python3 tracker_reference.py --emit       # print protocol frames
"""
import argparse
import math
import sys

from geo_reference import (EARTH_R, DEG, cumulative, haversine, bearing,
                           project)

# ---- maneuver codes, mirroring Maneuver.kt ---------------------------------
(NONE, LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, SHARP_LEFT, SHARP_RIGHT, UTURN,
 STRAIGHT, MERGE_LEFT, MERGE_RIGHT, FORK_LEFT, FORK_RIGHT, ROUNDABOUT, DEPART,
 ARRIVE, RAMP_LEFT, RAMP_RIGHT, KEEP_LEFT, KEEP_RIGHT) = range(20)

F_OVER, F_OFF, F_GPS, F_ARRIVED, F_LOWCONF, F_NIGHT = 1, 2, 4, 8, 16, 32

# ---- RouteTracker constants -------------------------------------------------
OFF_ROUTE_M = 45.0
OFF_ROUTE_STREAK = 3
LIMIT_HOLD_M = 400.0
MANEUVER_PASSED_M = 8.0
OVER_LIMIT_TOLERANCE_KPH = 2
ARRIVED_M = 25.0

# ---- DemoDrive --------------------------------------------------------------
SECTIONS = [
    ( 20.0,  260.0,  30, DEPART,      "Rue du Depart",       0),
    ( 95.0,  740.0,  50, RIGHT,       "Rue de la Loi",       0),
    ( 95.0,   60.0,  50, ROUNDABOUT,  "Avenue de Tervueren", 2),
    ( 40.0, 1150.0,  70, SLIGHT_LEFT, "Avenue de Tervueren", 0),
    ( 75.0,  420.0,  90, RAMP_RIGHT,  "E40 slip road",       0),
    ( 75.0, 6200.0, 120, MERGE_LEFT,  "E40",                 0),
    (110.0,  600.0,  70, RAMP_RIGHT,  "Exit 22",             0),
    (150.0,  480.0,  50, LEFT,        "Chaussee de Louvain", 0),
    (150.0,   90.0,  30, ARRIVE,      "Destination",         0),
]
VERTEX_SPACING_M = 20.0
START_LAT, START_LON = 50.8467, 4.3525


def destination(lat, lon, brg_deg, dist):
    d = dist / EARTH_R
    b = brg_deg * DEG
    p1, l1 = lat * DEG, lon * DEG
    p2 = math.asin(math.sin(p1) * math.cos(d) + math.cos(p1) * math.sin(d) * math.cos(b))
    l2 = l1 + math.atan2(math.sin(b) * math.sin(d) * math.cos(p1),
                         math.cos(d) - math.sin(p1) * math.sin(p2))
    return [p2 / DEG, ((l2 / DEG) + 540.0) % 360.0 - 180.0]


def point_along(pts, cum, along):
    if along <= 0:
        return list(pts[0])
    if along >= cum[-1]:
        return list(pts[-1])
    i = 0
    while i < len(cum) - 2 and cum[i + 1] < along:
        i += 1
    seg = cum[i + 1] - cum[i]
    t = (along - cum[i]) / seg if seg > 1e-6 else 0.0
    return [pts[i][0] + (pts[i + 1][0] - pts[i][0]) * t,
            pts[i][1] + (pts[i + 1][1] - pts[i][1]) * t]


def build_route():
    pts, seg_limits, maneuvers = [], [], []
    lat, lon, along = START_LAT, START_LON, 0.0
    pts.append([lat, lon])
    for brg, length, limit, man, street, ex in SECTIONS:
        maneuvers.append(dict(along=along, code=man, exit=ex, name=street))
        done = 0.0
        while done < length - 0.001:
            step = min(VERTEX_SPACING_M, length - done)
            lat, lon = destination(lat, lon, brg, step)
            pts.append([lat, lon])
            seg_limits.append(limit)
            done += step
            along += step
    cum = cumulative(pts)
    limits = list(seg_limits)
    hole = int(len(limits) * 0.62)
    for i in range(hole, min(len(limits), hole + 15)):
        limits[i] = 0
    return dict(pts=pts, cum=cum, limits=limits, maneuvers=maneuvers,
                total=cum[-1], duration=cum[-1] / 16.0)


def speed_mps_at(route, along):
    idx = next((i for i in range(len(route["pts"])) if route["cum"][i] >= along), 0)
    seg = max(0, min(idx - 1, len(route["limits"]) - 1))
    limit = route["limits"][seg] or 70
    nxt = next((m for m in route["maneuvers"] if m["along"] > along + 8.0), None)
    d = (nxt["along"] - along) if nxt else 1e9
    if d < 25.0:
        factor = 0.35
    elif d < 80.0:
        factor = 0.35 + 0.65 * ((d - 25.0) / 55.0)
    else:
        factor = 1.0
    target = limit * factor + (8.0 if limit >= 100 else 0.0)
    return (target * min(1.0, 0.25 + along / 60.0)) / 3.6


class Tracker:
    def __init__(self, route):
        self.route = route
        self.last_seg = 0
        self.off_streak = 0
        self.held_limit = 0
        self.held_along = -1e9
        self.along = 0.0
        self.off_route = False
        self.cross = 0.0

    def update(self, lat, lon, speed_mps, brg, has_fix=True, night=False):
        r = self.route
        if not has_fix:
            return dict(speed=-1, limit=self.held_limit, man=NONE, ex=0, dist=0,
                        eta=0, rem=0, street="",
                        flags=(F_NIGHT if night else 0)
                              | (F_OFF if self.off_route else 0) | F_LOWCONF)

        heading = brg if speed_mps > 2.0 else None
        seg, t, along, cross = project(
            r["pts"], r["cum"], lat, lon,
            from_idx=self.last_seg,
            window=max(400.0, speed_mps * 20.0),
            heading=heading)
        self.last_seg, self.along, self.cross = seg, along, cross

        self.off_streak = self.off_streak + 1 if cross > OFF_ROUTE_M else 0
        self.off_route = self.off_streak >= OFF_ROUTE_STREAK

        raw = r["limits"][seg] if seg < len(r["limits"]) else 0
        low_conf = False
        if raw != 0:
            limit = raw
            self.held_limit, self.held_along = raw, along
        elif self.held_limit != 0 and along - self.held_along < LIMIT_HOLD_M:
            limit, low_conf = self.held_limit, True
        else:
            limit, self.held_limit = 0, 0
        if self.off_route:
            low_conf = True

        nxt = next((m for m in r["maneuvers"] if m["along"] > along + MANEUVER_PASSED_M),
                   None)
        remaining = max(0.0, r["total"] - along)
        arrived = remaining < ARRIVED_M
        eta = round(r["duration"] * (remaining / r["total"])) if r["total"] > 1 else 0
        speed = round(speed_mps * 3.6)
        over = limit > 0 and speed > limit + OVER_LIMIT_TOLERANCE_KPH

        flags = F_GPS
        if over:      flags |= F_OVER
        if self.off_route: flags |= F_OFF
        if arrived:   flags |= F_ARRIVED
        if low_conf:  flags |= F_LOWCONF
        if night:     flags |= F_NIGHT

        return dict(
            speed=speed, limit=limit,
            man=ARRIVE if (arrived or nxt is None) else nxt["code"],
            ex=(nxt["exit"] if nxt and nxt["code"] == ROUNDABOUT else 0),
            dist=round(nxt["along"] - along) if nxt else 0,
            eta=eta, rem=round(remaining),
            flags=flags, street=(nxt["name"] if nxt else ""))


def encode(f):
    body = "HUD,%d,%d,%d,%d,%d,%d,%d,%d,%s" % (
        f["speed"], f["limit"], f["man"], f["ex"], f["dist"],
        f["eta"], f["rem"], f["flags"], f["street"][:20])
    cs = 0
    for ch in body.encode("ascii"):
        cs ^= ch
    return "$%s*%02X\r\n" % (body, cs)


def drive(route, tick=0.25, off_route_at=None):
    """Yields (along, frame) walking the demo route at a plausible speed."""
    tr = Tracker(route)
    along = 0.0
    guard = 0
    while along < route["total"] + 30 and guard < 20000:
        guard += 1
        v = speed_mps_at(route, along)
        p = point_along(route["pts"], route["cum"], along)
        ahead = point_along(route["pts"], route["cum"], along + 15.0)
        brg = bearing(p[0], p[1], ahead[0], ahead[1])
        lat, lon = p
        if off_route_at and off_route_at[0] <= along <= off_route_at[1]:
            # slide 80 m sideways, as if we took a wrong turn
            lat, lon = destination(lat, lon, (brg + 90) % 360, 80.0)
        yield along, tr.update(lat, lon, v, brg), tr
        along += v * tick


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--emit", action="store_true")
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    route = build_route()

    if args.emit:
        for _, f, _ in drive(route):
            sys.stdout.write(encode(f))
        return 0

    fails = []

    def check(cond, msg):
        if not cond:
            fails.append(msg)

    print("route: %.0f m, %d vertices, %d maneuvers"
          % (route["total"], len(route["pts"]), len(route["maneuvers"])))

    frames = [(a, f) for a, f, _ in drive(route)]
    check(len(frames) > 500, "enough frames (%d)" % len(frames))

    print("\n1. the tracker follows the route it was given")
    # A few metres is expected at corners: the simulated heading is taken 15 m
    # ahead, so through a bend it points between the two section bearings and
    # the heading penalty legitimately prefers the segment we are turning onto.
    max_cross = 0.0
    for _, f, tr in drive(route):
        max_cross = max(max_cross, tr.cross)
    check(max_cross < 8.0, "stays snapped to the line, max cross %.2f m" % max_cross)
    print("    max cross-track error: %.3f m" % max_cross)

    print("2. every maneuver is announced, in order")
    seen = []
    for _, f in frames:
        key = (f["man"], f["street"])
        if not seen or seen[-1] != key:
            seen.append(key)
    announced = [s for s in seen]
    expected = [(s[3], s[4]) for s in SECTIONS[1:]]      # DEPART is behind us at t=0
    print("    announced:", [(m, n) for m, n in announced])
    for man, name in expected:
        check(any(m == man and n == name for m, n in announced),
              "maneuver %d '%s' was announced" % (man, name))

    print("3. the distance to the next turn counts down, never up")
    bad_jumps = 0
    prev = None
    for _, f in frames:
        if prev and f["street"] == prev["street"] and f["man"] == prev["man"]:
            if f["dist"] > prev["dist"] + 1:
                bad_jumps += 1
        prev = f
    check(bad_jumps == 0, "monotonic countdown (%d jumps)" % bad_jumps)
    print("    non-monotonic ticks: %d" % bad_jumps)

    print("4. remaining distance and ETA only decrease")
    rem_bad = sum(1 for i in range(1, len(frames))
                  if frames[i][1]["rem"] > frames[i - 1][1]["rem"] + 1)
    check(rem_bad == 0, "remaining decreases (%d)" % rem_bad)
    print("    remaining regressions: %d" % rem_bad)

    print("5. the roundabout carries its exit number")
    rb = [f for _, f in frames if f["man"] == ROUNDABOUT]
    check(len(rb) > 0, "roundabout announced")
    check(all(f["ex"] == 2 for f in rb), "exit 2 on every roundabout frame")
    print("    %d roundabout frames, exits seen: %s"
          % (len(rb), sorted({f["ex"] for f in rb})))

    print("6. speed limits match the section being driven")
    # The polyline has a vertex every 20 m, so the limit can only change on a
    # 20 m boundary. Disagreement within one vertex of a section change is the
    # resolution of the data, not a bug; anywhere else it is.
    boundaries = []
    pos = 0.0
    for brg, length, limit, man, street, ex in SECTIONS:
        pos += length
        boundaries.append(pos)

    near_boundary, real_mismatch = 0, 0
    for along, f in frames:
        pos, sect = 0.0, None
        for brg, length, limit, man, street, ex in SECTIONS:
            if pos <= along < pos + length:
                sect = limit
                break
            pos += length
        if sect is None or (f["flags"] & F_LOWCONF) or f["limit"] in (sect, 0):
            continue
        if min(abs(along - b) for b in boundaries) <= VERTEX_SPACING_M + 5:
            near_boundary += 1
        else:
            real_mismatch += 1
    check(real_mismatch == 0, "limits track the sections (%d real mismatches)" % real_mismatch)
    print("    mismatches: %d at section boundaries (expected), %d elsewhere"
          % (near_boundary, real_mismatch))

    print("7. the data gap triggers hold-over, then clears")
    lowconf = [a for a, f in frames if f["flags"] & F_LOWCONF]
    check(len(lowconf) > 0, "hold-over fired somewhere")
    check(not (frames[-1][1]["flags"] & F_LOWCONF), "cleared by the end")
    if lowconf:
        print("    held between %.0f m and %.0f m along" % (lowconf[0], lowconf[-1]))
    unknown = [f for _, f in frames if f["limit"] == 0]
    print("    frames showing an unknown limit: %d" % len(unknown))

    print("8. going 120+ on the motorway raises the over-limit flag")
    over = [f for _, f in frames if f["flags"] & F_OVER]
    check(len(over) > 0, "over-limit seen")
    print("    %d over-limit frames, top speed %d km/h"
          % (len(over), max(f["speed"] for _, f in frames)))

    print("9. arrival is flagged at the end and not before")
    arr = [i for i, (_, f) in enumerate(frames) if f["flags"] & F_ARRIVED]
    check(len(arr) > 0, "arrival flagged")
    check(arr and arr[0] > len(frames) * 0.9, "not flagged early")
    print("    first arrival flag at frame %d of %d" % (arr[0] if arr else -1, len(frames)))

    print("10. an 80 m detour is detected as off-route")
    off_seen, off_before = 0, 0
    for along, f, _ in drive(route, off_route_at=(2000, 2600)):
        if f["flags"] & F_OFF:
            off_seen += 1
            if along < 1900:
                off_before += 1
    check(off_seen > 0, "off-route detected during the detour")
    check(off_before == 0, "no false positives before it")
    print("    off-route frames: %d (false positives before: %d)" % (off_seen, off_before))

    if fails:
        print("\n%d CHECK(s) FAILED:" % len(fails))
        for f in fails:
            print("  -", f)
        return 1
    print("\nall checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
