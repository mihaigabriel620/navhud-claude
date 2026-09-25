// ---------------------------------------------------------------------------
//  hud_pins.h -- every pin on this board, in one place, checked by the compiler.
//
//  This file exists because a pin conflict is invisible. Nothing warns you. The
//  display draws for a second and stops, or a bus that is wired perfectly never
//  answers, and you go looking in the driver. So every pin is declared once,
//  here, and the static_asserts at the bottom refuse to build if two things
//  claim the same one or if a pin is asked to do something it physically cannot.
//
//  EVERY CONSTRAINT BELOW IS FROM A PRIMARY SOURCE. Cited, because "I think D3
//  is fine" is how you lose an evening.
//
//    [DS]  Espressif ESP8266EX Datasheet v7.1
//    [TR]  Espressif ESP8266 Technical Reference (2020.10)
//    [WR]  Espressif ESP-WROOM-02 Datasheet v3.6, Table 2-1 (strap levels)
//    [BM]  esptool docs, "ESP8266 Boot Mode Selection"
//    [SCH] LOLIN D1 mini schematic v3.0.0 / v4.0.0
//    [ST]  ST7796S datasheet V1.0, 4-SPI timing table p.54
//    [MCP] Microchip MCP2515 datasheet DS20001801K, Table 13-6
// ---------------------------------------------------------------------------
#ifndef HUD_PINS_H
#define HUD_PINS_H

// ---------------------------------------------------------------------------
//  THE MAP
// ---------------------------------------------------------------------------
//
//  A D1 mini has exactly six pins that are not spoken for by the SPI bus or the
//  serial port, and this design needs exactly six. There is nothing spare, so
//  any advice to "move X to free up a pin" is moving a pin that was never free.
//
//        pin   gpio  owner            why this pin and not another
//        ----  ----  ---------------  ------------------------------------
//        D5     14   SPI SCK          fixed by the HSPI peripheral
//        D7     13   SPI MOSI         fixed by the HSPI peripheral
//        D6     12   SPI MISO         fixed by the HSPI peripheral
//        D8     15   CAN CS           MEASURED, not chosen. See below: this is
//                                     the pin the MCP2515 answers on, proved by
//                                     a loopback that ran the whole chip. Strap
//                                     must be LOW at reset and the board fits a
//                                     12k pull-down [SCH], which satisfies it;
//                                     we drive it high in setup() before SPI.
//        D2      4   display CS       clean pin, nothing fitted [SCH], which is
//                                     all a chip select ever wanted.
//        D1      5   display DC       clean pin, nothing fitted [SCH]
//        D0     16   backlight PWM    RTC-domain pin: no interrupts, no
//                                     internal pull-up [TR 2.2.5, DS 4.1].
//                                     A backlight needs neither. PWM DOES work
//                                     here: analogWrite accepts any pin <= 16
//                                     and the core's waveform generator carries
//                                     an explicit `pin == 16` path driving
//                                     GP16O (core_esp8266_waveform_phase.cpp).
//        D3      0   I2C SDA          strap must be HIGH at reset; board fits
//                                     12k pull-up [SCH] and I2C is open-drain
//                                     (idles high), so the bus satisfies the
//                                     strap by doing nothing.
//        D4      2   I2C SCL          same, plus the onboard LED [SCH] -- it
//                                     pulls toward 3V3, the way the strap
//                                     wants, so it is harmless here.
//
// WHY THREE PINS MOVED IN 2.5, because it cost weeks and nobody should have to
// find the reasoning again.
//
// The MCP2515 never answered. Every register read came back FF, on wiring that
// measured perfect, and the search went through the display's SDO, the module's
// 3V3 supply, the crystal and the SPI clock. What settled it was coryjfowler's
// LOOPBACK example, run with nothing else connected and the CAN chip select on
// D8/GPIO15: it configures the controller, sends a frame, the CAN engine loops
// it back internally, and it reads out again. That exercises SPI in both
// directions, the bit timing and the crystal all at once -- so the module, the
// wiring and the library were never the problem. The chip select pin was.
//
// CAN CS is therefore D8, because that is where it has been observed working.
// The display's CS moves to D2, and the backlight takes the RTC pin the CAN
// select vacated -- the one job on this board that genuinely does not care
// about interrupts or pull-ups. Three wires move; the compass does not.
//
// THE MCP2515'S INT PIN STAYS UNWIRED, and that is a decision rather than an
// omission. The only pin left would be GPIO16, and GPIO16 "cannot trigger the
// IO interrupt" [TR 2.2.5]. Wiring INT there would mean polling a pin instead
// of polling a register -- strictly worse, because the register read also says
// WHICH buffer is full. The bus is polled.
//
// HUD_SPI_*, not PIN_SPI_*: the ESP8266 core already defines PIN_SPI_SCK and
// friends in its own pins_arduino.h, and quietly redefining a core macro is
// how you end up with two different answers to the same question. They are
// cross-checked against the core's values below instead.
#define HUD_SPI_SCK        14    // D5
#define HUD_SPI_MOSI       13    // D7
#define HUD_SPI_MISO       12    // D6   -- goes to the MCP2515 module's header
                                 //         pin printed "SO", the chip's serial
                                 //         out (and D7, MOSI, to "SI"): the
                                 //         plain reading of the labels, and how
                                 //         the owner's board is wired. Nothing
                                 //         else may sit on this line -- see the
                                 //         warning below.

