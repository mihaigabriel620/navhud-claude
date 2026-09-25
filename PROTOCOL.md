# NavHUD serial protocol v3

Plain ASCII, NMEA-style, so you can debug the whole thing with a serial monitor
and read it with your own eyes. 115200 baud, 8N1, no flow control.

## Frames

### `$HUD` — the display frame (phone → Arduino, 4 Hz)

```
$HUD,<spd>,<lim>,<man>,<rbx>,<dist>,<eta>,<rem>,<flags>,<street>*<CS>\r\n
```

| # | Field    | Type   | Meaning                                                        |
|---|----------|--------|----------------------------------------------------------------|
| 1 | `spd`    | int    | Current speed, km/h. `-1` = no GPS fix.                        |
| 2 | `lim`    | int    | Speed limit, km/h. `0` = unknown, `-1` = unlimited (Autobahn). |
| 3 | `man`    | int    | Maneuver code, see table below.                                |
| 4 | `rbx`    | int    | Roundabout exit number, `0` when not a roundabout.             |
| 5 | `dist`   | int    | Metres to the maneuver.                                        |
| 6 | `eta`    | int    | Seconds remaining to destination.                              |
| 7 | `rem`    | int    | Metres remaining to destination.                               |
| 8 | `flags`  | int    | Bitfield, see below.                                           |
| 9 | `street` | string | Road you end up on. Max 20 chars, ASCII, no `,` `*` `$`.       |

### `$RAB` — where the roundabout exit really points (phone → Arduino, 4 Hz)

```
$RAB,<exit>,<bearing>*<CS>\r\n
```

Sent right after every `$HUD` frame whose maneuver is a roundabout, when the
phone knows the angle. `exit` is 1..12 and must match the `$HUD` frame's `rbx`;
`bearing` is degrees from the road you come in on, −180..180: 0 straight on,
positive to the right, ±180 a U-turn. The phone takes it from the banner's
`degrees`, else from the exit road's own bearing in the route's intersections.
Which way round the ring runs comes from flags bit 7 (below).

Its own frame because nothing can follow the street name on `$HUD`. The exit
number rides along, and the board stamps each `$RAB` with the distance to the
maneuver as it arrives: an angle is only drawn while the exit matches and the
distance has not grown since (30 m of slack) — so the last roundabout's angle
is never aimed at the next one, even when both are "exit 2". An angle outside
±180 is refused, not clamped. With no usable `$RAB` the board falls back to a
guess from the exit number (1..7, the same table as the app's card), and with
no exit number to a bare ring.

The board draws it as a thick ring: the part you drive — in, round, out —
bright, the rest dim, the exit number in the middle and the arrow at the
angle. Firmware before 2.7 ignores the frame.

### `$CAR` — the car's own data, Arduino → phone (2 Hz, while the phone is up)

```
$CAR,<kmh>,<rpm>,<ps>,<peak>,<volts>,<ign>[,<voltsRaw>[,<coolantC>]]*<CS>
```

From the E60's K-CAN (listen-only: the board never transmits on the car's bus).
`kmh` is **`-1` once the bus's speed frame is stale** (firmware 2.8; 2.7 repeated
the last speed); the app refuses such a line and falls back to GPS. `ign` is 1
with the engine running. `voltsRaw` is the undivided battery count, `coolantC`
is −99 until the coolant frame is heard. The app reads the first six fields
positionally and ignores the rest. `$CANDROP,<overflows>,<frames>` is sent
only when the receive-overflow count changes.

### `$MAG` — compass heading, Arduino → phone (optional, 5 Hz)

```
$MAG,<headingDeg>,<calibrating>,<samples>,<fieldUt>,<spreadPct>,<mount>*<CS>
```

`headingDeg` is **magnetic**, not true. Declination depends on where you are,
which the phone knows and the board does not, so the correction is applied on
the phone.

`fieldUt` and `spreadPct` are the health check: once the hard-iron offset is
right, the field magnitude reads the same at every heading, so a spread that
will not go away means the calibration is stale or something magnetic has moved
in beside the sensor.

