// ---------------------------------------------------------------------------
//  hud_arrows.h -- the manoeuvre glyphs, drawn in one place.
// ---------------------------------------------------------------------------
//
//  WHERE TO LOOK WHEN SOMETHING IS WRONG
//
//    one manoeuvre looks wrong -> its case in arrowArt(). They are independent.
//    everything is mirrored    -> not here. HudCanvas applies the mirror.
//    it is the wrong size      -> the caller's `size`; all art is drawn in a
//                                 120x120 box and scaled by size/120.
//
//  Every shape is authored in a -60..+60 square with +y DOWN and the origin at
//  the glyph's centre, then scaled. That box is the same one the HUD Bench
//  draws in, so a coordinate here and a coordinate there mean the same thing.
//
// ---------------------------------------------------------------------------
#ifndef HUD_ARROWS_H
#define HUD_ARROWS_H

// ---- roundabout geometry, measured off real road-sign icons ---------------
// Relative to the ring's outer radius R: stroke 0.42R, entry stub 0.81R,
// shaft to 1.3R, head 0.5R half-wide and 0.88R long, tip 2.04R.
//
// THREE RULES. Each one was learned by drawing it wrong:
//   1. the shaft must run PAST the head's notch, not stop at the wing roots,
//      or the swept-back rear bites a V out of the middle of the arrow;
//   2. the shaft must start INSIDE the ring stroke, so ring and arrow are one
//      continuous shape -- a gap reads as two objects;
//   3. hypot(WING_R, WING_W) must exceed R, or the head's base corners fold
//      back over the circle and it reads as a fin.
#define RAB_R        26
#define RAB_RING_W   11
#define RAB_ROAD_W   10
#define RAB_IN_R1    47
#define RAB_SHAFT_R0 15
#define RAB_SHAFT_R1 42
#define RAB_WING_R   30
#define RAB_WING_W   13
#define RAB_NOTCH_R  38
#define RAB_TIP_R    53

/**
 * "We have no idea which way this exit points."
 *
 * A bearing, not a flag, so it threads through the existing signature without
 * adding a parameter to every call. 1000 is outside any real angle and outside
 * anything the parser will accept.
 */
#define RAB_NO_BEARING 1000.0f
static inline bool rabAimable(float b) { return b > -360.0f && b < 360.0f; }

// THE FALLBACK, and it is only a fallback now.
//
// Exit bearings guessed from the exit number, 1..7. These are the app's numbers
// (ui/ManeuverView.kt) rather than a second set derived here -- they used to be
// independent and disagreed on five of the seven exits, including a sign flip
// on exit 6 where the app pointed down-left and the panel down-right. Two
// displays showing opposite turns for the same instruction is worse than either
// being a few degrees off, so if you touch these, touch both.
//
// But understand what they are: an exit NUMBER is not an angle. On a three-exit
// roundabout, exit 2 is almost always straight ahead and this table says 30
// degrees. On a five-exit one it might be 45. The arrow came out plausible and
// rarely right, which is the worst way for a display to be wrong.
//
// So these are used only when the phone has not sent a real bearing on $RAB.
// When it has, the real one wins -- see rabBearing().
static const float RAB_BEARINGS[7] =
    { 100.f, 30.f, -30.f, -80.f, -120.f, -150.f, -170.f };

/** True when the exit number is one the fallback table can point at. */
static inline bool rabHasExit(uint8_t exitNo) { return exitNo >= 1 && exitNo <= 7; }

static float rabBearingFor(uint8_t exitNo) {
  return rabHasExit(exitNo) ? RAB_BEARINGS[exitNo - 1] : RAB_NO_BEARING;
}

/**
 * The bearing to actually draw: the phone's if it sent one for THIS exit,
 * otherwise the guess, otherwise nothing.
 *
 * The exit check is the load-bearing part. $RAB arrives as its own frame, so
 * without it an angle measured at the last roundabout would be aimed at the
 * next one -- and it would look every bit as authoritative as a correct one.
 */
static float rabBearing(uint8_t exitNo, int16_t angle, uint8_t angleExit) {
  if (angle != HUD_RB_ANGLE_NONE && exitNo >= 1 && angleExit == exitNo)
    return (float)angle;
  return rabBearingFor(exitNo);
}

