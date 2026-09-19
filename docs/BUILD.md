# Building NavHUD

## Parts

| Part | What to get | Roughly | Notes |
|---|---|---|---|
| MCU | **NodeMCU v3 / Wemos D1 mini (ESP8266)** | €4 | What the current firmware targets. ESP32 also builds |
| Display | **4.0" ST7796S SPI TFT, 480×320** | €26 | What the layouts are drawn for. 320×240 panels no longer fit |
| Cable | USB-C to USB-C or C-to-micro, **data** | €5 | Charge-only cables are the classic time-waster |
| Power | USB-C hub with PD passthrough | €15 | The important one — see below |
| Car supply | 12 V → USB-C PD car charger, 30 W+ | €12 | |
| Enclosure | 3D print or a project box | — | Matte black inside if you reflect it off the glass |
| **Gyroscope** *(optional)* | **GY-521 / MPU-6050** | **€3** | Four wires. Makes the map turn with the car instead of a second later — see below |

Total, about €60–65, or €63–68 with the gyroscope.

**Not an Arduino Uno or Nano.** They compiled for the old 320×240 layout and
were already slow; a 480×320 panel is 2.25× the pixels down a 4 MHz SPI bus, and
an ATmega328P has 2 KB of RAM against a sketch that now carries a homography.
The ESP8266 is cheaper than the Nano anyway.

**Why ESP8266 rather than ESP32.** For this job there is nothing to choose
between them and the ESP8266 costs less: no framebuffer is ever allocated (every
draw goes straight down the SPI bus), the geometry maths runs a few hundred
times a second at most, and 115200 baud is a rounding error. The ESP32 is a
better board and the sketch still builds for it; if you have one, use it. What
you cannot do on *either* is warp a finished image — see below.

## Running it from an Android head unit

**This is the best version of the build, and it is worth saying why.**

The single biggest problem with the phone version is power: when a phone talks
to the Arduino over USB, the *phone* is the host, and hosts supply 5 V. Your
phone powers the ESP32 off its own battery and does not charge while doing it.
Two hours later you have a dead phone.

A head unit does not have that problem at all. It is permanently powered from
the car, its USB ports are already host ports, and there is no battery to
flatten. Plug the board in and it just works. Everything below about hubs and
Y-cables is for the phone case — on a head unit you can skip all of it.

What to check on the head unit:

- **Android 8 or newer.** The app targets API 26+, which covers essentially
  every Android head unit sold since about 2018.
- **A spare USB port.** Most have two or three. If yours has a dedicated
  "USB DVR" or "USB OTG" port, any of them will do — they are all host ports.
- **GPS quality.** This is the one thing worth testing before you trust it.
  Built-in receivers in cheap head units vary from excellent to dreadful, and
  the antenna is often a stub buried behind the dashboard. Run the app, watch
  the speed readout while driving a road you know, and compare it against the
  car's own speedometer. If it lags or jumps, a €10 external GPS antenna on the
  windscreen usually fixes it — most head units have an SMA socket for one.
- **Autostart.** The app registers for `USB_DEVICE_ATTACHED`, so plugging the
  board in launches it. On a head unit that powers up with the ignition, that
  means the HUD comes alive on its own when you start the car.

The app's main screen is landscape and dark for exactly this reason: it is
meant to live on a head unit, not a phone in a cradle.

## The power problem — only if you use a phone

Three ways out, best first:

1. **USB-C hub with PD passthrough.** Phone → hub, car charger → hub's PD port,
   ESP32 → one of the hub's A ports. The phone charges and hosts at the same
   time. Works on essentially any USB-C Android from ~2019 onwards.
2. **Bluetooth instead.** Tick "Use Bluetooth" in the app, swap `Serial` for
   `BluetoothSerial` in the sketch, and power the ESP32 from its own car
   adapter. The phone charges normally on its own cable. Same protocol, same
   display code — genuinely one line of change on each side.
3. **Micro-USB OTG Y-cable.** Cheap, works on some phones, ignored by others.
   Fine to experiment with, not something to plan the build around.

