// ---------------------------------------------------------------------------
//  PanelDiag -- why is the screen white?
//
//  A white screen with a working backlight means the panel is powered but its
//  controller was never initialised. Only two things cause that: the library
//  was built for the wrong panel, or a control wire is not making contact.
//
//  This sketch does not try to draw anything you have to interpret. It prints
//  what the library ACTUALLY compiled with, tries to make the controller
//  answer, and then wiggles each control pin slowly so you can find a dead
//  wire with a multimeter. Open the serial monitor at 115200 and read it.
//
//  Deliberately no #error guard: this is the sketch you run when the guard has
//  already told you something is wrong, so it has to compile either way.
//
//  After the report it runs the five visual checks that used to live in
//  PanelTest. They were a separate sketch and a separate startup sequence, and
//  when one worked and the other did not there was no way to tell whether the
//  difference was the drawing or the setup. One sketch, one startup, one
//  variable at a time.
// ---------------------------------------------------------------------------

#include <TFT_eSPI.h>
#include <SPI.h>

// ---------------------------------------------------------------------------
//  THE BISECT SWITCH
//
//  Uncomment the line below and re-flash. It adds to this sketch the two
//  things NavHUD has and this one does not: the WiFi stack and the flash-backed
//  settings store. Nothing else changes -- same pins, same init, same drawing.
//
//  The includes are the suspects rather than any line of code, because
//  #include <ESP8266WiFi.h> links the WiFi stack in and the SDK brings the
//  radio up and starts hunting for the last access point it saw *before*
//  setup() runs. On a board that is also lighting a 4" backlight, that is a
//  couple of hundred milliamps arriving through the same regulator at exactly
//  the moment the panel is trying to initialise.
//
//  Two outcomes, and each one ends the guessing:
//
//    still works  -> the includes are innocent. The fault is somewhere in
//                    NavHUD's own setup, and I bisect that next.
//    goes white   -> proven. NavHUD is browning the panel out at boot, and the
//                    fix is power, not code.
//
//#define DIAG_LIKE_NAVHUD
// ---------------------------------------------------------------------------

#ifdef DIAG_LIKE_NAVHUD
  #include <ESP8266WiFi.h>
  #include <EEPROM.h>
#endif

TFT_eSPI tft = TFT_eSPI();

#define BL_PIN 4          // GPIO4

// Uncomment to meter the control pins instead of running the visual checks.
//#define PIN_WIGGLE

// ---- turn the compiled configuration into something readable ---------------

static const char* driverName() {
#if defined(ST7796_DRIVER)
  return "ST7796   <-- correct for this panel";
#elif defined(ILI9341_DRIVER)
  return "ILI9341  <-- WRONG. User_Setup.h is not the file being compiled.";
#elif defined(ILI9488_DRIVER)
  return "ILI9488  <-- WRONG for this panel.";
#elif defined(ST7789_DRIVER)
  return "ST7789   <-- WRONG for this panel.";
#elif defined(ILI9481_DRIVER)
  return "ILI9481  <-- WRONG for this panel.";
#else
  return "none     <-- no driver defined at all.";
#endif
}

// The D-numbers, in the NodeMCU and D1-mini convention.
//
// Which is NOT universal. An Uno-shaped Wemos D1 calls GPIO15 "D10" and GPIO5
// "D3"; there is no agreement between board makers at all. The GPIO number is
// the only thing that means the same on every board, so it is printed first
// and the D-label is offered as a hint, not as an instruction.
static const char* dLabel(int gpio) {
  switch (gpio) {
    case 16: return "D0"; case 5:  return "D1"; case 4:  return "D2";
    case 0:  return "D3"; case 2:  return "D4"; case 14: return "D5";
    case 12: return "D6"; case 13: return "D7"; case 15: return "D8";
    default: return "?";
  }
}

static void reportPin(const char* name, int gpio, int expect) {
  Serial.print(F("  ")); Serial.print(name);
  if (gpio < 0) {
    Serial.println(F(": -1  (not driven by the library)"));
    return;
  }
  Serial.print(F(": GPIO")); Serial.print(gpio);
  Serial.print(F("  (")); Serial.print(dLabel(gpio));
  Serial.print(F(" on a NodeMCU / D1 mini)"));
  if (expect >= 0 && gpio != expect) {
    Serial.print(F("   <-- expected GPIO")); Serial.print(expect);
    Serial.print(F(" (")); Serial.print(dLabel(expect)); Serial.print(F(")"));
  }
  Serial.println();
}

