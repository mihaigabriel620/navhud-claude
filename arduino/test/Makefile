CXX      ?= g++
# CAN_USE_LIBRARY=0 everywhere on the host: the tests exercise the hand-written
# driver against a register-level MCP2515 simulator, which is the only place its
# protocol is ever checked. The library is the shipping default on hardware and
# is somebody else's to test.
CXXFLAGS ?= -std=c++17 -Wall -Wextra -Wno-unused-function -O2 -DHUD_TEST -DCAN_USE_LIBRARY=0
INC      := -I. -I../NavHud

BINS := t geom parse_stdin can can500 can16 can16_500 mount layout_e60 \
        sketch_dash sketch_imu sketch_e60 sketch_bench bringup_diag

all: $(BINS)

t: test_protocol.cpp ../NavHud/hud_protocol.h ../NavHud/hud_geom.h
	$(CXX) $(CXXFLAGS) $(INC) -o $@ test_protocol.cpp

geom: test_geom.cpp ../NavHud/hud_geom.h ../NavHud/hud_store.h
	$(CXX) $(CXXFLAGS) $(INC) -o $@ test_geom.cpp

parse_stdin: parse_stdin.cpp ../NavHud/hud_protocol.h
	$(CXX) $(CXXFLAGS) -o $@ parse_stdin.cpp

