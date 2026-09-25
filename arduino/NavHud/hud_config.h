// ---------------------------------------------------------------------------
//  hud_config.h -- every knob, and nothing else.
//
//  Pins are NOT here. They live in hud_pins.h, where the compiler checks them.
//  This file is for numbers you might reasonably want to change: what is
//  fitted, how fast, how bright, how long before something is stale.
// ---------------------------------------------------------------------------
#ifndef HUD_CONFIG_H
#define HUD_CONFIG_H

// ===========================================================================
//  WHAT IS FITTED
// ===========================================================================

/** The MCP2515 on K-CAN. Comment out for a board with no CAN module at all. */
#ifndef HUD_CAN
  #define HUD_CAN
#endif

/**
 * The I2C sensors: the QMC5883P compass, and the MPU-6050 if one is fitted.
 * Each is found at boot and looked for again while missing, so either may be
 * absent; without the MPU the compass is a flat one, right only with the box
 * level. Comment out for a board with nothing on I2C at all.
 */
#ifndef HUD_MAG
  #define HUD_MAG
#endif

/**
 * Bench mode: no car, no key, no bus.
 *
 * Lights the panel unconditionally and ignores the saved screen alignment, so
 * a board on a desk looks alive instead of dead. It changes NOTHING about I2C,
 * SPI, the compass or the CAN controller -- if the compass behaves differently
 * with this on and off, the difference is electrical, and the first suspect is
 * the backlight loading the 3V3 rail that the compass shares.
 */
//#define HUD_BENCH

// ===========================================================================
//  CAN
// ===========================================================================

/**
 * K-CAN on an E60 runs at 100 kbit/s. The powertrain bus (PT-CAN) is 500.
 * Get this wrong and the controller comes up happily and never receives a
 * single frame, because every one of them is a bit-timing error.
 */
#define CAN_BITRATE     CAN_100KBPS

/**
 * The crystal ON THE MODULE, which is not always the one on the silkscreen.
 * The blue boards are usually 8 MHz; some are 16. If the chip answers on SPI
 * but bring-up fails, this is the first thing to change.
 */
#define CAN_XTAL        MCP_8MHZ

/**
 * Frames to take from the controller per loop pass.
 *
 * Not "until empty", deliberately. The MCP2515 holds two received frames, about
 * 2.2 ms of a busy 100 kbit/s bus, and a full repaint takes about 185 ms -- so
 * frames WILL be missed during a repaint whatever this is set to. The bound
 * exists so a talkative bus can never keep us in the drain loop instead of
 * drawing. Falling behind gracefully beats a frozen display.
 */
#define CAN_DRAIN_MAX   8

/** How often to print the bus report when the phone is listening. */
#define CAN_REPORT_MS   500

/**
 * How often to look for a controller that stopped answering mid-drive. Each
 * look is an 8 ms probe; the library is only called once the chip answers.
 */
#define CAN_RETRY_MS    5000

/**
 * Longer than this between two drains and the frames waiting in the controller
 * are older than the timestamp they would be given.
 *
 * There is no interrupt wire, so a frame is stamped when it is READ, not when
 * it arrived. The chip holds two frames -- about 2.2 ms of a 100 kbit/s bus --
 * and a full repaint takes about 185 ms, so after a repaint the frames handed
 * over can be a tenth of a second old. 0x1A6's speed is a difference over time,
 * and a stale timestamp on a fresh count swings it by tens of km/h in both
 * directions. Past this gap the speed baseline is dropped instead, and
 * re-acquired on the next frame.
 */
#define CAN_STALE_GAP_MS 50

/**
 * The speed scale, and the one number here most likely to need a drive.
 *
 * 0x1A6 is a distance accumulator; km/h is (delta counts / delta ms) * this.
 * It is 100 as of 2.3, derived from r00li/CarCluster -- the full derivation is
 * written out next to the default in hud_car.h. It USED to be 80.4672, which
 * read about 20 % low and turns out to be 50 mph written in km/h.
 *
 * FIRST DRIVE: hold an indicated 100 km/h. If the HUD reads about 80, the old
 * number was right after all -- uncomment the line below and reflash.
 */
//#define CAR_SPEED_K    80.4672f

/**
 * How far back the accumulator is differenced, in ms. 0 = frame to frame.
 *
 * The counter moves in whole km/h once per frame, so frame-to-frame
 * differencing multiplies the loop's timestamp jitter by about ten. 300 ms of
 * sliding window costs at most 150 ms of lag and cut the worst error on a
 * jittered 97.4 km/h test stream from 11.5 km/h to 2.6.
 */
//#define CAR_SPEED_WIN_MS 300     // 300 is already the default; 0 = frame to frame

// ===========================================================================
//  COMPASS
// ===========================================================================

