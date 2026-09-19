// Runs a bring-up sketch on the host and checks the two things that make it
// worth trusting: nothing drawn off the panel, and no string handed to a font
// that cannot render it.
#include "bringup_stub.h"

const char* g_zone = "bringup";
std::vector<Box> g_boxes;
int g_outOfBounds = 0;
int g_badGlyphs = 0;
bool TFT_eSPI::g_reportOob = true;

#if 1
  #include "../PanelDiag/PanelDiag.ino"
  static const char* kName = "PanelDiag";
#else
  #include "../PanelTest/PanelTest.ino"
  static const char* kName = "PanelTest";
#endif

static int failures = 0;
#define CHECK(c, m) do { if (!(c)) { printf("  FAIL: %s\n", m); failures++; } } while (0)

int main() {
  printf("sketch: %s\n\n", kName);
  g_millis = 0;
  setup();

  // Twenty cycles: every screen several times, including the rotation flip
  // that only alternates on repeat visits.
  for (int i = 0; i < 100; i++) { g_millis += 3100; loop(); }

  printf("1. nothing is drawn off the panel\n");
  printf("    %d out-of-bounds primitives\n", g_outOfBounds);
  CHECK(g_outOfBounds == 0, "stays inside 480x320");

  printf("2. every string is drawn in a font that has its characters\n");
  printf("    %d impossible glyphs\n", g_badGlyphs);
  CHECK(g_badGlyphs == 0, "no letters asked of a digits-only font");

  printf(failures ? "\n%d CHECK(s) FAILED\n" : "\nall checks passed\n", failures);
  return failures ? 1 : 0;
}
