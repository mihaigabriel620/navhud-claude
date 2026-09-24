# Other APIs worth considering (2026 survey)

You already run Mapbox Directions (traffic, maxspeed) + Overpass (cameras,
speed limits, level crossings) + OpenFreeMap/MapLibre (tiles) + Mapbox
geocoding. This is a look at what else is out there, what's actually free,
and what the small print says. **Speed limits and speed cameras already have
their own deep-dive docs** — see `PROVIDERS.md` and `CAMERAS.md` — this file
covers routing, traffic, search, tiles, and offline routing, then ranks what's
worth adding.

## Routing (with traffic)

| Provider | Free tier | Traffic? | Licence catch | Europe quality |
|---|---|---|---|---|
| **Mapbox Directions** (current) | 100k req/mo | Yes, `driving-traffic` profile | **Must not export/cache/store Navigation API results** — session use only | Very good BE–RO |
| TomTom Routing | 2,500 non-tile req/day (~75k/mo), shared across all TomTom APIs | Via separate Traffic API | Downloaded map data capped at 90 days on-device | Good, own survey data |
| HERE Routing | ~30k transactions/mo, then $0.75–2.50/1k | Yes, blends live traffic into ETA | Prices rose ~6% Apr 2026 | Excellent, esp. outside W. Europe |
| GraphHopper Directions API | **No real free tier** (free trial only, needs contact for pricing) | No | Engine itself (Apache-2.0) is free to self-host | Good, OSM-based |
| openrouteservice (HeiGIT) | 2,500 req/day / 40,000 mo, 40 concurrent | No | CC-BY 4.0 + OSM attribution required | Good, OSM-based |
| Valhalla @ FOSSGIS demo | Free, fair-use rate limits | No | Non-production only; must send `X-Client-Id` header | Good, OSM-based |
| OSRM demo server | Free, 1 req/s | No | "Reasonable, non-commercial" only, no uptime guarantee, ODbL attribution | Good, OSM-based |
| Stadia Maps | 200k credits/mo | No routing product | Free tier is **non-commercial only** | n/a |

**Read this one twice:** Mapbox's Navigation API terms explicitly forbid
exporting, downloading, caching, or storing results from Directions requests.
I checked `OfflineRoutes.kt` and `AreaCache.kt` — the offline corridor cache
stores MapLibre *map tiles* and raw *Overpass* JSON, never a Mapbox route
response, so you're compliant today. Just don't add a "save this route for
later" feature that persists a Directions API response to disk — that would
cross the line.

## Live traffic / incidents

| Source | Free tier | Notes |
|---|---|---|
| TomTom Traffic API | Shares the 2,500/day non-tile pool | Flow + incidents, decent BE/DE/AT/HU coverage |
| HERE Traffic API | 5,000 transactions/mo, then $2.50/1k | Flow + incidents |
| Mapbox | Included in `driving-traffic` congestion annotations | No separate incidents endpoint at this tier |
| **Vlaams Verkeerscentrum DATEX II** | **Free, no key**, reuse under a free "model licence" | Flemish motorways + some regional roads: incidents, roadworks, closures |
| Wallonia / Brussels | Thin | SPW mostly RDS-TMC/VMS, no rich open feed; Brussels has camera data (see CAMERAS.md) but not much else |

Flanders' feed (via [transportdata.be](https://transportdata.be/), Belgium's
national access point) is genuinely free and official — real value since
that's the region you actually drive daily. There's no equivalent
pan-European open feed; every country's DATEX II access point differs wildly
in richness, so chasing DE/AT/HU/RO open feeds isn't worth it next to what
Mapbox/TomTom already give you.

## Search / geocoding

| Provider | Free tier | Catch |
|---|---|---|
| Mapbox Geocoding (current) | 100k/mo | Only for **temporary** (not-stored) results; storing needs a paid plan |
| **HERE Geocoding & Search** | **250,000/mo** — biggest of the bunch, autocomplete included | developer.here.com portal being retired March 2026, migrate to newer HERE portal |
| TomTom Search | Shares the 2,500/day pool | Tight quota |
| Nominatim (OSM public) | Free | Policy explicitly **bans "heavy use"**; 1 req/s; must self-host beyond light per-user search |
| Photon (komoot public) | Free, fair-use, no hard cap documented | Same idea as Nominatim — fine for occasional lookups, self-host if it grows |

## Map tiles / offline maps

| Option | Free tier | Catch |
|---|---|---|
| **OpenFreeMap** (current) | Unlimited, no key | Donation-funded, not a contractual SLA — but fully open, self-hostable if it ever disappears |
| MapTiler | 100k req/mo, 5GB storage | **Non-commercial only**; real offline packages ("MapTiler Data") cost $2,500+/yr |
| Stadia Maps | 200k credits/mo | **Non-commercial only** |
| **Protomaps / PMTiles** | Free, open-source tooling (`go-pmtiles`) | Not a hosted service — you generate your own regional `.pmtiles` extract and bundle/download it |

MapTiler and Stadia's "generous" free tiers are irrelevant here — OpenFreeMap
already gives the same thing with no commercial restriction. Protomaps is the
one worth a look: it lets you bake a single-file BE + Belgium→Romania
corridor basemap that MapLibre reads straight off disk, no server at all —
complementary to, not a replacement for, the offline MapLibre regions
`OfflineRoutes.kt` already downloads.

