// ---------------------------------------------------------------------------
//  hud_bus.h -- two devices, one SPI bus, and the rules that keep them apart.
//
//  The display and the MCP2515 share SCK, MOSI and MISO. That works, but only
//  if somebody owns the discipline, and previously nobody did: the CAN driver
//  assumed the bus was in a sane state and TFT_eSPI assumed nothing else ever
//  touched it. Both assumptions are wrong at the same time, which is the worst
//  case, because each library keeps working while quietly breaking the other.
//
//  Everything here is from the libraries' own source, not from folklore.
//
//  1. SUPPORT_TRANSACTIONS IS NOT AUTOMATIC ON ESP8266.
//     TFT_eSPI defines it for itself on ESP32 ("mandatory... so the hal mutex
//     is toggled") and on the Generic processor. On ESP8266 it is off unless
//     you uncomment it, and with it off TFT_eSPI never calls
//     SPI.beginTransaction() at all -- it sets the bus once in init() and
//     assumes it stays that way for ever. Bodmer's own note in User_Setup.h:
//     "Transaction support is required if other SPI devices are connected."
//     checkBus() below refuses to compile without it.
//
//  2. MCP_CAN::begin() CALLS SPI.begin(), AND SPI.begin() IS DESTRUCTIVE.
//     From the ESP8266 core's SPI.cpp it rewrites SPI1C, SPI1U, SPI1U1 and the
//     clock: bit order back to MSB, CPHA back to 0, data bits back to 8, and
//     the frequency back to 1 MHz. With transactions on, TFT_eSPI repairs all
//     of that on its next write. With them off, the display silently runs at
//     1 MHz for the rest of the session -- a documented TFT_eSPI issue where
//     the panel only speeds up again when somebody touches the screen.
//
//  3. THE DUPLEX BIT IS THE SUBTLE ONE.
//     In the installed TFT_eSPI it is begin_tft_write() that sets SPI1U to
//     SPI1U_WRITE, clearing SPIUDUPLEX -- the bit that makes the peripheral
//     capture MISO during a transfer -- and end_tft_write() that restores it.
//     The core's beginTransaction() sets frequency, bit order and mode and does
//     not touch SPI1U at all. So a plain SPI.transfer() issued while that bit
//     is clear returns the byte it sent instead of the byte the slave drove,
//     silently, and a perfectly wired MCP2515 reads back as dead.
//     It balances out here because every TFT write pairs the two calls and this
//     firmware never calls startWrite(). The note stays because the next person
//     to leave a transaction open, or to add a readPixel(), needs to know.
//
//  4. CS DISCIPLINE. Every chip select is driven HIGH before any device is
//     initialised, because a device whose select floats at power-on can see its
//     neighbour's configuration clocked past it as a command. TFT_eSPI's own
//     shared-bus example does the same. It matters more on this board than most:
//     GPIO15 is a boot strap with a 12k pull-DOWN fitted, so the CAN chip select
//     is asserted from power-on until setup() drives it high.
// ---------------------------------------------------------------------------
#ifndef HUD_BUS_H
#define HUD_BUS_H

#include <Arduino.h>
#include <SPI.h>
#include "hud_pins.h"

// -- the firmware's map and TFT_eSPI's map have to agree --------------------
//
// This file is included AFTER <TFT_eSPI.h>, which is the whole point: the same
// checks in hud_pins.h were evaluated before TFT_eSPI had defined anything and
// silently did nothing.
//
// TFT_eSPI is configured in ITS OWN header (User_Setup.h), which this file
// cannot reach into. Two files holding two copies of the same truth is how
// they drift, so where TFT_eSPI has already spoken, check we agree.
#ifdef TFT_CS
static_assert(TFT_CS == PIN_TFT_CS, "User_Setup.h and hud_pins.h disagree about the display's CS pin");
#endif
#ifdef TFT_DC
static_assert(TFT_DC == PIN_TFT_DC, "User_Setup.h and hud_pins.h disagree about the display's DC pin");
#endif
#ifdef TFT_SCLK
static_assert(TFT_SCLK == HUD_SPI_SCK, "User_Setup.h and hud_pins.h disagree about SCK");
#endif
#ifdef TFT_MOSI
static_assert(TFT_MOSI == HUD_SPI_MOSI, "User_Setup.h and hud_pins.h disagree about MOSI");
#endif


#if defined(ARDUINO_ARCH_ESP8266) && !defined(SUPPORT_TRANSACTIONS) && !defined(HUD_HOST_TEST)
  #error "TFT_eSPI needs SUPPORT_TRANSACTIONS defined in User_Setup.h. Without it the display never re-applies its SPI settings and the CAN controller's SPI.begin() leaves the panel running at 1 MHz for ever."
#endif

/**
 * The clock for the raw register pokes this firmware makes itself.
 *
 * Note what this does NOT govern: mcp_can.cpp hardcodes SPISettings(10000000)
 * at every one of its own transfer sites, so bring-up and every received frame
 * run at 10 MHz whatever is set here. This applies only to the handful of bytes
 * canPump() and canOverflows() read directly. Both are legal -- DS20001801K
 * Table 13-6 gives FCLK max 10 MHz across the whole 2.7-5.5 V range -- and
 * 8 MHz is an exact divider of the ESP8266's 80 MHz peripheral clock.
 */
#ifndef CAN_SPI_HZ
  #define CAN_SPI_HZ  8000000
#endif

/** The MCP2515 speaks SPI mode 0,0 and 1,1 (datasheet front page). Mode 0. */
#define CAN_SPI_MODE  SPI_MODE0

/**
 * Park every chip select high, before anything is initialised.
 *
 * Called first in setup(), before tft.init() and before the CAN controller is
 * touched. A device whose CS floats at power-on can see its own configuration
 * being clocked to its neighbour as a command.
 */
static inline void busParkChipSelects() {
  pinMode(PIN_TFT_CS, OUTPUT);
  digitalWrite(PIN_TFT_CS, HIGH);
  pinMode(PIN_CAN_CS, OUTPUT);
  digitalWrite(PIN_CAN_CS, HIGH);
}

/**
 * Hand the bus to the CAN controller for one transaction.
 *
 * Always paired with busCanEnd(). The frequency and mode are re-stated on
 * every transaction rather than set once, because the display re-states its
 * own on every write and whoever spoke last owns the registers.
 */
static inline void busCanBegin() {
  SPI.beginTransaction(SPISettings(CAN_SPI_HZ, MSBFIRST, CAN_SPI_MODE));
  digitalWrite(PIN_CAN_CS, LOW);
}

static inline void busCanEnd() {
  digitalWrite(PIN_CAN_CS, HIGH);
  SPI.endTransaction();
}

#endif  // HUD_BUS_H
