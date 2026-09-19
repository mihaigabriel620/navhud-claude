// ---------------------------------------------------------------------------
//  TFT_eSPI setup:  ESP8266 (NodeMCU v3 / Wemos D1 mini)  +  4.0" ST7796S
//
//  Copy this file over User_Setup.h in the TFT_eSPI library folder:
//      ~/Arduino/libraries/TFT_eSPI/User_Setup.h
//  (Keep a copy of the original. TFT_eSPI is configured by editing the
//  library, not the sketch -- an unfortunate design, but it is the library's.)
//
//  Every value below was taken from TFT_eSPI's own driver tables and from the
//  ST7796S datasheet, not from a forum post. The reasoning is in the comments
//  because half of these are the difference between a working display and an
//  ESP8266 that will not boot.
// ---------------------------------------------------------------------------

// ---- 1. the panel ----------------------------------------------------------
#define ST7796_DRIVER

// TFT_WIDTH / TFT_HEIGHT are deliberately NOT set here. ST7796_Defines.h
// declares the panel in portrait -- 320 x 480 -- and setRotation() derives
// landscape from that. Overriding them here breaks the rotation table, and
// User_Setup.h's own width/height block is documented as being for ST7789,
// ST7735, ILI9163 and GC9A01 only.

// Colour order. Leave this alone unless red and blue come out swapped, in
// which case uncomment it. ST7796_Defines.h defaults to BGR.
//#define TFT_RGB_ORDER TFT_RGB

// ---- 2. wiring -------------------------------------------------------------
//
// SCK, MOSI and MISO are not a choice. The ESP8266's HSPI peripheral is wired
// to GPIO14 / GPIO13 / GPIO12 in silicon; SPIClass::pins() rejects anything
// else, so changing the numbers below moves nothing but the comment.
// Raw GPIO numbers, not the PIN_Dn macros. Those are only defined by the
// NodeMCU and D1-mini board variants, so a sketch built as "Generic ESP8266
// Module" fails with "PIN_D7 was not declared in this scope" -- which reads
// like a broken library rather than a board-menu setting.
#define TFT_MOSI 13   // D7   fixed by the SPI peripheral
#define TFT_SCLK 14   // D5   fixed

// MISO must be declared even though the panel is never read, because the
// MCP2515 shares this bus and does need it. TFT_eSPI's own README: "If you
// wish to use this library and an SD card on the same SPI bus you must ensure
// that TFT_MISO, TFT_MOSI and TFT_SCLK are explicitly defined... even if you
// are using the default SPI pins" -- the same applies to any second device.
// -1, deliberately, and the panel's SDO pin must be left UNWIRED.
//
// The MCP2515 is on this bus too and only chip select separates them. ST7796S
// silicon tri-states SDO correctly (TOH 15-50 ns in the datasheet), but the
// MODULES frequently do not -- TFT_eSPI's author warns that on ST7796 and
// ILI9488 boards "the SDO/MISO pin may not go tristate when the TFT chip
// select is high", usually because of a series diode in the CS line. A panel
// that keeps driving MISO means the CAN controller can never be heard: every
// register reads FF on wiring that is perfect.
//
// The HUD never reads from the display, so defining this buys nothing and
// costs the CAN bus. TFT_eSPI defaults it to -1 when undefined.
#define TFT_MISO -1
// MISO is left out on purpose: nothing here ever reads the panel back, and an
// unconnected MISO is one less wire to pick up noise.

#define TFT_CS   4      // D2. MOVED off D8/GPIO15 in 2.5: that pin is the
                        // MCP2515's chip select now, because a loopback test
                        // proved the controller answers there and nowhere else.
                        // GPIO4 is a clean pin with nothing fitted to it, which
                        // is all a chip select ever wanted.
#define TFT_DC    5   // D1
#define TFT_RST  -1   // wire the panel's RESET to the board's own RST pin

// Why these three and not the library's defaults:
//
// The ESP8266 samples GPIO0, GPIO2 and GPIO15 as it comes out of reset to
// decide where to boot from. It needs GPIO15 low, GPIO0 high and GPIO2 high.
// TFT_eSPI's stock ESP8266 setup puts TFT_DC on GPIO0 and TFT_RST on GPIO2 --
// and a display module whose RST line sits low at power-up then stops the
// board booting entirely. It looks like a dead ESP8266 and it is the single
// most common failure in this combination. So: DC on GPIO5 and reset tied to
// the board's reset, which leaves GPIO0 and GPIO2 free for an I2C gyro.
//
// GPIO15 (D8) is still used, for chip select. Every NodeMCU and D1 mini has a
// 10k pulldown on it, which is what boot wants; check that your display module
// does not pull its CS input high, or the board will not start with the panel
// attached. If it does, move CS to D0/GPIO16 -- slower, but harmless for a
// signal that changes once per transaction.
//
// The backlight is NOT declared here. TFT_eSPI would then drive it hard on and
// leave it there; the sketch wants PWM on it for night dimming, so it owns the
// pin itself (BACKLIGHT_PIN in NavHud.ino, GPIO4 / D2).
//
// This panel is 3.3 V only -- no regulator and no level shifters on the board,
// so the ST7796S sits directly on your supply rail and its absolute maximum is
// about 3.6 V. Feed VCC from 3V3, never from VIN.