/**
 * Which chip axis feeds which car axis, and which way round.
 *
 * The car's frame is X FORWARD, Y LEFT, Z UP, and BOTH chips must be mapped
 * into it: the compass here, the MPU below. They are separate boards, so each
 * needs its own mapping unless they are mounted the same way round.
 *
 * ORDER picks the source: { 0, 1, 2 } means car-X takes chip-X, car-Y takes
 * chip-Y, car-Z takes chip-Z. SIGN flips it.
 *
 * Worked example. Say the board is lying flat but rotated a quarter turn, so
 * the chip's +Y points out of the windscreen and the chip's +X points at the
 * passenger door. Car-forward is chip-Y, so ORDER[0] = 1. Car-left is the
 * opposite of chip-X, so ORDER[1] = 0 and SIGN[1] = -1. Car-up is still chip-Z.
 *   #define MAG_AXIS_ORDER 1, 0, 2
 *   #define MAG_AXIS_SIGN  1, -1, 1
 *
 * A box turned on the dash needs no change here: point the car at a known
 * bearing and type `north <deg>` once, and the offset is stored. That handles
 * any rotation of the whole box about the vertical. What it cannot fix is the
 * two chips disagreeing with each other, so with an MPU fitted both mappings
 * must be right.
 */
#define MAG_AXIS_ORDER  0, 1, 2
#define MAG_AXIS_SIGN   1, 1, 1

/**
 * The same for the MPU-6050. Its board has the axes printed on it; the
 * identity means the X arrow points out of the windscreen and the Y arrow at
 * the driver's door on a left-hand-drive car. To check: type `status`, lift
 * the front of the box, and the pitch must go positive; lower the right side
 * and the roll must.
 */
#define MPU_AXIS_ORDER  0, 1, 2
#define MPU_AXIS_SIGN   1, 1, 1

/** Samples that must be collected before a calibration can be saved. */
#define COMPASS_CAL_MIN_SAMPLES   120

/**
 * How far each horizontal direction must sweep, in microtesla.
 *
 * The horizontal field in central Europe is about 20 uT, so a full turn sweeps
 * it across roughly 40 uT peak to peak. Requiring 25 means most of a circle
 * and refuses a calibration built from a quarter turn in a car park, which
 * would put the centre of the circle in the wrong place.
 */
#define COMPASS_CAL_MIN_SPAN_UT   25.0f

/**
 * How often each chip is read: 50 Hz. The MPU filters to 10 Hz and the heading
 * goes up the cable five times a second, so reading faster buys nothing and
 * costs bus time. One chip per loop pass, so the CAN controller is drained
 * between the two.
 */
#define SENSOR_READ_MS            20

/** How often to send $MAG up the cable. */
#define MAG_SEND_INTERVAL_MS      200

/** How often to send $IMU: the turn rate, averaged over the interval. */
#define IMU_SEND_INTERVAL_MS      50

/** How often to go looking again for a chip that is not answering. */
#define MAG_RETRY_MS              3000

// ===========================================================================
//  SCREEN GEOMETRY
// ===========================================================================
//
// The panel lies flat on the dash and is read as a reflection in the
// windscreen, so what leaves the glass has to be mirrored to arrive the right
// way round. The saved alignment in flash overrides these; they are what a
// board with empty flash starts from.
#ifndef HUD_DEFAULT_MIRROR_X
  #define HUD_DEFAULT_MIRROR_X 1
#endif
#ifndef HUD_DEFAULT_MIRROR_Y
  #define HUD_DEFAULT_MIRROR_Y 0
#endif

/**
 * Shortest gap between two repaints driven by a geometry change.
 *
 * Dragging a keystone slider in the app sends a $GEOM every few milliseconds.
 * Repainting on each one would spend the whole drag redrawing and never finish
 * a frame, so they coalesce: stage every one, paint at most this often.
 */
#define GEOM_REPAINT_MS   120

// ===========================================================================
//  BACKLIGHT
// ===========================================================================
#define BACKLIGHT_DAY     255
#define BACKLIGHT_NIGHT   70

/**
 * What to do when the CAN controller never came up.
 *
 * The key is the only thing that turns this panel on and off, and the key is
 * read from the bus. No controller means no key signal -- which is not the same
 * as "key out", and treating it as such produced a black screen on a board
 * whose display, compass and serial link all worked. A fault that disguises
 * itself as a dead display is the worst outcome available, so the default is to
 * light the panel and say so on the serial line.
 *
 * Define this to take the other trade: dark until the bus talks, at the price
 * of a black panel being ambiguous again.
 */
//#define CAN_DEAD_MEANS_DARK

// ===========================================================================
//  LINK AND TIMING
// ===========================================================================

/** Serial to the head unit. The app opens the port at this rate. */
#define HUD_BAUD              115200

/** No frame for this long and the phone is treated as gone. */
#define LINK_TIMEOUT_MS       2000

// DISPLAY_BOOT_GRACE_MS lives in hud_display.h, next to the rule it serves.

/**
 * Leave the alignment pattern if the app that asked for it goes quiet.
 *
 * Two minutes, as PROTOCOL.md, the Keystone screen and the 1.22 notes say. It
 * had drifted to 15 s, and the Keystone screen sends nothing while no slider
 * moves -- so fifteen seconds of looking at the windscreen took the grid away
 * until the screen was left and opened again.
 */
#define GEOM_TEST_IDLE_MS     120000UL

#endif  // HUD_CONFIG_H
