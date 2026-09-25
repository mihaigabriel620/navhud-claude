CXX      ?= g++
CXXFLAGS ?= -std=c++17 -Wall -Wextra -Wno-unused-function -O2 -DHUD_TEST
INC      := -I. -I../NavHud

BINS := t geom parse_stdin can layout_e60 \
        sketch_dash sketch_e60 sketch_bench bringup_diag

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
THEME_DEPS := $(SKETCH_HEADERS) tft_stub.h TFT_eSPI.h EEPROM.h SPI.h Wire.h mcp_can.h Arduino.h

# The CAN half: canBegin()/canPump() through a stand-in for mcp_can 1.5.1
# (mcp_can.h) over a register-level MCP2515 simulator built from the datasheet
# (SPI.h), and the E60 decoders. The bit timing is the library's own business
# since 2.4 -- CAN_BITRATE and CAN_XTAL are handed to it -- so the test pins
# what is passed, not the CNF bytes the library derives from it.
can: test_can.cpp $(SKETCH_HEADERS) SPI.h mcp_can.h Arduino.h tft_stub.h
	$(CXX) $(CXXFLAGS) $(INC) -DHUD_HOST_TEST -o $@ test_can.cpp

sketch_dash: test_sketch.cpp ../NavHud/NavHud.ino $(THEME_DEPS)
	$(CXX) $(CXXFLAGS) $(INC) -o $@ test_sketch.cpp

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
# themes' layout, the CAN half, the real sketch running against a scripted
# serial stream, and two end-to-end passes of generated frames through the
# exact parser the sketch compiles.
#
# tools/check_layout.py (the dash layout against the hud-editor.html bench) is
# not run: the bench page did not survive the 1.26 restore, so there is nothing
# to compare against. theme_dash.h is the layout's only source now, and the
# layout tests below still catch a field off the panel or on top of another.
check: all
	@echo "=== 1/11 protocol unit tests ==="
	./t
	@echo "\n=== 2/11 screen geometry (mirror, keystone, persistence) ==="
	./geom
	@echo "\n=== 3/11 map geometry (polyline, projection, heading) ==="
	python3 ../../tools/geo_reference.py
	@echo "\n=== 4/11 navigation logic (RouteTracker) ==="
	python3 ../../tools/tracker_reference.py --check
	@echo "\n=== 5/11 voice guidance (VoiceGuide) ==="
	python3 ../../tools/voice_reference.py --check
	@echo "\n=== 6/11 E60 theme layout ==="
	./layout_e60
	@echo "\n=== 7/11 sketch on the host, E60 theme ==="
	./sketch_e60
	@echo "\n=== 8/11 sketch on the host, bench mode ==="
	./sketch_bench
	@echo "\n=== 9/11 CAN: bring-up, drain guards, E60 decoders ==="
	./can
	@echo "\n=== 10/11 sketch on the host, dash theme ==="
	./sketch_dash
	@echo "\n=== 11/11 bring-up sketch: PanelDiag ==="
	./bringup_diag
	@echo "\n=== and generated frames -> parser ==="
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

# The pictures in docs/img/ (README), cut from out/. Needs python3 with Pillow.
docs-images: render
	python3 docs_images.py

clean:
	rm -f $(BINS) render_dash render_e60 *.exe
	rm -rf out

.PHONY: all check clean render docs-images