## Wiring

NodeMCU v3 (ESP8266) → ST7796S, HSPI bus:

```
   NodeMCU                   ST7796S TFT
   -------                   -----------
   3V3    ---------------->  VCC
   GND    ---------------->  GND
   D5  GPIO14 ------------>  SCK          fixed by the SPI peripheral
   D7  GPIO13 ------------>  MOSI (SDI)   fixed
   D6  GPIO12 <-----------   MISO (SDO)   leave unconnected
   D8  GPIO15 ------------>  CS
   D1  GPIO5  ------------>  DC   (RS)
   RST        ------------>  RESET        the board's own reset pin
   D2  GPIO4  ------------>  LED  (backlight, PWM for night dimming)
        or
   3V3        ------------>  LED          always on, if you skip the dimming
```

Three things that catch people out, and one of them will cost you an evening:

- **GPIO0, GPIO2 and GPIO15 are read at reset to pick the boot mode.** They must
  be high, high and low respectively. TFT_eSPI's stock ESP8266 setup puts DC on
  GPIO0 and RESET on GPIO2, so a display module that holds either low at
  power-up stops the board booting entirely — and it looks exactly like a dead
  ESP8266. The pin map above uses neither. GPIO15 is still used for chip select,
  which is fine because every NodeMCU and D1 mini has a pulldown on it; check
  your display module does not pull its CS input high.
- **SCK, MOSI and MISO are not a choice.** `SPIClass::pins()` in the ESP8266
  core accepts exactly two pin sets and the TFT one is fixed in silicon.
  Renaming them in `User_Setup.h` moves nothing but the comment.
- **These modules are 3.3 V**, which is what an ESP8266 runs at, so there is
  nothing to think about. Keep the SPI wires under 10 cm.

The shipped `SPI_FREQUENCY` is 20 MHz, which is deliberately conservative: the
ST7796S datasheet's minimum serial write cycle is 66 ns, or 15.15 MHz, and
TFT_eSPI's own 27 MHz default is already 1.8× over it. These panels usually
tolerate it on short PCB traces; loose dupont wire is where they stop. If you
see stray pixels or a scrambled display once in ten power-ons, go to 10 MHz
before suspecting anything else. Achievable rates are 80 MHz over a whole
number, so 27 MHz actually runs at 26.67 and 25 MHz would silently drop you
to 20.

**Power.** The panel draws about 120 mA with the backlight on, which is more
than some USB-serial adapters will supply. Power the board from the car, not
from a laptop port, before blaming the wiring for a display that resets under
load.

## Mirroring and keystone

The panel is meant to lie flat on the dash and be read as a reflection in the
windscreen. Two things follow, and both are set from the app's **Align the
screen** page rather than by editing code.

**The reflection flips the picture left-to-right.** The board flips it back with
a MADCTL bit — rotations 1, 7, 5 and 3 are the four landscape orientations of an
ST7796, so this costs nothing per frame and every one of them still reports
480×320 to the driver, which is what keeps clipping correct. Hand-writing 0x36
over the top of `setRotation(1)` would flip the pixels and leave the library's
width and height stale; the firmware never does that.

**A dashboard is rarely level with the screen sitting on it**, so the reflection
comes out as a trapezoid. A projector fixes that by warping the finished image.
That is not available here: 480 × 320 × 16 bpp is 307,200 bytes and an ESP8266
has about 40 KB of heap, so a full-frame warp is not slow, it is impossible —
and it is impossible on an ESP32 without PSRAM too.

What a head-up display has going for it is that it does not draw photographs. It
draws lines, boxes, triangles and text, all with known coordinates, so the
correction goes in *before* rasterising: every coordinate passes through a
homography on its way to the panel. No RAM at all, it composes with the delta
renderer, and glyphs stay crisp because nothing is ever resampled. The honest
limit is that a glyph is *placed and sized* by the transform rather than sheared
by it — your layout goes properly rectangular, but a capital H stays upright
instead of leaning into the trapezoid. Below about fifteen degrees of correction
you will not see it.

