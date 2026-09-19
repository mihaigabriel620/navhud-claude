# Speed cameras

## What the app does, and why it changes at the border

Camera-warning law is not the same across Europe, and on a Belgium-to-Romania
run it changes eight times. The app reverse-geocodes your position to a country
once every 25 km of travel and applies that country's rule automatically.

| Mode | Behaviour | Where |
|---|---|---|
| `EXACT` | Announces the camera and counts down the distance | Belgium and most of Europe |
| `ZONE` | Announces a "danger zone" over a stretch, never a position | France |
| `OFF` | No alerts at all | Germany, Switzerland |

**The user's setting can only tighten this, never loosen it.** Asking for exact
positions while in Germany gets you no alerts; asking for them in France gets
you danger zones. `CountryRules.effective()` enforces that, and it is tested.

## Sources

- **Belgium — permitted.** Article 62bis of the traffic code bans equipment that
  detects or jams enforcement devices. GPS apps and databases like Waze and
  Coyote are explicitly outside that ban because they replay known positions
  rather than detecting anything.
- **France — danger zones only.** Since the 2011 agreement between the state and
  the navigation industry, apps may show a zone covering a stretch of road but
  not a precise camera position.
- **Germany — not permitted while driving** (§23 StVO).
- **Switzerland — not permitted.**

Countries not on that list default to `ZONE`: it still warns you that
enforcement is likely without publishing a position, which is the behaviour that
is lawful in the strictest country we know of that permits anything at all.

This is a best effort from public sources and is **not legal advice**. If you
are about to drive somewhere not listed, check the local rule — the setting is
there so you can turn it down.

## Where the data comes from

OpenStreetMap, via the Overpass API. Waze's and TomTom's databases are theirs;
OSM is the only source a DIY build can use.

The query hugs the route rather than using a bounding box — a Brussels-to-
Bucharest box would be most of Europe — sampling every 1.5 km with a 250 m
`around` radius:

```
[out:json][timeout:60];
(
  node(around:250,<lat>,<lon>,…)["highway"="speed_camera"];
  node(around:250,<lat>,<lon>,…)["enforcement"="maxspeed"];
);
out body;
```

Each result is then projected onto the route polyline and dropped if it is more
than **15 m** off it (`MATCH_TOLERANCE_M`), which removes cameras on the
parallel service road. 28 m was too generous — it let the camera watching the
road *parallel* to yours snap onto your route and announce itself.

Distance alone is not enough, so there is a second test. `onOurRoad()` asks
whether some *other* road in the loaded OSM network passes closer to the camera
than the road carrying our route does. The comparison is way-against-way: a
routed polyline is a generalisation and sits 5–12 m off the tarmac it follows,
so comparing "distance to the route line" against "distance to any OSM way"
made every camera's own carriageway look nearer and threw away cameras that
were genuinely ours. A short window of route vertices around the camera is used
to work out which ways carry the route, and those are excluded.

Free drive does the same two tests against the road you are matched to
(`CAMERA_ON_ROAD_M = 18 m`, plus the nearest-other-road check). With no matched
road it falls back to how far the camera sits off your current track — stricter
than the road test, not looser, because it cannot follow a bend. And with no
heading at all it says nothing, rather than warning about a camera behind you.

Where OSM tags `direction`, cameras facing the other carriageway are skipped —
untagged ones are always kept, because a missed warning is worse than a spurious
one. A pair of cameras a few metres apart with *opposing* directions is the
dual-carriageway pair, one per direction, and is deliberately not collapsed.

Coverage is good in western Europe and patchier further east. **This is an
assist, not a guarantee.**

## Tags read

| Tag | Use |
|---|---|
| `highway=speed_camera` | the camera itself |
| `enforcement=maxspeed` | enforcement nodes not tagged as cameras |
| `maxspeed` | the enforced limit, `50`, `50 km/h` and `30 mph` all parsed |
| `direction` | degrees or a compass point, used to skip the other carriageway |
| `camera:type=section` | average-speed check, announced differently |