#define PIN_TFT_CS          4    // D2
#define PIN_TFT_DC          5    // D1
#define PIN_TFT_RST        (-1)  // panel RESET wired to the board's own RST pin
#define PIN_BACKLIGHT      16    // D0

#define PIN_CAN_CS         15    // D8  -- measured, see the note above
#define PIN_CAN_INT        (-1)  // not wired; the bus is polled. GPIO16 could
                                 // not serve as an interrupt anyway [TR 2.2.5].

#define PIN_I2C_SDA         0    // D3
#define PIN_I2C_SCL         2    // D4

// ---------------------------------------------------------------------------
//  THE DISPLAY'S SDO MUST NOT BE CONNECTED
// ---------------------------------------------------------------------------
//
//  Read this before wiring anything, because it is the single most expensive
//  mistake available on this board and it took months to find.
//
//  The panel and the MCP2515 share SCK, MOSI and MISO; only chip select
//  differs. That is fine in principle -- a deselected SPI device is supposed to
//  let go of MISO. ST7796S silicon does: the datasheet gives an output disable
//  time TOH of 15-50 ns and draws the SDO line as Hi-Z outside the data phase.
//
//  The MODULES do not. Bodmer, who wrote TFT_eSPI, keeps a standing warning for
//  ST7796 and ILI9488 boards:
//
//      "The SDO/MISO pin may not go tristate when the TFT chip select is high
//       (it depends on the direction of parasitic input currents)."
//
//  Many of these boards fit a series diode in the chip-select line, so the
//  controller is never cleanly deselected and keeps driving SDO. A display
//  sitting on MISO means the MCP2515 can drive its SO all day and the ESP will
//  never see it -- which reads at the ESP as a line stuck high: FF FF FF FF
//  from every register, on wiring that is perfect.
//
//  So: leave the panel's SDO pin unconnected, and TFT_MISO undefined. The HUD
//  never reads from the display -- it has no need to -- so nothing is lost.
#if defined(TFT_MISO) && (TFT_MISO >= 0)
  #warning "TFT_MISO is defined. The display must NOT share MISO with the MCP2515 -- set TFT_MISO to -1 in User_Setup.h and leave the panel's SDO pin unwired. See the comment above."
#endif

// ---------------------------------------------------------------------------
//  COMPILE-TIME CHECKS
// ---------------------------------------------------------------------------
namespace hudpins {

// GPIO6..GPIO11 are the flash interface. Using one does not misbehave, it
// crashes: the core's own docs say "trying to use these pins as IOs will
// likely cause the program to crash".
constexpr bool isFlashPin(int p) { return p >= 6 && p <= 11; }

// GPIO16 lives in the RTC module, not the GPIO module [TR 2.2.5]. It can be an
// input or an output and it can do PWM, but it "cannot trigger the IO
// interrupt", and per [DS 4.1] only XPD_DCDC has a pull-DOWN rather than a
// pull-up. Both matter for what you are allowed to ask of it.
constexpr bool canInterrupt(int p)   { return p >= 0 && p <= 15; }
constexpr bool hasPullUp(int p)      { return p >= 0 && p <= 15; }
constexpr bool isUart(int p)         { return p == 1 || p == 3; }
constexpr bool isValid(int p)        { return p >= 0 && p <= 16 && !isFlashPin(p); }

// How many of the declared pins equal p. Anything above one is two owners.
constexpr int uses(int p) {
  return (HUD_SPI_SCK   == p) + (HUD_SPI_MOSI == p) + (HUD_SPI_MISO == p) +
         (PIN_TFT_CS    == p) + (PIN_TFT_DC   == p) +
         (PIN_BACKLIGHT == p) + (PIN_CAN_CS   == p) +
         (PIN_I2C_SDA   == p) + (PIN_I2C_SCL  == p);
}

}  // namespace hudpins

