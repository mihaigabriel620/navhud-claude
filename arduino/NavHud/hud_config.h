// ---------------------------------------------------------------------------
//  hud_config.h -- every setting you might change before flashing, in one file.
//
//  Nothing in here does anything. It is only knobs, so that "how do I turn the
//  compass on" and "why is my screen mirrored" have one place to look instead
//  of being scattered through a thousand-line sketch.
//
//  Anything defined on the compiler command line (-DHUD_BENCH, -DBACKLIGHT_PIN=4)
//  wins over the guarded defaults below, which is how the host test compiles
//  configurations the sketch does not ship with.
// ---------------------------------------------------------------------------
#ifndef HUD_CONFIG_H
#define HUD_CONFIG_H

// ---- theme -----------------------------------------------------------------
// The default is the three-band E60 layout: nav plus the CAN data, in the
// factory head-up display's amber. Uncomment for the older nav-only E60
// layout, which predates the bus and shows no rpm, PS or volts.
//
// There was a third, full-colour "modern" theme. It is gone: nobody was going
// to run a blue-and-white display in an E60, and it was 9 KB of flash and a
// third set of arrow glyphs to keep in step with the other two.
//#define HUD_THEME_E60_CLASSIC

// ---- the car half ----------------------------------------------------------
// Comment out to build the nav-only HUD with no MCP2515 fitted. The theme
// still compiles: every CAN field simply reads as stale and is left blank.
#define HUD_CAN

// How many frames to drain per pass. Listen-only mode ignores the hardware
// filters (MCP2515 datasheet 10.3), so EVERY frame on the bus arrives here and
// is sorted in software. Unbounded draining would let a busy bus starve the
// display; this caps the worst case at a few hundred microseconds and lets the
// overflow counter tell us if it is too tight.
//
// K-CAN is 100 kbit/s, so a full 8-byte frame is about 1.1 ms on the wire and
// twelve of them is more than a loop pass will ever find waiting. The number
// is sized for the 500 kbit/s case it may yet meet on PT-CAN.
// Use coryjfowler's MCP_CAN library instead of the driver in hud_can.h.
// Install "mcp_can" from the Arduino Library Manager. Both are audited and
// their bit timing agrees byte for byte; having two matters when a board will
// not talk, because "my code is correct" is a claim and a second
// implementation is evidence.
#ifndef CAN_USE_LIBRARY
  #define CAN_USE_LIBRARY 1
#endif

#define CAN_DRAIN_PER_PASS 12
#define CAN_REPORT_MS      5000

// ---- the cable to the phone ------------------------------------------------
#define LINK_BAUD        115200
#define LINK_TIMEOUT_MS  3000    // no frame for this long -> NO LINK screen

// ---- backlight -------------------------------------------------------------
// Overridable so the host test can compile the backlight in and watch it.
// Without that the whole block vanishes on a PC build and the key rule in
// hud_backlight.h is the one piece of logic there that nothing ever checks.
#ifndef BACKLIGHT_PIN
  #if defined(ARDUINO_ARCH_ESP8266)
    #define BACKLIGHT_PIN  4     // D2. -1 to disable, or wire LED to 3V3.
  #else
    #define BACKLIGHT_PIN  -1
  #endif
#endif
#define BACKLIGHT_DAY    255
#define BACKLIGHT_NIGHT  70

// ---- screen orientation ----------------------------------------------------
// Where the panel starts before the phone has ever said otherwise. 1 is right
// for the intended mount -- flat on the dash, read as a reflection in the
// windscreen, which flips the image left-to-right. Set it to 0 if you are
// bench-testing by looking straight at the panel, or just toggle it from the
// app's keystone screen, which is what it is for.
#define HUD_DEFAULT_MIRROR_X 1
#define HUD_DEFAULT_MIRROR_Y 0

// ---- bench mode ------------------------------------------------------------
// Unmirrored, no keystone, and the saved settings in flash are ignored
// entirely.
//
// The default above only applies to a board whose flash has never been
// written. Once you have pressed Save in the app even once, that stored
// setting wins for ever and changing the line above does nothing -- which is
// correct in a car and maddening on a desk. This overrides it without erasing
// anything: uncomment, flash, read the screen the right way round, comment it
// out again and your saved alignment is still there.
//
// It also forces the backlight on. On the car the backlight follows the key
// off 0x130 and nothing else, so a board on a desk -- no bus, no key, no
// frames -- would sit there black. Uncommenting this is what makes the panel
// visible without a car attached, and it is the escape hatch if the MCP2515
// ever fails and takes the key signal with it.
//#define HUD_BENCH