/** Roundabout, road-sign style: ring, entry stub, one exit arrow. */
static void roundaboutArt(int cx, int cy, float u, float bearing,
                          uint8_t exitNo, uint16_t col, uint8_t numFont) {
  const float ringR = RAB_R * u, w = RAB_RING_W * u;
  const int   cyc   = cy - (int)(4 * u);          // the ring sits a little high

  // ring: concentric circles are cheaper and rounder than a stroked path
  for (float r = ringR; r > ringR - w; r -= 0.8f)
    tft.drawCircle(cx, cyc, (int)(r + 0.5f), col);

  // the road you came in on, from the bottom
  thickLine(cx, cyc + (int)((RAB_R - RAB_RING_W) * u), cx, cyc + (int)(RAB_IN_R1 * u),
            (int)(RAB_ROAD_W * u), col);

  // No bearing, no arrow.
  //
  // Mapbox omits the exit on "roundabout turn" and "exit rotary", and drawing
  // one anyway means picking a direction at random and stating it with total
  // confidence -- the app hit exactly this and its fix is to draw a bare ring,
  // so this matches. A ring with no arrow reads as "a roundabout, follow the
  // voice", which is honest; an arrow pointing at the wrong exit does not.
  //
  // This used to test the exit NUMBER against the fallback table's 1..7, which
  // meant exit 8 got no arrow even when the phone knew exactly where it was.
  // Now it asks the only question that matters: do we have a direction.
  if (!rabAimable(bearing)) return;

  // Keep the exit arrow off the road you came in on.
  //
  // The entry stub is drawn straight down from the ring, and now that the
  // bearing is real rather than one of seven table values, a roundabout that
  // doubles you back can ask for exactly 180 -- which lays the exit arrow on
  // top of the entry road and reads as one line through a circle. Ten degrees
  // is enough to separate them and is invisible as an error: this is a symbol,
  // not a survey. The old table stopped at -170 for the same reason.
  float aim = bearing;
  if (aim >  170.0f) aim =  170.0f;
  if (aim < -170.0f) aim = -170.0f;

  const float a  = aim * DEG_TO_RAD;
  const float dx = sinf(a), dy = -cosf(a);
  const float px = -dy,     py = dx;              // perpendicular

  // shaft: starts inside the ring stroke, ends PAST the notch
  thickLine(cx + dx * RAB_SHAFT_R0 * u, cyc + dy * RAB_SHAFT_R0 * u,
            cx + dx * RAB_SHAFT_R1 * u, cyc + dy * RAB_SHAFT_R1 * u,
            (int)(RAB_ROAD_W * u), col);

  // head: tip, wing, notch, wing -- two triangles make the swept-back shape
  const float tx = cx + dx * RAB_TIP_R * u,   ty = cyc + dy * RAB_TIP_R * u;
  const float nx = cx + dx * RAB_NOTCH_R * u, ny = cyc + dy * RAB_NOTCH_R * u;
  const float w1x = cx + dx * RAB_WING_R * u + px * RAB_WING_W * u;
  const float w1y = cyc + dy * RAB_WING_R * u + py * RAB_WING_W * u;
  const float w2x = cx + dx * RAB_WING_R * u - px * RAB_WING_W * u;
  const float w2y = cyc + dy * RAB_WING_R * u - py * RAB_WING_W * u;
  tft.fillTriangle(tx, ty, w1x, w1y, nx, ny, col);
  tft.fillTriangle(tx, ty, w2x, w2y, nx, ny, col);

  // Any exit we could aim at gets its number. Printing a digit with no arrow
  // beside it is the confusing case, and that one returned above.
  if (exitNo >= 1) {
    tft.setTextDatum(MC_DATUM);
    tft.setTextColor(col, TFT_BLACK);
    tft.setTextPadding((int)(RAB_R * u));
    tft.drawNumber(exitNo, cx, cyc, numFont);
    tft.setTextPadding(0);
  }
}

/**
 * Destination.
 *
 * A target -- ring with a filled centre -- not a map pin. The pin was better
 * looking in isolation and wrong in context: the app's card and the other two
 * themes all draw the target, so arriving showed you two different symbols on
 * two screens a hand's width apart.
 */
