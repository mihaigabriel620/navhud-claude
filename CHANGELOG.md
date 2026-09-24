# Changelog

## App 1.27 — the road-test list

Everything on "bugs and features to add", fixed at the cause rather than
patched over. Firmware unchanged.

**Map and arrow**
- Snap-to-road is on again (it had been switched off by default), so the arrow
  sits on the middle of the route line, not 5–10 m beside it.
- The arrow is drawn by MapLibre's location layer, updated in the same frame as
  the camera, so it no longer lags or jitters against the map. The 30 fps cap
  that beat against the 60 Hz screen is gone.
- The arrow moves with a speed- and acceleration-limited "rubber band": no
  lurches, never backwards. In a tunnel it keeps going at the car's speed (or
  the last GPS speed) for up to 3 minutes and eases back onto the fix after.
- Above 5 km/h the arrow and map follow the road (GPS course off-road); the
  compass/gyro is only used standing still, to show which way you face.
- The route line vanishes under the arrow as you drive, Waze style.
- Tilt and pinch-zoom no longer drop follow mode; only a one-finger pan does.
- Map tiles are downloaded in 80 km chunks ahead of the car for dead zones.

**Voice and sound**
- Beeps are TTS earcons with ducking focus: music is lowered, never paused.
- One voice queue: nothing interrupts a line already playing; stale lines
  are dropped; close turns become one call ("… puis à gauche").
- "Vitesse limitée": margin +10 (≤50), +20 (70–90), +30 (≥100) km/h, held
  3 s, then 3 minutes of silence unless you drop 10 under the limit or the
  limit changes.
- Cameras: ANPR added, 35 m corridor, kept across reroutes, cached offline,
  and camera rules checked per country (e.g. none in DE/CH, danger zones in FR).

**Routing, limits, speed**
- Arrival ends the route; no more rerouting back to where you just parked.
- Reroute after 30 m off the route (or a sustained turn into a side street),
  and the new route prefers carrying on over looping back.
- Speed limits: OSM and per-country legal defaults fill the gaps in Mapbox's
  data (Belgian regions, and the countries on the way to Romania); the old
  limit no longer carries through a turn.
- App and HUD show the same speed: the car's CAN speed when valid, else GPS.
- Speed limits, cameras and crossings cached in chunks ~100 km ahead; losing
  the network never ends guidance, and a failed reroute retries quietly.

**Icons** from the supplied pack: coming in the next build.

## 2.7 — the roundabout arrow points where the exit actually is

First, a correction to what I told you: `roundaboutArt()` was never drawing a
fixed stub. It takes a bearing and rotates the whole glyph -- shaft, tip, wings,
notch -- around it. Free rotation was already there. I was wrong.

The problem was one line up. The caller did:

    roundaboutArt(cx, cy, u, rabBearingFor(exitNo), exitNo, col, numFont);

and `rabBearingFor()` was a seven-entry lookup table indexed by the exit NUMBER:

    { 100, 30, -30, -80, -120, -150, -170 }

So the arrow moved between exit numbers and never moved for a given one --
because an exit number is not an angle. On a three-exit roundabout "exit 2" is
almost always dead ahead; the table said 30 degrees. On a five-exit one it might
be 45. Always plausible, rarely right, which is the worst way for a display to
be wrong.

**The phone now sends the real bearing, on its own frame:**

    $RAB,<exit>,<bearing>

`<exit>` is 1..12 and `<bearing>` is -180..180 degrees from the road you came in
on -- 0 straight ahead, positive to the right. Mapbox gives it directly:
`bearing_after - bearing_before` on the roundabout step, normalised.

Its own frame rather than two more fields on `$HUD`, and that is forced rather
than chosen: the `$HUD` frame ends with the street name, and the street is
deliberately allowed to contain commas, so nothing can ever be appended after
it. A field inserted before it would shift every later position and break an
older phone. `$CAM` and `$LANE` already work this way.

**The exit number rides along with the angle and is checked.** Frames arrive
independently, so without it an angle measured at the last roundabout would be
aimed at the next one -- and would look exactly as authoritative as a correct
one. `rabBearing()` refuses any angle whose exit does not match the manoeuvre in
front of it, and falls back to the old table.

**A bearing outside +-180 is refused, not clamped.** A clamped corrupt digit is
still an arrow pointing somewhere with total confidence.

**And a bug that was probably half of what you were seeing.** `dashTurn_`
decided whether to repaint from the manoeuvre code and the distance only --
`rbExit` was not in its paint cache at all. Two roundabouts in a row with no
other manoeuvre between them left the FIRST one's arrow and the FIRST one's
digit on the glass for the second. Both the exit and the bearing are cached now.

Two smaller things fell out:

- `rabHasExit()` capped the arrow at exits 1..7 because that is how long the
  table was. The guard now asks the only question that matters -- do we have a
  direction -- so exit 8 gets an arrow when the phone knows where it is, and a
  bare ring when nobody does.
- The arrow is clamped to +-170 degrees. With a real bearing a roundabout that
  doubles you back can ask for exactly 180, which lays the exit arrow on top of
  the entry road and reads as one line through a circle. Ten degrees separates
  them and is invisible as an error. The old table stopped at -170 for the same
  reason.

Checked rather than assumed: the arrow sweep was measured across the whole
clamped range at 0.1 degree steps. It stays inside the clear rectangle at every
angle (x 22..108 of 0..161, y 81..166 of 66..250), with 35 px of clearance to
the distance text, and at its lowest it reaches 4 px past where the entry road
already goes. Compiles clean, 55 % flash, 45 % RAM.

**UNTIL THE APP SENDS `$RAB`, NOTHING CHANGES ON THE GLASS.** The firmware falls
back to the old table exactly as before. The app half is: read
`bearing_before`/`bearing_after` off the roundabout step, send the frame, and
use the same number in `ui/ManeuverView.kt` -- those two tables are deliberately
kept identical and were found disagreeing on five of seven exits once already,
including a sign flip where the app pointed down-left and the panel down-right.

## 2.6 — a pass over every file, and one bug that could crash the board

You asked for the bloat out and the bugs found. Two audits went over the
firmware file by file. Here is what came back, worst first.

**THE ONE THAT MATTERS: a frame could smash memory.** `mcp_can.cpp` masks the
DLC nibble to four bits and then reads that many bytes into an eight-byte array:

    m_nDlc &= MCP_DLC_MASK;                                   // 0x0F, so 0..15
    mcp2515_readRegisterS(mcp_addr+5, &(m_nDta[0]), m_nDlc);  // writes up to 15

Seven bytes past the end. On that object they land on the `SPIClass*` it uses
for every later transfer, so the next register access dereferences whatever was
written there. It also copies those fifteen bytes into our eight-byte stack
buffer.

Two ways in, and both are real. A DLC of 9 to 15 is **legal on the wire** --
the standard says a receiver must treat it as 8 -- and this is a listen-only
sniffer with no acceptance filtering, so every id on a body bus reaches that
code. And if the module loses power or a wire lifts mid-drive, MISO floats
high, the status byte reads `0xFF`, `checkReceive()` says a frame is waiting on
every call for ever, and the length reads 15 every time. That one fires within
a single loop pass.

So the drain path now reads the status register and the flagged buffer's length
itself, with raw SPI, before the library is allowed near the receive buffer. A
length above 8 is discarded and counted (`status` shows the count; it should be
zero for ever). A status of `0xFF` marks the controller gone and stops calling
into the library at all -- which also fixes a second thing: `canOk` was set once
at boot and never re-evaluated, so a controller that died mid-drive left the
backlight frozen at whatever the last key signal said.

**Speed was being timestamped with a lie.** There is no interrupt wire, so a
frame is stamped when it is READ, not when it arrived. The chip holds about
2.2 ms of this bus and a full repaint takes 185 ms, so after a repaint the
frames handed over are a tenth of a second old while the timestamp is new. On a
difference-over-time signal that is a 38 % dip followed by a 40 % overshoot,
around every screen transition, both comfortably inside the sanity limits. Past
`CAN_STALE_GAP_MS` the baseline is now dropped and re-acquired on the next
frame instead. One frame of staleness beats a wrong number.

**An RTR frame was being decoded as data.** The library packs flags into the top
of the 32-bit id -- bit 31 extended, bit 30 remote-request -- and we truncated
to 16 bits, throwing both away. A remote-request for 0x1A6 carries no data at
all and was arriving looking like a speed frame, decoded from whatever was left
in the buffer. Both flags are checked before the truncation now.

**Torque had no sanity limit.** Only the derived PS number did, and that check
is skipped entirely when rpm is stale -- in which case the bogus torque was
still committed and still marked fresh, so the theme drew it. The field reaches
+-1023 Nm on an engine that makes about 300.

**`$CANDROP` was a latch pretending to be a counter.** One unavoidable overflow
during the first repaint made it non-zero for ever, so a line went up the cable
twice a second for the rest of the drive whether or not anything was still
being dropped -- the same latch this code had just fixed in hardware, moved into
software. It now reports only when the number changes.

**A refused calibration left itself running.** Tap calibrate on and off inside a
second or two and `calFinish()` refuses, correctly -- but this path had already
told the app calibration stopped, and did not stop it. `calOn_` stayed set for
the rest of the trip, `$MAG` kept claiming to be calibrating, and a later `spin
stop` would then pass its gate using bounds swept across the whole drive.
`calAbort()` existed for exactly this and had never been called from anywhere.

**A guard that had never once fired.** `hud_pins.h` cross-checks its pin map
against TFT_eSPI's `User_Setup.h`. The sketch includes it two lines *before*
`<TFT_eSPI.h>`, so `TFT_CS` was undefined when the `#ifdef` was evaluated and
every check was silently skipped. It only ever worked in the host tests, where
the include order differs. Moved to `hud_bus.h`, which is included after -- and
verified by deliberately breaking it, which now fails the build as it always
should have.

**And a file that would have undone the whole week.** `config/User_Setup_ESP8266.h`
still had `TFT_CS 15` and `TFT_MISO 12` -- the pre-2.5 map -- and the sketch
header, the `#error` text, `docs/BUILD.md` and `wiring.html` all told you to
copy *that* file into the library. Doing so would have put the display's chip
select back on the MCP2515's pin and the panel's SDO back on its MISO. Deleted,
and every reference now points at the correct one.

**Bloat out.** The stale wiring block at the top of `NavHud.ino` (still showing
the old pins, in the block it calls "the copy you read with a soldering iron in
your hand"); a boot self-test behind a symbol that was never defined anywhere,
with a comment telling you to switch off something already off; `CAR_IDS[]` and
`CAR_ID_COUNT`, which nothing read; `CarState::ignRaw`, written and never read;
`CANFAULT_GARBLED`, never assigned; five forward declarations for functions
already defined by then; two dead `HUD_MAG_FORCE_*` knobs; and every remaining
comment about a gyroscope, an IMU, an MPU-6050, the `scan` command or the
hand-written MCP2515 driver -- all of it gone. Comments claiming the RX buffers
hold "250 us at 500 kbit/s" now say 2.2 ms at 100, which is the bus this
actually runs on.

**One more guard added.** `hud_store.h` reserved a flash size for the compass
calibration by hand, with a comment asking the next person to keep it in step.
The last time those drifted, every saved calibration failed its own checksum on
reload. It is a `static_assert` now.

Also: the backlight is brought up **last**, after the compass. You noticed long
ago that the compass stops being found when bench mode is on, and bench mode is
the one thing that forces the backlight on regardless of the key. The panel's
LED runs straight off a GPIO on the same 3V3 rail the compass sits on, and a
sagging rail during the I2C probe reads exactly like a chip that is not there.
Probing first costs a dark panel for half a second. The real fix is a transistor
on the LED pin.

13/13 suites green, compiles clean, 55 % flash.

## 2.5 — the chip select was the bug

Your loopback test answered it. That example runs the whole controller: it
configures the chip, hands it a frame, the CAN engine loops it back internally
and it reads out the other side. SPI in both directions, the bit timing, the
crystal -- all of it, working, with the chip select on **D8/GPIO15**.

So the module was never the problem. Neither was the wiring, the library, the
crystal, the 3V3 supply or the SPI clock. It was the pin.

**Three pins rotate. The compass does not move.**

    D8  GPIO15   display CS   ->  MCP2515 CS      measured working
    D2  GPIO4    backlight    ->  display CS      a clean pin, nothing fitted
    D0  GPIO16   MCP2515 CS   ->  backlight       the one job that suits it

GPIO16 is the RTC pin `XPD_DCDC`, and Espressif are explicit that it is the one
pin on the part that can only be pulled **down**, never up. A chip select has
to idle high. That is a bad match, and it is the kind of bad match that does
not fail cleanly -- it gives you `FF FF FF FF` from every register, on wiring
that measures perfect, for as long as you care to keep looking.

A backlight, on the other hand, does not care about pull-ups or interrupts, and
PWM genuinely works there: `analogWrite` accepts any pin up to 16, and the
core's waveform generator carries an explicit `pin == 16` path driving `GP16O`.
I checked the core source rather than assuming it.

**The MCP2515's INT stays unwired, and that is a decision.** The only pin left
would be GPIO16, and GPIO16 cannot raise an interrupt at all. Wiring INT there
would mean polling a pin instead of polling a register -- strictly worse, since
the register read also tells you *which* buffer is full. The bus is polled, as
it always was.

**About SI and SO.** Your working loopback proves the orientation you have is
right, whichever way round it is, so nothing changed and nothing should. The
pin map now records it the way the Bastelplan reads it -- MISO to the header
pin printed `SI` -- with the note that these modules label their header from
the host's side. It looks crossed. It works. Leave it.

`hud_pins.h` carries the whole story next to the map, and its `static_assert`s
caught me twice while making this change: once when `User_Setup.h` still had
the display on GPIO15, and once when the host test stub did. That is the check
doing exactly the job it was written for.

`wiring.html` is updated for the new map and Trap 2 now says what actually
happened instead of telling you to fit a 10k pull-up on a pin that no longer
has a chip select on it. The compass section of that page is still older than
the firmware -- it talks about the MPU-6050 -- and I have left it alone rather
than half-rewrite it.

13/13 suites green, compiles clean.

## 2.4 — one sketch for the MCP2515, and less of everything else

**CanTest is a separate download.** One file, no display, no compass, no theme,
no fonts, no CAN library -- 240 KB of flash against this firmware's 582, and
nothing in it can be blamed for anything except the MCP2515.

It runs the bus at **1 MHz**, not 8. The datasheet allows 10; slow is the point.
It takes clock speed, wire length and signal integrity off the suspect list
before you start, so if it answers at 1 MHz and not at 8, you have learned
something instead of guessing.

What it actually checks, in order:

    1. reset, then CANSTAT and CANCTRL       -> is anything there
    2. sixteen reads of the same register    -> is the line stuck or floating
    3. write seven patterns and read back    -> MOSI and MISO, separately

Step 3 is the one that matters and the one the HUD never did. Reading a
register that happens to power up as 0x80 proves less than it looks: a bus
returning a fixed value passes that test. Writing 00, FF, AA, 55, 01, 80, 5A
into a scratch register and reading each one back proves MOSI, MISO, chip
select and the chip's own logic at once. Reads that work with writes that do
not is a different fault from neither working, and it names a different wire.

On failure it does not say "not found". It gives you the list in the order
worth checking, with the reason each one is on it -- the display's SDO first,
then 3.3 V at the MCP2515's own pin 18 rather than at the module header, then
SI/SO swapped, then the crystal (per DS 8.1 a dead crystal leaves the
Oscillator Start-up Timer never expiring, so the chip sits in reset and answers
nothing, which looks identical to no power).

Then `listen` dumps frames with their IDs. Listen-only always, never transmit
-- it is a car.

**`scan` is gone, and hud_probe.h with it.** Two hundred lines that CanTest now
does better and in isolation. It is also the file that blanked your panel a
while back, when its display probe sent an ILI9341 command to an ST7796S.

**`wipe` now rebuilds the screen in four stages** instead of one wash, because
you said the mark comes back the moment the interface does -- which means it is
drawn, and the way to find a thing that is drawn is to stop drawing everything
else:

    1  flat fills straight at the driver     nothing of ours on the glass
    2  the theme's full-screen clear only    one big SPI burst, no layout
    3  the car half                          rule, tacho, volts, PS, speed
    4  the nav half on top                   limit, turn, street

Two seconds each, each one printed on the serial line. Four stages is two
bisections: whichever number it arrives on removes three quarters of the places
it can be hiding, and then I can go straight at it instead of reasoning from a
photo of a finished screen.

## 2.3.1 — the line on the glass

You said earlier firmware did not do this, so I went and checked rather than
guessing. Three things came out of it, and the first one is the answer.

**It is not the drawing code.** I hashed every drawing file across all eleven
releases you have: `theme_dash.h`, `hud_canvas.h`, `hud_geom.h`,
`hud_display.h`, `hud_theme.h` and all eighteen font files are **byte-identical
from 1.33 to 2.3**. Not one pixel of drawing has changed since the first build
you ever flashed.

Then I measured where the line is, using the tacho as a ruler -- its segments
are exactly 25 px deep, which fixes the scale at 5.28 photo pixels per screen
pixel. On that scale the solid line lands at **y = 62.1**, and `DASH_RULE_Y` is
62. The mapping is exact. The dotted one lands at **y = 66.3**.

And row 66 is painted by exactly one thing in this firmware: a black clear.
Nothing draws there. I proved that rather than asserting it -- see the new test
below. So a *bright* line at row 66 cannot come from our drawing, and the two
candidates left are the panel itself or one corrupted SPI burst during the
single full-screen clear at boot. That second one is worth understanding: the
theme only repaints widgets that changed, so a band of wrong pixels written
once is never painted over again. It looks permanent because nothing disagrees
with it.

**A new `wipe` command tells you which, in twenty seconds.** Type it on the
serial line. It floods the panel red, green, blue and black straight at the
driver -- no theme, no geometry, no fonts, every addressable pixel written with
one solid colour -- then repaints.

    the mark vanishes under all three  -> the panel can address it, so
                                          something drew it. Ours, and findable.
    it survives a solid red screen     -> the panel is not showing that pixel.
                                          Bad pixel, bad ribbon, or a corrupt
                                          burst. No clock change will fix it.

If it turns out to be the bus, `SPI_FREQUENCY` in User_Setup.h is the knob and
the file now lists the exact 80 MHz dividers to step down through. I did not
lower it on a guess -- 13.33 MHz is inside the ST7796S's own 15.2 MHz limit and
dropping to 10 costs you a third of the repaint speed for nothing if the fault
is the panel.

**A bug in the test harness, which is the part that should not have happened.**
`MR_DATUM` was missing from the stub's datum switch. It fell through to the
default and was measured as top-left, so every middle-right field -- volts, PS,
peak, and the E60's whole right-hand column -- has been checked at coordinates
the panel never uses, silently, in every release. The layout tests were passing
on the wrong numbers.

**And the gap that let it hide: the car half had never been rendered with the
bus talking.** A default `CarState` has `tTorque = 0`, `carStale()` says yes,
and `dashPower_` draws a single space. So the widest string those fields can
ever show, and the opaque background box that comes with it, had never once
been drawn on the host in eleven releases. `test_layout.cpp` is built around
the E60 theme's zone functions and will not compile against the dash theme at
all, which is why nobody noticed.

New `carhalf` suite, 13 of them now: 600 ticks with live values across the
whole declared range -- PS from -500 to 500, revs past the limiter, volts 9 to
15 -- asserting no car field is drawn off the panel, none spills past the
band-1 floor, and nothing but the rule is ever drawn in rows 61..65. Those six
rows matter because nothing owns them: band 1 stops above, band 2's clear
starts below, and anything that lands there stays for the rest of the drive.
All clean, which is how I know the drawing is not what you are looking at.

Nothing else changed. 13/13 suites green, compiles clean.

## 2.3 — a second opinion on the bus

You pointed me at r00li/CarCluster. It is worth more than a forum post: it is a
transmitter that drives real E-series clusters on simulator rigs, so it has to
get the encode exactly right, and an encode there pins a decode here. If the
cluster shows 100 km/h for the bytes it sends, those bytes mean 100 km/h --
whoever put them on the wire.

Three of the four decodes came back clean.

    0x0AA rpm        uint16_t value = rpm * 4 at bytes 4/5.
                     Ours is (d[5]<<8 | d[4]) >> 2. Exact match, no change.
    0x130 ignition   0x45 on, 0x00 off. Inside our "> 0x41". No change.
    0x1D0 coolant    engineTempFrame[0] = coolantTemperature + 48.
                     Which is your own note for that frame, word for word.

**The speed constant was wrong, and by a lot.** Their sendSpeed():

    uint16_t speed_value = speed + previousSpeedValue;

so bytes 0-1 are an accumulator that gains `speed` every send. Three lines of
their source fix the scale:

  * `int speed = 0;  // Car speed in km/h`            GameSimulation.h:109
  * `speedCorrectionFactor = 1.00`                    GameSimulation.h:13
  * `dashboardUpdateTimeShort = 100`                  BMWESeriesCluster.h:42

One count per 100 ms is one km/h. So **CAR_SPEED_K is 100, not 80.4672** --
and the old number is worth a second look, because 80.4672 is 50 x 1.609344
to seven digits. That is fifty miles per hour written in km/h: the fingerprint
of a unit conversion somebody did in mph-land, not of anything measured.

The HUD would have read about **20 % low**. Worse, it could never have
corrected itself: the app learns a scale factor against GPS but refuses
anything outside 0.90 .. 1.10, so it would have sat pinned at the clamp for
ever, quietly wrong and never complaining.

**A measurement window, for a reason the same source made obvious.** If the
counter advances in whole km/h once per frame, then a frame-to-frame
difference carries one full count of quantisation plus the error of two
timestamps taken in a loop that also repaints a display. At 100 km/h that is
100 counts over a nominal 100 ms -- a 5 ms timestamp wobble alone is 5 km/h,
ten times a second, and the number would shiver on the glass.

So the single previous sample is now a 12-entry ring and the difference is
taken against a sample about 300 ms old: a sliding window, not a slower update
rate -- still a fresh number on every frame, with at most 150 ms of lag. The
test measures it on a jittered 97.4 km/h stream:

    jitter: worst error 2.59 km/h windowed, 11.49 km/h frame-to-frame

Two smaller things fell out of writing it. A fast bus can no longer fill the
ring with samples all too young to difference against (CAR_SPEED_SPACING_MS),
and an impossible delta now throws the history away and re-acquires on the
next frame instead of poisoning every reading until it aged out.

**Coolant is decoded and reported, not drawn.** You said keep it in mind and
do not touch the interface yet, so that is exactly where it is: an eighth
field on `$CAR` and a line in `status`. Nothing on the glass, nothing in the
app. It is on the wire so one drive can confirm the decode before anything
depends on it. It gets a 3 s stale window of its own, because CarCluster sends
0x1D0 on the 500 ms timer and the 800 ms used for speed would blank it on a
single dropped frame.

**What I did NOT take.** CarCluster writes the same value into bytes 2-3 and
4-5 of 0x1A6, because a simulator has one speed to give. A real car probably
does not -- three counters in one frame on a four-wheeled car reads like
per-wheel counts, which differ in every corner and differ a lot with one soft
tyre. Averaging them might well be better. It would also be a guess, and one
logging drive in a car park settles it, so they are written down and unused.
Bytes 6-7 turn out to be a time counter (`+= deltaTime * M_PI`), not the
message sequence number I had assumed -- also written down, also unused.

One thing to check on the first drive that works: hold an indicated 100 km/h.
If the HUD shows about 80, the old constant was right after all and it is one
line in hud_config.h to put back.

Two small things fixed on the way past, both found by the compiler rather
than by me. `spin stop`'s "not enough yet" message was being cut off
mid-sentence -- it overran its 128-byte buffer -- and is now two lines. And
`arduino/config/User_Setup.h` in the repo was still the old ESP32 / ILI9341
one: the right ST7796S file was only ever in the zips. Anyone who had trusted
the one in the repo would have got a dead panel. They are now the same file.

Everything else is untouched. 12/12 host suites green, and the only warnings
left at -Wall -Wextra are the drawing helpers the other theme uses.

## 2.2 — the compass works

Two findings from your capture, both from reading QST doc 13-52-19 properly.

**1. The write order. 0BH first, 0AH last.** Section 7's own examples:

    7.2 Continuous Mode Setup Example
      Write Register 29H by 0x06   (sign for X Y and Z axis)
      Write Register 0BH by 0x08   (Set/Reset On, Field Range 8 Gauss)
      Write Register 0AH by 0xC3   (set continuous mode)

Writing 0AH is what STARTS the chip -- suspend is the default state after
power-on *and* after a soft reset (6.2.4). Adafruit's library writes
mode/ODR/OSR/DSR into 0AH and only afterwards puts range and set/reset into
0BH: it starts the sensor and then reconfigures it while it runs. That is
`status = 0x08` with DRDY never setting -- present, addressable, configured,
and not measuring.

**2. The range write does not stick on this part.** Your readback:

    readback: 0AH=CB  0BH=00

0BH reads 0x00 after being written 0x08, so RNG stays 00 and the chip sits at
+-30 G = 1000 LSB/G. Scaling for the 8 G it was asked for gave 13.5 uT where
Belgium is 49. Scaling for the 30 G it reports: `-86, -258, -428` counts ->
`-8.6, -25.8, -42.8 uT` -> **50.7 uT total.** Exactly right.

So the scale is read back from CTRL2 at bring-up and never assumed. Ask, then
believe the answer.

The Adafruit library is gone -- the driver is direct register access again,
matching the MagId sketch that worked on your bench line for line. `begin()`
also refuses to report success until a real sample lands, and the boot banner
prints CTRL1, CTRL2 and the reported range, so the gap between "asked for" and
"got" can never be invisible again.

## 2.1.2 — MODE 01 does not measure

Your capture: `no data ready -- status 0x09 = 0x8`, forever. Chip on the bus,
right ID, configured, addressable — and never finishing a measurement. DRDY
(status bit 0) never sets.

`QMC5883P_MODE_NORMAL` is **0x01**. `QMC5883P_MODE_CONTINUOUS` is **0x03**. The
hand-written driver that worked here for weeks wrote `CTRL1 = 0xCB`, and the
bottom two bits of 0xCB are **11** — continuous. When I moved to the library I
copied its example's `setMode(QMC5883P_MODE_NORMAL)` and silently changed the
mode from 11 to 01. On this part that means "configured, not measuring".

Now `QMC5883P_MODE_CONTINUOUS`, and `begin()` refuses to report success until a
sample actually arrives — it waits 400 ms, and if nothing comes it walks the
other modes and reports which one worked. A sensor that answers every question
except the one that matters is not going to pass as healthy again.

## 2.1.1 — the compass init the library does not do

2.1 handed the whole QMC5883P to Adafruit's driver. That driver does no soft
reset and never writes register 0x29 — and those two writes are exactly the
difference between the build that worked on your bench and the one that went
quiet. "It worked before" is evidence; I threw the evidence away.

Both are restored, ahead of the library:

- **Soft reset (CTRL2 = 0x80).** The chip keeps its configuration across a warm
  ESP reset, so without this it comes up in whatever state the last run left it
  in — including suspend, which looks exactly like a dead sensor.
- **Register 0x29 = 0x06.** Undocumented. "Write Register 29H by 0x06 (Define
  the sign for X Y and Z axis)" appears three times in QST's own application
  section and 0x29 appears nowhere in the register map. Adafruit never writes
  it; ArduPilot, Betaflight and the build that worked here all do.