**`mount` is the field the app acts on**: anything but `1` and it ignores the
heading and uses the phone's own sensors. Firmware 2.x and 3.x send `1` whenever
the chip answers -- the heading is then the car's, aligned by the `north` offset
(typed command below), which defaults to "the box points forwards". Field 5
(`spreadPct`) is always 0.

Since firmware 3.0 the heading is **tilt-compensated** when an MPU-6050 is
fitted: it stays right with the box tilted on the dash or the car on a hill.
Without one it is the flat compass of 2.x, right only with the box level. And
`fieldUt` is the whole field (about 49 uT in Belgium); after a `spin` the 2.x
flat compass reported only its horizontal part, about 20.

Absent on firmware that predates the field, which the phone reads as `1` so an
older board keeps behaving as it did.

### Typed commands, board ← a human

Everything above is the app's half of the wire. Anything arriving that does
**not** begin with `$` is treated as somebody typing in a serial monitor, and
the replies are plain text — which the app's parser ignores, because it ignores
every line that does not start with `$`.

| Command | Does |
|---|---|
| `help` | lists these |
| `status` | everything the board currently knows: link, bus, sensors, calibration |
| `wipe` | rebuild the screen in four stages, to find where a mark comes from |
| `spin` | start the compass calibration: drive a slow circle, or turn the box round on the dash |
| `spin stop` | finish it: kept if both axes swept far enough, otherwise it says why and keeps going |
| `north <deg>` | "the car is pointing this way now" (0-359): sets the compass's north offset |
| `forget` | erase the compass calibration |

A kept calibration, `north` and `forget` do not write to flash where they are
typed. A calibration or `north` queued after a `forget` cancels it. `EEPROM.commit()`
on an ESP8266 erases and rewrites a whole 4 KB sector with interrupts off — tens
of milliseconds, and up to 400 ms by the datasheet — during which nothing fills
the UART receive buffer. A `$CAM` or `$LANE` clearing frame lost in that window
is lost for good, because both are edge-triggered. So the write is deferred to
the next standstill, or happens immediately when there is no CAN bus at all,
which is the bench case.

### `$CAM` — speed camera alert (phone → Arduino, 4 Hz while an alert is up)

```
$CAM,<kind>,<distance>,<limit>*<CS>
```

Sent with every frame while an alert is up (the distance counts down), and
`$CAM,0,0,0` once when it ends.
`kind`: 0 none, 1 fixed, 2 average-speed, 3 traffic-light, 4 danger zone.
`distance` is metres, and is always 0 for kind 4 — a danger zone deliberately
has no position (see docs/CAMERAS.md). `limit` is the enforced speed, 0 when the
map does not have it.

### `$LANE` — lane guidance (phone → Arduino, 4 Hz while it applies)

```
$LANE,<count>,<activeMask>,<d0>,…,<dN-1>[,<c0>,…,<cN-1>]*<CS>
```

`count` lanes, left to right as you see them through the windscreen. Bit *i* of
`activeMask` is set when lane *i* can be used for the upcoming maneuver. Each
`d` is a bitmask of every movement that lane allows: 1 u-turn, 2 sharp left,
4 left, 8 slight left, 16 straight, 32 slight right, 64 right, 128 sharp right.
`$LANE,0,0` clears the strip. Since app 1.32 the lanes go out with every frame
while they apply, like `$CAM`, so a board that dropped them (it forgets both
after two seconds with no frame) gets them back.

Each `c` is the **one** movement to follow in that lane — a single bit out of
that lane's `d`, or 0 when the lane is not yours or the router would not say.
This is the field that makes a shared lane readable: a lane tagged
`straight | right` with `c = right` means *be in this lane and take the exit*,
where two equally bright arrows meant nothing. The firmware drops a `c` bit the
matching `d` does not allow rather than drawing an arrow nobody may follow.

The `c` list is appended rather than interleaved, so it is optional: a firmware
built against protocol v2 reads exactly `3 + count` fields and ignores the tail,
and a phone running older software sends no tail at all. Both combinations work.

A frame claiming more lanes than it carries is rejected rather than read past.

### `$IMU` — turn rate, Arduino → phone (optional, 20 Hz)

```
$IMU,<yawDeg>,<pitchDeg>,<rollDeg>,<rateDps>*<CS>
```

