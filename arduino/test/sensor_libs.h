// The sensor libraries the firmware uses, compiled into the test's own
// translation unit rather than on their own: tft_stub.h's millis() and the
// fake Wire bus are per-unit state, so a library built separately would see a
// clock that never moves and a bus with nothing on it.
//
// Fetched at pinned versions by the Makefile (SENSOR_LIBS), the same versions
// firmware.yml installs for the real compile.
//
// Every source file of the three, which is what the Arduino build compiles
// too. Adafruit_SPIDevice.cpp and Adafruit_GenericDevice.cpp are here because
// Adafruit_BusIO_Register.cpp links against them; nothing on this board talks
// SPI through BusIO.
#include "GY521.cpp"
#include "Adafruit_I2CDevice.cpp"
#include "Adafruit_SPIDevice.cpp"
#include "Adafruit_GenericDevice.cpp"
#include "Adafruit_BusIO_Register.cpp"
#include "Adafruit_QMC5883P.cpp"