// -- one owner per pin ------------------------------------------------------
//
// This is the check that would have caught the pinout that put the display's
// DC and the I2C clock both on D1. DC toggles on every drawing command and SCL
// toggles on every compass read; each would corrupt the other, and nothing
// anywhere would have said so.
static_assert(hudpins::uses(HUD_SPI_SCK)   == 1, "pin conflict: SPI SCK is claimed by something else too");
static_assert(hudpins::uses(HUD_SPI_MOSI)  == 1, "pin conflict: SPI MOSI is claimed by something else too");
static_assert(hudpins::uses(HUD_SPI_MISO)  == 1, "pin conflict: SPI MISO is claimed by something else too");
static_assert(hudpins::uses(PIN_TFT_CS)    == 1, "pin conflict: the display's CS is claimed by something else too");
static_assert(hudpins::uses(PIN_TFT_DC)    == 1, "pin conflict: the display's DC is claimed by something else too");
static_assert(hudpins::uses(PIN_BACKLIGHT) == 1, "pin conflict: the backlight is claimed by something else too");
static_assert(hudpins::uses(PIN_CAN_CS)    == 1, "pin conflict: the CAN chip select is claimed by something else too");
static_assert(hudpins::uses(PIN_I2C_SDA)   == 1, "pin conflict: I2C SDA is claimed by something else too");
static_assert(hudpins::uses(PIN_I2C_SCL)   == 1, "pin conflict: I2C SCL is claimed by something else too");

// -- nothing on the flash bus or the serial port ----------------------------
static_assert(hudpins::isValid(PIN_TFT_CS)    && !hudpins::isUart(PIN_TFT_CS),    "the display's CS is on a flash or UART pin");
static_assert(hudpins::isValid(PIN_TFT_DC)    && !hudpins::isUart(PIN_TFT_DC),    "the display's DC is on a flash or UART pin");
static_assert(hudpins::isValid(PIN_BACKLIGHT) && !hudpins::isUart(PIN_BACKLIGHT), "the backlight is on a flash or UART pin");
static_assert(hudpins::isValid(PIN_CAN_CS)    && !hudpins::isUart(PIN_CAN_CS),    "the CAN chip select is on a flash or UART pin");
static_assert(hudpins::isValid(PIN_I2C_SDA)   && !hudpins::isUart(PIN_I2C_SDA),   "I2C SDA is on a flash or UART pin");
static_assert(hudpins::isValid(PIN_I2C_SCL)   && !hudpins::isUart(PIN_I2C_SCL),   "I2C SCL is on a flash or UART pin");

// -- the I2C pins must be able to hold themselves high ----------------------
//
// The Arduino core's Wire is a software bit-bang that emulates open drain
// properly -- it never drives either line high, it releases the driver and
// lets the pull-up do it. That is what makes GPIO0 and GPIO2 usable here at
// all: a released line is a high line, and high is what the boot strap wants.
// A pin with no pull-up available could not do that.
static_assert(hudpins::hasPullUp(PIN_I2C_SDA), "I2C SDA needs a pin that can pull up; GPIO16 cannot [DS 4.1]");
static_assert(hudpins::hasPullUp(PIN_I2C_SCL), "I2C SCL needs a pin that can pull up; GPIO16 cannot [DS 4.1]");

// -- the CAN interrupt pin, if it is ever wired -----------------------------
#if PIN_CAN_INT >= 0
static_assert(hudpins::canInterrupt(PIN_CAN_INT), "GPIO16 cannot raise an interrupt [TR 2.2.5] -- pick another pin for CAN INT");
static_assert(hudpins::uses(PIN_CAN_INT) == 0, "pin conflict: the CAN interrupt pin is claimed by something else");
#endif

// The cross-check against TFT_eSPI's own User_Setup.h USED TO LIVE HERE, and it
// did nothing. The sketch includes this file two lines before <TFT_eSPI.h>, so
// TFT_CS and friends were still undefined when the #ifdefs were evaluated and
// every one of them was skipped -- in the firmware build. It only ever fired in
// the host tests, where the include order is different. It now lives in
// hud_bus.h, which the sketch includes AFTER TFT_eSPI.h, so it is real.

// ...and against the core's own idea of where the SPI peripheral lives, which
// is the authority on a pin the hardware fixes rather than we do.
#ifdef PIN_SPI_SCK
static_assert(PIN_SPI_SCK == HUD_SPI_SCK, "the core says SCK is on a different pin");
static_assert(PIN_SPI_MOSI == HUD_SPI_MOSI, "the core says MOSI is on a different pin");
static_assert(PIN_SPI_MISO == HUD_SPI_MISO, "the core says MISO is on a different pin");
#endif

#endif  // HUD_PINS_H