---

## How reliable is the source, really? (v1.9 review)

The honest answer, with numbers, because "it uses OpenStreetMap" is not one.

### What OSM actually has

Counts from [taginfo](https://taginfo.openstreetmap.org/tags/highway=speed_camera)
and Geofabrik per-country extracts, August 2026:

| | `highway=speed_camera` | `type=enforcement` relations |
|---|---|---|
| Worldwide | 76,666 | 37,666 |
| Belgium | 1,574 | 876 |
| France | 3,856 | 4,784 |
| Germany | 5,198 | 5,702 |
| Austria | 1,399 | — |
| Hungary | 418 | — |
| Romania | **340** | 26 |

Tag completeness, measured over Overpass rather than assumed:

| tag | Belgium | France |
|---|---|---|
| `maxspeed` | 61.6 % | 63.0 % |
| `direction` | **28.4 %** | 42.5 % |
| `check_date` | 0.6 % | ~0 % |

Three real weaknesses follow from that:

1. **Direction is the big one.** Seven Belgian cameras in ten do not say which
   way they face, so the app cannot tell the camera watching your carriageway
   from the one watching the other. `facesUs()` therefore admits an untagged
   camera and relies on the road match to throw out the ones that are not on
   your road at all — which is what v1.8.1 added.
2. **Two in five have no speed limit**, so the roundel in a camera warning is
   often the road's limit rather than the enforced one.
3. **Nothing is re-surveyed.** `check_date` is essentially zero, so a
   decommissioned camera stays on the map indefinitely. OSM lists 3,918 French
   cameras against the ministry's official 3,309 — about 18 % more, and not all
   of that is municipal cameras missing from the state list.

### What v1.9 changed

- The Overpass query now also pulls **`type=enforcement` relations** and their
  member nodes. Section control is *supposed* to be mapped as a relation, and
  querying only nodes was missing 876 of them in Belgium alone.
- `highway=speed_display` — the "YOUR SPEED 48" feedback signs, 5,364 of them —
  is explicitly rejected. Warning about one teaches you to ignore the warnings
  that matter.
- France's danger zones are now **300 m / 2 km / 4 km** by road type, per the
  2011 state–industry agreement, instead of a flat 2 km; and the distance shown
  inside a zone is deliberately rounded so it cannot be used to locate the
  camera.

### Sources that are better than OSM, and not yet wired in

- **Brussels — official, CC0, and it works.** Bruxelles Mobilité publishes
  132 fixed cameras, 15 municipal, and 14 section-control stretches as WFS:
  `https://data.mobility.brussels/geoserver/bm_security/wfs?service=WFS&version=2.0.0&request=GetFeature&outputFormat=application/json&srsName=EPSG:4326&typeName=bm_security:speedcameras`
  ([metadata](https://data.mobility.brussels/info/472bf315-4669-4397-afb9-ccbcb174e664)).
  Caveat: the `direction` fields are empty on all 132 records.
- **France — official, Licence Ouverte v2.0, ministry-published**, 3,309 radars
  with type and enforced limit:
  [data.gouv.fr](https://www.data.gouv.fr/datasets/liste-des-radars-fixes-en-france).
  Semicolon-delimited, **latin-1 encoded**, no road name or direction.
- **Flanders — trajectcontrole**, 226 points, but published only as an
  undocumented Datawrapper CSV behind the AWV site, with no licence stated and
  covering only AWV-placed installations.
- **SCDB.info** — €9.95 one-off including 12 months of updates, 114,000 cameras
  across 113 countries, daily. The only realistic *licensed* option.

There is **no Belgian federal dataset**, none in Wallonia, and none in Romania —
for Romania, OSM's 340 nodes are all there is.

### Why not Waze

See `docs/WAZE.md`. Short version: no API, official or otherwise, exposes fixed
camera positions.
