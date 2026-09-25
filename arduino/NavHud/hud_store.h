// The one setting that has to survive a power cycle.
//
// The keystone is a property of where the panel is glued, not of the drive, so
// re-doing it every morning is not an option. It goes in flash.
//
// Written as an explicit byte string rather than EEPROM.put() on a struct.
// A struct has padding, the padding is uninitialised, and a checksum over
// sizeof(struct) therefore changes for reasons that have nothing to do with
// the settings -- the block would read back "corrupt" on some builds and not
// others. Twenty-two named bytes cannot do that.
//
//   [0][1]  'N' 'H'
//   [2]     format version
//   [3]     mirrorX      [4] mirrorY
//   [5..20] dx0,dy0,dx1,dy1,dx2,dy2,dx3,dy3 as little-endian int16
//   [21]    XOR of bytes 0..20
//
// ESP8266 EEPROM is emulated in one 4 KB flash sector, and commit() erases and
// rewrites the whole sector every time. So this is called when the driver taps
// Save, and never from the frame loop -- see the note on $GEOM in
// hud_protocol.h.

#ifndef HUD_STORE_H
#define HUD_STORE_H

#include "hud_geom.h"

#define HUD_STORE_MAGIC0  'N'
#define HUD_STORE_MAGIC1  'H'
#define HUD_STORE_VERSION 1
#define HUD_STORE_BYTES   22
/** Where the compass calibration lives: straight after the geometry blob. */
// The compass block sits straight after the geometry, in the same 4 KB sector,
// so saving both is one erase rather than two.
//
// Its layout is HudHeading's (hud_heading.h), with its own magic byte. Firmware
// 3.0 changed it -- the flat compass's 31-byte block held an offset that is
// wrong for a tilt-compensated one -- so an old block fails its magic and the
// board starts "not calibrated", which is the right outcome: recalibrating
// takes one slow circle, and believing a misread offset would point the arrow
// confidently in the wrong direction for ever.
#define HUD_MAG_STORE_OFF   HUD_STORE_BYTES
#define HUD_MAG_STORE_BYTES 19      // HudHeading::STORE_BYTES

#define HUD_STORE_EEPROM  64      // rounded up; EEPROM.begin() wants a size

/** Serialise into exactly HUD_STORE_BYTES bytes. */
inline void hudStorePack(const Geom& g, uint8_t* b) {
  b[0] = HUD_STORE_MAGIC0;
  b[1] = HUD_STORE_MAGIC1;
  b[2] = HUD_STORE_VERSION;
  b[3] = g.mirrorX ? 1 : 0;
  b[4] = g.mirrorY ? 1 : 0;
  for (int i = 0; i < 4; i++) {
    const uint16_t x = (uint16_t)g.corners.dx[i];
    const uint16_t y = (uint16_t)g.corners.dy[i];
    b[5 + i * 4] = (uint8_t)(x & 0xFF);
    b[6 + i * 4] = (uint8_t)(x >> 8);
    b[7 + i * 4] = (uint8_t)(y & 0xFF);
    b[8 + i * 4] = (uint8_t)(y >> 8);
  }
  uint8_t sum = 0;
  for (int i = 0; i < HUD_STORE_BYTES - 1; i++) sum ^= b[i];
  b[HUD_STORE_BYTES - 1] = sum;
}

/**
 * Read back. False when the block is blank, from another version, or damaged
 * -- in which case the caller keeps its compiled-in defaults rather than
 * applying half a setting.
 */
inline bool hudStoreUnpack(const uint8_t* b, Geom& g) {
  if (b[0] != HUD_STORE_MAGIC0 || b[1] != HUD_STORE_MAGIC1) return false;
  if (b[2] != HUD_STORE_VERSION) return false;
  uint8_t sum = 0;
  for (int i = 0; i < HUD_STORE_BYTES - 1; i++) sum ^= b[i];
  if (sum != b[HUD_STORE_BYTES - 1]) return false;

  g.mirrorX = b[3] != 0;
  g.mirrorY = b[4] != 0;
  for (int i = 0; i < 4; i++) {
    g.corners.dx[i] = (int16_t)((uint16_t)b[5 + i * 4] | ((uint16_t)b[6 + i * 4] << 8));
    g.corners.dy[i] = (int16_t)((uint16_t)b[7 + i * 4] | ((uint16_t)b[8 + i * 4] << 8));
  }
  return true;
}

#endif  // HUD_STORE_H