# Every header the sketch pulls in. A wildcard rather than a list, deliberately:
# the list version silently went stale the moment the sketch was split into
# modules, and a suite that does not rebuild when you edit the code is worse
# than no suite at all -- it reports the last build's result as though it were
# this one's. Caught by a negative test that passed when it had to fail.
SKETCH_HEADERS := $(wildcard ../NavHud/*.h)
THEME_DEPS := $(SKETCH_HEADERS) tft_stub.h TFT_eSPI.h EEPROM.h SPI.h Wire.h

# The CAN half, against a register-level MCP2515 simulator built from the
# datasheet. The driver talks to a chip nobody can watch, so this is the only
# place its register protocol is ever checked.
can: test_can.cpp $(SKETCH_HEADERS) SPI.h tft_stub.h
	$(CXX) $(CXXFLAGS) $(INC) -DHUD_HOST_TEST -o $@ test_can.cpp

# The other three crystal/bit-rate combinations. An assertion behind an #if for
# a configuration nobody compiles is not a test: the timing check used to cover
# only 8 MHz / 500 kbit/s, and when the default moved to K-CAN's 100 kbit/s it
# stopped running entirely without anything going red.
can500: test_can.cpp $(SKETCH_HEADERS) SPI.h tft_stub.h
	$(CXX) $(CXXFLAGS) $(INC) -DHUD_HOST_TEST -DCAN_BITRATE_KBPS=500 -o $@ test_can.cpp

can16: test_can.cpp $(SKETCH_HEADERS) SPI.h tft_stub.h
	$(CXX) $(CXXFLAGS) $(INC) -DHUD_HOST_TEST -DCAN_XTAL_MHZ=16 -o $@ test_can.cpp

can16_500: test_can.cpp $(SKETCH_HEADERS) SPI.h tft_stub.h
	$(CXX) $(CXXFLAGS) $(INC) -DHUD_HOST_TEST -DCAN_XTAL_MHZ=16 -DCAN_BITRATE_KBPS=500 -o $@ test_can.cpp

sketch_dash: test_sketch.cpp ../NavHud/NavHud.ino $(THEME_DEPS)
	$(CXX) $(CXXFLAGS) $(INC) -o $@ test_sketch.cpp

# The gyroscope is an option, so it is off in every other build -- which is
# exactly how a header rots. This one compiles it.
sketch_imu: test_sketch.cpp ../NavHud/NavHud.ino ../NavHud/hud_imu.h Wire.h $(THEME_DEPS)
	$(CXX) $(CXXFLAGS) $(INC) -DHUD_IMU -DHUD_MAG -o $@ test_sketch.cpp

# The mounting calibration. Pure maths against simulated rotations, because a
# sign error here does not crash and does not warn -- it produces a compass that
# reads correctly at north and south and mirrored at east and west, which looks
# like bad luck rather than a bug.
mount: test_mount.cpp ../NavHud/hud_mount.h
	$(CXX) $(CXXFLAGS) $(INC) -o $@ test_mount.cpp

layout_e60: test_layout.cpp corpus.h $(THEME_DEPS)
	$(CXX) $(CXXFLAGS) $(INC) -DHUD_THEME_E60_CLASSIC -o $@ test_layout.cpp


# BACKLIGHT_PIN is ESP8266-only in the sketch, so on a PC the whole backlight
# block compiles out and the key rule -- the one thing in there that can flatten
# a parked car's usefulness by glowing all night -- is never checked. Forcing a
# pin in gives the host something to watch. analogWrite() is a no-op stub, so
# the only observable effect is backlightNow.
sketch_e60: test_sketch.cpp ../NavHud/NavHud.ino $(THEME_DEPS)
	$(CXX) $(CXXFLAGS) $(INC) -DHUD_THEME_E60_CLASSIC -DBACKLIGHT_PIN=4 -o $@ test_sketch.cpp


# HUD_BENCH is a shipping configuration too -- somebody will leave it on. A
# build whose own test suite fails is a trap, so it gets checked as well.
sketch_bench: test_sketch.cpp ../NavHud/NavHud.ino $(THEME_DEPS)
	$(CXX) $(CXXFLAGS) $(INC) -DHUD_BENCH -DHUD_THEME_E60_CLASSIC -DBACKLIGHT_PIN=4 -o $@ test_sketch.cpp

# The bring-up sketch. It is a throwaway diagnostic, which is exactly why it
# shipped asking font 6 to draw an "x" -- a sketch whose whole job is to tell
# you whether your hardware works has to be the most trustworthy code here.
BRINGUP_DEPS := ../PanelDiag/PanelDiag.ino bringup_stub.h tft_stub.h \
                ../NavHud/hud_protocol.h

bringup_diag: test_bringup.cpp $(BRINGUP_DEPS)
	$(CXX) $(CXXFLAGS) $(INC) -DBRINGUP_DIAG -o $@ test_bringup.cpp

# The whole suite: protocol, screen and map geometry, navigation logic, both
# themes' layout,
# the real sketch running against a scripted serial stream, and two end-to-end
# passes of generated frames through the exact parser the sketch compiles.
check: all
	@echo "=== 0/12 firmware layout matches the bench ==="
	python3 ../../tools/check_layout.py
	@echo
	@echo "=== 1/12 protocol unit tests ==="
	./t
	@echo "\n=== 2/12 screen geometry (mirror, keystone, persistence) ==="
	./geom
	@echo "\n=== 3/12 map geometry (polyline, projection, heading) ==="
	python3 ../../tools/geo_reference.py
	@echo "\n=== 4/12 navigation logic (RouteTracker) ==="
	python3 ../../tools/tracker_reference.py --check
	@echo "\n=== 5/12 voice guidance (VoiceGuide) ==="
	python3 ../../tools/voice_reference.py --check
	@echo "\n=== 6/12 E60 theme layout ==="
	./layout_e60
	@echo "\n--- and the mounting calibration ---"
	./mount
	@echo "\n=== 7/12 sketch on the host, E60 theme ==="
	./sketch_e60
	@echo "\n=== 8/12 sketch on the host, bench mode ==="
	./sketch_bench
	@echo "\n=== 9/12 CAN: MCP2515 register protocol + E60 decoders ==="
	./can
	@echo "--- and the bit timing for the other three crystal/rate combinations ---"
	./can500 | head -4
	./can16 | head -4
	./can16_500 | head -4
	@echo "\n=== 10/12 sketch on the host, merged dash theme ==="
	./sketch_dash
	@echo "\n--- and again with the gyroscope compiled in ---"
	./sketch_imu
	@echo "\n=== 11/12 bring-up sketch: PanelDiag ==="
	./bringup_diag
	@echo "\n=== 12/12 generated frames -> parser ==="
	python3 ../../tools/hud_sim.py --stdout --frames 3000 | ./parse_stdin
	python3 ../../tools/tracker_reference.py --emit | ./parse_stdin

# ---- the screen renderer ------------------------------------------------------
# Every screen of both themes as a PNG in out/, drawn by the unmodified themes
# through tft_stub.h built with -DHUD_RENDER (see tft_raster.h). The built-in
# fonts are TFT_eSPI's own tables, so the library is fetched once, pinned.
TFT_ESPI_DIR ?= .tft_espi
TFT_ESPI_TAG ?= V2.5.43
TFT_ESPI_FONTS := $(TFT_ESPI_DIR)/Fonts/Font16.h
RENDER_DEPS := render_screens.cpp corpus.h tft_raster.h png_write.h $(THEME_DEPS) $(TFT_ESPI_FONTS)
RENDER_FLAGS := -DHUD_RENDER -I$(TFT_ESPI_DIR)

$(TFT_ESPI_FONTS):
	rm -rf $(TFT_ESPI_DIR)
	git clone --quiet --depth 1 --branch $(TFT_ESPI_TAG) https://github.com/Bodmer/TFT_eSPI.git $(TFT_ESPI_DIR)

render_dash: $(RENDER_DEPS)
	$(CXX) $(CXXFLAGS) $(INC) $(RENDER_FLAGS) -o $@ render_screens.cpp

render_e60: $(RENDER_DEPS)
	$(CXX) $(CXXFLAGS) $(INC) $(RENDER_FLAGS) -DHUD_THEME_E60_CLASSIC -o $@ render_screens.cpp

render: render_dash render_e60
	rm -rf out
	mkdir -p out
	./render_dash out
	./render_e60 out

clean:
	rm -f $(BINS) render_dash render_e60 *.exe
	rm -rf out

.PHONY: all check clean render
