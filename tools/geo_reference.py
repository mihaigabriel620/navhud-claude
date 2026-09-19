#!/usr/bin/env python3
"""Line-for-line Python port of Geo.kt, used to check the Kotlin maths.

Run:  python3 geo_reference.py
"""
import math

EARTH_R = 6371008.8
DEG = math.pi / 180.0
BACKTRACK_M = 50.0
WIDEN_IF_CROSS_OVER_M = 60.0


def decode_polyline(encoded, precision=6):
    factor = 10.0 ** precision
    out, index, lat, lng = [], 0, 0, 0
    n = len(encoded)
    while index < n:
        shift, result = 0, 0
        while True:
            b = ord(encoded[index]) - 63
            index += 1
            result |= (b & 0x1F) << shift
            shift += 5
            if b < 0x20:
                break
        lat += ~(result >> 1) if (result & 1) else (result >> 1)

        shift, result = 0, 0
        while True:
            b = ord(encoded[index]) - 63
            index += 1
            result |= (b & 0x1F) << shift
            shift += 5
            if b < 0x20:
                break
        lng += ~(result >> 1) if (result & 1) else (result >> 1)

        out.append([lat / factor, lng / factor])
    return out


def haversine(lat1, lon1, lat2, lon2):
    d_lat = (lat2 - lat1) * DEG
    d_lon = (lon2 - lon1) * DEG
    a = (math.sin(d_lat / 2) ** 2
         + math.cos(lat1 * DEG) * math.cos(lat2 * DEG) * math.sin(d_lon / 2) ** 2)
    return 2 * EARTH_R * math.atan2(math.sqrt(a), math.sqrt(1 - a))


def cumulative(pts):
    cum = [0.0] * len(pts)
    for i in range(1, len(pts)):
        cum[i] = cum[i - 1] + haversine(pts[i - 1][0], pts[i - 1][1], pts[i][0], pts[i][1])
    return cum


def bearing(lat1, lon1, lat2, lon2):
    p1, p2 = lat1 * DEG, lat2 * DEG
    dl = (lon2 - lon1) * DEG
    y = math.sin(dl) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dl)
    return (math.atan2(y, x) / DEG + 360.0) % 360.0


def bearing_delta(a, b):
    d = abs(a - b) % 360.0
    return 360.0 - d if d > 180.0 else d


def project(pts, cum, lat, lon, from_idx=0, window=400.0, heading=None, search_all=False):
    if len(pts) < 2:
        return (0, 0.0, 0.0, 0.0)
    kx = EARTH_R * math.cos(lat * DEG) * DEG
    ky = EARTH_R * DEG
    here = cum[min(from_idx, len(cum) - 1)]
    if search_all:
        start = 0
    else:
        start = min(from_idx, len(pts) - 2)
        back_limit = here - BACKTRACK_M
        while start > 0 and cum[start] > back_limit:
            start -= 1
    limit = float('inf') if search_all else here + window

    best_cost, best_i, best_t, best_cross = float('inf'), start, 0.0, float('inf')
    i = start
    while i < len(pts) - 1:
        if cum[i] > limit and best_cost < float('inf'):
            break
        ax = (pts[i][1] - lon) * kx
        ay = (pts[i][0] - lat) * ky
        bx = (pts[i + 1][1] - lon) * kx
        by = (pts[i + 1][0] - lat) * ky
        dx, dy = bx - ax, by - ay
        len2 = dx * dx + dy * dy
        t = min(1.0, max(0.0, -(ax * dx + ay * dy) / len2)) if len2 > 1e-9 else 0.0
        px, py = ax + t * dx, ay + t * dy
        cross = math.hypot(px, py)
        cost = cross
        if heading is not None and len2 > 1e-9:
            seg = bearing(pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1])
            cost += bearing_delta(seg, heading) / 180.0 * 60.0
        if cost < best_cost:
            best_cost, best_i, best_t, best_cross = cost, i, t, cross
        i += 1
    if not search_all and best_cross > WIDEN_IF_CROSS_OVER_M:
        return project(pts, cum, lat, lon, from_idx, window, heading, search_all=True)
    seg_len = cum[best_i + 1] - cum[best_i]
    return (best_i, best_t, cum[best_i] + best_t * seg_len, best_cross)


