// TFT_eSPI configuration for NavHUD.
//
// Copy this file over  Arduino/libraries/TFT_eSPI/User_Setup.h
// (and make sure User_Setup_Select.h has `#include <User_Setup.h>` uncommented,
// which is the default).
//
// Wiring below is for an ESP32 dev board on the VSPI bus. Change the pin
// numbers if you wired it differently -- nothing else in the project cares.

#define USER_SETUP_INFO "NavHUD ESP32 ILI9341"

// ---- driver ----------------------------------------------------------------
#define ILI9341_DRIVER
// If you bought an ST7789 240x320 instead, comment the line above and use:
//#define ST7789_DRIVER
//#define TFT_WIDTH  240
//#define TFT_HEIGHT 320

// Most cheap red ILI9341 boards need this; if your colours come out inverted
// or blue/red swapped, toggle it.
//#define TFT_RGB_ORDER TFT_BGR

// ---- pins (ESP32) ----------------------------------------------------------
#define TFT_MISO 19
#define TFT_MOSI 23
#define TFT_SCLK 18
#define TFT_CS   15
#define TFT_DC    2
#define TFT_RST   4
// Backlight: wire it to a PWM pin and set BACKLIGHT_PIN in NavHud.ino to match.
// Leave TFT_BL undefined here so the sketch owns the dimming.
//#define TFT_BL   32

// ---- pins (Arduino Uno / Nano fallback) ------------------------------------
// The Uno drives this display, just slowly -- expect a visible sweep on a full
// repaint. Comment out the ESP32 block above and use:
//#define TFT_CS   10
//#define TFT_DC    9
//#define TFT_RST   8
// (MOSI 11, MISO 12, SCK 13 are fixed by the hardware SPI peripheral.)

// ---- fonts -----------------------------------------------------------------
// The sketch uses fonts 2, 4, 6 and 7. Font 7 is the 48px seven-segment face
// used for the two big numbers -- do not drop it.
#define LOAD_GLCD    // font 1,  8px  ASCII
#define LOAD_FONT2   // font 2, 16px  small labels
#define LOAD_FONT4   // font 4, 26px  street names, ETA
#define LOAD_FONT6   // font 6, 48px  distance to turn, speed limit
#define LOAD_FONT7   // font 7, 48px  seven-segment digits: the speed readout
#define LOAD_FONT8   // font 8, 75px  spare, in case you want a bigger speed
#define SMOOTH_FONT

// ---- SPI -------------------------------------------------------------------
// 40 MHz is fine on short, tidy wiring. Drop to 27000000 if you see noise,
// stray pixels or a display that resets when the engine cranks.
#define SPI_FREQUENCY       40000000
#define SPI_READ_FREQUENCY  20000000