The library still does the reading and the scaling, which is what it is here
for — `getGaussField()` reads the range register back on every conversion, so
the 13.3 uT bug cannot come back.

### Boot now says what the chip actually is

"no compass found" covered three different faults. It does not any more:

    compass : QMC5883P at 0x2C
              CTRL1 0xC9  CTRL2 0x08  range +-8 G  producing data

or, when it is not there:

    compass : no QMC5883P found at 0x2C
              0x2C did NOT ack, chip id 0x00 (want 80). Type `scan`.

The bus is asked directly, before the driver gets a vote, so "nothing on the
wire" and "something answered with the wrong ID" stop looking the same. And
bring-up now waits up to 400 ms for a real sample and says **NO DATA --
configured but silent** if none arrives, instead of reporting a healthy compass
that never sends a reading.

## 2.1 firmware + app 1.8.2 — libraries, and the reason the arrow froze

### The app bug. This is the one that was actually stopping the arrow.

`HeadingFusion.onExternalCompass(headingDeg, nowMs)` — the parameter `nowMs`
**shadows a field of the same name**, and the field is the fusion's internal
clock, which only `setClock()` moves. So the function stamped `lastCompassMs`
with real time, then called `onCompass()`, which stamped it again from the
*field*.

`setClock()` was called from exactly two places and both can be absent on your
hardware: the phone's own sensor callback (a head unit has no sensors) and the
GPS fix handler (no fix indoors or in a garage). With neither, the field stayed
**0 for ever** — so `onCompass()` wrote `lastCompassMs = 0`, and `usingCompass`
reads zero as "this compass has never reported" and returns false. `onCompass()`
then returned *before writing the heading*.

The result was the worst possible combination: `hudCompassLive` went true, which
correctly locked the phone's compass out, while the board's own reading could
never be applied. **The arrow froze and looked like it was ignoring the HUD.**

Fixed in two places: `onExternalCompass` calls `setClock` first, and the frame
loop advances the clock every frame instead of only inside the GPS branch.
New test in `CompassPriorityTest` reproduces exactly that configuration — no
GPS, no phone sensors — and it fails on the old code and passes on the new.
461 app tests green.

### Both sensor drivers are now libraries

**Compass: Adafruit_QMC5883P.** My hand-written driver is deleted. Its
`getGaussField()` reads the range register back on every single conversion and
picks the divisor from what the chip actually reports — which is exactly the
bug that made the field read 13.3 uT instead of 49, and it now cannot happen at
all. Configured the way the library's own example does it: normal mode, 100 Hz,
OSR 8, 8 G, set-and-reset on.

**CAN: the MCP2515 library, and my pre-flight can no longer veto it.** The SPI
probe used to `return false` before the library was ever called if it did not
like what it saw on MISO. That meant one of my own diagnostics could stop a
controller the library might have brought up perfectly well. It is now purely
informational — it records what it saw, and the library always gets its turn.

Still `MCP_LISTENONLY`, not the example's `MCP_NORMAL`: this taps K-CAN on a car
that gets driven, sharing a bus with the cluster and the body modules, and in
listen-only the chip physically cannot put a dominant level on the wire — not
even acknowledge bits.

## 2.0.2 — the compass reads the right field strength, and field 6 stops lying

Your serial capture had both bugs in it, and both were mine.

```
$MAG,282.6,0,0,13.3,0,0
        ^headings perfect  ^13.3 uT   ^0 = app ignores it
```

**Bug 1 — the scale factor.** The driver wrote "range = 8 G" to CTRL2 and then
hard-coded 3750 LSB/G from the datasheet table. The chip ignored the write and
stayed in its 30 G default, which is 1000 LSB/G — so every reading came out
**3.75x too small**, and Belgium's 49 uT field reported as 13.3. Exactly
3750/1000.

