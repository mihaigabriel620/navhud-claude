#!/usr/bin/env python3
"""
Python port of VoiceGuide.kt, run against the demo drive.

Guidance is the part of a nav app you notice only when it is wrong: an
announcement that repeats, one that never comes, or three in a row as you come
off a motorway. Those are all timing bugs, and they are miserable to debug in
a moving car. So they get checked here instead.

    python3 voice_reference.py --check
    python3 voice_reference.py --transcript
"""
import argparse
import sys

from tracker_reference import (build_route, drive, ROUNDABOUT, ARRIVE, LEFT,
                               RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, SHARP_LEFT,
                               SHARP_RIGHT, UTURN, MERGE_LEFT, MERGE_RIGHT,
                               FORK_LEFT, FORK_RIGHT, KEEP_LEFT, KEEP_RIGHT,
                               RAMP_LEFT, RAMP_RIGHT, DEPART, F_ARRIVED)

# ---- must match VoiceGuide.kt ----------------------------------------------
FAST   = [2000, 700, 200]      # >= 90 km/h
MEDIUM = [800, 300, 80]        # >= 50 km/h
SLOW   = [300, 120, 40]        # town
MIN_GAP_TICKS = 16             # 4 s at the 250 ms tick, matching MIN_GAP_MS

ORDINALS = ["", "first", "second", "third", "fourth", "fifth",
            "sixth", "seventh", "eighth", "ninth", "tenth"]


