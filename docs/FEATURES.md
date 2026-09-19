# What it does

Two pieces: an Android app that navigates, and an ESP32 display that shows the
next instruction where you can see it without looking down. They talk over one
USB cable.

## The app

### With no destination at all

Open it and drive. This is how the app spends most of its life, and everything
below works with nothing typed in:

- The **speed limit of the road you are on**, matched from OpenStreetMap road
  data fetched around the car and refreshed as you move.
- The **road's name** in a pill at the bottom.
- **Camera warnings**, same distances and same country rules as on a route.
- **Your speed**, on a dial with the limit roundel tucked into its shoulder;
  the ring fills to the limit and turns red past it.
- **Level crossings, speed bumps and toll booths** about eight seconds ahead,
  and only when they are on the road you are actually on.
- The car **snapped onto the road it is on**, never drawn inside a building.
- The HUD gets the same frames it would on a route, with the maneuver field
  empty — the display needs to know nothing about the difference.

### Routing

- Mapbox Directions with the live-traffic profile, up to three alternatives with
  the roads each one uses and the delay each is carrying.
- Turn-by-turn for every maneuver type — turns, forks, merges, ramps,
  roundabouts with the exit number, U-turns, arrival.
- Speed limits per road segment, held over short gaps in the data rather than
  blanking, and marked low-confidence when they are held.
- **Lane guidance in three states**: the movement to make in full amber, the
  other movements that lane allows dimmed, lanes that are not yours grey. It
  appears by *time* rather than distance — up to 1500 m on a motorway.
- **A junction view**: the carriageway in perspective with the slip road
  peeling off it, the signpost painted the colour that country actually uses.
  Every lane-count and exit-position combination; wider than eight lanes is
  windowed around the lane you need rather than truncated.
- Motorway exit boards — exit number and destinations — in the corner from
  3 km out, the way Waze and Sygic do it.
- **A route picker as a sheet over the map**, alternatives drawn behind it, one
  card each with the arrival time, the distance, how much worse than the best
  it is, and what is wrong with it: roadworks, a toll, a ferry, a low-emission
  zone.
- **Roadworks and closures**: a chip on the glass, one spoken warning 2 km out,
  dark red on the route line.
- **Low-emission zones** named on the route card before you set off, from
  OpenStreetMap's `boundary=low_emission_zone` areas.
- ETA and distance remaining, with the traffic delay next to it once it is worth
  noticing.
- Rerouting in about four seconds, from your current heading so it cannot open
  with a U-turn onto the other carriageway.

### The map

- MapLibre vector tiles from OpenFreeMap. **No API key, no account, no quota.**
- BMW E60 style: amber on black, 19 layers, built for glancing at.
- **One constant tilt**, moving or stopped, the way Waze holds it. It used to
  ramp with speed, which made the map heave at every set of lights. A gesture
  can shift it between 28° and 58° and no further — flat is not reachable.
- **Turns to face the route the moment it is ready**, standing still.
- Auto-zoom on speed *and* on time to the next junction, capped so a motorway
  view never becomes a street view.
- The route line coloured by live traffic, through to a dark red for a stretch
  reported closed.
- The car marker drawn **on the road** — projected onto the route, pointed along
  it, moving along the route between fixes — and **lying on the ground plane**
  like Waze's, not standing up to the camera like a sticker. Pre-stretched
  along its axis so the tilt's foreshortening lands it in correct proportion.
- Touch the map and it stops following; one tap on the follow button and it
  resumes.
- Live moving map with no route set, like Waze or Google Maps.

### When something goes wrong

- **Crash reports kept on the device.** If the app dies, the next launch shows
  the stack trace with a *Copy full report* button. Nothing is sent anywhere.
- Fix quality, heading source and camera-data age all shown in plain language on
  the driving screen, so a wrong reading can be explained rather than guessed at.

### Position