Fixed properly rather than by changing a constant: CTRL1 is written before
CTRL2 (QST's own order, the reverse of what I had), and then **CTRL2 is read
back and the scale derived from the range the chip says it is in**. If a write
does not stick — wrong order, too soon after a soft reset, a clone that ignores
it — the readings stay correct anyway.

**Bug 2 — field 6, again.** 2.0.1 tied it to the field magnitude being
plausible. That looked reasonable and was wrong: the magnitude was 3.75x off,
the check correctly said "not the Earth's field", and the app threw away
headings that were **perfectly correct**. Correct because a heading is atan2 of
two components scaled by the same factor, and atan2 does not care about scale.
A magnitude error cannot move a heading by one degree.

Field 6 now answers only what the app asks it: is this a car heading. Whether
the magnitude looks like the Earth's is field 4's job, and belongs in a
warning, never in a silent switch that disables the only absolute heading in
the system.

`status` now prints the range the chip reports, so a scale problem is visible
instead of arriving as a wrong number.

**Tests:** compass group 7 simulates a chip that refuses the range write and
reports 30 G; the driver must still produce 49 uT and still read north as
north. And `make magrate` runs the real firmware and measures what goes up the
cable — 42 `$MAG` lines per 10 s, field 6 = 1, heading tracking a turn — which
is what settled firmware-versus-app here instead of another round of guessing.

## 2.0.1 — the app was ignoring the HUD compass

My regression, introduced in 2.0. Field 6 of `$MAG` tells the app whether the
number in field 1 is a heading for the **car** or just for the **chip**, and
anything other than `1` makes it drop the HUD compass completely and fall back
to the phone's sensors. Silently — the only symptom is that the arrow follows
the phone.

2.0 reported `compass.calibrated()` there, so a board that had never been told
`spin` sent `0` for ever. Wrong question: hard-iron calibration is about how
*accurate* the heading is, not whether it is a car heading at all.

It now reports 1 when the chip is answering and the field it measures is
plausible to navigate by. Alignment to the car is the `north` offset, which
defaults to zero — "the chip points forwards" — and one command fixes it if
that is wrong. A sensor sitting in a 300 uT field because something magnetic is
next to it still reports 0 and is still ignored, which is the case this field
actually earns its place on.

Host test group 20 now parses the `$MAG` line and asserts field 6. Checked both
ways: it fails on the 2.0 code and passes on this one.

## 2.0 — the hardware layer, rewritten

Everything below the drawing is new. Themes, fonts, canvas, geometry and the
wire protocol are untouched; every module that talks to a pin, a bus or a chip
was thrown away and rebuilt. 1279 lines of sensor code deleted, RAM down from
49% to 46%.

### The MCP2515: it was almost certainly the display holding MISO

The panel and the CAN controller share SCK, MOSI and MISO. ST7796S *silicon*
tri-states SDO properly — the datasheet gives an output disable time of
15–50 ns. The *modules* often do not, and TFT_eSPI's author keeps a standing
warning for exactly these boards: "The SDO/MISO pin may not go tristate when
the TFT chip select is high." Many of them fit a series diode in the CS line so
the controller is never cleanly deselected.

A display holding MISO high means the MCP2515 can drive its SO all day and the
ESP never sees it — which reads as `CANSTAT FF FF FF FF`, on wiring that is
perfect, with GPIO16 switching correctly and I²C working. Which is your log,
exactly.

**Unwire the panel's SDO from D6.** `TFT_MISO` is now `-1` and nothing in the
firmware ever reads from the display.

### hud_pins.h — the compiler checks the wiring now

Every pin is declared once, with the datasheet citation for why it is that pin,
and `static_assert`s refuse to build if two things claim one. Tested by
deliberately breaking it: putting SCL on D1 alongside the display's DC — the
conflict in the pinout you were sent — now fails the build with

    error: static assertion failed: pin conflict: I2C SCL is claimed by something else too

rather than silently corrupting both buses.

### Other things the research turned up

- **SPI dropped from 20 MHz to 13.33 MHz.** The ST7796S datasheet gives the
  minimum write clock cycle as 66 ns — 15.2 MHz. 20 MHz was 32% over spec. It
  is also an exact divider of 80 MHz, where 20 MHz was not; a full repaint goes
  from ~123 ms to ~185 ms.
- **I²C bus recovery at boot.** SDA is GPIO0, a boot strap. A slave left holding
  it low does not just break the compass — it stops the board booting at all.
  The bus is now clocked free before `Wire.begin()`.
- **100 kHz I²C**, because `analogWrite` on this chip drives its waveform from a
  **non-maskable** interrupt that `noInterrupts()` cannot hold off, and `Wire`
  is bit-banged.
- **Chip selects parked high before anything initialises.**
- The probe never runs at boot. It is `scan`, and only `scan`.

### The compass: 1279 lines out, 287 in

`hud_imu.h`, `hud_mag.h` and `hud_mount.h` are deleted. The TRIAD mount
discovery in them was correct and could never run, because it needs an
accelerometer and there is not one on this board. What replaced it is what you
actually asked for: axis remap, hard-iron removal, soft-iron equalisation, the
heading, and one stored offset you set with `north <deg>`.

It does not tilt-compensate, and `status` says so out loud. Dip here is ~65°, so
1° of tilt is 2.1° of heading error. **Mount it flat.**

### Three bugs the new tests caught before you did

1. **The stored calibration never came back.** The checksum byte was written on
   top of the last byte of the north offset, so every calibration failed its own
   checksum on reload and the board came up uncalibrated after every reboot.
2. **No calibration was EVER accepted.** The sample counter only counted samples
   that pushed a bound outward — a full circle does that for its first quarter
   and then stops, scoring ~30 against a gate of 120.
3. **The panel booted lit on a healthy car.** `backlightAtBoot()` asked
   "is the controller dead?" before anything had tried to start it.

### Tests

`test_compass.cpp` is new: the heading at four cardinals (the check that catches
a stray minus sign, which reads correctly at north and south and mirrored at
east and west), the calibration gates, hard-iron removal against a simulated
magnet, and the flash round trip including erased and corrupted blobs. The
MCP2515 register tests are gone — that is the library's job now — and the E60
decoder tests stay. The I²C stub is a real two-device bus instead of one that
acknowledged all 119 addresses.

12 test binaries, all green.

## 1.35 — undo the damage

My fault. Two things, both mine.

**The display blanking a second after boot.** `probeAll()` was running
automatically at boot whenever anything was missing, and in 1.33 I had added a
probe that reads the ST7796's ID register. TFT_eSPI's `readcommand8()` prefixes
an ILI9341-specific `0xD9` that an ST7796S does not define. That probe is
**deleted**, and `probeAll()` no longer runs at boot at all -- it is `scan` and
only `scan`. A tool for finding a fault must never be able to cause one, and I
put one in the boot path of a display that was working.

**The backlight staying on with bench mode off.** That one is deliberate and it
is not new -- a dead MCP2515 must not be able to disguise itself as a dead
display, which is the black screen you had two versions ago. What was missing is
that the board never said so. It does now:

    backlight: ON and staying on -- no CAN controller, so no key signal.
               This is deliberate; see CAN_DEAD_MEANS_DARK.

Define `CAN_DEAD_MEANS_DARK` in `hud_config.h` if you want the other trade.

Kept from 1.34: I2C at 100 kHz, three compass attempts at boot, and the retry
every three seconds. None of those touch the display.

## 1.34 — the compass disappearing in bench mode

Not reproducible in software: on the host, `HUD_BENCH` prints
`compass : QMC5883P at 0x2C` exactly like the normal build, and bench mode
never touches I2C, the compass, or `diag()`. So this is electrical, and bench
mode makes exactly one electrical difference — **it is the only setting that
lights the backlight at boot.** Those LEDs pull upwards of 100 mA out of the
same 3V3 the QMC5883P and the bus pull-ups sit on.

Three changes, all edits, no new files:

- **I2C dropped from 400 kHz to 100 kHz** (`HUD_I2C_HZ`). The ESP8266's `Wire`
  is a bit-banged master whose timing comes from software loops; a sagging rail
  and an interrupt-sensitive bus at 400 kHz is a bad combination, and at 100 kHz
  every margin is four times wider. Costs 500 us per compass read, and the loop
  comments already budget for the 100 kHz figure because that is what they were
  written against.
- **Three attempts at boot instead of one.** The first I2C transaction of a
  board's life happens while the rail is still settling.
- **It keeps looking.** Two address probes every three seconds while no compass
  is present, so one browned out during bring-up is no longer written off for
  the rest of the run. It says so when it turns up, and reloads its calibration.

## 1.33 — tidied, and a compass that was never being read

### The thing you did not ask about, which mattered more

With `HUD_IMU` off, **the compass was detected at boot and then never read
again.** Not once. The board printed `compass : QMC5883P at 0x2C`, answered the
`mag` command, and in normal running never asked the chip a single question.

The whole compass half of `loop()` was nested inside `#ifdef HUD_IMU`. That was
invisible while the gyroscope was always compiled in — and stopped being
invisible the moment the MPU-9250 in the box turned out to be an MPU-6500 and
`HUD_IMU` went off by default. Same mistake as the one in `hud_imu.h:161` that
stopped the compass from ever starting, one layer further down: the compass was
still treated as an accessory of a chip it has nothing to do with.

`loadMagCal()` was in there too, so `spin` wrote the hard-iron calibration to
flash and nothing ever read it back. Silently, and only after a reboot — which
is the opposite of what you asked for when you said a reboot must not affect it.

Both are out of that block now. There is a host regression test (group 20) that
fails on the old code and passes on the new, so it cannot come back quietly.

With no accelerometer the code now assumes the sensor is level and says so in
the comment where the assumption is made. Dip here is about 65°, so every degree
the chip is actually tilted costs 2.1° of heading — but reading the compass
badly beats not reading it at all, which is what this build did before.

### Tidied, as asked

- `imu : NOT FOUND, WHO_AM_I 0x00` is gone. The IMU line is only printed when
  `HUD_IMU` is switched on, i.e. when one is expected, and says "nothing
  answered at 0x68 or 0x69" rather than quoting a register that was never read.
- `status` gained a NOTE when there is no accelerometer: the heading is not
  tilt-compensated and that is worth saying once, in the place you go to look.
- `HUD_IMU` is commented out in `hud_config.h` with the reasoning next to it.

### Two more probes for the MCP2515

**Is MISO shorted to MOSI?** D7 and D6 are adjacent on the header. Clock out
0x55 and 0xAA with CS **high**, when nothing should be driving the line. If they
come back, the two are shorted — and every register read would return its own
transmitted byte, which reads as "stuck low" and sends you hunting a power fault
that is not there.

**Does the chip respond to CS at all?** Compare the idle level with CS high
against CS low. A selected MCP2515 drives SO; a deselected one leaves it
high-impedance. Identical means nothing is listening to chip select.

**Does the DISPLAY answer over the same MISO?** This is the decisive one. The
panel is on the same SCK, the same MOSI and the same MISO as the CAN module —
only the chip select differs. If the ST7796 answers a register read, then SPI,
the clock wire, the MISO wire and the ESP's own pins are all proven good, and
the fault is on the far side of the MCP2515's chip select or its power, and
nowhere else. That turns "check everything" into one wire and one rail.

(Read a *non*-answer as inconclusive: plenty of cheap ST7796 boards bring MISO
out to the header and never connect it to the panel's SDO.)

The display's chip select is now explicitly parked high before every raw-SPI
probe, so a panel left selected cannot answer for the MCP2515.

### Host test harness

- The I2C stub was answering **every** address, so the bus sweep reported 119
  devices on a bus with two and `hud_qmc.h`'s detection was never exercised at
  all. It is now a two-device bus: an MPU-6050 at 0x68 and a QMC5883P at 0x2C
  holding a real central-European field, and nothing else acknowledges.
- `Wire` is defined unconditionally, as the Arduino core defines it. Guarding it
  on `HUD_IMU` broke the link the moment the compass and the I2C sweep existed.

## 1.32 — stop guessing, measure the wires

Your log settled the software question: **"Entering Configuration Mode
Failure..." is coryjfowler's own message.** Two independent drivers, audited
separately, failing at the identical stage. More code inspection is wasted time.

And it is not only CAN. `WHO_AM_I 0x00` and no QMC at either address means the
I2C bus is silent too — two separate buses, both dead, which is a fact worth
more than either one alone.

### A probe that names the wire

`scan`, and automatically at boot whenever anything is missing. Three tests,
each answering one question, each with a failure mode distinguishable from its
neighbours':

**Can GPIO16 be driven?** It is not an ordinary pin — XPD_DCDC lives in the RTC
domain, a different register block reached by different code in the core. It is
the one pin here that deserves proving rather than assuming. Driven both ways
and read back; a pin that cannot switch cannot select a chip, and every
downstream symptom would then be "nothing answers".

**What comes back on MISO?** CANSTAT after a reset reads 0x80 and only 0x80, so
the raw byte is the diagnosis. Read four times at a deliberately slow 1 MHz:

    00 00 00 00   stuck low: MISO not reaching D6, or no 3V3 at the module,
                  or its GND is not shared with the ESP
    FF FF FF FF   floating high: nothing driving it -- wrong pin, or CS never
                  reaches the module so the chip never answers
    80 80 80 80   SPI is fine; the crystal is the remaining suspect
    anything else something is driving it but the bytes are wrong: too fast,
                  MOSI/MISO swapped, or a long jumper on SCK

**Every I2C address, 0x01 to 0x77.** The whole sweep, not the two we expect,
because "the QMC is not at 0x2C or 0x0D" and "nothing at all is on this bus" are
different faults with different fixes and a targeted probe cannot tell them
apart. A board silkscreened HMC5883L answering at 0x1E, or a VCM5883L at 0x0C,
shows up here rather than as a mystery. Nothing acknowledging at all points at
pull-ups, power or swapped wires rather than at any sensor.

It also states the D1 mini trap explicitly: D1/GPIO5 and D2/GPIO4 are the usual
I2C pins, but **the display has them** — DC and backlight — so this build moves
I2C to D3/GPIO0 and D4/GPIO2.

### The MPU text is gone

`hud_config.h` now describes what is fitted rather than what used to be: a
gyroscope that can be a 6050, a 6500 or the gyro half of a 9250 (they answer on
the same registers and the code cannot tell them apart, which is fine), and a
QMC5883 compass that is independent of it. `hud_imu.h`'s header is about a rate
sensor rather than a shopping recommendation, `HUD_MAG_CHIP_QMC` is gone along
with the AK8963 it used to select, and the boot line no longer says "type
'mount' to start" for something that now works itself out while you drive.


## 1.31 — the compass was never being asked

### Why the QMC "does not answer"

`hud_imu.h` started the magnetometer from inside `HudImu::begin()`, and only
after the MPU had answered its WHO_AM_I — line 161, `if (present) { ...
mag.begin() }`, with an early `return false` above it.

That was right for the AK8963 it was written for. That chip is **inside** the
MPU-9250 package, behind the MPU's I²C bypass, and there is genuinely no way to
reach it without the MPU.

A QMC5883 is its own board on the same two wires and has no such relationship.
So an MPU that was unplugged, dead, or simply not fitted took the compass down
with it: the IMU returned early and `mag.begin()` was never called at all. The
compass answers perfectly well — nothing was asking it anything.

Now:

- `Wire.begin()` happens **once, in `setup()`**, before anything on the bus is
  touched. It used to live inside `HudImu::begin()`, which quietly made every
  device on the bus depend on the MPU.
- The compass is started **first and independently**, and reports itself on its
  own line whether or not an IMU exists.
- `mag` is a top-level object rather than a member of the IMU.
- The **AK8963 path is gone entirely** — its register block, its bypass
  handling, its status strings. It was the thing doing the coupling, and
  keeping dead code for a chip you do not have is how the coupling comes back.

Bus clock is 400 kHz now, which both the MPU and the QMC5883 are rated for.

### It shows the car screen, full stop

Booting with no phone said "NO LINK — check the USB cable", which is a wrong
answer stated confidently: the cable is fine, the bus is what is quiet, and the
driver only wanted to see their speed.

The car screen no longer waits for the bus to prove itself. With CAN compiled
in it **is** the display, from power-on, whatever the bus is doing. A tacho with
no needle and a blank speed is an honest picture of a silent bus; why it is
silent belongs on the serial line and in `status`, not on a windscreen.

The backlight still follows the key, so with the key out the panel is dark — but
what is behind the dark panel is now the car screen, so the key coming back
lights something useful rather than a complaint.

### The MCP2515, both ways

`CAN_USE_LIBRARY` now selects **coryjfowler's MCP_CAN** (install "mcp_can" from
the Library Manager) and is on by default. The hand-written driver stays behind
the same switch and is still what the host tests exercise against the
register-level simulator.

The audit found that driver correct — opcodes, reset delay, mode verification
through CANSTAT rather than CANCTRL, config-mode ordering, SPI mode and clock —
and its bit timing for 8 MHz at 100 kbit/s is byte-for-byte what the library
derived independently. But when a board will not talk, "my code is correct" is a
claim and a second implementation is evidence. **If the library fails at the
same stage, the fault is not in anyone's software.**

Two things the switch surfaced, both now documented in the code:

- The library `#define`s `CAN_OK` as a plain macro, which beats an enumerator:
  the preprocessor rewrote our `CanStage::CAN_OK` to `(0)` and the compiler then
  reported an invalid conversion pointing at the library's header rather than at
  anything we wrote. The stages are `CANDIAG_*` now.
- mcp_can 1.5.1 keeps its bit-modify helper private, and EFLG's overflow bits
  latch until the host clears them. Counting a flag that can never be cleared
  counts the same overflow once per poll for ever, so on the library build
  `$CANDROP` is a **latch, not a count** — 0 means never, 1 means at least once.
  It still answers the question the number was for.


## 1.30 — a QMC5883, and one file of settings

### It detects the chip rather than being told

You asked for a switch between the QMC5883**P** and the **L** in case the wrong
one turned up. There is one — `HUD_MAG_FORCE_QMC5883P` / `..._L` — but the
default probes for both and uses whichever answers, because the boxes are the
problem rather than the order. GY-271 boards silkscreened HMC5883L have shipped
QMC5883Ls for years, and the current wave ships **QMC5883Ps marked "HP5883"**.
Detecting costs two I²C transactions at boot.

They share a name and almost nothing else, so the branch is real:

| | QMC5883P | QMC5883L |
|---|---|---|
| Address | **0x2C** | **0x0D** |
| Chip ID | 0x00 → 0x80 | 0x0D → 0xFF |
| Data | 0x01–0x06 | 0x00–0x05 |
| Status | 0x09 | 0x06 |
| Control | 0x0A, 0x0B | 0x09, 0x0A |
| Range bits | 0x0B[3:2], four ranges | 0x09[5:4], two |
| At 8 G | 3750 LSB/G | 3000 LSB/G |
| DRDY clears on | reading **status** | reading **any data register** |
| Magic init write | 0x29 ← 0x06 (undocumented) | 0x0B ← 0x01 (documented) |

Three of those differences are traps rather than inconveniences. A P driver
that clears DRDY by reading data spins forever; an L driver that reads only
status never clears it. And on the L, **reading the data registers without
reading through 0x05 locks the chip** — 6.2.1.4: "data register cannot be
updated until the last bits 05H (ZOUT[15:8]) have been read." Stop short and it
stops updating for good, which looks exactly like a dead sensor.

`0xFF` is also a terrible identity check, since an absent device on a pulled-up
bus reads 0xFF too, so the L is confirmed by writing 0x0B and reading it back.

**Deliberately not copied** from the popular drivers, because a reader would
otherwise assume they were intentional: ArduPilot's QMC5883P init writes 0x29
*into* register 0x06 with the arguments reversed; its range defines read `0x10`
and `0x11` as though they were binary, producing 0x40, which collides with the
self-test bit; its P scale factor is the L's, a 25% error; and its L
data-ready test reads bit 2, which is "data skipped", not bit 0.

### The check that catches a wrong axis mapping

The QMC is a separate board, so its axes need not match the MPU's.
`MAG_AXIS_ORDER` and `MAG_AXIS_SIGN` say how they relate, defaulting to "the
same way up" — which is what you get by sticking both to the same face, and the
reason to do that.

Getting it wrong doesn't look like a fault. It looks like a compass that is
confidently wrong in a way that changes with heading. So `status` now prints the
**magnetic dip** — the angle the Earth's field makes with the horizontal, about
65° in central Europe. It depends on *where you are*, not which way you point,
so turning the car through a circle should barely move it. If it swings by tens
of degrees, the mapping is wrong and no amount of hard-iron calibration will
rescue a heading built on it.

### One file of settings

Every pin, part number, rate and threshold is now in `hud_config.h` — CAN chip
select, crystal, bit rate and listen-only; the I²C pins and IMU address; which
magnetometer and how it is oriented; the mounting-calibration gates.

What is **not** there is anything that is a fact about a chip rather than a
choice: register addresses, LSB-per-gauss, the bit-timing tables. Those stay
with their drivers, because moving them here would fill the file with numbers
you can never change and bury the ones you can.


## 1.29 — the CAN bring-up now tells you which wire

### "$CAN,absent" said nothing useful, and it had everything it needed

The two mode changes in `canBegin()` fail for completely different physical
reasons and both printed the same string.

Entering **configuration mode** is a pure SPI test. The datasheet (10.1) says
the part is already in configuration mode after a reset, so all it asks is
whether a read of CANSTAT comes back as `100xxxxx` — the SPI block is clocked by
SCK, so that passes or fails on MOSI, MISO, SCK, CS and power alone. **The
crystal is not involved.**

Entering **listen-only** is the opposite: 10.0 says the mode "will not actually
change until all pending message transmissions are complete" and must be
verified through CANSTAT.OPMODE, which needs the internal state machine, which
needs OSC1 oscillating.

So the stage that fails names the fault, and the board now says which:

    can     : nothing answered on SPI. Check CS (GPIO16) and its 10k pull-up
              to 3V3, MISO, and that RESET is pulled high
    can     : SPI works, but the controller refuses to change mode: the crystal
              is not oscillating. Check the can and its two load capacitors
    can     : no bit timing for this crystal and bit rate -- a build setting in
              hud_config.h, not a wiring fault

That last one used to be reported as a soldering fault: an unsupported
crystal/rate combination returned before a single SPI byte was sent, and the
caller then told you to go and check your chip select.

There is also a **real presence test** now, not just a mode echo. The old check
was satisfied by exactly one value, `0x80`, which happens to survive a
stuck-high or stuck-low MISO — luck, not design, and blind to a MISO weakly
coupled to MOSI or an SCK at the wrong speed. It now writes `0x55` and `0xAA`
to TXB0D0 and reads them back, which exercises every bit in both directions.

### The hardware filters were on the whole time

Every comment in `hud_can.h` said listen-only bypasses the acceptance filters.
The code wrote `RXM = 00`, which leaves them **on**.

The claim rested on one sentence of 10.3. The *first* sentence of the same
section says the opposite — listen-only receives everything "by configuring the
RXBnCTRL.RXM<1:0> bits" — and two other places back it: Register 4-1 defines
`11` as "turn mask/filters off; receive any message" against `00`'s "receive all
valid messages ... that meet filter criteria", and 7.6.1 describes an overflow
as happening when a message "meets the criteria of the acceptance filters".

It worked by coincidence: all five ids were programmed into filters with
exact-match masks, so all five arrived either way. A sixth id — the loop
silently drops anything past index 5 — or one mistyped, and the frames would
simply have stopped, with the file's own header pointing you at the bit rate.

**No test could have caught it**, because the MCP2515 simulator had no
acceptance-filter model at all: `RXM` was inert and both values passed. The
simulator models the filters now, and the test asserts the *behaviour* — a frame
whose id is in no filter must still reach the driver — rather than the register
value alone. With `RXM = 00` put back, four checks fail.

Also: `CANINTF` is cleared with a bit modify rather than a byte write, which is
what the datasheet recommends.

### It boots to the car, not to a complaint about USB

Booting with no phone connected showed the splash for four seconds and then
"NO LINK — check the USB cable", with a perfectly good CAN bus behind it.

The grace period exists to avoid flashing that at a cable which is fine and
about to work. That is an argument for waiting when there is **nothing else to
show** — not for sitting on a splash while the bus is reporting a speed. The car
screen now outranks it. The splash still holds when there genuinely is nothing,
and the message says which thing is missing rather than always blaming the USB.

### The mounting works itself out

You no longer type anything. When the board has no mounting calibration it
starts learning at boot, watches ordinary driving, and saves the moment there is
enough evidence and it agrees with the speed on the bus — measured at 150
driving samples in simulation, from a mount twisted 112°, tipped 18° and rolled
27°, to a worst heading error of 0.00°.

If gravity later stops matching the saved mounting — somebody knocked the
display — it says so and starts working it out again by itself instead of
waiting to be asked.

`mount` is still there, but only to force a restart.


## 1.28 — the compass knows which way the car is pointing

### The phone was still steering the arrow

With the HUD plugged in, picking up the phone and turning it moved the arrow —
slightly. Slightly is what made it look like a mystery: the compass path is a
5 %-per-sample trim rather than an integration, so it was worth about a degree
per second of waving rather than anything dramatic.

Two compasses arrived at `HeadingFusion` through the same door with nothing to
tell them apart — the board's magnetometer via `onExternalCompass`, the phone's
rotation vector via `onCompass` — so whichever reported more often won, and the
phone reports far more often. The board's now outranks it outright.

This is *not* the guard that was there once and removed as wrong. That one
blocked every compass whenever the board supplied a yaw *rate*, which locked out
the only absolute source in the system and left the arrow never appearing at
all. A yaw rate says how far the car turned and never which way it points. This
blocks the phone's compass only while the board's own **compass** is reporting,
and hands straight back the moment the cable comes out.

### Which way is the sensor bolted in?

A heading is an absolute direction, so the firmware has to know where the car's
nose is relative to the chip before it can say which way the car points. Nobody
measured that angle and nobody is going to, so it is learned from two things the
car gives away for free:

- **Up** — the accelerometer while parked.
- **Forward** — the accelerometer while accelerating or braking in a straight
  line, with the sign settled by whether the CAN speed is rising or falling.

That second one is Ford's, from US20130081442A1, and the CAN speed is what makes
it work with no GPS: braking pushes the horizontal acceleration *backwards*, so
without the speed you have an axis and no way to tell the nose from the boot.

TRIAD turns the pair into a rotation, with **gravity as the primary vector** —
Shuster proves the algorithm treats the two unsymmetrically, satisfying the
first exactly and only minimising the error in the second, and gravity averaged
over a minute of standing still is worth a fraction of a degree where the
forward estimate carries several.

Stored as **two unit vectors, not three Euler angles**. u-blox document the
failure for their own receivers: at ±90° of mount pitch, roll and yaw stop being
distinguishable and "start to heavily fluctuate" — and a sensor glued flat to
the *back* of a dash panel is plausibly at exactly that angle.

Verified against simulated rotations at seven mounting angles including the
gimbal-lock one. Worst heading error at every one: **0.000°**.

### Two commands you type in the serial monitor

Anything arriving that does not start with `$` is now treated as a person
typing. `help` lists them; `status` prints everything the board knows.

- **`spin`** — the compass calibration. Pick the board up and turn it slowly
  through every face. The AK8963's own zero-field error can be ±300 µT, which is
  six times the whole Earth field, and until it is removed a compass reads much
  the same in every direction. `spin save` writes it to flash.
- **`mount`** — the mounting. Park and sit still for ten seconds, then drive
  with some ordinary accelerating and braking. `status` shows it filling up.

Both survive a reboot. Neither writes to flash where you type it: `EEPROM.commit()`
erases a whole 4 KB sector with interrupts off, and anything the phone sends in
that window is gone — so the write is deferred to the next standstill, or done
immediately when there is no bus, which is the bench.

### The review of all that found two things that would have shipped broken

**The acceptance gate scored 0.9 on pure noise.** The correlation was computed
between `sign(dv/dt) × |horizontal accel|` and `dv/dt` — and since the sign was
*taken from* `dv/dt`, the two agreed on every sample by construction. Measured
0.921 and 0.960 against a gate of 0.60 on an accelerometer pointing in a random
direction every sample. It would have written a nose vector pulled out of the
noise to flash and reported "mount: calibrated" for ever after. Projecting onto
the running nose estimate instead removes the tautology: the same two datasets
now score −0.029 and −0.030, and an honest drive still scores 1.000.

The existing tests could not see it, because both drove with a *single-signed*
`dv/dt` — all acceleration or all braking — which is the one case where the sign
is constant and the correlation measures something real. Every actual drive has
both. There is a test for it now.

**Asking to save too early killed the calibration.** `learnFinish()` and
`calFinish()` cleared their "running" flag before checking the gates, so typing
`mount save` a minute early ended the session while printing "keep driving and
try again" — and the counter never moved again however far you drove. The gates
come first now.

Three more from the same review: the CAN speed derivative was sampled at its
endpoints, where loop jitter against a 100 ms frame interval is a couple of
m/s² of phantom acceleration at cruise (it averages across the window now); a
mounting the board had decided was suspect kept steering the arrow anyway (it is
a field on `$MAG` now, and the phone refuses it); and an overlong typed line ran
its truncated head instead of being dropped.


## 1.27 — the arrow that appeared on a parked car

**The bug.** Open the app with no destination set and a straight-ahead arrow
appeared on the HUD. Two things had to be true for it, and both were:

The app had been killed while a route was live — a crash, or Android reclaiming
memory. Stop clears the saved destination, cancelling clears it, arriving clears
it, and a kill does none of those, so it stayed in preferences. Android then
restarted the service with a null intent and `onStartCommand` read the
destination straight back out and resumed routing to it, on a car standing on
the drive.

And the HUD had no way to disagree, because it was *inferring* whether a route
existed from "the maneuver is not `MAN_NONE`" — a guess dressed as a fact.

Both halves are now fixed, and each fix would be enough on its own:

- **The route is offered, not restored.** A kill leaves a "Resume route to X?"
  action on the notification. Until it is tapped there is no route and the HUD
  is in free drive: speed and limit, no arrow. Tapping it reads the destination
  back out of preferences rather than out of the notification, so a stale
  notification left over from a route cancelled on the map resolves to nothing
  instead of driving you somewhere you abandoned.
- **`FLAG_ROUTE` (bit 6) is now on the wire.** `RouteTracker` sets it,
  `FreeTracker` cannot. Without it the firmware strips maneuver, distance, ETA,
  street and the off-route and arrived flags out of the frame the moment it
  arrives — once, at one choke point, so no theme can draw a phantom arrow by
  accident. Speed, limit, cameras and lanes survive; none of them needs an
  itinerary to be true.

**Update both halves together.** New firmware with an old app shows speed and
limit but never a maneuver, because the old app never sets the bit. That is the
safe direction for the mismatch to fail in, but it is still a mismatch.

**A second, dormant one.** `theme_e60.h`'s maneuver switch had a `default:` that
drew a straight arrow, and `MAN_NONE` fell into it. That theme is off in the
shipping build, so it is not what showed on the glass — but it was a trap lying
in wait for whoever turned it on.

### The screen rules, written down

| Phone | CAN | Route | On the glass |
|---|---|---|---|
| — | — | — | NO LINK (on the car the panel is dark anyway: no CAN means no key signal) |
| — | ✓ | — | car only: speed, tacho, PS, volts |
| ✓ | — | — | speed from GPS + limit. No arrow |
| ✓ | — | ✓ | the above plus arrow, distance, street, ETA |
| ✓ | ✓ | — | speed from CAN + limit + tacho, PS, volts. No arrow |
| ✓ | ✓ | ✓ | all of it |

The car half now follows `displayOn` (key in the barrel) rather than
`ignitionOn` (engine running), which is the same signal the backlight uses. At
key position 1 with the engine off that means a tacho reading zero and a battery
voltage — the pair you actually want at that moment — instead of a lit panel
saying NO LINK at a car that is plainly talking to us.

### The firmware is split by concern

`NavHud.ino` went from 976 lines to 397 — `setup()` and `loop()`, and little
else. Everything else moved into a file named after what it owns:
`hud_config.h`, `hud_state.h`, `hud_display.h`, `hud_backlight.h`, `hud_link.h`,
`hud_align.h`. `docs/BUILD.md` has the map of which symptom points at which
file. Every moved function body is byte-identical to the original.

Three behaviour changes came out of the review of that split, two of them
regressions the split had introduced:

- The boot grace has to be checked **before** the car screen, not after. The
  board is powered from the head unit's USB, so the key is always in by the
  time it boots and 0x130 lands within a pass or two — with the order wrong, a
  normal start painted the splash, then a full car repaint, then a full drive
  repaint, two extra 123 ms blocking `fillScreen`s inside the window where the
  phone is opening the port and the RX buffer is filling.
- The backlight is now updated **before** the drawing. Taking the key out with
  no phone connected both darkens the panel and repaints it to NO LINK on the
  same pass; darkening first means the driver getting out of the car does not
  get a red "check the USB cable" flashed at them on the way past. Skipping the
  repaint instead looked like the cheaper fix and was wrong — the old drive
  display would have stayed in the panel's own RAM and lit up with the key.
- The car screen's threshold moved from `ignitionOn` to `displayOn`, as above.

### The CAN bit timing is now pinned, in all four configurations

The one number that fails silently — a controller at the wrong bit rate does not
receive corrupted frames, it receives *nothing*, and `$CANDROP` sits at zero
looking healthy — had exactly one assertion behind it, and that assertion was
for 8 MHz / 500 kbit/s. When the default moved to K-CAN's 100 kbit/s it stopped
running at all, without anything going red.

All four crystal and bit-rate combinations are now built and checked, against
two independent derivations: our own hand-decode of the datasheet's
`TQ = 2*(BRP+1)/FOSC`, and coryjfowler/MCP_CAN_lib's published tables. They
agree byte for byte, and the test also re-derives the bit rate and sample point
from the register values so a byte that matches the library but means the wrong
thing still fails.

    100k /  8 MHz   20 TQ, BRP 2, sample 75%   CNF 81 F6 84   <- K-CAN, the default
    500k /  8 MHz    8 TQ, BRP 1, sample 75%   CNF 00 D1 81
    100k / 16 MHz   16 TQ, BRP 5, sample 75%   CNF 44 E5 83
    500k / 16 MHz   16 TQ, BRP 1, sample 75%   CNF 40 E5 83

### And the test suite was not rebuilding

Found by a negative test that passed when it had to fail. The Makefile listed
the sketch's headers by hand, and that list went stale the moment the sketch was
split — editing `hud_link.h` did not trigger a rebuild, so the suite reported the
previous build's result as though it were this one's. It is a wildcard now.

## 1.26 — K-CAN, and the backlight follows the key

- `CAN_BITRATE_KBPS` 500 → **100**. The E60 cluster gateways speed, rpm, torque,
  ignition and battery onto K-CAN, which is where the tap goes.
- Battery scale ×0.015 → **÷68**, with the undivided count added as a seventh
  field on `$CAR` so it can be checked against a multimeter. (Published E9x and
  E65 sources both say ×0.015, which is 2 % away from ÷68 — the meter settles
  it.)
- The backlight follows the key off 0x130, latched, with no staleness test.
  K-CAN keeps chattering for about five minutes after the car is locked and then
  sleeps; treating that silence as "no data" and falling back to lit left a
  parked car glowing.

## 1.24 — speed limits on the roads nobody tagged

You asked how accurate the data actually is. Measured rather than asserted, on
2026-09-07, against OpenStreetMap's Belgian extract.

**Speed limits, by road length:**

    motorway      99.5 %  have a maxspeed tag
    trunk         97.2 %
    primary       91.1 %
    secondary     74.2 %
    tertiary      55.9 %
    residential   48.6 %
    unclassified  28.1 %
    ----------------------------
    all drivable  50.8 %

So the gap is not spread evenly. It is concentrated in exactly the residential
and unclassified streets where the limit is least obvious from the road and
most likely to be enforced — and on a route it was already covered, because the
Mapbox Directions call asks for `annotations=maxspeed`. Free driving had
nothing.

**A legal default is not a guess.** Every European country defines the limit
that applies in the absence of a sign, and a road with no sign *is* at that
limit. The uncertainty is never in the number; it is in deciding which of a
handful of numbers applies here. So `SpeedDefaults` derives one, and refuses
when the inputs cannot settle it.

Three things had to be right:

**The region.** Flanders dropped its rural default from 90 to 70 on 1 January
2017; Wallonia did not; Brussels went to 30 in town in 2021. Same country, same
road class, twenty km/h apart — and `country == "BE"` cannot tell you which.
The fix costs no network call: about 148,000 Belgian ways carry
`source:maxspeed` or `zone:traffic`, every value starts with the region code,
and a fetch window is kilometres across while a region is hundreds of
kilometres across. `Area.regionCode` takes a majority vote over whatever the
window happens to contain, so a tagged neighbour settles it for the untagged
street you are on. It works out of signal, which a reverse geocode does not.

**Knowing when not to answer.** `unclassified` covers both a village high
street and a lane between two fields, and 21,467 km of it in Belgium has no
maxspeed. Guessing there is how you show 70 in a 30 zone, so it stays blank
unless something — the road class, `lit`, or a scheme tag — actually answers
the built-up question. Service roads and tracks never get a default at all: a
sign reading 50 in a supermarket yard is worse than no sign.

**Saying so on the glass.** Every derived limit sets `FLAG_LOW_CONF`, the same
treatment a held limit gets, so the display distinguishes "probably 50" from
"50 on a sign you just passed". The driver is the one carrying the fine.

Two places where the law itself is ambiguous, handled explicitly rather than
papered over. **France:** the rural limit went 90 to 80 in July 2018 and then
37 departments put it back under the LOM — roughly 33,400 km by a 2021 Interior
Ministry count, not derivable from a country code. It returns 80, the value
that cannot tell somebody they are legal when they are not. **Netherlands:**
the statutory motorway default is 130, but most of the network has been signed
100 between 06:00 and 19:00 since March 2020, so it follows the clock.

Where an unknown input forces a choice, the lower value wins throughout. Being
pessimistic costs the driver nothing; being optimistic costs them a fine.

### Cameras: measured, and left alone at your call

    cameras OSM has in Belgium              1,603
    cameras Belgium actually has            3,185 (Lufop) - 4,737 (SCDB)
    carry a direction                       472  (29 %)
    carry the enforced limit                991  (62 %)
    never edited since created              47 %
    not edited since before 2020            37 %
    official Flemish section controls
      with an OSM node within 250 m         15 %

Verified first-hand against Geofabrik's Belgian taginfo instance, not taken on
trust from a summary. The 29 % direction figure is the one that matters: seven
times in ten the app cannot tell whether a camera faces you or the opposite
carriageway, which is the false-alarm problem from months ago showing up as a
data problem rather than a logic one.

You chose to stay on OSM, so nothing changed. For the record, the alternative
was Lufop — free tier covering BE/FR/CH, ODbL so licence-compatible with the
OSM data already shipped, and a numeric azimuth plus enforced speed on every
one of 3,185 Belgian records. It is there if the false alarms get annoying.

Germany was already correct: `CameraPolicy.OFF`, because §23 Abs. 1c StVO bans
the app outright and OLG Karlsruhe 35 Ss 93/23 held that a passenger operating
it is no defence.


## 1.23 — bring-up, and the discipline of walking one build into the other

No feature work. The 4" panel came up perfectly under the bring-up sketch and
stayed white under the firmware, on the same board, the same wiring and the
same library — and I spent three rounds re-reading my own source instead of
doing the one thing that was actually available: taking the build that worked
and walking it, one change at a time, into the build that did not.

**What the firmware's boot path now is: the bring-up sketch's, byte for byte.**
Serial, four hundred milliseconds, backlight, four hundred more, `init()`.
Every difference before the panel is spoken to has been moved to after the
splash rather than argued about — the RX buffer resize, the radio shutdown, the
flash read of the saved geometry. The RX buffer had been there since the
beginning and was never once suspected, because "it only allocates a kilobyte"
is the kind of thing you conclude rather than test.

It works. Which of the removed differences was the culprit is not yet known,
and I am not going to pretend otherwise — the honest suspect is
`Serial.setRxBufferSize(1024)`, because it ran before `Serial.begin()` in every
version that failed and appears in no version that works, and because the radio
calls were moved to both sides of `init()` across earlier attempts with no
change either way. It is called after the splash now, which keeps the benefit
where it cannot cost anything. Anyone who wants the answer can put them back
one at a time; `HUD_BOOT_SELFTEST` makes each attempt a five-second test.

**And the bring-up sketch can now become the firmware on purpose.**
`DIAG_LIKE_NAVHUD` in `PanelDiag.ino` adds the WiFi stack and the settings
store to the sketch that works. Two outcomes, both of which end the guessing:
it still works and the fault is in the firmware's own setup, or it goes white
and the fault is power. That switch should have existed on day one of this.

### Also fixed on the way

**PanelTest is gone.** It was a second sketch with a second startup sequence,
so when one worked and the other did not there was no way to tell whether the
difference was the drawing or the setup. Its five checks now run inside
PanelDiag, on the startup already proven on the hardware. One sketch, one
variable at a time.

**A word handed to a font that has no letters.** PanelTest drew `"480 x 320"`
at font 6. TFT_eSPI's large built-in fonts are digits and a little punctuation
— font 6 is `1234567890:-.apm` and fonts 7 and 8 are narrower still — so the
`x` indexed a character table with no entry for it, and a bad pointer read on
an ESP8266 is an exception and a reboot. A reboot loop looks exactly like a
dead display.

**The bring-up sketches are under test now**, which is backwards from how it
was: they were throwaway diagnostics, so they were not tested, and a sketch
whose only job is to tell you whether your hardware works has to be the most
trustworthy code in the project. The host stub now refuses any string given to
a font that cannot render it, and that check runs over both HUD themes too —
both clean.

**Talking to the panel too soon.** RESET is tied to the board's reset line, so
the ST7796S comes out of reset with the ESP, and the datasheet wants 120 ms
after that before it will accept a command. The firmware went almost straight
from boot to `init()`. The bring-up sketch only worked because it spent that
time printing a report first — an accidental delay doing load-bearing work.

**`setRotation` is measured, not trusted.** The mirrored landscape orientations
are 5 and 7, which exist because TFT_eSPI takes `m % 8`; an older copy stops at
3, leaves the driver's width and height at portrait, and clips every landscape
draw away. Same white screen, different cause. The sketch now checks the driver
really came back 480×320 and falls back with a `$ROTERR` up the cable if not.

**A compile-time guard for the setup step everybody misses.** TFT_eSPI is
configured by editing a file inside the library, and skipping that gives you a
white screen with a working backlight and nothing to go on. Both sketches now
refuse to compile, with instructions.

**Pin labels.** D-numbers are not standard across ESP8266 boards — a NodeMCU
calls GPIO15 "D8" and an Uno-shaped Wemos D1 calls it "D10". The config uses
raw GPIO numbers, the diagnostics lead with GPIO, and the wiring sheet says so
in bold. The `PIN_Dn` macros only exist on some board variants and would have
pointed at the wrong pins entirely.

**The gyro's I2C pins are explicit.** `Wire.begin()` with no arguments takes
GPIO4 and GPIO5 on an ESP8266 — the backlight and DC on this build. It would
have filled the screen with garbage and left the gyro silent, with nothing to
say why. `hud_imu.h` now sets the pins and refuses to compile if any two land
on the same one; the guard is checked against six pin maps.


## 1.22 — a 4" screen, mirrored, with keystone

New hardware: a 4.0" ST7796S at 480×320 driven by an ESP8266, lying flat on the
dash and read as a reflection in the windscreen. Three things had to be true for
that to work, and none of them were.

**The layouts are re-drawn, not scaled.** 480×320 is 2.25 times the area of the
old 320×240, and stretching a layout by 1.5 gives you the same design with
fatter pixels. Both themes were re-laid to a stated vertical budget instead —
E60: speed 10–146, rule at 154, navigation 166–262, footer 268–312 — and the
extra room went into the three things that were previously compromises: a speed
limit roundel you read rather than squint at, a distance at font 6 with the unit
beside it instead of under it, and a lane strip with room for a real arrow. The
speed is font 8 now, 75 px against 48.

The host layout test was checking against a hard-coded 320×240 and would have
passed every one of these layouts without looking at them. It reads the panel
size from the protocol header now, which is the same number the firmware uses.

**Mirroring is a MADCTL bit, not a transform.** The windscreen flips the image
left-to-right, so the panel has to flip it back. Rotations 1, 7, 5 and 3 are the
four landscape orientations of an ST7796 — `0x28`, `0xA8`, `0x68`, `0xE8` — and
all four still report 480×320, so clipping and the viewport stay correct. Doing
it that way costs nothing per frame, which matters: the alternative would have
sent every `fillRect` in the UI down the two-triangle path for a setting that is
on all the time.

There is a subtlety worth writing down because it is not obvious and it decides
a lot. The panel mirror and the windscreen reflection are flips of the same
axis, so they cancel: a layout coordinate is exactly what the driver sees. That
is why the keystone corners are in the driver's view with no un-mirroring
anywhere, and why the preview on the phone is not mirrored either — drag the
corner that looks top-left and the top-left of the reflection moves.

**Keystone, for a dash that is not level.** A projector corrects keystone by
warping the finished image. That is not available here: 480 × 320 × 16 bpp is
307,200 bytes and an ESP8266 has about 40 KB of heap, so a full-frame warp is
not slow, it is impossible — and it is impossible on an ESP32 without PSRAM too.

But a head-up display does not draw photographs. It draws lines, boxes,
triangles and text with known coordinates, so the correction goes in *before*
rasterising: every coordinate passes through a homography on its way to the
panel. No RAM at all, it composes with the delta renderer, and glyphs stay crisp
because nothing is ever resampled — which on a screen read as a reflection in
daylight matters more than geometric perfection. Heckbert's closed-form
square-to-quad map, so there is no 8×8 solve and no iteration.

The honest limit: a glyph is placed and sized by the transform, not sheared by
it. Your layout goes rectangular; a capital H stays upright rather than leaning
into the trapezoid. Below about fifteen degrees you will not see it.

**A `HudCanvas` that is not a `TFT_eSPI` subclass.** The themes draw through a
class with the same method names, which applies the geometry and forwards. Not
inheritance: TFT_eSPI's methods are not virtual, so a missed override would
silently bypass the transform and the failure would be one widget in the wrong
place — exactly the kind of thing that survives a review. Composition makes
anything unwrapped a compile error. With keystone off it forwards each call
verbatim, so the code path that shipped before is still the code path that runs.

**A new screen in the app, and a test pattern on the board.** `$GEOM` previews
live, `$GEOMSAVE` commits, `$GEOM?` reads back, `$GEOMTEST` puts a border, a
thirds grid, a centre cross and four corner labels on the glass. The pattern is
there for three reasons: you are aligning the *edges* of the projection and the
drive display has no edges; it is a few dozen lines rather than a screenful, so
a slider drag stays live; and the corner labels answer "is the mirror the right
way round" at a glance, which no amount of numbers will.

### Things the tests caught before the car did

**Two hundred slider movements would have been two hundred flash writes.** The
ESP8266 emulates its EEPROM by erasing a whole 4 KB sector, and `commit()`
rewrites all of it however few bytes changed. Preview and save are separate
messages now, and the host test drives two hundred previews and asserts zero
erases, then one save and asserts exactly one — and a second identical save and
asserts still one.

**And two hundred full repaints.** A full repaint is about a tenth of a second
at 20 MHz, so repainting per message would have put the board a second behind
inside ten seconds of dragging. Coalesced to one per 120 ms; the test counts
them and fails if it is one per message *or* if the preview stops being live.

**Both keystone sliders at maximum produced a shape the board refuses.** At 35 %
each, "narrow the bottom" plus "narrow the right" drags the bottom-right corner
across the diagonal and the quad turns concave — and the firmware's response to
a concave quad is to fall back to no correction at all, so the sliders would
have appeared to work right up until they silently stopped. Convexity holds
everywhere to 33 % and breaks at 34 %; the sliders are capped at 25 %, the
corner nudges still reach 35 %, and the test is now exhaustive over the whole
39,000-point slider grid rather than sampled — a step of 7 walked straight past
the failure.

**A pull of 99999 would have arrived as −31073.** `atol` into an `int16_t`
wraps, and the range check was inside `rebuild()`, downstream of the cast. The
clamp is on the parser now, before the value is ever narrowed.

**An alignment pattern with no way out.** A crashed app would have left a test
grid on the glass at 120 km/h. The board leaves it by itself after two minutes
without a `$GEOM*` message — and a nav frame does not dismiss it, or it would
vanish the moment the background service ticked.

**A checksum in the documentation that was wrong.** `$GEOM?` is `*3F`, not
`*0A`. The protocol test asserts every checksum quoted in `PROTOCOL.md`, which
is why this was a red test rather than an evening with a serial terminal.

**Dismissing the alignment pattern flashed a stale drive display.** Open the
keystone screen, lose the cable, and two minutes later the board dismisses the
pattern by itself — and repainted the speed and speed limit that were live
before the pattern went up, two minutes earlier, before correcting itself to NO
LINK on the same pass. A tenth of a second of a stale limit on the glass is
exactly what the NO LINK screen exists to prevent. The cause was two owners for
one decision: `applyGeom()` decided what should be on the screen using a link
state it had inherited, and the loop below decided it again from a freshly
computed one. There is one owner now. The regression test counts primitives on
the dismissal pass — 34 before the fix, 2 after — and I checked it fails against
the old code rather than assuming it would.

**The backlight and the gyro stopped while the pattern was up.** The early
return skipped them. A test grid at full daytime brightness at night is the last
thing anyone wants, and the car is still a car while somebody is aiming the
screen at the windscreen.

**The screen overwrote a board that had been set up from another phone.** The
app asked the board for its settings and pushed its own cached ones in the same
breath, then discarded the reply because it had just sent something. So "the
board's flash is the record" was true in the documentation and false in the
code. It now asks first, adopts the answer, and only then pushes — with a 1.5 s
fallback to the cache for a board that does not answer, and the driver's hand
always beating a late reply.

**Corners could be pushed outward, off the panel.** Legal on the wire and the
board would take it, but there are no pixels out there, so anything landing past
the edge is simply not drawn. Every useful correction shrinks the picture into
the shape the windscreen actually reflects. Corner nudges are inward-only now,
which also lets the preview promise never to draw outside itself; 6,561 corner
combinations are checked exhaustively.

**`RejectedExecutionException` on the serial reader thread.** A `$HELLO`
arriving as the service is being destroyed would have killed the thread — the
codebase already had a comment about exactly this failure mode elsewhere.

**A white screen on real hardware, which none of the above would have caught.**
The panel's RESET is tied to the board's reset line, so it comes out of reset
with the ESP — and an ST7796S wants 120 ms after that before it will accept a
command. The firmware went almost straight from boot to `init()`, so the init
sequence went into a controller that was not listening and the panel stayed at
its power-on white with a working backlight. Nothing about it looks like a
timing bug; it looks like bad wiring. The bring-up sketch that *did* work only
worked because it spent 700 ms printing a report first — an accidental delay
doing load-bearing work. Now explicit, with the reason written next to it.

Alongside it, `setRotation` is measured rather than trusted. The mirrored
landscape orientations are 5 and 7, which exist because TFT_eSPI takes `m % 8`;
an older copy of the library stops at 3, leaves the driver's width and height
at portrait, and clips every landscape draw away — the same white screen, from
a different cause. The sketch now checks the driver really came back 480×320
and falls back to plain landscape with a `$ROTERR` up the cable if not. An
unmirrored display beats no display. The test stub models an old library so the
fallback is exercised rather than assumed.

**And the bring-up sketches are under test now.** They were throwaway
diagnostics, so they were not — which is backwards, because a sketch whose only
job is to tell you whether your hardware works has to be the most trustworthy
code in the project. One of them shipped asking font 6 to draw an "x". The big
built-in fonts have no letters at all: font 6 is `1234567890:-.apm` and fonts 7
and 8 are digits and three punctuation marks, so a word handed to one indexes a
character table with no entry for it, and a bad pointer read on an ESP8266 is a
reboot. The stub now refuses any string given to a font that cannot render it,
and that check runs over both HUD themes too.

### Also

The phone and the board now hold two implementations of the same homography —
one to draw the preview, one to draw the screen — which is a thing worth being
uneasy about. `HomographyTest` checks the Kotlin against values produced by
compiling and running the C++, so if either side changes the other fails.

`config/User_Setup_ESP8266.h` avoids GPIO0, GPIO2 and GPIO15 for anything the
display holds at power-up. TFT_eSPI's stock ESP8266 setup puts DC on GPIO0 and
RESET on GPIO2, and a module that pulls either low stops the board booting
entirely — which looks exactly like a dead ESP8266 and is the most common
failure in this combination.

SPI ships at 20 MHz rather than the library's 27. The ST7796S datasheet's
minimum write cycle is 66 ns, which is 15.15 MHz, so the library default is
already 1.8× over spec; on dupont wire that is where it stops being tolerated.

The serial RX buffer is 1 KB rather than the stock 256 bytes: 115200 baud
delivers 1.4 KB during a full repaint, so every reconnect was losing a frame or
two to an overflow.

Both splash screens draw at font 4 doubled. TFT_eSPI's built-in fonts stop at
26 px for anything containing letters — 6, 7 and 8 are digits only — so on a 4"
panel the headline was a caption.

373 tests on the app, all passing, lint clean; ten test groups on the firmware,
all passing.


## 1.21 — the vibecoded checklist, honestly scored

Not a rewrite. You told me a while back to edit the project rather than
regenerate it, and a rewrite is also the surest possible way to add bugs to
something with 355 passing tests. What I did instead was score the app against
that list item by item, with evidence, and fix the ones that actually hit.

**Fifteen of the twenty cannot exist in a native Android app** — there is no
purple-to-blue gradient, no gradient hero text, no glassmorphism, no shadcn, no
Inter, no Space Grotesk, no cursor beam, no hover state, no fade-in on scroll,
no grain. Verified rather than assumed: one gradient exists in the whole
codebase, on the car marker, white at the tip to amber at the tail, and it is
there because a flat arrow lying on the ground plane looks like a sticker.

**Five hit, and they are fixed:**

**#7 low-contrast dark mode.** Real, and it matters more here than on a website
because this is read at seventy. Help text measured **4.13:1** on the page and
**3.58:1** on a card, under the 4.5 floor; the dim lane arrow was **3.83:1**;
"End route" was white on a red at **4.09:1** — the one destructive control in
the app. Now 5.00 at worst, 5.97 for End route, and every text pair in every
layout is above the floor.

**#8 three icon boxes in a row.** The quick menu was literally four 96×80 tiles
with a glyph and a caption. It is a list now: same-sized squares carry no
hierarchy, so you had to read all four labels to find one, and the label was
the only thing telling them apart. A list gives each action a full-width
target, puts the name where the eye already is, and has room on the right for
what the setting currently is — which is the question you open that sheet to
answer.

**#10 icons imported wholesale.** Fourteen of twenty-one were unmodified
Material. The set also mixed Material *Filled* with Material *Outlined*, which
is why ink coverage ran from 10% to 34% between glyphs sitting next to each
other: the outlined ones looked disabled. Picked one — outlined — and redrew
the three that did not match. The universal glyphs (search, back, mic) stayed
conventional on purpose; redrawing a magnifying glass to be different from
every other magnifying glass is worse design, not better.

**#4 one font used without thinking.** The Android version of that is four
families on one screen: Roboto, condensed, condensed-light and monospace, plus
two more set in code. Nobody chose four, they accumulated. The rule now:
condensed for numbers you read at a glance, because that is what an instrument
cluster does; regular for language; monospace only where columns must line up.

**#16 em dashes everywhere.** Guilty, in the copy and in my own changelogs.
Eighteen removed from user-facing strings. Four Calibrate buttons went from
"◀ 90°" to "90° left", because a button label is words and an icon is drawn.

### Caught in review before shipping

Two of these were mine from this same batch:

- **Turning the tiles into a list overflowed the screen.** Four 56dp rows plus
  a route, alternatives and diagnostics needs 422dp; a landscape phone with the
  bars hidden has 360dp, and a plain LinearLayout does not scroll — it clamps
  and clips off the bottom, silently. The status line went off-screen, and with
  diagnostics on, so did "End route". It scrolls now, and has a fixed width so
  it stops covering its own dismiss-scrim.
- **Fixing the dim lane arrow's contrast destroyed the cue it exists for.**
  Raising it off the floor moved it to **1.50:1 against the bright arrow** —
  half the 3:1 that a graphic carrying meaning needs, and that pair is the
  whole point of lane guidance. The maths says no amber satisfies both: legible
  against the cell means invisible against its neighbour. So the distinction is
  no longer carried by hue at all. The chosen movement is **filled**, the
  alternatives are **hollow** — which is what road markings do, survives a
  dirty screen and low sun, and does not depend on telling two oranges apart at
  a glance.

Plus: the Settings row was labelled with the settings *screen's* title; a
string duplicated another three lines above it and made TalkBack say it twice;
a style was written and never applied; and the two greys had drifted to 1.32:1
apart, which is three levels of hierarchy doing the work of two.


## 1.20 — a finished app, not a workbench

### Why the crash box kept coming back

The trace you sent finally said it: the report was written by **1.15**, on
24 August, by the BootReceiver reading preferences before the phone was
unlocked — a bug fixed in 1.17. The report just outlived the fix, because
nothing ever asked whether it was still about the version you were running.
A report from a build that is no longer installed is now discarded silently.

And it is no longer a stack trace in a system-grey box. It is a card in the
app's own colours that says the app restarted cleanly and there is nothing to
do, with the trace behind "Show details" for when there is.

### The design pass

The audit that started this counted, across the layouts, drawables and views:
**107 distinct hex colours** and not one reference to the colour file;
**fourteen near-blacks**; "the brightest text on the screen" spelled **five
different ways**; **fourteen text sizes**, seven of them consecutive integers;
**eighteen spacing values**, twelve off any grid; **ten corner radii**, four of
which rendered as the same capsule.

- **The theme was `DayNight` with no night resources.** Every colour in every
  layout is hard-coded dark, but the *widget* theme followed the device — so on
  a phone in light mode, which is the default, every Button, CheckBox, Spinner,
  EditText, Toast and dialog rendered light grey on white against a black
  screen. That single attribute was doing more damage to how finished the app
  looked than every layout in it. It is pinned dark now, with proper styles for
  all five widget types.
- **One palette**, twenty-two named tokens, and 226 literals migrated onto it.
  **One type scale**, six sizes. **One spacing scale**, on a 4dp grid. **Three
  corner radii.**
- **Setup was thirty-four widgets in a single flat column**, ending in the raw
  serial sentence being sent to the display, in monospace. It is now five
  titled cards in the order you need them, one primary action at the top, and
  everything a driver never needs folded behind "Diagnostics".
- **Toasts are gone.** A Toast is a grey system capsule at the bottom-centre —
  the wrong palette, the wrong end of a landscape screen, and un-styleable from
  Android 12. Messages now slide down from the top in the app's own card.
- **Empty states.** Search with no history, no results, no connection and no
  Mapbox key were four identical voids with one line of text over them. They
  are four designed states now, and "no key" no longer tells you to check your
  wifi when the fix is on the Setup screen.
- **Icons.** Fourteen of the twenty-one were unmodified Material clip-art —
  including a consumer camera with a lens ring meaning "speed camera", which
  read as "take a photo". That one, the settings cog, re-centre and the menu
  are redrawn, and Work no longer out-weighs Home by two to one beside it.
- **One mark for the app.** The launcher was a raster PNG sitting 6.6% off
  centre with no themed-icon layer, and its arrow appeared nowhere else — the
  notification used a different chevron, the map marker a third shape. It is
  one vector chevron now, centred, shared by the launcher, the notification and
  the re-centre button, with a monochrome layer for Android 13+.
- Twelve dead drawables and the fake "go" arrow on search rows — which implied
  a second tap target that never existed — are gone. Durations no longer
  disagree with themselves ("1 h 20" on a card, "1 h 20 min" in the pill).

### Rerouting

The wrong-turn test no longer waits for the debounce at all: off the line on
**this** fix, and pointing more than sixty degrees away from where the route
runs, means the turn has already happened and the request goes out immediately.
The GPS is also asked for everything the chip will give rather than one fix a
second, and the debounce that remains is measured in **time** rather than in
fixes, so it behaves the same at 1 Hz and at 10 Hz.

Two constants had to be rescaled with it, and this is the kind of thing that
bites quietly: a synthesised bearing needed "three metres between fixes", which
meant *above 11 km/h* at one fix a second and *above 54 km/h* at five. Both are
expressed as speeds now.

### Caught in review before shipping

- `ic_search` was tinted with the page colour, so it painted itself invisible —
  in the search empty state it was black on black at 1.00:1.
- Text fields and the three Setup pickers were invisible on the cards they sat
  on (1.06:1), and replacing the spinner background had taken the dropdown
  arrow with it, so they read as plain text.
- The Calibrate screen's buttons still missed the new styles, because that
  activity extends `Activity` and AppCompat never maps the platform attributes.
- At five fixes a second, a country lookup that keeps failing enqueues a task
  per fix on the same single-thread queue the road network and camera list are
  fetched on — so an hour out of signal would have starved the speed limit
  behind thousands of retries.
- `0L` was doing duty as both "not off the line" and a legal clock value, and
  it is exactly what the stubbed clock returns in a unit test — so the new
  debounce silently disabled itself in every test that did not pass a clock.


## 1.19 — the stutter, properly this time

**1.18 did not fix the stutter, and the reason is that I caused it in 1.17.**

The camera check added in 1.17 — "is this camera on my road, or the one next
door?" — walked *every road in the loaded window* for *every camera in range*,
four times a second. The window is up to six kilometres, which around Brussels
is about 17,000 OpenStreetMap ways and 100,000 vertices. Measured on the real
Overpass response for a 6 km circle here:

| | free-drive tick |
|---|---|
| before 1.17 | ~4 ms |
| 1.17 and 1.18 | **19–21 ms**, four times a second |
| 1.19 | **0.13 ms** |

On a phone or a head unit that is a CPU core held down permanently, which is
heat, a throttled GPU, and a location callback that cannot get a look in.

The fix is a spatial grid on the loaded road network — 250 m cells, built once
on the thread that fetched the data, so nothing has to scan the whole network
again. Verified against the real 17,000-way Brussels network over 300 random
query points against a brute-force scan: no road within the match limit is ever
missed, and the candidate set is 565 of 16,985.

### "GPS says no signal most of the time"

The receiver was fine. The app subscribes to GPS *and* the network provider;
the network one fires every two seconds and is correctly refused while GPS is
alive, because a cell-tower fix can be a kilometre wide. But each refusal was
written into the status field, and the map reports any refusal as WAITING FOR
GPS — so under a clear sky with a solid lock the chip flickered to "no signal"
about half the time, reporting a fix the app had *deliberately thrown away* as
the state of the receiver.

Now it only says anything when the last *usable* fix is more than 3.5 s old.
A receiver that stops delivering entirely is still caught, by the tick.

### "When driving, turning my phone does not turn the arrow"

Also real, and 1.18's changelog claimed the opposite. 1.18 made the compass
drive the *fused heading* below 70 km/h — but the map screen never used it:
whenever the car was snapped to a road and moving, the arrow took the **road's**
bearing, and the compass was in charge of a number nothing displayed. At
23 km/h on the Grote Baan, turning the phone did precisely nothing.

The arrow and the map now use different headings, on purpose: the map keeps the
road bearing so it does not wobble down a straight street, and the arrow takes
the compass. In a cradle with the offset learned the two agree, so it still
points up the road; pick the phone up and it turns.

Gated on the mounting offset actually being known — unlearned, the compass
reads the *phone's* heading rather than the car's, and an arrow at 45° to a
correctly aligned map reads as broken rather than as uncalibrated.

That rule now lives in its own file with tests. It was three lines buried in a
three-hundred-line method, which is how 1.18 shipped a changelog claiming it
worked with 337 green tests that could not tell.

### The crash box about version 1.15

The dialog was only cleared when you tapped one of its buttons — and it is
deliberately not dismissible, so pressing Home instead left the file in place.
It then came back on every launch, for months and several versions, reporting a
crash from 1.15 as though it had just happened, and hiding any real one behind
it. It is now marked seen as soon as it is shown, and the file is deleted when
the screen actually closes — not on a night-mode switch, which recreates the
screen and would otherwise take the report with it.

### Diagnostics, so the next one is not guesswork

**Setup → "Show frame timings in the quick menu"**, then open the ⋮ menu while
driving. It shows the running version, actual vsync rate, camera frames per
second, average and worst frame cost, the background tick cost, how many roads
and cameras are loaded, the heading source and the GPS state. Screenshot that
instead of describing it — "stutters" can mean late frames, fine frames over a
map that is not moving, a starved GPS, or a busy CPU, and those have nothing in
common except how they look from the driver's seat.

**Dismissing the alignment pattern flashed a stale drive display.** Open the
keystone screen, lose the cable, and two minutes later the board dismisses the
pattern by itself — and repainted the speed and speed limit that were live
before the pattern went up, two minutes earlier, before correcting itself to NO
LINK on the same pass. A tenth of a second of a stale limit on the glass is
exactly what the NO LINK screen exists to prevent. The cause was two owners for
one decision: `applyGeom()` decided what should be on the screen using a link
state it had inherited, and the loop below decided it again from a freshly
computed one. There is one owner now. The regression test counts primitives on
the dismissal pass — 34 before the fix, 2 after — and I checked it fails against
the old code rather than assuming it would.

**The backlight and the gyro stopped while the pattern was up.** The early
return skipped them. A test grid at full daytime brightness at night is the last
thing anyone wants, and the car is still a car while somebody is aiming the
screen at the windscreen.

**The screen overwrote a board that had been set up from another phone.** The
app asked the board for its settings and pushed its own cached ones in the same
breath, then discarded the reply because it had just sent something. So "the
board's flash is the record" was true in the documentation and false in the
code. It now asks first, adopts the answer, and only then pushes — with a 1.5 s
fallback to the cache for a board that does not answer, and the driver's hand
always beating a late reply.

**Corners could be pushed outward, off the panel.** Legal on the wire and the
board would take it, but there are no pixels out there, so anything landing past
the edge is simply not drawn. Every useful correction shrinks the picture into
the shape the windscreen actually reflects. Corner nudges are inward-only now,
which also lets the preview promise never to draw outside itself; 6,561 corner
combinations are checked exhaustively.

**`RejectedExecutionException` on the serial reader thread.** A `$HELLO`
arriving as the service is being destroyed would have killed the thread — the
codebase already had a comment about exactly this failure mode elsewhere.

**A white screen on real hardware, which none of the above would have caught.**
The panel's RESET is tied to the board's reset line, so it comes out of reset
with the ESP — and an ST7796S wants 120 ms after that before it will accept a
command. The firmware went almost straight from boot to `init()`, so the init
sequence went into a controller that was not listening and the panel stayed at
its power-on white with a working backlight. Nothing about it looks like a
timing bug; it looks like bad wiring. The bring-up sketch that *did* work only
worked because it spent 700 ms printing a report first — an accidental delay
doing load-bearing work. Now explicit, with the reason written next to it.

Alongside it, `setRotation` is measured rather than trusted. The mirrored
landscape orientations are 5 and 7, which exist because TFT_eSPI takes `m % 8`;
an older copy of the library stops at 3, leaves the driver's width and height
at portrait, and clips every landscape draw away — the same white screen, from
a different cause. The sketch now checks the driver really came back 480×320
and falls back to plain landscape with a `$ROTERR` up the cable if not. An
unmirrored display beats no display. The test stub models an old library so the
fallback is exercised rather than assumed.

**And the bring-up sketches are under test now.** They were throwaway
diagnostics, so they were not — which is backwards, because a sketch whose only
job is to tell you whether your hardware works has to be the most trustworthy
code in the project. One of them shipped asking font 6 to draw an "x". The big
built-in fonts have no letters at all: font 6 is `1234567890:-.apm` and fonts 7
and 8 are digits and three punctuation marks, so a word handed to one indexes a
character table with no entry for it, and a bad pointer read on an ESP8266 is a
reboot. The stub now refuses any string given to a font that cannot render it,
and that check runs over both HUD themes too.

### Also

- The 200 ms refresh computed the whole status line twice and rebuilt a colour
  tint on every tick; both once now, and only when they change.
- Five different hand-written `User-Agent` strings had drifted to 1.0, 1.1,
  1.4, 1.6 and 1.8 while the app was on 1.18 — so Overpass and Nominatim, whose
  free service this leans on, were being told five different things about who
  was calling. One string now, from the build.
- A way with an implausibly wide bounding box can no longer make the index
  builder allocate a hundred thousand cells.

### On the APK size

It is ~22 MB in every version and that is expected, not a sign the build did
not change: almost all of it is MapLibre's native rendering libraries, which
are the same in every release. 1.19 is 23,477,908 bytes against 1.18's
23,460,964 — the difference is the code. You can confirm the running version at
the top of the diagnostics overlay above.


## 1.18 — stutter, reaction time, and the compass

### The stutter

The map was being redrawn thirty times a second whether or not anything had
changed — parked at a red light, engine off, still thirty frames a second.
MapLibre only draws when something marks it dirty, and this app marked it dirty
on every frame: `moveCamera()` has no equality check of its own, and the
camera's smoothing is exponential, so it approaches its target and never
reaches it. The position kept "changing" by nanometres, for ever. Same for the
marker's GeoJSON source. Both now return early when nothing moved by more than
5 cm — under one physical pixel even on a 3× phone at junction zoom.

- **The frame clock was beating against the display.** 33 ms against a 16.67 ms
  vsync is *just* short of two frames, so the phase drifted and roughly every
  three and a half seconds a camera update missed its vsync and the map jumped
  three frames instead of two. It runs off `Choreographer` now — one callback
  per displayed frame, phase-locked, gated to ~30 Hz by elapsed time so a
  120 Hz phone does not get double the main-thread work.
- **150 sensor callbacks a second were landing on the main thread**, ahead of
  the camera frames in the same queue. They have their own looper now, and
  `HeadingFusion` was made properly thread-safe to go with it.
- **Every 200 ms tick forced a full measure-and-layout pass of the whole
  screen.** `TextView.setText` has no equality check and every one of these is
  `wrap_content`, so eleven of them, five times a second, threw away and
  rebuilt their text layouts for text that was almost always identical. Guarded
  now, as are the maneuver, lane and junction views, which were redrawing
  unconditionally — `JunctionView.onDraw` alone is about 150 canvas operations.
- **3D buildings cost twice what they needed to.** `fill-extrusion-opacity` of
  anything below 1 makes MapLibre draw the whole building mesh twice, once for
  depth and once for colour. The layer is now opaque at the colour the
  translucent version resolved to — identical picture, half the work — and
  filtered to buildings tall enough to be worth extruding.
- Render thread capped at 30 fps; tile prefetch dropped from 4 levels to 1
  (the source tops out at zoom 14, so prefetching four levels up was fetching
  z10 tiles that cover the screen thousands of times over).
- The route line was being rebuilt as **200–400 separate GeoJSON features** for
  a 6 km route — one per congestion transition — because the guard meant to
  merge short runs only ran above 1500 vertices and a 6 km route has 400–1200.
  Runs shorter than 40 m are now folded in: 10–30 features, no visible
  difference, and no lurch on the redraw after a reroute.

### Reaction time on a wrong turn

Distance is slow evidence at a junction. Turn right where the route went
straight and you are still only fifteen metres from the line for the first
couple of seconds, so a purely distance-based test waits for you to get far
enough away to be certain. Direction is fast evidence: the moment the car is
pointing 60° away from where the route runs *and* it is off the line, the turn
has already happened, and the reroute goes out immediately with no confirmation
delay. The off-route streak also drops from three fixes to two, which the
direction test is what makes safe — a spurious fix under a bridge does not also
come with the car pointing the wrong way.

Roughly six or seven seconds down to about two.

### The compass turns the arrow now

The old rule handed the arrow to GPS at 0.7 m/s — 2.5 km/h — and locked the
compass out for four seconds after every fix. In practice the compass never
drove anything while the car was moving.

Speed decides now: **the compass turns the arrow up to 70 km/h, GPS above it**,
with 10 km/h of hysteresis so sitting on a 70 limit does not flip back and
forth. So the marker behaves the way a compass behaves — swing the car in a car
park, crawl in traffic, sit at a light, and the arrow follows.

What keeps that honest is the mounting offset, which is now learned from the
GPS bearing from 14 km/h up (was 25) and converges in seconds rather than a
minute. The compass supplies the fast response; GPS supplies the truth it is
measured against. And the compass is still demoted outright when the two
disagree by more than 45° for two seconds.

Two ways the arrow could go wrong, both fixed:

- **A silenced compass used to freeze the heading.** The "this phone has a
  magnetometer" flag latched on the first reading and never cleared, but the
  compass can be silenced without ever being *distrusted* — a neodymium mount
  puts the field far outside the plausible range, and so does a multi-storey or
  a tunnel. The arrow then had no source that could correct it below 70 km/h
  for the rest of the drive, while the demotion path that exists for exactly
  this was unreachable, because it needs a fresh reading to compare against. A
  stale compass now hands the arrow straight back to GPS.
- **Switching the heading axis** (which end of the phone the heading is read
  from) no longer blanks the learned offset or leaves a stale one in place; it
  marks it unlearned so the next GPS bearing takes it outright.

**Dismissing the alignment pattern flashed a stale drive display.** Open the
keystone screen, lose the cable, and two minutes later the board dismisses the
pattern by itself — and repainted the speed and speed limit that were live
before the pattern went up, two minutes earlier, before correcting itself to NO
LINK on the same pass. A tenth of a second of a stale limit on the glass is
exactly what the NO LINK screen exists to prevent. The cause was two owners for
one decision: `applyGeom()` decided what should be on the screen using a link
state it had inherited, and the loop below decided it again from a freshly
computed one. There is one owner now. The regression test counts primitives on
the dismissal pass — 34 before the fix, 2 after — and I checked it fails against
the old code rather than assuming it would.

**The backlight and the gyro stopped while the pattern was up.** The early
return skipped them. A test grid at full daytime brightness at night is the last
thing anyone wants, and the car is still a car while somebody is aiming the
screen at the windscreen.

**The screen overwrote a board that had been set up from another phone.** The
app asked the board for its settings and pushed its own cached ones in the same
breath, then discarded the reply because it had just sent something. So "the
board's flash is the record" was true in the documentation and false in the
code. It now asks first, adopts the answer, and only then pushes — with a 1.5 s
fallback to the cache for a board that does not answer, and the driver's hand
always beating a late reply.

**Corners could be pushed outward, off the panel.** Legal on the wire and the
board would take it, but there are no pixels out there, so anything landing past
the edge is simply not drawn. Every useful correction shrinks the picture into
the shape the windscreen actually reflects. Corner nudges are inward-only now,
which also lets the preview promise never to draw outside itself; 6,561 corner
combinations are checked exhaustively.

**`RejectedExecutionException` on the serial reader thread.** A `$HELLO`
arriving as the service is being destroyed would have killed the thread — the
codebase already had a comment about exactly this failure mode elsewhere.

**A white screen on real hardware, which none of the above would have caught.**
The panel's RESET is tied to the board's reset line, so it comes out of reset
with the ESP — and an ST7796S wants 120 ms after that before it will accept a
command. The firmware went almost straight from boot to `init()`, so the init
sequence went into a controller that was not listening and the panel stayed at
its power-on white with a working backlight. Nothing about it looks like a
timing bug; it looks like bad wiring. The bring-up sketch that *did* work only
worked because it spent 700 ms printing a report first — an accidental delay
doing load-bearing work. Now explicit, with the reason written next to it.

Alongside it, `setRotation` is measured rather than trusted. The mirrored
landscape orientations are 5 and 7, which exist because TFT_eSPI takes `m % 8`;
an older copy of the library stops at 3, leaves the driver's width and height
at portrait, and clips every landscape draw away — the same white screen, from
a different cause. The sketch now checks the driver really came back 480×320
and falls back to plain landscape with a `$ROTERR` up the cable if not. An
unmirrored display beats no display. The test stub models an old library so the
fallback is exercised rather than assumed.

**And the bring-up sketches are under test now.** They were throwaway
diagnostics, so they were not — which is backwards, because a sketch whose only
job is to tell you whether your hardware works has to be the most trustworthy
code in the project. One of them shipped asking font 6 to draw an "x". The big
built-in fonts have no letters at all: font 6 is `1234567890:-.apm` and fonts 7
and 8 are digits and three punctuation marks, so a word handed to one indexes a
character table with no entry for it, and a bad pointer read on an ESP8266 is a
reboot. The stub now refuses any string given to a font that cannot render it,
and that check runs over both HUD themes too.

### Also

- Tapping "follow" after panning the map could do nothing at all while the car
  was stationary — the new no-change check compared against values describing a
  camera the finger had already moved. The cache is dropped whenever follow is
  re-engaged, the route changes, or the style reloads.
- Belgian buildings with no height tag are no longer hidden by the new filter.


## 1.17 — the line-by-line sweep

A full audit of all 12,000 lines plus the firmware, subsystem by subsystem,
with every uncertain assumption checked against the official documentation
rather than guessed. 33 defects found and fixed, 15 new regression tests.

### Wrong warnings — the ones that were visible from the driver's seat

- **Speed bumps, level crossings and toll booths were being announced as speed
  cameras.** The free-drive Overpass query asks for all four kinds of node,
  because the road-feature warnings need them, and the parser added *every*
  returned node to the camera list with no tag test. Every hump in every
  residential street was a radar.
- **A camera genuinely on your road could be silently suppressed.** The
  "is another road nearer to this camera than mine?" test compared the camera's
  distance to the *route line* against its distance to every OSM *way*. A route
  polyline is a generalisation and sits 5–12 m off the tarmac it follows, so
  the camera's own carriageway always looked nearer. It now compares way
  against way, using a window of route vertices to identify which ways carry
  the route — and the decision is no longer cached across a reroute.
- **Free drive did none of that.** It used a 30 m tolerance against a single
  OSM way, skipped the test entirely whenever the road match was untrusted, and
  with no heading at all announced cameras *behind* the car. It now measures
  against both the matched way and the line being driven, applies the
  nearest-other-road test, and says nothing until it knows which way the car
  points. A road running parallel to us is never treated as a rival claimant:
  OSM splits a street at every junction, and the continuation is our own road.
- **Every camera from an `enforcement=maxspeed` node or an enforcement relation
  was drawn on the HUD as a positionless "ZONE" with its distance discarded** —
  876 of them in Belgium alone. The wire kind was `ordinal + 1`, and `UNKNOWN`
  is ordinal 3, which is the danger-zone code.
- **Danger-zone mode plotted the camera's exact position on the map.** The
  distance was blurred, the text said "enforcement likely", the HUD printed
  ZONE — and the map dropped a pin on the radar. ZONE is the default for every
  country not explicitly verified, not a rare case.
- **Turning camera alerts off mid-route did nothing.** `CameraWatcher` captures
  the policy when it is built, and changing the setting never rebuilt it.
- **A failed camera refresh deleted every camera on the route** and then
  reported "0 cameras, just now". A camera fetch for an abandoned route could
  also install itself onto the route you were actually driving.
- **A failed country lookup froze the previous country's rules for 25 km**,
  because the checkpoint was stamped before the lookup rather than after it.

### Heading

- **The compass was dead on an upright cradle-mounted phone.** `bestAxis`
  scored the device's Y axis while the reader used the *display's* Y axis, and
  the activity is locked to landscape — so on a portrait-native phone the two
  are never the same axis. It re-elected an axis the reader could not resolve,
  on every sample, forever: no heading at all while stopped, which is the one
  thing the compass exists for.
- Switching the heading axis kept the mounting offset learned against the old
  one — up to 90° wrong until an hour of motorway driving blended it out.
- Declination was applied as zero until the first GPS fix, so the pre-fix arrow
  was magnetic rather than true and disagreed with the calibration screen.
- The HUD board's IMU rate was integrated over the camera frame (33 ms) instead
  of its own sample interval (50 ms): every 90° roundabout came out as 60°.
  "IMU lost" also fired about ten times a second while the IMU was healthy.
- A synthesised GPS bearing had no time bound, so the first fix after a tunnel
  took the straight line from entrance to exit as the car's heading — and above
  25 km/h *persisted* that as a mounting offset.
- The compass-distrust hysteresis could be skipped entirely, demoting a healthy
  compass for a full minute on one bad sample.

### Voice

- **Audio focus was taken once and never given back**, so the radio stayed
  ducked for the entire drive after a single announcement.
- A camera 199 m ahead was announced as "in one hundred metres" — the distance
  was truncated to the hundred below, not rounded.
- The final "turn right now" was queued behind narration instead of flushing
  it, and could arrive after the junction.
- The de-duplication maps were trimmed *after* recording the announcement about
  to be made, so every 65th maneuver was said twice.
- The over-limit chime never re-armed once the speed limit became unknown.
- "You have arrived" repeated on GPS jitter while parked.

### Display

- The straight-ahead lane arrow was drawn as a bar with a triangle floating
  half a radius above it. The U-turn maneuver arrow had the same break, 0.6 of
  a radius wide.
- The chosen lane arrow was always drawn in the rightmost slot whatever it was,
  reversing the arrows the driver matches against the road markings — and a
  lane with four movements could lose its bright arrow entirely.
- A roundabout with no exit number drew the *first* exit — a hard right — with
  no digit to contradict it.
- "HUD DISCONNECTED" was shown for the whole drive with a working cable: the
  test matched the link description against "Usb", and the USB link describes
  itself by its driver class.
- A closure 144 km ahead was reported from the first tick as "Road closed in
  143820 m", which also suppressed the destination line for the whole trip.
- Choosing a new destination left the previous route line and camera pin
  painted on the map — permanently, if the new route request failed.

### Service and links

- `onDestroy` did a blocking serial write and close **on the main thread** — an
  ANR waiting for a wedged board.
- A cancelled route could be resurrected after a process kill:
  `START_REDELIVER_INTENT` is only finished by `stopSelf(startId)`, which this
  service never calls. The live destination is now kept in preferences and
  cleared on cancel, on Stop and on arrival.
- Reopening the cable queued behind a 60-second Overpass call on a shared
  executor, so the HUD stayed blank for a minute with a good cable plugged in.
- Restarting the tick wiped the whole worker queue, including the "no GPS fix
  yet, retry routing in three seconds" retry — a cold start with a destination
  and no fix waited for a route that was never requested again.
- Bluetooth leaked a socket on every failed connect, ran unsynchronised across
  three threads, and let `cancelDiscovery()` — which needs a permission the app
  does not hold and does not need — abort the whole connection.
- The USB permission dialog's answer was opened on the main thread inside the
  lock, and could be thrown away entirely by the retry throttle.
- `LOCKED_BOOT_COMPLETED` crashed the process on every boot of an encrypted
  device, and `ACCESS_BACKGROUND_LOCATION` was missing, so a boot start could
  never bring the HUD up on Android 14.

### Map data

- `oneway=-1` — the legal direction is the reverse of the way as drawn — was
  collapsed to a plain one-way, so those streets never matched at all: no name,
  no speed limit, no snapping, and no camera filtering.
- The two `maxspeed` parsers disagreed: `FR:urban` was 50 to one and 0 to the
  other, which gave a French town camera a 2 km danger zone instead of 300 m.
- Belgian junction-sign colours were inverted in three of four cases. Green is
  for a motorway destination signed *from the ordinary network*; every panel on
  a motorway gantry is blue. Sourced from AM 11.10.1976 art. 12.9.1 and the
  Flemish and Walloon implementing standards.
- All the sign-colour tests were passing vacuously — the stubbed `Color.rgb()`
  returns 0 in unit tests, so blue, green, yellow and white were the same value
  and two mutually contradictory Belgium assertions both passed.
- French D-roads were classified as motorways.
- Mapbox geocoding put form-encoded text (spaces as `+`) into a URL *path*.

### Firmware

- An unrecognised camera kind *cleared* the warning instead of being ignored.
- The E60 over-limit brackets were drawn outside the rectangle that gets
  cleared, so at three-digit speeds they stayed on the glass until a link drop.
  The distance and street clears were 2 and 4 px shorter than the font cell,
  leaving ghost rows that accumulated.
- A dropped cable left the camera bar and lane strip in the state that gets
  repainted on reconnect — so a camera you had already passed came back and
  never left.
- The modern theme drew the remaining distance and the camera string at the
  same anchor: "128.3 km" plus "ZONE" rendered as "12ZONEm".
- Gyro re-zeroing blocked the loop for 440 ms — about 5,000 bytes into a
  256-byte UART buffer — and was not cancelled when the car pulled away.
- The whole HUD state was copied twice per received byte.


## 1.16

**The camera on the road you are not on.** A camera was allowed to snap onto
the route from 28 metres away, and 28 metres is the width of a back street —
so the camera watching the road *parallel* to yours attached itself to your
route and announced at zero metres. Two fixes, because distance alone cannot
tell "beside my road" from "on the next road over":

- the tolerance is 15 m, the widest still defensible for a camera at the kerb
  of a dual carriageway;
- and the real test, which is *which road the camera is nearest to*. A camera
  belongs to the carriageway it watches, so if another road in the loaded
  network is clearly closer to it than our route is, it is watching that one.
  A 6 m margin keeps a near tie — a service road beside a main road — rather
  than throwing away a camera that is genuinely ours. The answer is memoised
  per camera and thrown away whenever the road network is refetched.

**The arrow sitting beside the road instead of on it.** Self-inflicted, in
1.11.1. Snapping was switched off whenever the projection clamped to the end
of an OSM way — a rule added to stop the marker sticking at a junction. But
OSM splits ways *at* junctions, so approaching any of them the snap turned off
and the marker sprang sideways onto the pavement, which in town is most of the
time. The rule now only rejects a clamp that has to drag the point more than
10 m to reach the vertex. A clamp that barely moves it is not a lie: you are
standing at the end of the road, and the end of the road is still the road.


## 1.15

**Built for the head unit, now that I know that is where it lives.** Android 10
turns out to be the *easy* target: every restriction that makes a long-running
foreground service awkward on a modern phone arrived after API 29, and each
needs both a matching targetSdk and a device running it. On the head unit there
is no ban on starting a foreground service from the background, no mandatory
service types, no type permissions, and no timeout. Background location needs
no extra permission either — a service typed `location` counts as foreground
for location purposes, which is exactly what this one declares.

**Starts with the car.** A boot receiver brings the service up when the unit
powers on, so the speed limit is on the glass by the time the engine is
running. On/off in Setup, on by default.

**`android:persistent="true"`.** On a side-loaded APK this only gets the
process started at boot. Copy the same APK into `/system/app` on a rooted unit
and the other half switches on: the framework starts it before `BOOT_COMPLETED`
is even broadcast, parks it at `oom_adj -800` where the low-memory killer will
not reclaim it, and **restarts it if the process ever dies** — a watchdog
nobody had to write.

**`tools/headunit-setup.sh`** — one-time setup with a pass/fail line per step,
and almost none of it needs root: the shell user already holds the permissions
involved. Doze whitelist, three app ops pinned to allow, standby bucket, and a
location check.

**The thing worth knowing about head units.** Doze is supposed to stay away
while a device is charging, but the check is not "is power connected" — it is
`present && plugged`, where `present` means a *battery* is present. Plenty of
head units have none and report `present=false`, so the unit is wired to the
ignition and Doze arms itself anyway the moment the screen sleeps. In Doze,
wake locks are ignored, which stops the 4 Hz loop dead. On this hardware the
Doze whitelist is not insurance, it is the fix. The script prints what the unit
reports so you can see which case you are in.

Also documented, in `docs/HEADUNIT.md`: use `/system/app` and never
`/system/priv-app` — since Android 9 a privileged app whose permissions are not
in the allowlist stops the device booting, and there is nothing in `priv-app`
this app needs.


## 1.14

**A foreground service does not keep the CPU awake, and this one had no wake
lock.** That is the bug behind "the HUD stops when I leave the app" — and it
would have been invisible, because nothing crashes and nothing is logged. The
screen goes off, the process is suspended, the 4 Hz tick stops advancing, and
three seconds later the display decides the cable has fallen out and shows
NO LINK. Android's own guidance opens its decision tree with *"Is your app
running a foreground service, and you need to keep the device awake when screen
is off?"*, which would be a strange question if the service did it for you.

The service now holds a `PARTIAL_WAKE_LOCK` for as long as it runs, re-armed
every half hour from the tick — a timeout rather than a bare acquire, so a lost
release path costs a few hours of battery rather than the day.

`android:stopWithTask="false"` is now written out explicitly. It was already
the default, but that attribute decides whether swiping the app away takes the
display with it, and a default nobody can see in the manifest is a poor place
to keep that.

**Setup now says whether the phone will let it run.** Battery optimisation,
background restriction, Low Power Standby and battery saver, each answered
yes/no by the system, with a one-tap button for the exemption dialog. Plus the
settings only you can reach — and on a OnePlus there are two that nobody would
guess: locking the app in the recents list is what stops the phone silently
reverting the battery setting, and "Deep optimisation" is a separate switch that
overrides the per-app one. dontkillmyapp.com ranks OnePlus third worst on the
market for exactly this.

The screen also reports **why the app stopped last time** — system kill, crash,
low memory — read from `getHistoricalProcessExitReasons`. It is the only way to
tell "OnePlus killed it" from "it crashed" after the fact.

Confirmed while researching this: on stock Android 16, swiping the task away
neither stops a foreground service nor kills its process — `ActivityTaskSupervisor`
returns early for any process with one. `location` and `connectedDevice`
services have no runtime limit either. So the quiet-mode behaviour was right;
it just could not survive the screen going off.


## 1.13

Researched properly this time, before writing anything — the working notes are
in `docs/HEADING.md` with the sources.

**Turning the phone gave the opposite direction, and it was a misread doc.**
Android's `remapCoordinateSystem` names *"the axis of the new coordinate system
that coincides with the X axis of the original"*. I had read it as the
transpose — which axis becomes the new one. For a quarter turn the transpose is
the inverse, so ROTATION_90 and ROTATION_270 swapped and every reading came out
180° wrong. Held one way up it looked right; USB-C on the other side, backwards.

**A phone in a windscreen cradle had no heading at all.** `getOrientation`
reports the azimuth of the frame's +Y axis, and a phone stood upright points +Y
at the roof, where the azimuth is not inaccurate but undefined — simulated
against Android's own implementation it returns a *constant* whichever way the
car faces. OsmAnd #21283 and Organic Maps #6410 are both this bug. The app now
reads the direction the back of the phone faces, which is horizontal in exactly
that case and, usefully, does not change when the handset is turned end for end
in its cradle.

**Arbitrate, don't blend.** A GPS bearing above walking pace takes the heading
and locks the compass out for four seconds; below that the compass has it. That
is not a number I picked — Organic Maps, CoMaps and OsmAnd all converged on it
independently, all having started higher. Standing still the arrow is a compass,
so you know which way you are facing before setting off; moving, it is GPS,
measured from orbit and unbendable by the car. The 40 km/h I was asked for
turned out to be too generous: there is no speed band where a compass in a steel
shell beats a GPS course.

**Three gates before a compass reading is believed** — the platform's own
accuracy flag, a field-strength check (Earth's field is 30–60 µT; outside
10–100 µT it is the door pillar, a speaker or the mount, and that is what
produces a 20–40° jump), and the rotation vector's published 95% heading-error
bound, which almost nobody reads. What survives is smoothed as a circular
average of sin and cos so it is correct across the 0/360 seam. Neither OsmAnd
nor Organic Maps does the field check, and both have open "drunk arrow" bugs.

**Magnetic north is not true north.** `GeomagneticField` now corrects it —
+2.6° in Brussels, about +6° in Romania — recomputed as the car moves rather
than once per process.

**Caught in verification, before shipping.** Choosing the heading axis per
sample would have introduced a *90° flip* on any cradle that leans, worse than
anything this release set out to fix; it is a held decision now. The HUD's own
gyro was blocking the compass entirely, so with the cable in and no GPS the
arrow never appeared — an IMU reports a turn rate and can never say which way
the car points. The compass was also five times slower than its own comment
claimed, trailing 53° behind a car swinging into a parking space. Plus: the
mounting offset could be learned from a reading the gates had just rejected and
then persisted; a stale compass reading could demote a healthy compass; the
hand calibration was off by the declination; and a stalled HUD cable kept the
arrow turning for a second and a half at the last rate it saw.


## 1.12

**The arrow is a compass now, and above 40 km/h it stops being one.** That is
the whole design, and it is simpler than what it replaces.

Below 40 km/h the heading comes straight from the magnetometer: point the car
somewhere and the arrow points there, standing still, immediately, with no
waiting for a GPS bearing and no dead reckoning to go wrong. Above 40 km/h it
switches to GPS, because at that speed the compass has lost its only advantage
— a magnetometer inside a steel shell full of speakers and current is the worse
instrument, and GPS is measuring the direction the car is actually travelling.
There is a gap between the two thresholds (back to compass below 32 km/h) so
stop-start traffic cannot make it flap between sources.

What this replaces was a gyroscope integrating differences with GPS as its only
absolute reference: more machinery, and worse behaviour at exactly the speeds
where you are looking at the screen.

**The compass is read in the display's frame**, the way Android's own compass
app reads it. That is why a phone lying in a landscape cradle now needs no
mounting offset at all — the top of the screen is already pointing down the
road, and the reading matches what a compass app would show for the same phone.

**Calibration is turning the phone through a full circle.** The ring around the
dial fills as each direction is covered, so "turn it all the way round" has an
end you can see instead of being a guess, and the screen reports when the
sensor declares itself accurate. That circle is what actually calibrates a
magnetometer: the estimator watches the field sweep past and solves for the
constant error the bodywork adds. The nudge buttons are still there for the
mount where the screen does not face forward.


## 1.11.2

**The arrow spun on the spot when the phone was tilted.** Two orientation
sensors were feeding one difference. The app registers both of Android's
rotation vectors — the game one for smooth turn rates, the full one for north —
and they describe the same physical attitude in *different yaw frames*, because
the game variant has no magnetometer and its zero is arbitrary and drifts.
Subtracting a sample of one from a sample of the other gives the angle between
two reference frames rather than any motion of the car, fifteen times a second.

Deltas now come from exactly one sensor, chosen when the listeners are
registered; the other is used only for north. `onOrientation` also takes a frame
id and re-establishes its reference rather than differencing across a change, so
the same mistake can never again turn into a phantom turn — it would cost one
sample instead. Both are covered by a test that was checked against the old code
and fails on it: a stationary car "turned" 24 degrees.

`Compass.MIN_HORIZONTAL` went from 0.25 to 0.5 as well. An axis 75 degrees from
horizontal technically still has a heading, and the last stretch before that
cut-off is exactly where the reading starts whirling, because the horizontal
projection whose direction it reports has almost no length left. 60 degrees
still covers any sane cradle.


## 1.11.1

**Fixes the crash 1.11 shipped, and the reason it got through.** Two style-id
constants had drifted onto the same string — `navhud-route-casing` was declared
twice, so the app added the same layer twice, MapLibre threw
`CannotAddLayerException` from inside the style callback, and the map screen
could not be opened at all. A casing layer already existed; the change should
have darkened it rather than adding a second one.

The mistake was sitting in a thirteen-line column of constants, which is
exactly the length at which an eye stops reading. So the ids now live in
`map/MapIds.kt` with no Android dependencies, and a unit test asserts they are
all distinct — verified by deliberately reintroducing the duplicate and
watching the test fail. `installLayers` is also idempotent now and logs instead
of throwing, so a duplicate can never again be the difference between a working
app and no app.

**A silent no-op edit in 1.10 meant the dot marker was never registered.** The
change that was supposed to add its `addImage` call matched nothing and did
nothing. MapLibre does not object to a layer naming an image that does not
exist — it just draws nothing — so the marker would have vanished whenever the
heading was unknown, with no error anywhere. Registered now, and `verifyStyle`
logs any layer, source or image that failed to make it into the style.

**Found in review before shipping, all in the same area:**

- The free-drive re-projection had no gate. `projectOnto` clamps to the ends of
  a way, and OSM splits ways at junctions — so at every turn the marker pinned
  itself to the junction and sat there while the car drove into the side
  street, then jumped. `snapWithin` now refuses both a projection that is too
  far away and one that has clamped to an end vertex.
- `roadPts` was never cleared when a route started, so it held whatever road
  free drive matched before the trip. Entering a new destination mid-route
  cleared `currentRoute` on the main thread while `snapTrusted` still said
  true, and for a quarter of a second the map projected onto a road hundreds of
  kilometres away — swinging the whole camera, not just the marker.
- Removing a source while one of its layers still stood was refused natively
  but dropped the Java peer anyway, leaving a source that could never be
  replaced. Layers now come out before sources.
- The 30 Hz camera loop swallowed exceptions with no log at all.


## 1.11

**The lane arrows pointed the wrong way, and that is the worst bug this app has
had.** Mapbox counts `distanceAlongGeometry` down through the step you are
*driving* towards the next turn, so a step's banner instructions describe the
maneuver at its **end**. A step's own `maneuver` field is the opposite: the turn
that got you *into* it. Reading both off the same step paired every junction
with the lane guidance for the junction after it — which on the R0 meant being
told to take the right-hand exit for St.-Pieters-Leeuw while the arrows
underneath pointed left, because the left turn was the one waiting at the far
end of the slip road. Lanes and exit signs now come from the previous step,
with a test built from a real R0 payload that asserts the ramp gets right
arrows and the turn after it gets left ones.

**The marker floated beside the road.** Free drive snapped the position at the
moment of the fix and *then* dead-reckoned and eased on top of it, which undoes
the snap: a second of travel plus the smoothing lag is ten or twenty metres, and
on a bend all of it is sideways. The projection now happens last, after all the
motion, so the marker ends up on the tarmac whatever the dead reckoning did.
The snap threshold also matched the road-matcher's 35 m instead of sitting at
22 m — between the two the app would print a road's name and refuse to draw the
car on it.

**The route line was the same colour as the roads.** The base map's roads are
warm grey now and the route keeps the amber, so the screen reads as a grey world
with one bright line through it, the way an E60 screen actually looks. The route
also gained a dark casing so it holds its edge over a junction full of roads.

**The turn arrows were broken at the corner.** Two butt-capped strokes meeting
at a point leave a notch outside the bend and a hole inside — "like you glued a
straight piece and a turned piece". A round dot the width of the stroke fills
the join exactly, without lengthening either arm.

**Exit signs are one colour rule.** The small corner board was a fixed amber
drawable while the full junction view used the country-correct colour, so the
same Belgian exit appeared green in one place and amber in the other. Both now
go through `RoadSigns.junctionPanel`.


## 1.10

**The arrow knows which way the car faces before you move.** Until now the only
absolute reference was GPS, which cannot give you a bearing while you are
parked — so with no fix the app drew the arrow at zero, which does not read as
"direction unknown", it reads as "pointing north" stated with total confidence.
Two changes:

- The phone's magnetometer now provides an absolute heading, corrected by a
  **learned mounting offset**. The offset absorbs everything the app cannot
  know: the cradle angle, which way up the phone sits, whether it is lying flat
  or standing upright, and the fact that a car is a steel box that bends the
  field being measured. It is learned automatically from the first GPS bearing
  above 25 km/h and kept, and a GPS bearing always overrules the compass once
  you are moving. The compass is eased towards, never snapped to, and only
  while stopped — a raw compass jitters several degrees a second and would make
  the arrow shiver on a parked car.
- **With no heading at all, the marker is now a dot, not an arrow.** Honest
  about what it knows. It becomes a chevron the moment a direction arrives.

**Settings → Calibrate arrow direction.** A compass rose showing where the app
thinks the car is pointing, with the raw phone reading as a second, dimmer
needle so you can see the offset for yourself. Nudge by 5°, 90° or straight
round 180° until it matches what you see out of the windscreen, then save. You
should not normally need it — it exists for the case the automatic path cannot
cover, which is a phone that has just gone into a different holder on a journey
that has not reached road speed yet.


## 1.9.1

**"Where do I type my address?"** — the fair question that means a design
failed. In 1.9 the search button was a 26dp grey magnifier at the left end of
the trip pill, with the current road name beside it, and opening Quick actions
covered it entirely. Nothing on the screen said "type an address here".

With no destination the pill is now an invitation rather than an instrument:
an amber magnifier on an amber wash, **"Where to?"** in amber beside it, the
road name demoted to the second line, and the whole block is one target. Once
there is a route it goes back to being the trip figures with a plain glyph.
There is also a **Search** tile in Quick actions, which is where the question
got asked.

## 1.9

**The arrow points where the car points, even parked.** Android's rotation
vector — gyroscope, accelerometer and magnetometer fused into an absolute
orientation — now drives the heading, and only its *changes* are used, so
turning the car in a car park with the handbrake on swings the map round with
it. Because the value is absolute rather than integrated, there is nothing to
drift, which is what made the old gyro-only path freeze at a standstill. It
works at any mounting angle: the yaw is taken as the vertical-axis component of
the relative rotation, which has no singularity where `getOrientation` has one —
exactly the attitude a windscreen cradle holds the phone at. `GAME_ROTATION_
VECTOR` is preferred over `ROTATION_VECTOR` because the difference between them
is the magnetometer, and a car is the worst place on earth for one.

The raw-gyro fallback also turns a parked car now, above a deadband so its own
noise cannot creep. And standing still, the *car's* orientation beats the road's
direction: a road bearing cannot tell which of two ways along it you are facing.

**The map cannot be flattened.** Pitch is clamped to 28–58°. The gesture stays;
it can no longer leave the range the screen is designed around.

**Sygic layout.** Search, the trip figures and the menu are one rounded slab in
the bottom-left corner; the speed dial and re-centre are on the right; and
everything else moved behind the four-dot button into a Quick actions sheet —
settings, sound, alternative routes, and Cancel route. Gone: the full-width
bottom strip and the row of five chips. Search results now put the distance and
the address on one line under a red pin, with the distance in white, because
that is the number you compare between rows.

**Stop is gone.** Cancel route lives in the sheet.

**Quitting the app leaves a dashboard display.** Swipe it out of recents and the
service drops to quiet mode: the route and the voice go, the speed limit and
camera warnings keep going to the HUD. Pressing home is not quitting — guidance
carries on, because a dark screen on a motorway is not a reason to stop
navigating.

**Fixed in review before shipping.** The first cut of the orientation work had
the gyro *and* the rotation vector integrating the same turn, because the source
was claimed before the "is something better already driving this?" test read it
— so the map rotated through twice the angle the car did. Also: the free-drive
road bearing was the raw OSM way direction rather than the direction of travel,
so on a two-way street digitised against you the arrow faced backwards; the
Quick actions scrim was painted under the trip pill, so the bottom-left of
"Cancel route" opened Search instead; quiet mode was never cleared when the app
was reopened, silently muting every camera warning for the rest of the drive;
and unplugging the HUD applied minutes of accumulated yaw in one 20 ms step.

**Cameras.** The Overpass query now also pulls `type=enforcement` relations and
their members — 876 of them in Belgium that node-only queries missed — and
rejects `highway=speed_display` feedback signs. France's danger zones are
300 m / 2 km / 4 km by road type per the 2011 agreement rather than a flat 2 km,
and the distance shown inside a zone is rounded so it cannot locate the camera.
Section-control relations are read for their own tags, so they are announced as
average-speed checks with the enforced limit rather than as fixed cameras.
Austria and Hungary added as verified-permissive.

The map now shows a warning chip when something is actually wrong — no GPS, HUD
cable gone, cameras suppressed by local law — instead of a permanent grey status
line that was read as chrome and ignored.

## 1.8.1 — the four things you found

### The voice language setting did nothing

It was saved correctly and applied nowhere. Free drive starts the service the
moment the app opens, and `onStartCommand` — the only place that ever read the
language — had already run by the time anyone reached Setup, so the setting
went into preferences and stayed there. Picking English changed a stored string
and nothing else.

It now reaches the running service immediately, and **says one line out loud in
the language you picked**, so a change that did not work would be obvious
instead of silent.

### ...and it was Canadian French

Separately: the voice picker ranked every installed voice by the engine's own
quality score with a bonus for network voices, and on a phone where the
Québécois voice is the good one, that is what won. It now treats the region as
a whitelist rather than a tiebreak — for French: Belgium, then France, then
Switzerland and Luxembourg, and **fr-CA is rejected outright however good the
engine says it is**. Beyond that it keeps whatever voice you have set in
Android's own text-to-speech settings and only overrides it when the language
is actually wrong. Which voice ended up being used is printed on the Setup
screen.

### The map could be flattened and there was no way back

A two-finger drag accepted any pitch, including zero. The gesture stays — a
shallower view on a motorway is a reasonable thing to want — but it is now
clamped to **28°–58°**, so the map cannot leave the range the rest of the
screen is designed around. The driving tilt is still a constant 40°.

### The arrow was a sticker on the windscreen

It was a billboard: painted in screen space, standing up to the camera, so it
appeared to swing as the pitch changed and never looked like it was *on* the
road. It now lies flat on the ground plane the way Waze's does.

Two things had to change with it. The shape: a raised wedge with a lit face and
a shaded face is right for a marker that stands up and turns to mud lying flat,
so it is a clean top-down arrowhead with a rim and a shadow. And the colour:
amber-on-amber made it disappear into the route line, so the body is now
near-white fading to amber inside a dark rim — legible on the amber route and
on bare tarmac in free drive.

It is also drawn **1 / cos(40°) longer than it should be**, because the tilt
foreshortens anything lying in the map plane. Drawn in correct proportion it
arrives on screen looking squat; drawn wrong, it arrives correct.

### Warnings for things that were not on your road

A speed bump on the street running beside you passed both tests the app was
making — inside the distance, inside the heading cone — so it got announced.
The test is now "is this on the road I am on": the feature has to lie within
14 m of the centreline of the matched road, or of the route line when there is
a route. If the app cannot tell which road it is on, it says nothing rather
than guessing from a bearing.

Cameras got the same treatment, plus two numbers tightened: a camera has to be
within 28 m of the route line rather than 45 m — 45 m picked up the service
road beside a motorway and the cross street at a junction — and a camera with a
tagged facing direction now has to be pointing within 70° of oncoming rather
than 100°, which had been accepting cameras aimed almost exactly across our
path.

## 1.8 — the Waze round

Everything in this one came out of the eight screenshots you sent.

### The map behaves like Waze's now

**One tilt, always.** It used to lie flat when you stopped and swing up to 55°
once you were moving, so the whole map heaved between a plan view and a
perspective at every set of lights. It now holds 40° from the moment it opens
and never touches it — which is what you meant by "Waze how they did it is
perfect".

**The map turns to face the route the moment it is ready**, standing still,
before you have moved a metre. A heading filter fed by a stationary GPS can
never produce that: the car has no heading yet, but the route does.

**The arrow is always on a road.** With no destination set it used to be drawn
wherever the GPS said, which is regularly inside somebody's front room. It is
now pulled onto the road it matched, the same way it already was on a route.

### Speed and limit are one instrument

They were a pill and a roundel side by side. The roundel vanished whenever the
map had no limit and shoved everything sideways, and at a glance the two
numbers looked alike. Now it is a dial with the sign tucked into its shoulder,
the way you liked in Waze — and the ring around it fills to the *limit* rather
than to some fixed 180 km/h, so a nearly-closed ring means the same thing in a
30 zone as on the motorway. Past the limit it turns red and pushes into an
overrun band, which at 5 km/h over is far more visible than a needle that has
barely moved.

### Lane guidance, with every exit combination

Three states instead of two. The router sends every movement a lane allows,
whether the lane is yours, and — the field that matters — **which one of that
lane's movements is yours**. A shared through-and-exit lane used to get two
equally bright arrows, which told you nothing. Now the movement to make is full
amber, the lane's other movements are dim, and lanes that are not yours are
grey.

Two lanes where the exit is the third, three where it is the fourth, two exit
lanes, a left-hand exit, a shared lane — all of it falls out of the same data.
Carriageways wider than eight lanes are **windowed around the lane you need**,
with a torn edge on the side that was cut; the old code truncated at eight from
the left, which on an eleven-lane approach threw away exactly the exit lane and
confidently drew eight through lanes.

Lane guidance also appears **by time rather than distance** now — up to 1500 m
on a motorway instead of a flat 500 m, which at 120 km/h gave you fifteen
seconds to read a sign, decide, check a mirror and cross two lanes.

### The junction view

New: the carriageway drawn in perspective with the slip road peeling off it and
your lane lit up, hanging off the bottom of the instruction card. The signpost
beside it is painted the colour that country actually uses — and Belgium's rule
is the awkward one, because the colour describes the road that *reaches* the
destination rather than the road the sign stands on. A gantry over the E40 is
blue for an ordinary-road destination and green for another motorway.

### Choosing a route

The picker was an `AlertDialog` printing "42 min · 61 km via E40" three times.
It is now a sheet over the map — the alternatives drawn in grey behind it, so
"via E40" is a line on a map — with a card each showing the arrival time, how
much worse than the best it is, the distance, and **what is wrong with it**:
roadworks, a toll, a ferry, a low-emission zone.

### Roadworks, and everything else

You asked whether the app knows about roadworks. It does now, properly:

- **Closures** from the router get a chip on the glass, one spoken warning
  2 km out, and dark red on the route line.
- **Low-emission zones** are checked against OpenStreetMap and named on the
  route card *before* you set off — the fine arrives in the post weeks later,
  so a warning after the fact is no warning at all.
- **Level crossings, speed bumps and toll booths** now warn about eight
  seconds out, from the same road download the app already makes.
- **Tolls and ferries** are flagged on the route card.

`docs/WAZE.md` is the full feature-by-feature comparison, including what is
still missing and why.

### Protocol

`$LANE` gained an optional tail carrying the chosen movement per lane. Old
firmware ignores it and keeps working; old phones send no tail and new firmware
copes.

## 1.7 — it stops looking like a prototype

You were right. Going back over the screenshots, every one of these was on
screen at once.

### Icons

Every control was a text glyph, and it showed. The search button was a **colour
emoji magnifier** sitting in an amber-on-black car interface. The mute button
was a **musical note**. Re-centre was a **triangle**. The camera warning was the
word "CAM" in a box.

They are all proper vector drawables now — a magnifier, a speaker (struck
through when muted), a crosshair for re-centre, a gear, a fork for alternative
routes, a camera — one weight, one style, sized and spaced consistently.

### The empty card

With a route running but no instruction yet, an empty card floated in the top
left with a bare arrow pointing straight up. Hiding the arrow inside the card
was not enough; the card goes too.

### "18 h 33"

That was the *duration* formatted like a clock time, so a 1,113-minute drive
read as an arrival at half past six. It now shows the **arrival time**, then the
duration, then the distance: `20:47 · 18 h 33 min · 1876 km`.

### The debug line

The bottom of the driving screen was a developer dump: link state, the accuracy
of a rejected fix, heading source, country code, camera count, all of it
permanently on the glass while you drive. Every one of those is worth having and
none is worth a driver's attention, so they live on the Setup screen now. The
line on the map says something only when there is something to say — waiting for
GPS, camera alerts restricted by local law, a road closed ahead, a HUD that was
connected and is not any more — and otherwise just says where you are going.

### The speed

A bare number beside a red roundel, with nothing to say which was which. It has
a **km/h** label now, and when the limit is unknown the roundel leaves the row
rather than holding open an empty hole beside the speed.

### The bottom row

The road-name pill was centred with 200 dp side margins, which does exactly
nothing: in a FrameLayout the two margins cancel and the pill sits dead centre —
underneath the buttons. On a 1024×600 head unit with alternative routes
available it was covered for *any* road name. The speed cluster, the road name
and the controls are one weighted row now, so they cannot overlap; the pill
takes whatever is left and ellipsises inside it.

### The arrow

Redrawn and rendered at actual size before shipping rather than after: a deeper
notch so it reads as an arrow and not a triangle, a brighter lit face against a
deeper shaded one, two layers of skirt, and a shadow that is no longer sliced
off by the edge of its own bitmap.

**Dismissing the alignment pattern flashed a stale drive display.** Open the
keystone screen, lose the cable, and two minutes later the board dismisses the
pattern by itself — and repainted the speed and speed limit that were live
before the pattern went up, two minutes earlier, before correcting itself to NO
LINK on the same pass. A tenth of a second of a stale limit on the glass is
exactly what the NO LINK screen exists to prevent. The cause was two owners for
one decision: `applyGeom()` decided what should be on the screen using a link
state it had inherited, and the loop below decided it again from a freshly
computed one. There is one owner now. The regression test counts primitives on
the dismissal pass — 34 before the fix, 2 after — and I checked it fails against
the old code rather than assuming it would.

**The backlight and the gyro stopped while the pattern was up.** The early
return skipped them. A test grid at full daytime brightness at night is the last
thing anyone wants, and the car is still a car while somebody is aiming the
screen at the windscreen.

**The screen overwrote a board that had been set up from another phone.** The
app asked the board for its settings and pushed its own cached ones in the same
breath, then discarded the reply because it had just sent something. So "the
board's flash is the record" was true in the documentation and false in the
code. It now asks first, adopts the answer, and only then pushes — with a 1.5 s
fallback to the cache for a board that does not answer, and the driver's hand
always beating a late reply.

**Corners could be pushed outward, off the panel.** Legal on the wire and the
board would take it, but there are no pixels out there, so anything landing past
the edge is simply not drawn. Every useful correction shrinks the picture into
the shape the windscreen actually reflects. Corner nudges are inward-only now,
which also lets the preview promise never to draw outside itself; 6,561 corner
combinations are checked exhaustively.

**`RejectedExecutionException` on the serial reader thread.** A `$HELLO`
arriving as the service is being destroyed would have killed the thread — the
codebase already had a comment about exactly this failure mode elsewhere.

**A white screen on real hardware, which none of the above would have caught.**
The panel's RESET is tied to the board's reset line, so it comes out of reset
with the ESP — and an ST7796S wants 120 ms after that before it will accept a
command. The firmware went almost straight from boot to `init()`, so the init
sequence went into a controller that was not listening and the panel stayed at
its power-on white with a working backlight. Nothing about it looks like a
timing bug; it looks like bad wiring. The bring-up sketch that *did* work only
worked because it spent 700 ms printing a report first — an accidental delay
doing load-bearing work. Now explicit, with the reason written next to it.

Alongside it, `setRotation` is measured rather than trusted. The mirrored
landscape orientations are 5 and 7, which exist because TFT_eSPI takes `m % 8`;
an older copy of the library stops at 3, leaves the driver's width and height
at portrait, and clips every landscape draw away — the same white screen, from
a different cause. The sketch now checks the driver really came back 480×320
and falls back to plain landscape with a `$ROTERR` up the cable if not. An
unmirrored display beats no display. The test stub models an old library so the
fallback is exercised rather than assumed.

**And the bring-up sketches are under test now.** They were throwaway
diagnostics, so they were not — which is backwards, because a sketch whose only
job is to tell you whether your hardware works has to be the most trustworthy
code in the project. One of them shipped asking font 6 to draw an "x". The big
built-in fonts have no letters at all: font 6 is `1234567890:-.apm` and fonts 7
and 8 are digits and three punctuation marks, so a word handed to one indexes a
character table with no entry for it, and a bad pointer read on an ESP8266 is a
reboot. The stub now refuses any string given to a font that cannot render it,
and that check runs over both HUD themes too.

### Also

- A slow turn — a tight junction, a car park, a mini-roundabout — used to freeze
  the map, because the 7 km/h threshold that keeps GPS noise from spinning it
  was being applied to a gyro heading and a road direction, neither of which has
  any noise in it. Those now steer the map at any speed.
- On a head unit with no `TYPE_GRAVITY` sensor, "up" is derived from the
  accelerometer with a two-speed filter: fast for two seconds to find the
  mounting angle, then slow, so a long corner is not mistaken for the phone
  tilting.
- The OpenStreetMap attribution was buried under the bottom strip where nobody
  could reach it.
- Re-centre and mute change colour rather than fading the whole chip, which had
  made one button look unlike its neighbours.

189 Kotlin unit tests and the nine C++/Python stages, all green; lint clean.

## 1.6.1 — the crash

**What it was.** From Android 14, `startForeground` no longer just takes a
service type — it checks you are entitled to it, and throws if you are not. The
service asked for `location|connectedDevice` unconditionally, and
`connectedDevice` additionally requires one of the Bluetooth runtime
permissions to be *granted*, or a USB device you have been given permission
for. Declaring `FOREGROUND_SERVICE_CONNECTED_DEVICE` in the manifest is
necessary but not sufficient. Nothing ever asked for `BLUETOOTH_CONNECT` on the
driving screen, so the claim was refused and the exception took the app down.

**Why now.** That code had been wrong since 1.4 and never fired, because until
1.6 the service only started once you had searched for a destination. Free
drive starts it the moment the map opens — which turned a latent bug into the
first thing that happens.

**The fix.** The service now claims only what it can justify: `location` when
location permission is granted, `connectedDevice` only when Bluetooth is
granted or a HUD board is plugged in. If a claim is refused it narrows rather
than dying.

**And the fix's own bug**, found in review before shipping: refusing *every*
type and then calling `stopSelf()` is also fatal, because
`startForegroundService` arms a timer in the system that only a successful
`startForeground` clears — Android throws
`ForegroundServiceDidNotStartInTimeException` for the omission. The shutdown
path now goes foreground under `specialUse`, which has no runtime prerequisite,
purely so it can stop tidily.

**Dismissing the alignment pattern flashed a stale drive display.** Open the
keystone screen, lose the cable, and two minutes later the board dismisses the
pattern by itself — and repainted the speed and speed limit that were live
before the pattern went up, two minutes earlier, before correcting itself to NO
LINK on the same pass. A tenth of a second of a stale limit on the glass is
exactly what the NO LINK screen exists to prevent. The cause was two owners for
one decision: `applyGeom()` decided what should be on the screen using a link
state it had inherited, and the loop below decided it again from a freshly
computed one. There is one owner now. The regression test counts primitives on
the dismissal pass — 34 before the fix, 2 after — and I checked it fails against
the old code rather than assuming it would.

**The backlight and the gyro stopped while the pattern was up.** The early
return skipped them. A test grid at full daytime brightness at night is the last
thing anyone wants, and the car is still a car while somebody is aiming the
screen at the windscreen.

**The screen overwrote a board that had been set up from another phone.** The
app asked the board for its settings and pushed its own cached ones in the same
breath, then discarded the reply because it had just sent something. So "the
board's flash is the record" was true in the documentation and false in the
code. It now asks first, adopts the answer, and only then pushes — with a 1.5 s
fallback to the cache for a board that does not answer, and the driver's hand
always beating a late reply.

**Corners could be pushed outward, off the panel.** Legal on the wire and the
board would take it, but there are no pixels out there, so anything landing past
the edge is simply not drawn. Every useful correction shrinks the picture into
the shape the windscreen actually reflects. Corner nudges are inward-only now,
which also lets the preview promise never to draw outside itself; 6,561 corner
combinations are checked exhaustively.

**`RejectedExecutionException` on the serial reader thread.** A `$HELLO`
arriving as the service is being destroyed would have killed the thread — the
codebase already had a comment about exactly this failure mode elsewhere.

**A white screen on real hardware, which none of the above would have caught.**
The panel's RESET is tied to the board's reset line, so it comes out of reset
with the ESP — and an ST7796S wants 120 ms after that before it will accept a
command. The firmware went almost straight from boot to `init()`, so the init
sequence went into a controller that was not listening and the panel stayed at
its power-on white with a working backlight. Nothing about it looks like a
timing bug; it looks like bad wiring. The bring-up sketch that *did* work only
worked because it spent 700 ms printing a report first — an accidental delay
doing load-bearing work. Now explicit, with the reason written next to it.

Alongside it, `setRotation` is measured rather than trusted. The mirrored
landscape orientations are 5 and 7, which exist because TFT_eSPI takes `m % 8`;
an older copy of the library stops at 3, leaves the driver's width and height
at portrait, and clips every landscape draw away — the same white screen, from
a different cause. The sketch now checks the driver really came back 480×320
and falls back to plain landscape with a `$ROTERR` up the cable if not. An
unmirrored display beats no display. The test stub models an old library so the
fallback is exercised rather than assumed.

**And the bring-up sketches are under test now.** They were throwaway
diagnostics, so they were not — which is backwards, because a sketch whose only
job is to tell you whether your hardware works has to be the most trustworthy
code in the project. One of them shipped asking font 6 to draw an "x". The big
built-in fonts have no letters at all: font 6 is `1234567890:-.apm` and fonts 7
and 8 are digits and three punctuation marks, so a word handed to one indexes a
character table with no entry for it, and a bad pointer read on an ESP8266 is a
reboot. The stub now refuses any string given to a font that cannot render it,
and that check runs over both HUD themes too.

### Also fixed

- **The app now keeps its own crash reports.** If it dies, the next launch
  shows what happened with a *Copy full report* button. A sideloaded app has no
  Play Console and no logcat, so without this a crash is just "it closed" —
  which is the one thing you already knew. Nothing is sent anywhere; the file
  is in the app's private storage and is deleted once you have read it.
- **Voice guidance could be silently mute.** Android 11 hides other packages by
  default, and a text-to-speech engine is another package. Without a `<queries>`
  declaration the engine is simply not found — no error, no voice. Same for
  speech recognition, which the search microphone needs.
- **`requestLocationUpdates` threw on a device with no GNSS.** It raises
  `IllegalArgumentException` when the provider does not exist, which a wifi-only
  head unit genuinely does not have. The network provider was already guarded;
  GPS was not.
- **Starting navigation without location permission** would start a service that
  then could not go foreground, and Android would kill the process for it.
- **A GPS fix arriving after Stop** could hit a shut-down executor and take the
  process with it.
- The gravity estimate on a head unit with no `TYPE_GRAVITY` sensor now uses a
  two-speed filter: fast for a couple of seconds to find the mounting angle,
  then slow, so a long corner or a hard brake is not mistaken for the phone
  having tilted.

187 Kotlin unit tests (was 175) and the nine C++/Python stages, all green.

## 1.6 — it works before you type anything

### Free drive: no destination needed

Open the app and it is already navigating, just without a destination. Speed,
the speed limit of the road you are actually on, the road's name, and camera
warnings — all of it, with nothing typed in.

That needed a second engine. With a route, the limit arrives annotated onto the
directions; without one there is nothing to annotate, so `AreaRoads` fetches the
road network around the car from OpenStreetMap and `FreeTracker` works out which
road you are on. The interesting part is *which*: a slip road runs a few metres
from the motorway and a service road runs nearer still, so the match combines
distance, how far the road's direction is from yours, and road importance. A
one-way road only matches the way it runs, and a road crossing under you is not
a road you are on.

The fetch radius is scaled by speed — about a minute of driving — so a motorway
gets the kilometres it needs and a town centre does not pull down half of
Brussels. The window is centred ahead of you, because half a circle behind is
road you have already driven.

The X button now means **End route**, not "switch off". Speed, limits and
cameras carry on, which is what it means in every other nav app.

### Search, the way Waze does it

No more Go button — a magnifying glass. No more Search button either: matches
appear while you type. Recent destinations are there the moment the screen
opens, because you drive to the same six places, with Home and Work shortcuts
above them. There is a microphone, which matters more than it sounds when the
alternative is typing an address on a touchscreen in a car.

Keystrokes are coalesced rather than fired at the geocoder one at a time, and a
reply for a query you have already typed past is dropped rather than drawn over
the results you are looking at.

### A 3D arrow

A flat arrow on a map tilted 55° is not a flat arrow — it is a smear, because
the tilt foreshortens it. The marker now rotates with the map but stands up to
the camera, the way Google Maps' chevron does, and is drawn as a shaded wedge
with a lit face, a shaded face, a skirt and a shadow.

### The gyroscope, spelled out

**HUD gyro** when the board has an MPU-6050 fitted and is reporting.
**Phone gyro** when it is not. **Plain GPS heading** when the device has
neither, which plenty of Android head units do not. All three work; they differ
only in how quickly the map answers the steering wheel. The map's status line
now says which one is in use.

### Demo mode is gone

It earned its keep while there was nothing else to point the tracker at, and
stopped earning it the moment the app worked on a real road — a synthetic drive
that always behaves is exactly the thing that stops telling you anything. The
canned route lives in the test sources now, where a repeatable 10 km drive with
known maneuvers, limits, cameras and lanes is genuinely useful.

### Found while reviewing the above

- **Pressing X during a route request brought the route back.** The reply was
  adopted unconditionally, so cancelling during the second or two a Mapbox call
  takes installed a route to the destination you had just abandoned, voice and
  all. Requests now carry a generation number and stale replies are discarded.
- **Searching for a second destination while the first was in flight** silently
  navigated you to the first, with the notification showing the second.
- **The map's "make sure you're running" start could wipe a destination.** The
  activity sends one every time it comes to the front, and it raced the search
  result. A start with no destination now means "make sure you are running", not
  "cancel my route"; cancelling is a separate action.
- **The route line stayed painted after pressing X** — the local and service
  state both went null in the same frame, so "has it changed?" said no.
- **Stop did not stay stopped**: returning to the map restarted the service.
- **A nearer legal road could be lost to a further important one.** Candidates
  were admitted at 70 m, ranked with an importance bonus, then re-checked at
  35 m — so a motorway at 40 m beat a residential road at 34 m, won, failed the
  final check, and took the legitimate match with it.
- **Losing GPS for good left a speed limit on the glass**, because the hold
  expiry was only applied while a fix was live. That is the one thing a display
  like this must never do.
- **A camera you passed was announced again if you stopped at a light**, because
  below walking pace the heading went to null and disabled the direction filter.
- **The same camera was never announced twice**, so the one warned about on the
  way out was silent on the way home.
- One malformed element in an Overpass reply discarded the whole road network,
  and a duplicated node made an east-west road read as running due north.

175 Kotlin unit tests (was 146) and the nine C++/Python stages, all green.

**Dismissing the alignment pattern flashed a stale drive display.** Open the
keystone screen, lose the cable, and two minutes later the board dismisses the
pattern by itself — and repainted the speed and speed limit that were live
before the pattern went up, two minutes earlier, before correcting itself to NO
LINK on the same pass. A tenth of a second of a stale limit on the glass is
exactly what the NO LINK screen exists to prevent. The cause was two owners for
one decision: `applyGeom()` decided what should be on the screen using a link
state it had inherited, and the loop below decided it again from a freshly
computed one. There is one owner now. The regression test counts primitives on
the dismissal pass — 34 before the fix, 2 after — and I checked it fails against
the old code rather than assuming it would.

**The backlight and the gyro stopped while the pattern was up.** The early
return skipped them. A test grid at full daytime brightness at night is the last
thing anyone wants, and the car is still a car while somebody is aiming the
screen at the windscreen.

**The screen overwrote a board that had been set up from another phone.** The
app asked the board for its settings and pushed its own cached ones in the same
breath, then discarded the reply because it had just sent something. So "the
board's flash is the record" was true in the documentation and false in the
code. It now asks first, adopts the answer, and only then pushes — with a 1.5 s
fallback to the cache for a board that does not answer, and the driver's hand
always beating a late reply.

**Corners could be pushed outward, off the panel.** Legal on the wire and the
board would take it, but there are no pixels out there, so anything landing past
the edge is simply not drawn. Every useful correction shrinks the picture into
the shape the windscreen actually reflects. Corner nudges are inward-only now,
which also lets the preview promise never to draw outside itself; 6,561 corner
combinations are checked exhaustively.

**`RejectedExecutionException` on the serial reader thread.** A `$HELLO`
arriving as the service is being destroyed would have killed the thread — the
codebase already had a comment about exactly this failure mode elsewhere.

**A white screen on real hardware, which none of the above would have caught.**
The panel's RESET is tied to the board's reset line, so it comes out of reset
with the ESP — and an ST7796S wants 120 ms after that before it will accept a
command. The firmware went almost straight from boot to `init()`, so the init
sequence went into a controller that was not listening and the panel stayed at
its power-on white with a working backlight. Nothing about it looks like a
timing bug; it looks like bad wiring. The bring-up sketch that *did* work only
worked because it spent 700 ms printing a report first — an accidental delay
doing load-bearing work. Now explicit, with the reason written next to it.

Alongside it, `setRotation` is measured rather than trusted. The mirrored
landscape orientations are 5 and 7, which exist because TFT_eSPI takes `m % 8`;
an older copy of the library stops at 3, leaves the driver's width and height
at portrait, and clips every landscape draw away — the same white screen, from
a different cause. The sketch now checks the driver really came back 480×320
and falls back to plain landscape with a `$ROTERR` up the cable if not. An
unmirrored display beats no display. The test stub models an old library so the
fallback is exercised rather than assumed.

**And the bring-up sketches are under test now.** They were throwaway
diagnostics, so they were not — which is backwards, because a sketch whose only
job is to tell you whether your hardware works has to be the most trustworthy
code in the project. One of them shipped asking font 6 to draw an "x". The big
built-in fonts have no letters at all: font 6 is `1234567890:-.apm` and fonts 7
and 8 are digits and three punctuation marks, so a word handed to one indexes a
character table with no entry for it, and a bad pointer read on an ESP8266 is a
reboot. The stub now refuses any string given to a font that cannot render it,
and that check runs over both HUD themes too.

### Also

- `docs/SCREEN.md` answers the screen question: size, and why LCD rather than
  OLED or a projector.

## 1.5 — the road-test fixes

Every item below came from driving 1.4 in the car. The numbers are the ones you
reported them under.

### 1 · Rerouting was slow

Three separate delays were stacking up. `RouteTracker` already waits for three
consecutive off-route fixes (~3 s) before it believes you have left the route;
on top of that the service waited another 4 s for confirmation, and then a 15 s
cooldown — which was armed every time a route was *adopted*, including the very
first one. So the first missed turn of a drive was the worst case: the request
could be swallowed entirely and pushed out past twenty seconds.

Now: confirmation is 1.2 s, the cooldown is 6 s and only ever armed by an actual
reroute *request*, and a cross-track over 90 m skips confirmation altogether —
at that distance you are demonstrably on a different road. End to end, about
four seconds. The rule lives in `RerouteRule` so it is unit-tested rather than
tuned by feel.

The reroute also now sends your heading to Mapbox (`bearings=`), so a new route
on a dual carriageway can no longer open with "make a U-turn" because the other
side happened to be a metre nearer the fix.

### 2 · The arrow was not on the road

It was drawn at the raw GPS fix. A good urban fix is 5–10 m out and a bad one is
30, which puts the marker in the building next door.

It is now drawn at the projection of the fix onto the route polyline, and it
moves *along the route* between fixes rather than across open ground — the
operation Google sells as the Roads API. It stops being pinned as soon as you
are more than 25 m off the line, because past that point the route is no longer
where you are, and pretending otherwise would be a lie rather than a smoothing.

### 3 · The arrow did not turn with the car

Two bugs on top of each other.

The rotation was subtracted twice. With `icon-rotation-alignment: map` the
symbol is rotated relative to *map* north, and the map is already turned by the
camera bearing — so what lands on screen is `icon-rotate − mapBearing`. The code
was passing `heading − mapBearing`, giving `heading − 2·mapBearing`. Driving
straight the two errors nearly cancel, which is exactly why it looked *almost*
right and never turned properly. It now passes the absolute heading.

And the heading itself came from GPS alone: derived from movement between fixes,
delivered once a second, meaningless below walking pace. There is now a
complementary filter (`HeadingFusion`) — gyroscope for the fast part of the
motion, GPS correcting the drift — and when you are snapped to the route the
marker points along the road, which has no noise in it at all.

**Gyroscope, as you asked.** The phone's own is used by default. If you fit an
IMU to the HUD board it reports its yaw rate up the cable and that one wins,
because a sensor bolted to the car beats one in a cradle that gets knocked.

**The module: GY-521 / MPU-6050, about €3.** Four wires, wiring diagram in
[docs/BUILD.md](docs/BUILD.md). Deliberately not a BNO055: the expensive boards
sell on-chip fusion producing an absolute heading from a magnetometer, and
inside a steel car that is worse than the GPS heading it would replace. All this
sensor needs to do is cover the second between fixes. It self-calibrates at boot
and whenever the car stands still, and it works mounted at any angle — which
axis points at the sky is worked out from gravity.

### 4 · The French voice said *quatre-vingt-dix*

Ask any Android TTS voice to read "90" in French and you get *quatre-vingt-dix*,
because the engine applies metropolitan numeral rules to digits whatever locale
you set. There is no setting that changes this.

So the app no longer hands it digits. `FrenchNumbers` writes them out, and
*nonante* is just a word that every French voice reads correctly. Belgium keeps
*quatre-vingts* for 80 — *huitante* is Swiss, not Belgian. The language picker
now offers **Français (Belgique)** and **Français (France)** separately.

The app also now chooses its voice deliberately instead of taking the engine
default: right region first, then quality, and a network voice ahead of a local
one, since the head unit is permanently online and the network voices are
markedly better. The Setup screen shows which one it picked. If it says an
offline voice, installing Google's high-quality French from the system TTS
settings is the single biggest improvement available.

### 5 · "Maintenant"

Gone, in both languages. By the time a voice has finished saying it you are in
the junction. The final call is now the instruction alone — "Tournez à droite
sur Avenue de la Couronne." — which is what Google and TomTom both do.

### 6 · Motorway camera warnings came too late

They were fractions of a single warning distance, which put the first motorway
warning at 900 m — eleven seconds at 130 km/h — and the last at 160 m.

They are now written out per speed band, because the right answer is a time
budget:

| Band | Stages | At the top of the band |
|---|---|---|
| Motorway (≥ 90 km/h) | 1500 / 700 / **150** m | 41 / 19 / 4 s |
| Main road (≥ 50) | 900 / 450 / 150 m | 45 / 22 / 7 s |
| Town | 400 / 200 / 100 m | 29 / 14 / 7 s |

First call early enough to lift off rather than brake, last reminder at 150 m as
you asked.

### 7 · The GPS was wonky and jumped to another road

The app subscribed to both GPS *and* the network provider and used whichever
arrived last. A network fix comes from cell towers and can be half a kilometre
wide. Taking one of those while a perfectly good GPS fix exists is precisely how
the marker ends up on a different street.

`FixFilter` now sits in front of both, with three rules: GPS wins unless it has
genuinely gone quiet for ten seconds; a vague fix never replaces a sharp recent
one; and a jump implying more than 250 km/h is a reflection off a building, not
a car. The Setup and map screens show the current fix quality, so when it does
go wrong you can see *why*.

### 8 · Pressing Stop froze the map

The map read the navigation service's last known position and nothing else, so
when the service stopped, so did the map.

The driving screen now holds its own GPS subscription and only *prefers* the
service's fix while one is arriving. Press Stop and it carries on as a live
moving map, the way Waze and Google Maps do.

### Found while reviewing the above

A read-through of the changed code turned up several problems that had nothing
to do with what was reported but everything to do with why it felt unreliable.

- **The reroute was queued behind the speed-camera fetch.** Routing, the
  Overpass camera query and reverse geocoding all shared one single-thread
  executor. Adopting a route queues a camera fetch, and Overpass has a
  sixty-second server-side timeout — so a reroute requested three seconds later
  sat in a FIFO queue behind it for up to a minute, with the in-progress flag
  already latched so every further attempt was a silent no-op. All the work on
  the reroute *timing* was worth nothing against that. Routing now has a thread
  to itself.
- **Pressing Go twice leaked the serial link.** The old transport and its reader
  thread were dropped unreachable rather than closed. On Bluetooth the orphan
  still held the board's only SPP slot, so the new connection could never
  succeed and the HUD went dark for the rest of the drive.
- **A new destination did not clear the old route,** so during the routing call
  the app kept counting down and speaking the *previous* route — and if the
  request failed, for ever. Failed requests now retry with a back-off instead of
  giving up silently, which matters because losing signal is exactly when a
  reroute gets asked for.
- **Pressing Go twice could double the tick rate.** `removeCallbacks` cannot
  cancel a tick the worker thread is already executing, and that tick reposts
  itself — leaving two self-sustaining chains. 8 Hz frames, doubled voice, demo
  drive at double speed, and another chain per Go. Fixed with a generation
  counter.
- **Opening the cable blocked the thread that produces frames** — and, on the
  first Go, the main thread, which is an ANR when the board is switched off.
  Both now open in the background, with a sensible retry interval and no more
  USB permission dialog storm.
- **Live closures never worked.** `annotations=closure` is how you *ask*, but
  Mapbox returns them as `legs[].closures` — vertex ranges — not as a
  per-segment annotation array. Reading them the obvious way failed silently:
  every segment simply read as open, so nothing looked wrong, it just never lit
  up. Now parsed correctly, with a test against the real response shape.
- **One bad GPS fix could wedge the filter permanently.** Every rule compares
  against the last accepted fix, so a fix accepted with a corrupt timestamp
  (a mock-location app, an old HAL) made every real fix afterwards look
  out-of-order — for ever. There is now an escape hatch after twelve consecutive
  refusals.
- **A fix without a speed field killed the map rotation.** Network-provider
  fixes routinely omit speed and bearing, and everything downstream keys off
  them: the heading filter refuses to start, the camera stops tilting, the map
  sticks north-up. Both are now derived from the previous fix when missing.
- Traffic colouring no longer defeats the polyline point cap on a busy motorway,
  and two French numeral edges are fixed (`trois cent mille`, not "trois cents
  mille"; and spelling `Int.MIN_VALUE` no longer recurses for ever).

**Dismissing the alignment pattern flashed a stale drive display.** Open the
keystone screen, lose the cable, and two minutes later the board dismisses the
pattern by itself — and repainted the speed and speed limit that were live
before the pattern went up, two minutes earlier, before correcting itself to NO
LINK on the same pass. A tenth of a second of a stale limit on the glass is
exactly what the NO LINK screen exists to prevent. The cause was two owners for
one decision: `applyGeom()` decided what should be on the screen using a link
state it had inherited, and the loop below decided it again from a freshly
computed one. There is one owner now. The regression test counts primitives on
the dismissal pass — 34 before the fix, 2 after — and I checked it fails against
the old code rather than assuming it would.

**The backlight and the gyro stopped while the pattern was up.** The early
return skipped them. A test grid at full daytime brightness at night is the last
thing anyone wants, and the car is still a car while somebody is aiming the
screen at the windscreen.

**The screen overwrote a board that had been set up from another phone.** The
app asked the board for its settings and pushed its own cached ones in the same
breath, then discarded the reply because it had just sent something. So "the
board's flash is the record" was true in the documentation and false in the
code. It now asks first, adopts the answer, and only then pushes — with a 1.5 s
fallback to the cache for a board that does not answer, and the driver's hand
always beating a late reply.

**Corners could be pushed outward, off the panel.** Legal on the wire and the
board would take it, but there are no pixels out there, so anything landing past
the edge is simply not drawn. Every useful correction shrinks the picture into
the shape the windscreen actually reflects. Corner nudges are inward-only now,
which also lets the preview promise never to draw outside itself; 6,561 corner
combinations are checked exhaustively.

**`RejectedExecutionException` on the serial reader thread.** A `$HELLO`
arriving as the service is being destroyed would have killed the thread — the
codebase already had a comment about exactly this failure mode elsewhere.

**A white screen on real hardware, which none of the above would have caught.**
The panel's RESET is tied to the board's reset line, so it comes out of reset
with the ESP — and an ST7796S wants 120 ms after that before it will accept a
command. The firmware went almost straight from boot to `init()`, so the init
sequence went into a controller that was not listening and the panel stayed at
its power-on white with a working backlight. Nothing about it looks like a
timing bug; it looks like bad wiring. The bring-up sketch that *did* work only
worked because it spent 700 ms printing a report first — an accidental delay
doing load-bearing work. Now explicit, with the reason written next to it.

Alongside it, `setRotation` is measured rather than trusted. The mirrored
landscape orientations are 5 and 7, which exist because TFT_eSPI takes `m % 8`;
an older copy of the library stops at 3, leaves the driver's width and height
at portrait, and clips every landscape draw away — the same white screen, from
a different cause. The sketch now checks the driver really came back 480×320
and falls back to plain landscape with a `$ROTERR` up the cable if not. An
unmirrored display beats no display. The test stub models an old library so the
fallback is exercised rather than assumed.

**And the bring-up sketches are under test now.** They were throwaway
diagnostics, so they were not — which is backwards, because a sketch whose only
job is to tell you whether your hardware works has to be the most trustworthy
code in the project. One of them shipped asking font 6 to draw an "x". The big
built-in fonts have no letters at all: font 6 is `1234567890:-.apm` and fonts 7
and 8 are digits and three punctuation marks, so a word handed to one indexes a
character table with no entry for it, and a bad pointer read on an ESP8266 is a
reboot. The stub now refuses any string given to a font that cannot render it,
and that check runs over both HUD themes too.

### Also

- **Auto-zoom** now closes in on a junction as well as tracking speed, scaled by
  *time* to the junction rather than distance — 300 m is half a minute away in
  town and eight seconds away on a motorway, and zooming on distance gets both
  wrong. Capped so a motorway view never becomes a street view.
- **Live traffic on the route line**, amber through orange and red to a dark red
  for a stretch reported closed, from Mapbox's `congestion` and `closure`
  annotations. Delay in minutes appears next to the ETA once it is worth
  noticing.
- **Roadworks**, honestly: closures and traffic are live. The speed *limits*
  come from OpenStreetMap, where a temporary roadworks limit only exists if
  somebody mapped it — which for a two-week contraflow they usually have not.
  Feeds that carry temporary limits properly are commercial products with
  per-request pricing. There is no free source, and saying otherwise would be
  worse than saying this.
- Protocol is now **v2**: the cable is bidirectional, `$IMU` is implemented
  rather than reserved, and a board with a gyroscope announces itself as
  `$HELLO,NAVHUD,2,IMU`.
- 146 Kotlin unit tests (was 67) and the nine C++/Python stages, all green.

## 1.4

Google-quality address search (parse-then-multi-engine geocoder, after "avenue
de la couronne 329A 1050 ixelles" returned nothing), and the once-a-second
camera stutter — a 260 ms ease restarted from a 200 ms timer chasing 250 ms
position updates, three clocks beating at exactly 1 Hz. Replaced with a single
30 fps loop and time-based smoothing.

## 1.3

Waze-style interface: floating cards, bigger controls, the car marker anchored
properly above the bottom strip, motorway exit board, lane guidance.

## 1.2

MapLibre vector map with tilt and rotation, BMW E60 amber style, speed cameras
with country-aware legality, bilingual voice.

## 1.1

Own routing, speed limits and voice guidance.

## 1.0

Serial protocol, ESP32 sketch, two HUD themes.
