// Enough extra Arduino to compile PanelTest and PanelDiag on the host.
//
// They are throwaway bring-up sketches, which is exactly why they were not
// under test -- and exactly why one of them shipped with a bug the real
// firmware could not have had. A sketch whose whole job is to tell you whether
// your hardware works has to be the most trustworthy code in the project.
#pragma once

#include "tft_stub.h"

#ifndef SPI_FREQUENCY
#define SPI_FREQUENCY 20000000
#endif

#define TFT_RED   0xF800
#define TFT_GREEN 0x07E0
#define TFT_BLUE  0x001F
#define HEX 16

#ifndef F                               // tft_stub.h may already have it
#define F(x) (x)
#endif

#ifndef HUD_STUB_HAS_DIGITALWRITE      // tft_stub.h may already have it
inline void digitalWrite(int, int) {}
#endif
inline void analogWriteRange(uint32_t) {}
#define HIGH 1
#define LOW  0

// Serial gains the overloads the sketches use.
struct BringupSerial {
  void begin(long) {}
  void print(const char* s) { out += s; }
  void print(char c) { out += c; }
  void print(int v) { char b[16]; snprintf(b, sizeof b, "%d", v); out += b; }
  void print(long v) { char b[24]; snprintf(b, sizeof b, "%ld", v); out += b; }
  void print(unsigned v) { char b[16]; snprintf(b, sizeof b, "%u", v); out += b; }
  void print(int v, int) { char b[16]; snprintf(b, sizeof b, "%X", v); out += b; }
  void print(unsigned v, int) { char b[16]; snprintf(b, sizeof b, "%X", v); out += b; }
  void println() { out += "\n"; }
  void println(const char* s) { out += s; out += "\n"; }
  void println(int v) { print(v); out += "\n"; }
  void println(long v) { print(v); out += "\n"; }
  void println(unsigned v) { print(v); out += "\n"; }
  void println(int v, int) { print(v, HEX); out += "\n"; }
  void println(unsigned v, int) { print(v, HEX); out += "\n"; }
  std::string out;
};
#define Serial BringupSerial_inst
static BringupSerial BringupSerial_inst;
