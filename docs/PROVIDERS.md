# Where the speed limits come from

You asked for the most accurate source. There isn't a single winner, so here is
the honest comparison and why the default is what it is.

## The shortlist

| | Route + limits in one call | Speed limit source | Free tier | Verdict |
|---|---|---|---|---|
| **Mapbox** | **Yes** — `annotations=maxspeed` | OSM `maxspeed` + Mapbox probe data | 100k Directions + 100k Geocoding requests/month | **Default** |
| **HERE** | No — Routing v8 then Route Matching | HERE's own surveyed map | Freemium base plan | Best raw data, two calls |
| **TomTom** | No — Routing then Snap to Roads | TomTom's own surveyed map | 20k routing, 2.5k snap-to-roads/month | Good data, tightest quota |
| **Google** | — | Google's own map | — | **Ruled out** |
| **OSM self-host** | Depends | OSM `maxspeed` | Free, you run it | Best if you like sysadmin |

**Google is out on a technicality, not on quality.** The Roads API speed limits
endpoint is "available to all customers with an Asset Tracking license" — a
commercial fleet product. A hobby project cannot get at it. Worth knowing so you
don't spend an evening trying.

## Why Mapbox is the default

Not because its map is the most accurate in the abstract — HERE and TomTom
survey their own road networks and generally have the edge, especially outside
Western Europe. Mapbox wins on something that matters more for *this* build:

**One request returns the geometry, the turn-by-turn steps, and a speed limit
for every segment of the line you are driving.** The limits are indexed to the
exact same polyline the tracker snaps you onto, so "which limit applies right
now" is an array lookup with no room for error.

With HERE or TomTom you make two calls against two different segmentations and
then reconcile them — matching a speed-limit link to a routing span. That
reconciliation is where a HUD quietly starts showing you the limit for the
service road running alongside the motorway. Solvable, but it is real work and
real failure modes, and the payoff only shows up where OSM coverage is thin.

And in Belgium, the Netherlands, Germany and France, OSM `maxspeed` coverage is
excellent — these are among the best-mapped countries on earth. For a car driven
around Brussels, Mapbox and HERE will agree the overwhelming majority of the
time. If you were building this for rural Romania or Bulgaria, I'd flip the
recommendation to HERE without hesitating.

## The accuracy that actually bites you

Here is the thing worth internalising: **for a HUD, the map's speed-limit data
is rarely the weak link. Position matching is.**

If you snap to the wrong road, perfect data gives you a perfectly wrong number.
The two places this happens constantly:

- a motorway with a parallel service road 20 m away, at different limits
- a divided highway where the two carriageways are 15 m apart

`Geo.project()` handles both, and it's worth knowing how, because it is the part
that determines whether the display is trustworthy:

1. **It projects onto segments, not vertices.** Nearest-vertex matching jumps
   ahead in a way that makes the distance-to-turn countdown stutter.
2. **It searches a window forward of the last match.** A route that doubles back
   on itself can't teleport you to the wrong pass.
3. **It penalises segments running the wrong way** relative to your GPS heading,
   up to 60 m of equivalent cost. This is what keeps you on the correct
   carriageway. `tools/geo_reference.py` test 5 demonstrates exactly this case.
4. **It re-searches the whole polyline** if nothing in the window is plausible,
   so coming out of a tunnel recovers instead of confidently lying.

The tracker then adds hold-over: if a segment has no limit data, it keeps
showing the last known one for 400 m and sets the low-confidence flag, which the
display draws as an amber ring around the roundel. Better than a value that
blinks to `--` every time you cross an unmapped junction — and honest about it.

## Swapping in HERE or TomTom

Everything provider-specific lives behind one interface:

```kotlin
interface NavProvider {
    val name: String
    fun route(from: LatLon, to: LatLon): Route
    fun geocode(query: String, near: LatLon?): LatLon?
}
```

`Route` is already flattened into what the tracker wants: one polyline, one
cumulative-distance array, one speed limit per segment, and a list of maneuvers
pinned to a distance along the line. Write a class that fills that in and change
one line in `HudService.requestRoute()`. Nothing else in the project — not the
tracker, not the protocol, not the sketch — knows who drew the map.

### HERE, if you want it

Two calls:

1. `https://router.hereapi.com/v8/routes?transportMode=car&origin=…&destination=…&return=polyline,actions,instructions,summary&spans=speedLimit,names&apiKey=…`
2. Or the Route Matching API with `attributes=SPEED_LIMITS_FCn(*)`, which
   returns a limit in km/h per road link.

Two things to budget for:

- **HERE returns speeds in metres per second** in Routing v8 spans (the Route
  Matching attribute route gives km/h). Convert, don't assume.
- **HERE polylines use "flexible polyline" encoding**, not Google's. You need
  its own decoder — varint + zigzag over a different alphabet, with a header
  carrying the precision. It is about 60 lines. Do not try to feed it to
  `Geo.decodePolyline()`; it will decode to plausible-looking nonsense in the
  middle of the ocean, which is a genuinely annoying afternoon.

### TomTom

Calculate Route for the geometry and instructions, then Snap to Roads with
`fields=…speedLimit…` for the limits. Note the free Snap to Roads allowance is
2,500 requests/month, which is the real constraint — reroutes eat into it.

## If you'd rather own the whole stack

Self-host **Valhalla** or **OSRM** on a small VPS with a `.osm.pbf` extract of
Belgium (about 500 MB). No keys, no quotas, no company changing its pricing, and
you can edit OSM yourself when you find a wrong limit — the fix shows up in your
own routing within a day of your next extract. Valhalla is the better fit here:
it exposes speed limits per edge in its trace attributes, and its API is close
enough to OSRM's that `MapboxProvider` is most of the way to being a
`ValhallaProvider`.

This is more work up front and the most satisfying version of the project.

## Sources

- [Mapbox Directions API — annotations and maxspeed](https://docs.mapbox.com/api/navigation/directions/)
- [Mapbox pricing](https://www.mapbox.com/pricing)
- [Google Roads API — Speed Limits access restriction](https://developers.google.com/maps/documentation/roads/speed-limits)
- [HERE Routing v8 — spans](https://docs.here.com/routing/docs/routing-v8-span)
- [Accessing speed limit data via HERE Location Services](https://www.adci.com/blog/accessing-speed-limit-data-on-here-location-services)
- [TomTom pricing and free tiers](https://docs.tomtom.com/pricing)