def spoken_distance(m):
    if m >= 1000 and m % 1000 == 0:
        return "%d kilometre%s" % (m // 1000, "" if m == 1000 else "s")
    if m >= 1000:
        return "%.1f kilometres" % (m / 1000.0)
    if m >= 100:
        return "%d metres" % ((m // 100) * 100)
    return "%d metres" % m


def instruction_for(man, exit_no, street):
    onto = (" onto %s" % street) if street and street.strip() else ""
    table = {
        LEFT: "turn left", RIGHT: "turn right",
        SLIGHT_LEFT: "bear left", SLIGHT_RIGHT: "bear right",
        SHARP_LEFT: "turn sharply left", SHARP_RIGHT: "turn sharply right",
        MERGE_LEFT: "merge left", MERGE_RIGHT: "merge right",
        FORK_LEFT: "keep left at the fork", FORK_RIGHT: "keep right at the fork",
        KEEP_LEFT: "keep left", KEEP_RIGHT: "keep right",
        RAMP_LEFT: "take the ramp on the left",
        RAMP_RIGHT: "take the ramp on the right",
        DEPART: "start off",
    }
    if man == UTURN:
        return "make a U-turn"
    if man == ARRIVE:
        return "you have arrived"
    if man == ROUNDABOUT:
        n = ORDINALS[exit_no] if 1 <= exit_no <= 10 else None
        return ("at the roundabout, take the %s exit%s" % (n, onto)) if n \
               else ("at the roundabout, follow the route%s" % onto)
    return table.get(man, "continue straight") + onto


class Voice:
    def __init__(self):
        self.spoken = {}          # maneuver key -> highest stage said
        self.arrival_said = False
        self.last_spoke_tick = -999
        self.log = []             # (along, key, stage, text)

    def on_frame(self, along, f, key, tick=0):
        if f["flags"] & F_ARRIVED:
            if not self.arrival_said:
                self.arrival_said = True
                self.log.append((along, None, 99, "You have arrived."))
            return
        self.arrival_said = False

        if key is None or f["dist"] <= 0:
            return
        thresholds = FAST if f["speed"] >= 90 else (MEDIUM if f["speed"] >= 50 else SLOW)

        done = self.spoken.get(key, 0)
        stage = done
        for i in range(done, len(thresholds)):
            if f["dist"] <= thresholds[i]:
                stage = i + 1
        if stage == done:
            return
        self.spoken[key] = stage

        is_final = stage >= len(thresholds)
        # Arrival is announced by the arrived flag, not as a maneuver.
        if f["man"] == ARRIVE and is_final:
            return
        if not is_final and tick - self.last_spoke_tick < MIN_GAP_TICKS:
            return

        if f["man"] == ARRIVE:
            ins = "you will arrive at your destination"
        else:
            ins = instruction_for(f["man"], f["ex"], f["street"])
        if is_final:
            text = "%s now." % ins
        else:
            text = "In %s, %s." % (spoken_distance(thresholds[stage - 1]), ins)
        self.last_spoke_tick = tick
        self.log.append((along, key, stage, text))


def run(route):
    v = Voice()
    tick = -1
    for along, f, tr in drive(route):
        tick += 1
        nxt = tr.route["maneuvers"]
        # the tracker's own choice of next maneuver, keyed by distance along
        key = None
        for m in nxt:
            if m["along"] > tr.along + 8.0:
                key = int(m["along"])
                break
        v.on_frame(along, f, key, tick)
    return v


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--transcript", action="store_true")
    args = ap.parse_args()

    route = build_route()
    v = run(route)

    if args.transcript or not args.check:
        print("what you would actually hear on the demo drive:\n")
        for along, key, stage, text in v.log:
            print("  %6.0f m   %s" % (along, text))
        print()
        if not args.check:
            return 0

    fails = []
    def check(c, m):
        if not c:
            fails.append(m)

    print("1. every maneuver on the route gets announced")
    keys = {int(m["along"]) for m in route["maneuvers"] if m["along"] > 8.0}
    said = {k for _, k, _, _ in v.log if k is not None}
    missing = keys - said
    check(not missing, "maneuvers never announced: %s" % sorted(missing))
    print("    %d of %d maneuvers announced" % (len(said & keys), len(keys)))

    print("2. no maneuver is announced more than three times")
    counts = {}
    for _, k, _, _ in v.log:
        if k is not None:
            counts[k] = counts.get(k, 0) + 1
    worst = max(counts.values()) if counts else 0
    check(worst <= 3, "a maneuver was announced %d times" % worst)
    print("    most announcements for one maneuver: %d" % worst)

    print("3. stages only ever increase, and never repeat")
    seen = {}
    bad = 0
    for _, k, stage, _ in v.log:
        if k is None:
            continue
        prev = seen.get(k, 0)
        if stage <= prev:
            bad += 1
        seen[k] = stage
    check(bad == 0, "%d repeated or out-of-order stages" % bad)
    print("    out-of-order announcements: %d" % bad)

    print("4. the final call lands close enough to be useful")
    finals = {}
    for m in route["maneuvers"]:
        k = int(m["along"])
        for along, kk, stage, text in v.log:
            if kk == k and text.endswith("now."):
                finals[k] = m["along"] - along
    check(len(finals) > 0, "some 'now' announcements fired")
    if finals:
        worst_gap = max(finals.values())
        # the deepest threshold is 200 m, on the motorway
        check(worst_gap <= 205, "a 'now' fired %.0f m out" % worst_gap)
        print("    'now' fired between %.0f m and %.0f m before the turn"
              % (min(finals.values()), worst_gap))

    print("5. announcements are spaced out, not stacked")
    stacked = 0
    for i in range(1, len(v.log)):
        if v.log[i][0] - v.log[i - 1][0] < 15.0:      # two calls within 15 m
            stacked += 1
    check(stacked == 0, "%d announcements landed on top of each other" % stacked)
    print("    announcements less than 15 m apart: %d" % stacked)

    print("6. arrival is announced exactly once")
    arrivals = [t for _, k, s, t in v.log if s == 99]
    check(len(arrivals) == 1, "arrival announced %d times" % len(arrivals))
    print("    %d arrival announcement(s)" % len(arrivals))

    print("7. the phrasing is something a person would say")
    samples = [
        (ROUNDABOUT, 2, "Avenue de Tervueren"),
        (RAMP_RIGHT, 0, "Exit 22"),
        (UTURN, 0, "Rue Neuve"),
        (LEFT, 0, ""),
    ]
    for man, ex, st in samples:
        s = instruction_for(man, ex, st)
        check(s and not s.startswith(" ") and "  " not in s, "clean phrasing: %r" % s)
        print("    %r" % s)
    check(spoken_distance(2000) == "2 kilometres", "2000 m reads as '2 kilometres'")
    check(spoken_distance(700) == "700 metres", "700 m reads as '700 metres'")
    check(spoken_distance(1500) == "1.5 kilometres", "1500 m reads as '1.5 kilometres'")

    print("\ntotal announcements on a 10 km drive: %d" % len(v.log))
    check(len(v.log) <= 30, "not chatty (%d announcements)" % len(v.log))

    if fails:
        print("\n%d CHECK(s) FAILED:" % len(fails))
        for f in fails:
            print("  -", f)
        return 1
    print("\nall checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
