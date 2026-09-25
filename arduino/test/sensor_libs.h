// The sensor library the firmware uses, compiled into the test's own
// translation unit rather than on its own: tft_stub.h's millis() and the fake
// Wire bus are per-unit state, so a library built separately would see a
// clock that never moves and a bus with nothing on it.
//
// Fetched at a pinned version by the Makefile (SENSOR_STAMP), the same version
// firmware.yml installs for the real compile.
#include "GY521.cpp"