## Offline routing on the device

GraphHopper (Apache-2.0, Java) and Valhalla (MIT, C++) can both compute
routes fully offline from a downloaded OSM extract — GraphHopper claims
Germany-wide routing in seconds on ~32MB RAM via Contraction Hierarchies.
Neither has a polished, officially-maintained Android SDK today; you'd be
building your own JVM/JNI integration, generating and shipping a routing
graph, and keeping it updated. Organic Maps proves the *category* (crowd-
sourced OSM + fully offline turn-by-turn works fine on a phone) but uses its
own in-house engine, not a reusable Valhalla/GraphHopper Android build — so
it's evidence the idea works, not a shortcut to implementing it.

This is a multi-week project, not a config change. It only pays off if trips
start hitting hours of genuinely zero signal — the existing rolling
MapLibre-region + Overpass `AreaCache` already covers the realistic "brief
dead zone" case cheaply.

## Recommendation for NavHUD

Ranked by value for the effort, with risk flagged:

1. **Keep Mapbox Directions as-is** — best free-tier fit already covered in
   `PROVIDERS.md`. Only action: never persist a Directions response to disk
   (confirmed you don't). *Effort: none. Risk: none.*
2. **Add HERE Geocoding & Search as a backup geocoder** — 250k/mo free is the
   largest surveyed, autocomplete included; cheap insurance if Mapbox's or
   Nominatim's limits ever bite. *Effort: low (one more provider adapter).
   Risk: low.*
3. **Wire in Flanders' free DATEX II feed** for motorway incidents/roadworks
   on the roads you drive most — official, free, no key. *Effort: medium
   (DATEX II XML parsing). Risk: low, payoff is Flanders-only.*
4. **Generate a Protomaps/PMTiles regional basemap** (BE + corridor to RO) to
   complement the current offline tile cache, removing dependence on
   OpenFreeMap's uptime for the parts of the map you always need.
   *Effort: medium. Risk: low.*
5. **Skip** GraphHopper Directions, TomTom Routing, and HERE Routing as
   *primary* routers — smaller free tiers than Mapbox and none match its
   one-call route+maxspeed convenience. *Not worth switching.*
6. **Skip** on-device offline routing engines for now — real engineering
   effort, and today's dead-zone handling is already adequate. Revisit only
   if actual trips hit multi-hour connectivity gaps.
7. **Skip** MapTiler and Stadia Maps — their free tiers are non-commercial
   only and add nothing OpenFreeMap doesn't already give you unrestricted.
8. **Treat all public demo servers** (OSRM, Valhalla@FOSSGIS, Nominatim,
   Photon) as opportunistic fallbacks only — every one of them says "fair
   use, no uptime guarantee, may be withdrawn." Fine as a backup path, never
   as your only path.

## Sources

- [Mapbox Directions API docs](https://docs.mapbox.com/api/navigation/directions/) · [Mapbox pricing](https://www.mapbox.com/pricing) · [Mapbox Product Terms (caching/export restrictions)](https://cdn.prod.website-files.com/609ed46055e27a02ffc0749b/68dddd2815cb3d82685f0096_Mapbox%20Product%20Terms%20(October%201,%202025).pdf) · [Mapbox API caching](https://docs.mapbox.com/help/dive-deeper/api-caching/) · [Temporary vs permanent geocoding](https://docs.mapbox.com/help/dive-deeper/understand-temporary-vs-permanent-geocoding/)
- [TomTom pricing](https://docs.tomtom.com/pricing) · [TomTom developer terms](https://developer.tomtom.com/terms-and-conditions)
- [HERE pricing](https://placematic.com/here-location-services/here-pricing/) · [HERE Geocoding & Search](https://www.here.com/platform/geocoding)
- [GraphHopper Directions API](https://www.graphhopper.com/) · [GraphHopper GitHub](https://github.com/graphhopper/graphhopper)
- [openrouteservice ToS](https://openrouteservice.org/terms-of-service/) · [openrouteservice pricing](https://apispine.com/openrouteserviceorg/pricing)
- [OSRM API usage policy](https://github.com/Project-OSRM/osrm-backend/wiki/Api-usage-policy) · [Valhalla FOSSGIS demo](https://github.com/valhalla/valhalla/discussions/3373)
- [Nominatim usage policy](https://operations.osmfoundation.org/policies/nominatim/) · [Photon README](https://github.com/komoot/photon/blob/master/README.md)
- [Vlaams Verkeerscentrum open data](https://www.verkeerscentrum.be/data) · [transportdata.be (Belgium NAP)](https://transportdata.be/)
- [OpenFreeMap](https://openfreemap.org/) · [MapTiler pricing](https://www.maptiler.com/cloud/pricing/) · [Stadia Maps limits](https://docs.stadiamaps.com/limits/) · [Protomaps PMTiles docs](https://docs.protomaps.com/pmtiles/maplibre)
- [Organic Maps](https://github.com/organicmaps/organicmaps) · [Valhalla docs](https://valhalla.github.io/valhalla/)