Sent by firmware 3.0 and later when an MPU-6050 is fitted, once the gyro's
bias has been learned — two seconds parked, which is normally just after boot.
Firmware 2.x had no gyroscope and never sent it; the app then uses the phone's
own gyroscope and GPS.

`rateDps` is the car's turn rate about the true vertical, degrees per second,
**clockwise positive** like a bearing — about the vertical rather than the
chip's own Z, so a box mounted at an angle still reports the car's turn. It is
the average over the interval since the previous `$IMU`, so rate × interval is
exactly the heading change over it, which is how the app uses it; the lines
come on a fixed 50 ms schedule from reads every 20-25 ms, so single intervals
vary a little and average 50 ms. `yawDeg` is the compass heading (0 without a
compass); `pitchDeg` (nose up positive) and `rollDeg` (right side down
positive) are the box's own. The app reads only field 5.

### `$PING` — heartbeat (phone → Arduino, 1 Hz)

```
$PING*<CS>\r\n
```

Sent even when there is no active route. If the Arduino hears nothing for
2 seconds it drops everything the phone told it and shows the car-only screen
(a board with the CAN module) or "NO LINK" — never a stale speed limit. When
the link comes back, nothing from before is shown until the phone resends it.

### `$HELLO` — Arduino → phone, once on boot

```
$HELLO,NAVHUD,3*<CS>\r\n
```

Lets the app confirm it opened the right serial device and not, say, a 3D
printer. The trailing number is the protocol version.

Note that an ESP8266's boot ROM writes its own startup message on the same pins
at 74880 baud, so the first thing the phone sees after a reset is a short burst
of garbage. Every sentence is framed and checksummed precisely so that this does
not matter: the parser resynchronises on the next `$`.

## Screen geometry

The panel lies flat on the dash and is read as a reflection in the windscreen,
which flips it left-to-right, and a dashboard is rarely level with the screen
sitting on it. Both corrections live in the display's flash, so they survive a
power cycle and a different phone, and both are set from the app.

Corner offsets are in the **driver's view**, in pixels of a 480×320 panel, in
the order top-left, top-right, bottom-right, bottom-left. Positive x is right,
positive y is down. That is the same as the layout coordinates: the panel mirror
and the windscreen reflection are flips of the same axis and cancel, so neither
side has to un-mirror anything.

### `$GEOM` — set the geometry (phone → board)

```
$GEOM,<mirrorX>,<mirrorY>,<dx0>,<dy0>,<dx1>,<dy1>,<dx2>,<dy2>,<dx3>,<dy3>*<CS>\r\n
```

Applied immediately and **not** written to flash. The app sends one on every
movement of a slider; the board coalesces repaints to no faster than one every
120 ms. Offsets are clamped to ±35 % of the screen (±168 px, ±112 px) on the way
in, before they reach an `int16_t`.

A quad that is concave or self-crossing is rejected: the board falls back to no
correction and replies `$GEOMERR,quad`. The app checks the same condition before
sending, so this should never be seen.

`mirrorX` and `mirrorY` are not applied in software. They select one of the four
landscape rotations of the panel — 1, 7, 5 and 3, being MADCTL `0x28`, `0xA8`,
`0x68` and `0xE8` — so mirroring costs nothing per frame.

### `$GEOMTEST` — alignment pattern (phone → board)

```
$GEOMTEST,1*<CS>\r\n      show the pattern
$GEOMTEST,0*<CS>\r\n      back to the drive display
```

A border, thirds, a centre cross and the four corner labels. What you actually
need while aligning is the *edges* of the projection, which the drive display
does not have, and the labels tell you at a glance whether the mirror is the
right way round. It is also a great deal cheaper to repaint than the drive
display, which matters when a finger is moving a slider.

Frames continue to arrive and continue to update the state while the pattern is
up, so dismissing it brings the drive display straight back. The board leaves
the pattern by itself after two minutes without a `$GEOM*` message, so a crashed
app cannot leave a test grid on the glass at 120 km/h.

### `$GEOMSAVE` — commit to flash (phone → board)

```
$GEOMSAVE*<CS>\r\n
```