static void arriveArt(int cx, int cy, float u, uint16_t col) {
  const int rr = (int)(34 * u);
  const int rw = (int)(9 * u);
  for (int i = 0; i < rw; i++) tft.drawCircle(cx, cy, rr - i, col);
  tft.fillCircle(cx, cy, (int)(14 * u), col);
  return;
}

/** The old pin, kept only so the shape is not lost if it is ever wanted. */
static void arrivePinArt(int cx, int cy, float u, uint16_t col) {
  const int r = (int)(24 * u);
  for (int i = 0; i < (int)(9 * u); i++)
    tft.drawCircle(cx, cy - (int)(12 * u), r - i, col);
  // the taper down to the point
  tft.fillTriangle(cx - r * 0.72f, cy - 12 * u + r * 0.66f,
                   cx + r * 0.72f, cy - 12 * u + r * 0.66f,
                   cx,             cy + 44 * u, col);
}

/**
 * Draw the manoeuvre. `size` is the box the glyph lives in; everything scales
 * from it, so one number moves the whole family.
 */
static void arrowArt(int cx, int cy, int size, uint8_t man, uint8_t exitNo,
                     float rbBearingDeg, uint16_t col, uint8_t numFont) {
  const float u = size / 120.0f;
  const int   w = (int)(18 * u);

  switch (man) {
    case MAN_ROUNDABOUT:
      roundaboutArt(cx, cy, u, rbBearingDeg, exitNo, col, numFont);
      return;

    case MAN_ARRIVE:
      arriveArt(cx, cy, u, col);
      return;

    case MAN_UTURN:
      drawUturnArt(cx, cy, (int)(22 * u), w, col);
      return;

    case MAN_LEFT:
    case MAN_RIGHT: {
      // A square elbow: up the near lane, then across. Reads as a junction
      // rather than as a generic arrow, which is the whole point of drawing
      // 90-degree turns differently from the shallow ones.
      const float s = (man == MAN_RIGHT) ? 1.0f : -1.0f;
      thickLine(cx - s * 14 * u, cy + 46 * u, cx - s * 14 * u, cy - 8 * u, w, col);
      thickLine(cx - s * 14 * u, cy - 8 * u,  cx + s * 12 * u, cy - 8 * u, w, col);
      tft.fillTriangle(cx + s * 6  * u, cy - 32 * u,
                       cx + s * 44 * u, cy - 8  * u,
                       cx + s * 6  * u, cy + 16 * u, col);
      return;
    }

    default: {
      // Everything else -- straight on, slight, sharp, merges, forks, ramps.
      //
      // A vertical stem rooted at the bottom, bending to the manoeuvre's angle
      // partway up. NOT one straight shaft rotated about its centre, which is
      // what this used to be: at -42 degrees that put the tail out to the
      // bottom-RIGHT for a turn to the left, so the glyph read as a bare
      // diagonal stroke with no sense of a road you are already on. The app's
      // own icon (ui/ManeuverView.kt) has always drawn the stem-and-branch
      // form, and the two side by side on the same instruction did not look
      // like the same manoeuvre.
      //
      // The stem carries the meaning: it is the road under you, and the bend
      // is what you are about to do to it. At 0 degrees the bend is colinear
      // with the stem and this degenerates to the plain vertical arrow, which
      // is right for straight on and for depart.
      const float ang = angleForManeuver(man);
      const float a = ang * DEG_TO_RAD;
      const float dx = sinf(a), dy = -cosf(a);

      // Where the stem stops and the branch begins. Above centre, so a shallow
      // turn still shows a decent run of road below it.
      const int elbowY = cy + (int)(6 * u);
      const float branch = 30 * u;                 // how far the bend runs
      const float headL  = 26 * u;                 // and the head beyond it

      thickLine(cx, cy + 46 * u, cx, elbowY, w, col);   // the road you are on
      thickLine(cx, elbowY,                              // the turn
                cx + dx * branch, elbowY + dy * branch, w, col);
      // Fills the inside of the bend: two thick lines meeting at an angle
      // leave a notch on the outside of the corner otherwise.
      tft.fillCircle(cx, elbowY, w / 2, col);
      // Measured from the elbow, not from the branch end, so the head's base
      // lands exactly where the line stops. Measuring from the tip left a few
      // pixels of daylight between shaft and head at every angle.
      arrowHead(cx, elbowY, ang, branch + headL, headL, col);
      return;
    }
  }
}

#endif  // HUD_ARROWS_H