// ---- boot self-test --------------------------------------------------------
// Flash red, green and blue at boot before anything else draws. Straight at
// the driver: no theme, no geometry, no fonts.
//
// Off for normal use -- it costs two seconds every time the board wakes up.
// Uncomment it if the panel ever comes up white again: it separates "the
// controller is not listening", where nothing above it matters, from "the
// drawing code is wrong", which is mine to fix. That distinction took an
// evening to establish by argument and takes one boot to establish with this.
//#define HUD_BOOT_SELFTEST

// ---- the gyroscope --------------------------------------------------------
// A rate sensor on the I2C bus: an MPU-6050, an MPU-6500, or the gyro half of
// an MPU-9250 -- they answer on the same registers and any of them will do.
//
// It reports the car's yaw RATE back up the cable, and the phone uses it to
// rotate the map between GPS fixes. That is what makes the arrow turn WITH the
// car rather than a second after it. Without one the phone falls back to its
// own gyroscope, and a head unit has not got one.
//
// Comment out if none is fitted. Nothing else depends on it -- not the
// compass, not the display, not the bus.
#ifndef HUD_IMU
  #define HUD_IMU
#endif

#define IMU_SEND_INTERVAL_MS 50      // 20 Hz is plenty and barely touches the link

// ---- the compass ----------------------------------------------------------
// A QMC5883 on the same two I2C wires. This is what answers "which way do I
// pull out of this parking space" -- the one question a gyro cannot, because a
// gyro measures how fast you are turning and never which way you point.
//
// Independent of the gyro: it is its own board and is started whether or not
// an MPU is fitted or answering.
#ifndef HUD_MAG
  #define HUD_MAG
#endif

// Slower than the gyro on purpose. A heading is not a rate: it does not need
// integrating, nothing downstream reacts to it faster than a person can look
// up, and every line sent is UART time the nav frames want more.
#define MAG_SEND_INTERVAL_MS 200


// ---- keystone adjustment ---------------------------------------------------
// A full repaint of the drive display is roughly a tenth of a second at 20 MHz.
// The phone sends a $GEOM on every movement of a slider, so repainting on each
// one would put the board a second behind within ten seconds of dragging. Two
// things stop that: the alignment pattern, which is a few dozen lines rather
// than a screenful, and this coalescing window.
#define GEOM_REPAINT_MS   120
// And a way out if the phone never says it has finished: the pattern is not
// something to be driving behind.
#define GEOM_TEST_IDLE_MS 120000UL


// ===========================================================================
//  HARDWARE
//
//  Everything below is a wire, a pin or a part number. What is NOT here is
//  anything that is a FACT about a chip rather than a choice you get to make
//  -- register addresses, LSB-per-gauss, bit-timing tables. Those live with
//  their drivers, because moving them here would fill this file with numbers
//  you can never change and bury the ones you can.
// ===========================================================================

// ---- the MCP2515 CAN controller -------------------------------------------
// Chip select. GPIO16 is the only pin left once the panel has its five, and it
// is the one ESP8266 pin with no internal pull-up -- Espressif's own wording is
// that it "can only be configured with internal pull-down". An external 10k to
// 3V3 is NOT optional: without it CS floats low through reset and the
// controller sees a transaction that never ends.
#define CAN_CS_PIN        16

// Read the number off the metal can. Do not trust the listing: the common
// boards ship 8 MHz, most library examples assume 16, and a mismatch is silent
// -- you simply never receive a frame, and $CANDROP stays at zero rather than
// climbing, which is the tell.
#define CAN_XTAL_MHZ      8

// 100 for K-CAN (behind the cluster, where speed, rpm, torque, ignition and
// battery are gatewayed), 500 for PT-CAN. Only 8/16 MHz crossed with 100/500
// have pinned bit timing; anything else refuses to build rather than guessing.
#define CAN_BITRATE_KBPS  100