Replies `$GEOMOK,1` when it wrote, `$GEOMOK,0` when nothing had changed, or
`$GEOMERR,flash`. Separate from `$GEOM` because an ESP8266 emulates its EEPROM
by erasing a whole 4 KB flash sector: committing on every slider movement would
spend the chip's endurance in an afternoon.

### `$GEOM?` — read it back (phone → board)

```
$GEOM?*3F\r\n
```

The board replies with a `$GEOM` carrying its current settings. The app sends
one whenever a board says `$HELLO`, because the board — not the phone — is the
record.

## Checksum

XOR of every byte strictly between `$` and `*`, printed as two uppercase hex
digits. Identical to NMEA 0183. A frame with a bad checksum is dropped silently.

## Maneuver codes

| Code | Meaning        | Code | Meaning          |
|------|----------------|------|------------------|
| 0    | none / continue| 10   | merge right      |
| 1    | turn left      | 11   | fork left        |
| 2    | turn right     | 12   | fork right       |
| 3    | slight left    | 13   | roundabout       |
| 4    | slight right   | 14   | depart           |
| 5    | sharp left     | 15   | arrive           |
| 6    | sharp right    | 16   | ramp left        |
| 7    | u-turn         | 17   | ramp right       |
| 8    | straight       | 18   | keep left        |
| 9    | merge left     | 19   | keep right       |

## Flags bitfield

| Bit | Value | Meaning                                        |
|-----|-------|------------------------------------------------|
| 0   | 1     | Over the speed limit                           |
| 1   | 2     | Off route (reroute in progress)                |
| 2   | 4     | GPS fix valid                                  |
| 3   | 8     | Arrived at destination                         |
| 4   | 16    | Speed limit is from a lower-confidence source  |
| 5   | 32    | Night mode requested (dim the backlight)       |
| 6   | 64    | An itinerary is being followed                 |
| 7   | 128   | Traffic keeps left here: roundabouts run clockwise |

Bit 7 (app 1.32, firmware 2.8) only changes which way the HUD draws a
roundabout's driven path and mirrors its exit-number guess. Firmware before
2.8 tests only the bits it knows and ignores it; an app before 1.32 leaves it
clear, which is right-hand traffic.

### Bit 6 is not optional, and the HUD acts on its absence

Set by `RouteTracker` and by nothing else. `FreeTracker` cannot set it, because
in free drive there is no route to have.

Without this bit the HUD **discards the whole route half of the frame** —
maneuver, roundabout exit, distance, ETA, remaining, street, and the off-route
and arrived flags — before anything gets to draw it. The speed, the limit and
the camera and lane warnings all survive: none of them needs an itinerary to be
true, and a speed limit is a property of the road you are on.

It exists because the alternative was an inference. The HUD used to work out
"there is a route" from "the maneuver is not `MAN_NONE`", which put a
straight-ahead arrow on the glass of a parked car the moment the app was opened
with no destination — `angleForManeuver()` returns 0 for anything it does not
recognise, and 0 degrees is "carry straight on".

Version skew fails in the safe direction. A phone that predates the bit leaves
it clear and newer firmware reads that as "no route", so the mismatch shows up
as a missing arrow rather than a wrong one. **Update both halves together**:
new firmware with an old app shows speed and limit but never a maneuver.

## Examples

```
$HUD,72,50,2,0,180,845,7300,5,RUE DE LA LOI*62
$HUD,118,-1,8,0,4200,1810,52000,4,A4*21
$HUD,31,30,13,3,90,1204,9100,4,N9*00
$HUD,-1,0,0,0,0,0,0,0,*59
$PING*10
$HELLO,NAVHUD,3*71
$IMU,182.4,1.2,-0.4,15.30*73
$GEOM,1,0,0,0,0,0,0,0,0,0*01
$GEOMSAVE*01
$GEOM?*3F
```

(These exact checksums are regression-tested in `arduino/test/test_protocol.cpp`.)

Line 1: doing 72 in a 50, turn right in 180 m onto Rue de la Loi, over the limit
(bit 0) with a valid fix (bit 4).
Line 3: take the 3rd exit of a roundabout in 90 m.
Line 4: no fix, no route — the Arduino shows the idle screen.