// ---- can we make the controller answer? ------------------------------------
//
// The one measurement that settles "is the ESP talking to the panel at all".
// It needs the SDO(MISO) wire, which NavHUD does not otherwise use -- so this
// is worth one temporary dupont lead when nothing else is conclusive.

static void readId() {
  Serial.println();
  Serial.println(F("-- controller ID --"));
#if defined(TFT_MISO) && (TFT_MISO >= 0)
  const uint8_t a = tft.readcommand8(0xD3, 1);
  const uint8_t b = tft.readcommand8(0xD3, 2);
  const uint8_t c = tft.readcommand8(0xD3, 3);
  Serial.print(F("  RDDID(0xD3) = 0x"));
  if (a < 16) Serial.print('0'); Serial.print(a, HEX); Serial.print(' ');
  if (b < 16) Serial.print('0'); Serial.print(b, HEX); Serial.print(' ');
  if (c < 16) Serial.print('0'); Serial.println(c, HEX);

  if (b == 0x77 && c == 0x96) {
    Serial.println(F("  -> ST7796 answering. The wiring and the bus are good;"));
    Serial.println(F("     if the screen is still white the fault is the init"));
    Serial.println(F("     sequence, i.e. the driver define."));
  } else if ((a == 0x00 && b == 0x00 && c == 0x00) ||
             (a == 0xFF && b == 0xFF && c == 0xFF)) {
    Serial.println(F("  -> nothing on the bus. Either SDO is not wired to D6,"));
    Serial.println(F("     or CS / SCK / MOSI is not making contact."));
  } else {
    Serial.println(F("  -> something answered, but not an ST7796. Send me this."));
  }
#else
  Serial.println(F("  skipped: TFT_MISO is not defined."));
  Serial.println(F("  To run it: wire the panel's SDO(MISO) to D6, add"));
  Serial.println(F("      #define TFT_MISO 12"));
  Serial.println(F("  to User_Setup.h, and re-upload. This is the test that"));
  Serial.println(F("  proves whether the ESP is reaching the controller."));
#endif
}

// ---- wiggle the control pins so a meter can find a dead wire ---------------

static void wigglePin(const char* name, int gpio) {
  if (gpio < 0) return;
  Serial.print(F("  ")); Serial.print(name);
  Serial.print(F(" on ")); Serial.print(dLabel(gpio));
  Serial.println(F(" -- toggling 1 Hz for 6 s"));
  pinMode(gpio, OUTPUT);
  for (int i = 0; i < 6; i++) {
    digitalWrite(gpio, HIGH); delay(500);
    digitalWrite(gpio, LOW);  delay(500);
  }
  digitalWrite(gpio, HIGH);
}

// ---- the five visual checks ------------------------------------------------
//
// Every string here is drawn at font 2 or font 4. TFT_eSPI's larger built-in
// fonts have no letters in them at all -- font 6 is "1234567890:-.apm" and
// fonts 7 and 8 are digits and three punctuation marks -- so a word handed to
// one of them indexes a character table that has no entry for it. That is a
// bad pointer read, and a bad pointer read on an ESP8266 is an exception and a
// reboot. Which looks exactly like a display that does not work.

#define C_BG    TFT_BLACK
#define C_AMBER 0xFCE0    // #FF9D00, the amber NavHUD uses
#define C_FAINT 0x4940

static uint8_t screen = 0;
static uint32_t lastMs = 0;

static void header(const char* n, const char* title) {
  tft.fillScreen(C_BG);
  tft.setTextDatum(TL_DATUM);
  tft.setTextColor(C_AMBER, C_BG);
  tft.drawString(n, 8, 6, 2);
  tft.setTextColor(TFT_WHITE, C_BG);
  tft.drawString(title, 40, 6, 2);
}

// 1 -- alive, and the size the driver thinks it has
static void screenAlive() {
  header("1/5", "ALIVE");
  tft.setTextDatum(MC_DATUM);
  tft.setTextColor(C_AMBER, C_BG);
  tft.setTextSize(2);
  tft.drawString("PANEL OK", tft.width() / 2, tft.height() / 2 - 46, 4);

  char buf[48];
  snprintf(buf, sizeof buf, "%d x %d", tft.width(), tft.height());
  tft.setTextColor(TFT_WHITE, C_BG);
  tft.drawString(buf, tft.width() / 2, tft.height() / 2 + 16, 4);
  tft.setTextSize(1);
}