// ---- 3. fonts --------------------------------------------------------------
#define LOAD_GLCD    //  1  the 8px Adafruit font, ~1.8k
#define LOAD_FONT2   //  2  16px ASCII, ~3.5k
#define LOAD_FONT4   //  4  26px ASCII, ~5.8k -- street names, units, labels
#define LOAD_FONT6   //  6  48px digits, ".", ":", "-", "a", "p", "m", ~2.7k
#define LOAD_FONT7   //  7  48px seven-segment digits, ~2.4k
#define LOAD_FONT8   //  8  75px digits, ~2.4k -- the speed reading
#define SMOOTH_FONT  //     needed only if you add a .vlw DIN face, see BUILD.md

// Fonts 6, 7 and 8 have no letters in them. That is a property of the font
// files, not a setting: anything with words in it is font 2 or font 4, which
// is why both themes draw headings at font 4 with setTextSize(2).

// ---- 4. SPI ----------------------------------------------------------------
//
// The honest numbers, because the two sources disagree and it matters:
//
//   * The ST7796S datasheet gives a minimum serial write cycle of 66 ns
//     (section 7.4.3), which is 15.15 MHz. Reads are 150 ns, or 6.67 MHz.
//   * TFT_eSPI's README claims 40 MHz on an ESP8266 for this panel, and its
//     own default is 27 MHz.
//
// So the library's default is already about 1.8x over the datasheet. These
// panels usually tolerate it on short PCB traces; loose dupont wire is where
// it stops tolerating it, and the symptom is stray pixels or a display that
// comes up scrambled once in ten power-ons. 20 MHz costs about 30 ms on a full
// repaint -- which happens when the cable is plugged in, not while driving --
// and buys a comfortable margin.
//
// If you see corruption: 20000000 -> 10000000 before suspecting anything else.
// Achievable rates are 80 MHz divided by a whole number, so 27000000 actually
// runs at 26.67 MHz and 25000000 would drop you to 20.
// ---------------------------------------------------------------------------
//  THE ONE LINE THAT MAKES THE SHARED BUS WORK
// ---------------------------------------------------------------------------
// TFT_eSPI leaves this OFF by default on ESP8266 -- it is auto-enabled for the
// ESP32 and nowhere else. Its own comment says "Transaction support is
// required if other SPI devices are connected", and this build has one: the
// MCP2515 on GPIO16.
//
// Without it, begin_tft_write() never calls beginTransaction, so the library
// sets the clock and mode ONCE at init() and never again. The first CAN read
// leaves the bus at 8 MHz and the display turns to confetti -- or the display
// leaves it at 20 MHz and the CAN controller returns garbage. Which of the two
// you see depends on the order, which makes it look intermittent.
#define SUPPORT_TRANSACTIONS

// 13.33 MHz (80/6 -- an exact divider, so setFrequency() is two iterations
// rather than a 252-step search).
//
// It was 20 MHz. The ST7796S datasheet's 4-SPI table gives TSCYCW, the serial
// clock cycle for a write, as 66 ns minimum -- 15.2 MHz. 20 MHz is 32% over
// that, which is the kind of margin that works on the bench and fails warm, in
// a car, through soldered joints. A full repaint goes from about 123 ms to
// 185 ms, which is not the thing that will be noticed.
#define SPI_FREQUENCY       13333333

// IF YOU EVER SEE STRAY PIXELS OR A LINE THAT WILL NOT GO AWAY, THIS IS THE
// FIRST KNOB. Corruption on a shared SPI bus with jumper wires does not look
// like noise -- it looks like a stable mark, because the theme only repaints
// the widgets that changed. One bad burst during the single full-screen clear
// at boot leaves a band of wrong pixels that nothing ever paints over again.
//
// Type `wipe` on the serial line first: it floods the panel red, green and
// blue straight at the driver. A mark that survives a solid red screen is the
// panel or the ribbon and no clock will fix it. A mark that disappears under
// the fills and comes back is a bus problem, and these are the exact dividers
// of the ESP8266's 80 MHz peripheral clock -- use one of them, not a round
// number, or setFrequency() spends 252 iterations searching:
//
//     80/6 = 13333333   (here)
//     80/8 = 10000000
//     80/10 =  8000000
//     80/16 =  5000000  (if it still misbehaves at 8, the wiring is the fault)

// The stock 20 MHz read clock is three times the datasheet limit. Nothing in
// NavHUD reads the panel, but leave this sane in case you add tft.readPixel().
#define SPI_READ_FREQUENCY   5000000

// For the resistive touch controller on the same module, if you wire it up.
// NavHUD does not use touch -- the phone is the touchscreen.
#define SPI_TOUCH_FREQUENCY  2500000

// No DMA on ESP8266: Processors/TFT_eSPI_ESP8266.h defines DMA_BUSY_CHECK as
// nothing and there is no initDMA() for this architecture. Do not enable it.
