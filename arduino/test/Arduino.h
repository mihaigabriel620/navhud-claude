// Shim so the firmware headers that include <Arduino.h> compile on a PC. The
// Arduino core stand-ins (millis, digitalWrite, Serial, ...) live in
// tft_stub.h with the rest of the host environment.
#pragma once
#include "tft_stub.h"