// 2 -- colour order
static void screenColour() {
  header("2/5", "COLOUR - each bar must match its label");
  const int y = 40, h = tft.height() - 40;
  const int w = tft.width() / 3;
  const uint16_t col[3] = { TFT_RED, TFT_GREEN, TFT_BLUE };
  const char* nm[3] = { "RED", "GREEN", "BLUE" };
  const uint16_t ink[3] = { TFT_WHITE, TFT_BLACK, TFT_WHITE };
  tft.setTextDatum(MC_DATUM);
  tft.setTextSize(2);
  for (int i = 0; i < 3; i++) {
    tft.fillRect(i * w, y, w, h, col[i]);
    tft.setTextColor(ink[i], col[i]);
    tft.drawString(nm[i], i * w + w / 2, y + h / 2, 4);
  }
  tft.setTextSize(1);
}

// 3 -- is the whole panel addressable, with no offset?
static void screenEdges() {
  header("3/5", "EDGES - all four sides must be visible");
  const int w = tft.width(), h = tft.height();
  tft.drawRect(0, 0, w, h, C_AMBER);
  tft.drawRect(2, 2, w - 4, h - 4, C_FAINT);
  for (int i = 1; i <= 2; i++) {
    tft.drawFastVLine(w * i / 3, 3, h - 6, C_FAINT);
    tft.drawFastHLine(3, h * i / 3, w - 6, C_FAINT);
  }
  tft.drawFastHLine(w / 2 - 26, h / 2, 52, C_AMBER);
  tft.drawFastVLine(w / 2, h / 2 - 26, 52, C_AMBER);
  tft.setTextColor(C_AMBER, C_BG);
  tft.setTextDatum(TL_DATUM); tft.drawString("TL", 10, 30, 4);
  tft.setTextDatum(TR_DATUM); tft.drawString("TR", w - 10, 30, 4);
  tft.setTextDatum(BR_DATUM); tft.drawString("BR", w - 10, h - 10, 4);
  tft.setTextDatum(BL_DATUM); tft.drawString("BL", 10, h - 10, 4);
}

// 4 -- which rotation is the windscreen mirror?
static void screenMirror() {
  static bool mirrored = false;
  mirrored = !mirrored;
  tft.setRotation(mirrored ? 7 : 1);
  header("4/5", mirrored ? "MIRROR - rotation 7, what NavHUD ships"
                         : "MIRROR - rotation 1, plain landscape");
  tft.setTextDatum(MC_DATUM);
  tft.setTextColor(mirrored ? C_AMBER : TFT_WHITE, C_BG);
  tft.setTextSize(2);
  tft.drawString(mirrored ? "MIRRORED" : "NORMAL",
                 tft.width() / 2, tft.height() / 2 - 30, 4);
  tft.setTextSize(1);
  tft.setTextColor(0x8410, C_BG);
  tft.drawString("hold a mirror to the amber one - it should read straight",
                 tft.width() / 2, tft.height() / 2 + 40, 2);
  tft.setTextDatum(TL_DATUM);
  tft.setTextColor(C_AMBER, C_BG);
  tft.drawString("TL", 10, 32, 4);
}

// 5 -- will a GPIO drive the backlight?
static void screenBacklight() {
  tft.setRotation(1);
  header("5/5", "BACKLIGHT");
  tft.setTextDatum(MC_DATUM);
  tft.setTextColor(TFT_WHITE, C_BG);
#if BL_PIN >= 0
  tft.drawString("dimming on GPIO 4", tft.width() / 2, tft.height() / 2 - 30, 4);
  tft.setTextColor(0x8410, C_BG);
  tft.drawString("if it does not dim, LED is not on GPIO 4 - tie it to 3V3",
                 tft.width() / 2, tft.height() / 2 + 30, 2);
  for (int v = 255; v >= 40; v -= 5) { analogWrite(BL_PIN, v); delay(6); }
  for (int v = 40; v <= 255; v += 5) { analogWrite(BL_PIN, v); delay(6); }
#else
  tft.drawString("backlight tied to 3V3", tft.width() / 2, tft.height() / 2, 4);
#endif
}

// ---------------------------------------------------------------------------