- GPS fixes filtered before use: a cell-tower fix cannot displace a live GPS
  one, a vague fix cannot replace a sharp recent one, and a jump implying
  250 km/h is thrown away.
- Heading fused from a gyroscope and GPS, so the map turns *with* the car rather
  than a beat after it. Phone gyroscope by default; the HUD's own MPU-6050 wins
  when one is fitted; plain GPS heading when there is neither.
- Segment-wise projection with a forward search window and a heading penalty —
  the thing that keeps you on the right carriageway of a divided highway.
- Fix quality shown in plain language on both screens.

### Speed cameras

- OpenStreetMap, fetched along a corridor hugging the route, refreshed on every
  route and every 30 minutes of driving. The screen says how old the data is.
- Direction-aware: a camera facing away from you is not your problem.
- Three warnings each, at distances chosen per speed band — on a motorway
  1500 / 700 / 150 m.
- **Country-aware legality.** Exact positions in Belgium, danger zones only in
  France, nothing at all in Germany and Switzerland, decided by reverse
  geocoding as you cross borders. You can ask for less than the local law
  allows; you cannot ask for more.

### Voice

- English and French, French in **Belgian** (*septante*, *quatre-vingts*,
  *nonante*) or metropolitan flavour.
- Numbers spelled out as words, which is the only way to stop a French TTS
  engine saying *quatre-vingt-dix* for 90.
- The best voice on the device chosen deliberately — right region, then quality,
  network voices preferred since the car is online.
- Announcement distances scaled by speed so every call lands the same number of
  seconds before the turn.
- Over-limit chime that repeats like Waze, then stops nagging.
- Ducks the radio properly (`USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`).

### Search

- **Matches as you type** — no Search button. Keystrokes are coalesced so the
  geocoder is asked once you pause, not once per letter.
- **Recent destinations** the moment the screen opens, with **Home** and
  **Work** shortcuts above them.
- **Voice input**, which matters when the alternative is typing an address on a
  touchscreen in a car.
- Address parser that understands how Belgians actually type an address, then
  four geocoders in sequence: Nominatim structured, Google (only if you paste a
  key), Photon, Nominatim freeform, Mapbox.
- Results deduplicated across engines, precise street addresses ranked above
  fuzzy matches.

## The HUD

- Two themes: BMW E60 (amber, matched to the original cluster) and a modern
  full-colour one. One `#define`.
- Speed, speed limit in a proper red-ringed roundel, maneuver arrow, distance to
  it, street name, distance remaining, ETA.
- Roundabout exits drawn as a roundabout, not an arrow.
- Camera warnings with distance and limit.
- Lane guidance strip.
- Night dimming, driven by the app.
- **`NO LINK` after three seconds of silence.** The display never shows a stale
  speed limit — the one failure mode that would actually be dangerous.
- Optional MPU-6050 reporting yaw rate back to the phone.
- Delta rendering: only the digits that changed are repainted, so there is no
  visible sweep.

## The protocol

Plain ASCII, NMEA-style, XOR checksum — readable in a serial monitor and typeable
by hand to test a screen state. `$HUD` for the frame, `$CAM`, `$LANE`, `$PING`,
`$HELLO`, `$IMU`. Full spec in [PROTOCOL.md](../PROTOCOL.md).

## Which screen to buy

See [SCREEN.md](SCREEN.md) — size, and why LCD rather than OLED or a projector.

See `docs/WAZE.md` for the feature-by-feature comparison with Waze, including
what is still missing and why.

## What it does not do

- **Temporary speed limits.** Closures and traffic are live from Mapbox. The
  limits are OpenStreetMap's, where a roadworks limit exists only if somebody
  mapped it. Feeds that carry them properly are commercial products; there is no
  free source.
- **Offline.** Routing, tiles and cameras all need the connection. Your head
  unit has one permanently, which is what the design assumes.
- **Guarantee camera coverage.** OSM is good in Belgium and France, thinner east
  of Vienna. It is an assist.