Both settings live in the display's flash, so they survive a power cycle and a
different phone. The app previews changes live and only writes when you press
Save: the ESP8266 emulates its EEPROM by erasing a whole 4 KB sector, so
committing on every slider movement would spend the chip's endurance in one
afternoon.

### The optional gyroscope

The one to buy is a **GY-521 breakout carrying an MPU-6050**. About three euros,
sold by every Arduino shop in Europe, and the module most likely to already be
in your parts drawer.

Resist the upgrade. A BNO055 or an ICM-20948 costs five times as much and the
extra money buys on-chip sensor fusion producing an *absolute* heading from a
magnetometer — which, inside a steel car next to an alternator, a phone charger
and a set of speakers, is worse than the GPS heading it would be replacing. You
would be paying for the one part you must not use. All this sensor has to do is
answer "how fast is the car turning, right now", because GPS already knows which
way it is pointing; it just only says so once a second. A plain gyro does that
perfectly.

```
   ESP32                     MPU-6050 (GY-521)
   -----                     -----------------
   3V3    ---------------->  VCC        (the board has its own regulator, but
   GND    ---------------->  GND         3V3 keeps the I2C bus at 3.3 V)
   GPIO22 <-------------->   SCL
   GPIO21 <-------------->   SDA
                             AD0  left unconnected = address 0x68
```

On an Uno: SCL -> A5, SDA -> A4, VCC -> 5V.

Then uncomment `#define HUD_IMU` at the top of `NavHud.ino` and reflash.

**Mounting.** Anywhere, any way up, as long as it cannot move relative to the
car — cable-tied to the loom behind the dash is ideal. It does not need to be
level or square: the sketch works out which axis is pointing at the sky from
gravity at startup. What it must not do is sit loose on the passenger seat.

**Calibration happens by itself.** The zero-rate offset is measured at boot and
again every time the phone reports the car has been standing still for a
second — which is the only moment the true turn rate is known to be zero. There
is nothing to adjust.

**You do not need it.** Without the module the app uses the phone's own
gyroscope, which is nearly as good; without that either, it falls back to plain
GPS heading, which works but turns a beat late. The board tells the app which
one it is doing by announcing itself as `$HELLO,NAVHUD,2,IMU`, and the Setup
screen shows the link description.

## Which file owns what

The firmware is split by concern, so that a symptom points at one file rather
than at a thousand-line sketch. In rough order of how often you will open them:

| File | Owns | Open it when |
|---|---|---|
| `hud_config.h` | Every setting you change before flashing: theme, `HUD_CAN`, `HUD_BENCH`, pins, backlight levels, `HUD_IMU`/`HUD_MAG`, timeouts | You want to turn something on or off |
| `hud_display.h` | Which of the five screens is up, and the rule that no route means no maneuver | The screen shows the wrong thing |
| `hud_backlight.h` | Lit or dark (the key, off 0x130) and how bright (the phone's night flag) | The panel is dark when it should not be, or lit when it should not be |
| `hud_can.h` | The MCP2515 itself: SPI, registers, bit timing, listen-only | No frames arrive, or `$CANDROP` climbs |
| `hud_car.h` | Turning E60 CAN bytes into speed, rpm, torque, volts, key state | A number on the glass is wrong but the bus is clearly alive |
| `hud_link.h` | Everything crossing the USB cable, both directions | A message is missing or malformed |
| `hud_align.h` | Mirror, rotation, keystone, and the EEPROM sector they live in | The picture is the wrong way round, trapezoidal, or forgets itself |
| `hud_state.h` | The variables the modules share | "What else touches this?" |
| `theme_dash.h` | The three-band E60 layout that ships | Something is in the wrong place on screen |
| `theme_e60.h` | The older nav-only E60 layout (`HUD_THEME_E60_CLASSIC`) | Only if you turned that on |
| `hud_arrows.h` | The maneuver glyphs | An arrow is the wrong shape |
| `hud_imu.h` / `hud_mag.h` | MPU-9250 gyro and AK8963 compass | Heading or yaw rate is wrong |
| `NavHud.ino` | `setup()` and `loop()`, and nothing else | You want to see the order things happen in |

Two rules hold the split together, and both are load-bearing:

- **`hud_state.h` is included once**, by the sketch, which defines
  `HUD_STATE_OWNER` first. Every other module sees `extern` declarations.
- **`hud_align.h` and `hud_link.h` call into each other** — the command
  dispatch saves geometry, and the geometry reports over the cable — so each
  forward-declares the handful of functions it needs from the other. The
  alternative was splitting the cable in half, which would give "where does the
  board send things from" two answers.

## Flashing the display

1. Install the **ESP8266 board package** (Arduino IDE → Boards Manager →
   "esp8266 by ESP8266 Community").
2. Install the **TFT_eSPI** library (Library Manager).
3. Copy `arduino/config/User_Setup_ESP8266.h` over
   `libraries/TFT_eSPI/User_Setup.h`, keeping the original somewhere. TFT_eSPI
   is configured by editing the library rather than the sketch — an unfortunate
   design, but it is the library's.
4. Open `arduino/NavHud/NavHud.ino`, select **NodeMCU 1.0 (ESP-12E Module)**,
   set CPU frequency to 160 MHz, upload.
5. You should see `NavHUD — waiting for the phone...`, then `NO LINK` after 4 s.
   That is the sketch working correctly with nothing talking to it.

The text will read backwards, because the firmware ships mirrored for the
windscreen mount. That is not a fault — hold a mirror up to it, or turn the
mirror off on the app's alignment page. If you would rather it came up
unmirrored, `HUD_DEFAULT_MIRROR_X` at the top of `NavHud.ino` is the switch.

**If the screen comes up white with a working backlight**, the controller is
powered but was never initialised, and the usual reason is that you talked to
it too soon. RESET is tied to the board's own reset line rather than driven by
the library, so the ST7796S and the ESP8266 come out of reset together — and
the datasheet wants 120 ms after reset before the controller will accept a
command. The ESP is ready long before that. `NavHud.ino` waits 250 ms before
`init()` and 50 ms after, which is that budget with room to spare.

This is worth knowing because it does not fail cleanly: the init sequence goes
into a controller that is not listening, every later draw is ignored, and what
you see is indistinguishable from a dead panel or bad wiring. It cost an
evening here. If you write your own sketch against this display, put the delay
in first.

**Watch out for the boot message.** An ESP8266's ROM writes its own startup log
on the serial pins at 74880 baud, so the first thing a terminal sees after a
reset is a burst of garbage. Every NavHUD sentence is framed and checksummed
exactly so this does not matter.

## Themes

Two are included, switched with one line at the top of `NavHud.ino`:

```cpp
//#define HUD_THEME_MODERN     // commented out = BMW E60 (the default)
```

**E60** is the default: monochrome amber on black, big speed reading on top,
navigation underneath, a thin rule between them — the way the 2004–2010 5-series
head-up display looked. The one departure from the original is that the speed
turns red and gets bracketed when you are over the limit, and the roundel blinks
between red and amber. (It blinks between two *visible* colours deliberately —
a limit that disappears half the time is a limit you cannot read.)

**Modern** is full colour: green turn arrow going yellow as the turn approaches,
a proper red-and-white roundel, white speed turning red. Easier to read at a
glance; looks nothing like a BMW.

Both are plain C++ headers implementing the same five functions, so writing a
third is a copy-paste job. `tools/layout_preview.html` renders both in a browser
with the layout constants pulled straight from the headers — change them there
first, it is a much faster loop than reflashing.

### Making the E60 typography authentic

The real HUD used a DIN-like typeface. TFT_eSPI's built-in fonts are what the
sketch falls back on, and they look more "digital clock" than "BMW". To fix that
properly:

1. Download a free DIN-alike — **D-DIN** or **Barlow Condensed** both work well.
2. Convert it with the `Create_font` Processing sketch that ships with TFT_eSPI,
   at around 75 px for the speed and 26 px for the labels. You get `.vlw` files.
3. Put them in SPIFFS/LittleFS on the ESP32 and load with `tft.loadFont()`.

It is half an hour of faff and it is the difference between "amber numbers" and
"that's an E60".

## Test it from a PC before involving the phone

This is the step worth not skipping. With the board still plugged into your
computer:

```bash
pip install pyserial
python3 tools/hud_sim.py --port /dev/ttyUSB0        # or COM3 on Windows
```

It replays a synthetic drive: residential street, a right turn, a roundabout
with a 2nd exit, a slip road onto the E40 at 120, a stretch with no speed-limit
data (watch for the amber ring), then off the motorway and arrive. Every screen
element and every maneuver arrow gets exercised in about four minutes.

Add `--speed 8` to run it eight times faster while you tune the layout.

If the display stays on `NO LINK`, it is almost always one of: wrong port, wrong
baud, or a charge-only cable.

## Building the app

1. Get a free Mapbox token at <https://account.mapbox.com/access-tokens/>. The
   default public token (`pk.eyJ...`) is all you need — it covers routing,
   geocoding and the map tiles.
2. `cp android/local.properties.example android/local.properties` and paste the
   token in.
3. Open `android/` in Android Studio and run it on the head unit (or a phone).
4. Press **Go → Demo drive**. No GPS, no token, no car — it replays the same
   synthetic drive over the real USB link, with voice guidance. If the TFT comes
   alive and you hear "in 300 metres, turn right", the whole chain works.

The first time you plug the board in, Android asks for permission to access the
USB device. Tick "use by default" so it stops asking.

### What the app does

- **Map** — osmdroid with Mapbox's `navigation-day` / `navigation-night` raster
  tiles, switching by time of day. Follows your position and rotates to your
  heading; pan the map and it drops out of follow mode until you tap Recenter.
- **Search** — Mapbox geocoding, biased to where you are, with recents kept
  locally. You drive to the same six places; it should take one tap.
- **Voice** — spoken turn-by-turn with thresholds that scale with speed, so
  every call lands roughly the same number of seconds before the turn whether
  you are doing 30 or 130. Ducks the music rather than talking over it. There is
  also a single beep when you go 5 km/h over, with a 20-second cooldown and
  hysteresis so it does not nag.
- **Setup screen** — the raw protocol frames as they go down the wire, which is
  where you look when something is wrong.

Voice runs on Android's built-in TTS, so it speaks whatever languages the head
unit has installed. Change the locale in `HudService.onCreate` if you would
rather be told to *tourner à droite*.

## Mounting it in the car

The simplest version is a box on the dash with the screen facing you. That is a
"head-down display" but it is a fine place to start, and it is legible.

For a real HUD you reflect the screen off the windscreen, which means:

- Uncomment `#define HUD_MIRROR` in `NavHud.ino`, so text reads correctly in
  the reflection. If it flips the wrong axis on your panel, change the MADCTL
  value on the line below it — the comment there explains the bits.
- Reflect off a **combiner** (a sheet of acrylic angled ~30°) rather than the
  glass itself if you can. A windscreen is two panes and gives you a ghost
  image about 10 cm from the real one, which is much more distracting than it
  sounds.
- Everything around the screen wants to be matte black, or you get the dash
  reflected up at you along with the display.
- Check it at night before you trust it in daylight; the brightness that is
  right at noon is blinding at midnight, which is what the night flag is for.

One legal note worth checking before you fix anything permanently: rules about
what may sit in the driver's field of view vary by country, and in most of
Europe a device must not obstruct the view of the road. Worth a look at your
national road code before you drill anything.

## What it costs to run

A route request happens once when you start, and again on each reroute — call it
five to twenty calls per drive. Mapbox's free tier is far above anything a
personal build reaches. Speed limits ride along inside the same response, so
they cost nothing extra. There is no per-second billing here; the phone does all
the tracking locally between route requests.