// Listen-only: the controller never transmits, not even an acknowledge, so it
// physically cannot disturb the car's bus. Leave this alone unless you have a
// very good reason and a bus you own.
#define CAN_LISTEN_ONLY   1

// The MCP2515 tops out at 10 MHz (Table 13-6). This shares the display's bus.
#define CAN_SPI_HZ        8000000

// Frames drained per pass. Listen-only ignores the hardware filters, so the
// WHOLE bus arrives here and is sorted in software; unbounded draining would
// let a busy bus starve the display.
// Use coryjfowler's MCP_CAN library instead of the driver in hud_can.h.
// Install "mcp_can" from the Arduino Library Manager. Both are audited and
// their bit timing agrees byte for byte; having two matters when a board will
// not talk, because "my code is correct" is a claim and a second
// implementation is evidence.
#ifndef CAN_USE_LIBRARY
  #define CAN_USE_LIBRARY 1
#endif

#define CAN_DRAIN_PER_PASS 12
#define CAN_REPORT_MS      5000

// ---- the I2C bus ----------------------------------------------------------
// Not the default pins: GPIO4 and GPIO5 are the backlight and DC on this
// build, and Wire.begin() with no arguments would take them.
#define HUD_IMU_SDA       0      // D3, GPIO0
#define HUD_IMU_SCL       2      // D4, GPIO2
#define HUD_IMU_ADDR      0x68   // gyro. AD0 low or floating; 0x69 if tied high.

// ---- which QMC5883 ---------------------------------------------------------
// The QMC driver probes for both parts and uses whichever answers, because the
// boxes cannot be trusted: GY-271 boards silkscreened HMC5883L have shipped
// QMC5883Ls for years, and the current wave ships QMC5883Ps marked "HP5883".
// Uncomment one of these only to force the issue.
//#define HUD_MAG_FORCE_QMC5883P
//#define HUD_MAG_FORCE_QMC5883L

// QST's own QMC5883P init writes 0x06 to register 0x29, "define the sign for X
// Y and Z axis" -- a register that appears nowhere in the datasheet's register
// map. Adafruit's driver never writes it and works. On by default because it
// is the manufacturer's sequence; set to 0 if you ever need it off.
#define QMC_WRITE_SIGN_REG 1

// ---- how the magnetometer sits relative to the IMU ------------------------
// The QMC is a separate board, so its axes are whatever you glued them to and
// need not match the MPU's. These say which QMC axis feeds which IMU axis, and
// with what sign.
//
// The default is "the same way up", which is what you get by sticking both to
// the same face pointing the same way. That is by far the easiest thing to do
// and the reason to do it.
//
// If you cannot, work it out from the boards' own silkscreen arrows, then check
// it: 'status' prints the magnetic DIP angle, which is the angle the Earth's
// field makes with the horizontal -- about 65 degrees in central Europe. It is
// a property of where you are, not of where you are pointing, so turn the car
// through a full circle and it should barely move. If it swings by tens of
// degrees as you turn, the mapping is wrong, and no amount of calibration will
// fix a heading built on it.
//
//   ORDER: which QMC axis (0=X 1=Y 2=Z) becomes the IMU's X, Y, Z
//   SIGN:  +1 or -1 for each
#define MAG_AXIS_ORDER    0, 1, 2
#define MAG_AXIS_SIGN     1, 1, 1

// ---- learning how the whole thing is bolted in ----------------------------
// You should not need to touch these. They are the gates on the automatic
// mounting calibration -- see hud_mount.h for where each number comes from.
#define MOUNT_STILL_TOL   0.35f    // m/s^2 of wobble allowed while "standing still"
#define MOUNT_STILL_MS    3000UL   // ...for this long before gravity is believed
#define MOUNT_ACCEL_MIN   0.8f     // m/s^2 before an acceleration event is worth having
#define MOUNT_YAW_MAX_DPS 3.0f     // above this the car is turning, not going straight
#define MOUNT_FWD_SAMPLES 150      // straight-line samples before an answer is offered
#define MOUNT_CORR_MIN    0.6f     // agreement with the bus below which it is refused
#define MOUNT_STALE_DEG   12.0f    // gravity drift that means somebody moved the sensor

#endif  // HUD_CONFIG_H