# --------------------------------------------------------------------------
if __name__ == "__main__":
    fails = 0

    def check(cond, msg):
        global fails
        if not cond:
            print("  FAIL:", msg)
            fails += 1

    print("1. canonical Google polyline test vector (precision 5)")
    got = decode_polyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
    want = [[38.5, -120.2], [40.7, -120.95], [43.252, -126.453]]
    check(len(got) == 3, "3 points")
    for g, w in zip(got, want):
        check(abs(g[0] - w[0]) < 1e-9 and abs(g[1] - w[1]) < 1e-9, f"{g} vs {w}")
    print("   ", got)

    print("2. round-trip a polyline6 string")
    def encode_signed(v):
        v = ~(v << 1) if v < 0 else (v << 1)
        s = ""
        while v >= 0x20:
            s += chr((0x20 | (v & 0x1F)) + 63)
            v >>= 5
        return s + chr(v + 63)

    src = [[50.8503, 4.3517], [50.8600, 4.3600], [50.8700, 4.3800]]
    enc, plat, plng = "", 0, 0
    for la, lo in src:
        ila, ilo = round(la * 1e6), round(lo * 1e6)
        enc += encode_signed(ila - plat) + encode_signed(ilo - plng)
        plat, plng = ila, ilo
    back = decode_polyline(enc, 6)
    for g, w in zip(back, src):
        check(abs(g[0] - w[0]) < 1e-6 and abs(g[1] - w[1]) < 1e-6, f"roundtrip {g} vs {w}")
    print("    encoded:", enc)

    print("3. haversine against a known distance")
    # Brussels Grand-Place -> Atomium, ~5.3 km
    d = haversine(50.8467, 4.3525, 50.8949, 4.3415)
    check(5200 < d < 5500, f"got {d:.0f} m")
    print(f"    {d:.1f} m")
    # 1 degree of latitude at the equator
    d1 = haversine(0.0, 0.0, 1.0, 0.0)
    check(abs(d1 - 111194.9) < 1.0, f"1 deg lat = {d1:.1f}")
    print(f"    1 deg lat = {d1:.1f} m")

    print("4. projection onto a straight north-south line")
    pts = [[50.0000, 4.0000], [50.0100, 4.0000], [50.0200, 4.0000]]
    cum = cumulative(pts)
    # a point 30 m east of the midpoint of the first segment
    east_deg = 30.0 / (EARTH_R * math.cos(50.005 * DEG) * DEG)
    i, t, along, cross = project(pts, cum, 50.0050, 4.0000 + east_deg)
    check(i == 0, f"segment 0, got {i}")
    check(abs(t - 0.5) < 0.02, f"t~0.5, got {t:.3f}")
    check(abs(cross - 30.0) < 0.5, f"cross ~30 m, got {cross:.2f}")
    check(abs(along - cum[1] * 0.5) < 5.0, f"along, got {along:.1f} vs {cum[1]*0.5:.1f}")
    print(f"    seg={i} t={t:.3f} along={along:.1f} cross={cross:.2f}")

    print("5. heading penalty picks the correct carriageway")
    #   two parallel roads 25 m apart, opposite directions, in one polyline:
    #   northbound 0->1, then a link, then southbound 3->4
    off = 25.0 / (EARTH_R * math.cos(50.005 * DEG) * DEG)
    dual = [[50.0000, 4.0000], [50.0100, 4.0000],           # northbound
            [50.0100, 4.0000 + off],                         # link
            [50.0000, 4.0000 + off]]                         # southbound
    dcum = cumulative(dual)
    # sitting between the two, heading north -> must match the northbound leg
    mid_lon = 4.0000 + off / 2
    i_n, _, _, _ = project(dual, dcum, 50.0050, mid_lon, heading=0.0, search_all=True)
    i_s, _, _, _ = project(dual, dcum, 50.0050, mid_lon, heading=180.0, search_all=True)
    check(i_n == 0, f"heading north -> segment 0, got {i_n}")
    check(i_s == 2, f"heading south -> segment 2, got {i_s}")
    print(f"    north->seg{i_n}  south->seg{i_s}")

    print("6. forward window stops the route matching an earlier pass")
    # An out-and-back on the same street, densified to ~20 m vertices the way
    # a real `overview=full` geometry is. Outbound north, return 12 m to the
    # east. Positionally the two passes are nearly identical.
    ret_off = 12.0 / (EARTH_R * math.cos(50.005 * DEG) * DEG)
    loop = [[50.0 + 0.0001 * k, 4.0] for k in range(101)]            # 0..~1110 m north
    loop += [[50.01 - 0.0001 * k, 4.0 + ret_off] for k in range(101)]  # back south
    lcum = cumulative(loop)

    i_early, _, along_early, cross_early = project(loop, lcum, 50.0050, 4.00000,
                                                   from_idx=0)
    check(i_early < 100, f"outbound pass matches an outbound segment, got {i_early}")
    check(cross_early < 2.0, f"auto-widen found the true match, cross={cross_early:.1f}")
    check(abs(along_early - 555.0) < 20.0, f"along ~555 m, got {along_early:.0f}")

    # Now pretend we are well into the return leg (index ~150) at the same place.
    i_late, _, along_late, _ = project(loop, lcum, 50.0050, 4.0 + ret_off,
                                       from_idx=150, window=400.0)
    check(i_late >= 101, f"return pass matches a return segment, got {i_late}")
    check(along_late > along_early, "distance along route keeps increasing")
    print(f"    outbound seg{i_early} @{along_early:.0f} m, "
          f"return seg{i_late} @{along_late:.0f} m")

    print("7. off-route detection distance is sane")
    pts2 = [[50.0, 4.0], [50.01, 4.0]]
    c2 = cumulative(pts2)
    far = 120.0 / (EARTH_R * math.cos(50.005 * DEG) * DEG)
    _, _, _, cross_far = project(pts2, c2, 50.005, 4.0 + far)
    check(abs(cross_far - 120.0) < 1.0, f"cross ~120 m, got {cross_far:.1f}")
    print(f"    cross = {cross_far:.1f} m")

    print("\nall checks passed" if not fails else f"\n{fails} CHECK(s) FAILED")
    raise SystemExit(1 if fails else 0)
