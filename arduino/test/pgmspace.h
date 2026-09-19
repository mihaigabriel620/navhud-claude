// Test-side stand-in for the ESP8266 PROGMEM macros. On a PC the font arrays
// are ordinary const data, so these are all no-ops.
#pragma once
#include <cstdint>
#ifndef PROGMEM
#define PROGMEM
#endif
#ifndef pgm_read_byte
#define pgm_read_byte(a) (*(const uint8_t*)(a))
#endif