void setup() {
  Serial.begin(115200);
  delay(400);

#ifdef DIAG_LIKE_NAVHUD
  // Exactly what NavHUD does, in the same order, before it touches the panel.
  WiFi.persistent(false);
  WiFi.disconnect(true);
  WiFi.mode(WIFI_OFF);
  WiFi.forceSleepBegin();
  delay(200);
  EEPROM.begin(32);
  { volatile uint8_t sink = 0; for (int i = 0; i < 32; i++) sink ^= EEPROM.read(i); (void)sink; }
  Serial.println();
  Serial.println(F("  DIAG_LIKE_NAVHUD is on: WiFi stack linked, radio shut"));
  Serial.println(F("  down, settings sector read. If the panel now stays white,"));
  Serial.println(F("  that is the answer."));
#endif

#if BL_PIN >= 0
  analogWriteRange(255);
  pinMode(BL_PIN, OUTPUT);
  analogWrite(BL_PIN, 255);
#endif

  Serial.println();
  Serial.println(F("================================================"));
  Serial.println(F("  NavHUD panel diagnostic"));
  Serial.println(F("================================================"));

  Serial.println();
  Serial.println(F("-- what the library actually compiled with --"));
  Serial.print(F("  driver: ")); Serial.println(driverName());

#if defined(TFT_CS)
  reportPin("TFT_CS  ", TFT_CS, 15);
#else
  Serial.println(F("  TFT_CS  : not defined (CS tied to GND)"));
#endif
#if defined(TFT_DC)
  reportPin("TFT_DC  ", TFT_DC, 5);
#else
  Serial.println(F("  TFT_DC  : NOT DEFINED <-- the panel cannot work"));
#endif
#if defined(TFT_RST)
  reportPin("TFT_RST ", TFT_RST, -1);
#endif
#if defined(TFT_MOSI)
  reportPin("TFT_MOSI", TFT_MOSI, 13);
#endif
#if defined(TFT_SCLK)
  reportPin("TFT_SCLK", TFT_SCLK, 14);
#endif
#if defined(TFT_MISO)
  reportPin("TFT_MISO", TFT_MISO, 12);
#else
  Serial.println(F("  TFT_MISO: not defined (reads disabled)"));
#endif
  Serial.print(F("  SPI_FREQUENCY: "));
  Serial.print(SPI_FREQUENCY / 1000000); Serial.println(F(" MHz"));

  tft.init();
  tft.setRotation(1);
  Serial.print(F("  reported size: "));
  Serial.print(tft.width()); Serial.print(F(" x ")); Serial.println(tft.height());
  Serial.println(F("  -> must be 480 x 320. Anything else and User_Setup.h"));
  Serial.println(F("     is not the file the IDE is compiling."));

  readId();

  Serial.println();
  Serial.println(F("-- drawing --"));
  Serial.println(F("  filling red, then green, then blue, 2 s each."));
  Serial.println(F("  If the screen stays white through all three, no SPI"));
  Serial.println(F("  data is reaching the controller."));
  tft.fillScreen(TFT_RED);   delay(2000);
  tft.fillScreen(TFT_GREEN); delay(2000);
  tft.fillScreen(TFT_BLUE);  delay(2000);
  tft.fillScreen(TFT_BLACK);
  tft.setTextDatum(MC_DATUM);
  tft.setTextColor(0xFCE0, TFT_BLACK);
  tft.setTextSize(2);
  tft.drawString("DIAG", tft.width() / 2, tft.height() / 2, 4);
  tft.setTextSize(1);

#ifdef PIN_WIGGLE
  Serial.println();
  Serial.println(F("-- pin wiggle --"));
  Serial.println(F("  Put a multimeter on the DISPLAY's pin, not the board's,"));
  Serial.println(F("  so a broken lead shows up. Each should swing 0 <-> 3.3 V."));
  Serial.println(F("  This takes over the pins; reset the board afterwards."));
  delay(3000);
  #if defined(TFT_DC)
    wigglePin("DC/RS", TFT_DC);
  #endif
  #if defined(TFT_CS) && (TFT_CS >= 0)
    wigglePin("CS   ", TFT_CS);
  #endif
  wigglePin("LED  ", BL_PIN);
  Serial.println(F("  done. Press reset to run again."));
  while (true) delay(1000);
#endif

  Serial.println();
  Serial.println(F("-- visual checks --"));
  Serial.println(F("  five screens, three seconds each, repeating."));
  Serial.println(F("    1 size   2 colour   3 edges   4 mirror   5 backlight"));
  Serial.println(F("  Uncomment PIN_WIGGLE at the top to meter the pins instead."));

  lastMs = millis();
  screen = 0;
  screenAlive();
}

void loop() {
  if (millis() - lastMs < 3000) return;
  lastMs = millis();
  screen = (screen + 1) % 5;
  Serial.print(F("  screen ")); Serial.println(screen + 1);
  switch (screen) {
    case 0: tft.setRotation(1); screenAlive(); break;
    case 1: screenColour();     break;
    case 2: screenEdges();      break;
    case 3: screenMirror();     break;
    case 4: screenBacklight();  break;
  }
}
