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

// ---- the roundabout ----------------------------------------------------------
//
// Drawn the way the owner picked from the Google Maps / Waze references
// (docs/reference-icons/owner-picks): a thick ring, the part you drive --
// in, round, out -- bold in the theme's bright colour, the rest of the ring
// dim, the exit number in the middle with no box round it, and a big arrow at
// the exit's real angle, any way round the clock. No stubs for the other
// exits: the owner tried them and chose the cleaner picture. Anti-aliased with
// TFT_eSPI's smooth arc and wide line.
//
// Authored in the glyph box (-60..+60, units of u = size/120):
#define RAB_R         30     // ring, outer edge
#define RAB_RI        19     // ring, inner edge: the hole the exit number sits in
#define RAB_RM        24.5f  // middle of the band: attachments start here, hidden
#define RAB_UP         4     // the ring sits this far above the glyph centre
#define RAB_ROAD_W    11     // the road in, and the exit arrow's shaft
#define RAB_IN_R1     48     // the road in ends here (centre of its round end)
#define RAB_HEAD_R0   40     // arrow head: base
#define RAB_TIP_R     57     //             tip
#define RAB_HEAD_W    13     //             half-width at the base
// The exit arrow is kept this far round from the road in. A U-turn asks for
// 180, which would lay the arrow on top of the entry road and read as one line
// through a circle; 40 degrees apart the head clears the road in by 7 px and
// they read as out-and-back. This is a symbol, not a survey.
#define RAB_MAX_AIM  140

/**
 * How far the distance to the manoeuvre may have grown since an angle arrived
 * and still be about the same roundabout, in metres.
 *
 * $RAB arrives as its own frame, so an angle measured at one roundabout
 * could be aimed at the next. The exit number alone does not stop
 * that -- two roundabouts in a row, both "exit 2", is ordinary -- so each
 * angle is stamped with the distance to the manoeuvre as it arrives. Driving
 * towards one roundabout that only ever comes down; the next one starts far
 * away again. The slack is GPS jitter.
 */
#define RAB_DIST_SLACK 30

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
// So these are used only when the phone has sent no angle for this roundabout
// on $RAB -- see rabResolve().
static const float RAB_BEARINGS[7] =
    { 100.f, 30.f, -30.f, -80.f, -120.f, -150.f, -170.f };

/** True when the exit number is one the fallback table can point at. */
static inline bool rabHasExit(uint8_t exitNo) { return exitNo >= 1 && exitNo <= 7; }

/** Everything the glyph shows, resolved from the frames. Compared whole. */
struct RabDraw {
  int16_t aim;                            // the exit to take; HUD_RB_ANGLE_NONE = bare ring
  uint8_t exitNo;
  uint8_t left;                           // left-hand traffic: the ring runs clockwise
};

/** Is an angle stamped at `stampDist` still about the manoeuvre in `s`? */
static inline bool rabSameOne_(const HudState& s, int32_t stampDist) {
  return s.distToMan <= stampDist + RAB_DIST_SLACK;
}

/**
 * What to draw for the roundabout in `s`: the phone's $RAB if it is about this
 * roundabout, else the table, else a bare ring. Which way round comes from
 * FLAG_LEFT_HAND on the $HUD frame.
 *
 * Zero-filled first, so two of these compare with memcmp.
 */
static RabDraw rabResolve(const HudState& s) {
  RabDraw d;
  memset(&d, 0, sizeof d);
  d.aim = HUD_RB_ANGLE_NONE;
  if (s.maneuver != MAN_ROUNDABOUT || s.rbExit < 1) return d;
  d.exitNo = s.rbExit;
  d.left = (s.flags & FLAG_LEFT_HAND) ? 1 : 0;

  if (s.rbAngle != HUD_RB_ANGLE_NONE && s.rbAngleExit == s.rbExit &&
      rabSameOne_(s, s.rbAngleDist)) {
    d.aim = s.rbAngle;
  } else if (rabHasExit(s.rbExit)) {
    // The table assumes the ring runs anticlockwise; mirror it where it does not.
    const float t = RAB_BEARINGS[s.rbExit - 1];
    d.aim = (int16_t)(d.left ? -t : t);
  }
  // No angle and no table entry: a bare ring. Mapbox omits the exit on
  // "roundabout turn" and "exit rotary", and drawing one anyway means picking
  // a direction at random and stating it with total confidence. A ring with no
  // arrow reads as "a roundabout, follow the voice", which is honest.
  return d;
}

/** A point on the glyph at a bearing (0 up, positive clockwise) and radius. */
static void rabPt_(int cx, int cy, float u, float deg, float r, float* x, float* y) {
  const float a = deg * DEG_TO_RAD;
  *x = cx + sinf(a) * r * u;
  *y = cy - cosf(a) * r * u;
}

/** The ring clockwise from bearing `from` to bearing `to`. */
static void rabArc_(int cx, int cy, int ro, int ri, float from, float to,
                    uint16_t col, uint16_t bg, bool smooth) {
  if (to - from >= 360.0f) { tft.drawArc(cx, cy, ro, ri, 0, 360, col, bg, smooth); return; }
  // TFT_eSPI measures arcs from six o'clock, clockwise: bearing + 180.
  const int a0 = (((int)lroundf(from) + 180) % 360 + 360) % 360;
  const int a1 = (((int)lroundf(to) + 180) % 360 + 360) % 360;
  if (a0 == a1) return;
  tft.drawArc(cx, cy, ro, ri, a0, a1, col, bg, smooth);
}

/**
 * The arrow head, anti-aliased, as one shape with the end of its shaft.
 *
 * Drawn per pixel over the head's own bounding box, which is small (about
 * 25 x 25 px). Coverage is the UNION of the triangle and the shaft's capsule,
 * so where the two meet there is no edge to blend against the background and
 * no seam -- which is what three smooth lines round a filled triangle left at
 * every corner. The shaft's edge pixels inside the box come out exactly as
 * drawWideLine draws them (same distance rule, same thresholds, same blend).
 *
 * Pixels within `keepR` of (rcx, rcy) -- the ring and its soft outer edge --
 * are left alone. The box's bottom row can reach into the ring, and painting
 * the shaft's soft edge there (blended against the background) put a dark
 * pixel on the solid band; the ring's own passes have those pixels right.
 */
static void rabHead_(float tx, float ty, float w1x, float w1y, float w2x, float w2y,
                     float s0x, float s0y, float s1x, float s1y, float shaftW,
                     uint16_t col, uint16_t bg, float rcx, float rcy, float keepR) {
  if (!tft.geom.identity) { tft.fillTriangle(tx, ty, w1x, w1y, w2x, w2y, col); return; }
  const float vx[3] = { tx, w1x, w2x }, vy[3] = { ty, w1y, w2y };
  // Edge normals pointing out of the triangle, whichever way round it is wound.
  const float cross = (w1x - tx) * (w2y - ty) - (w1y - ty) * (w2x - tx);
  float nx[3], ny[3], nc[3];
  for (int i = 0; i < 3; i++) {
    const int j = (i + 1) % 3;
    float ex = vx[j] - vx[i], ey = vy[j] - vy[i];
    const float len = sqrtf(ex * ex + ey * ey);
    ex /= len; ey /= len;
    nx[i] = cross > 0 ? ey : -ey;
    ny[i] = cross > 0 ? -ex : ex;
    nc[i] = nx[i] * vx[i] + ny[i] * vy[i];
  }
  const float bax = s1x - s0x, bay = s1y - s0y, bb = bax * bax + bay * bay;
  const float ar = shaftW * 0.5f + 0.5f;      // drawWedgeLine's radius
  const int x0 = (int)floorf(fminf(tx, fminf(w1x, w2x))) - 1;
  const int x1 = (int)ceilf (fmaxf(tx, fmaxf(w1x, w2x))) + 1;
  const int y0 = (int)floorf(fminf(ty, fminf(w1y, w2y))) - 1;
  const int y1 = (int)ceilf (fmaxf(ty, fmaxf(w1y, w2y))) + 1;
  const float keep2 = keepR * keepR;
  for (int y = y0; y <= y1; y++) {
    for (int x = x0; x <= x1; x++) {
      if ((x - rcx) * (x - rcx) + (y - rcy) * (y - rcy) <= keep2) continue;
      // Triangle: half a pixel of ramp either side of the nearest edge.
      float sd = -1e9f;
      for (int i = 0; i < 3; i++) {
        const float d = nx[i] * x + ny[i] * y - nc[i];
        if (d > sd) sd = d;
      }
      float a = 0.5f - sd;
      // The shaft: drawWedgeLine's own alpha, radius minus distance.
      float h = ((x - s0x) * bax + (y - s0y) * bay) / bb;
      h = h < 0 ? 0 : (h > 1 ? 1 : h);
      const float dx = x - s0x - bax * h, dy = y - s0y - bay * h;
      const float as = ar - sqrtf(dx * dx + dy * dy);
      if (as > a) a = as;
      if (a <= 1.0f / 32.0f) continue;
      tft.drawPixel(x, y, a > 31.0f / 32.0f ? col : aaBlend((uint8_t)(a * 255.0f), col, bg));
    }
  }
}

/**
 * Roundabout. `col` is the path, `dim` the rest; `bg` is what is under it
 * (the smooth primitives blend their edges against it -- the panel cannot be
 * read back). `numBg` is the exit number's background: the theme's own for a
 * smooth font, `col` for a built-in one, which then draws with no cell box.
 *
 * Every smooth primitive blends its edge against `bg`, so wherever one lies
 * on another -- the road in crossing the ring -- its edge leaves a darker
 * line INSIDE the shape. Two things keep the glyph clean: the ring's band is
 * painted again, plain, after everything that crosses it (its plain pixels
 * stop exactly at the band, so the smooth outline is left alone), and the
 * head is drawn as one shape with the end of its shaft (rabHead_).
 */
static void roundaboutArt(int cx, int cy, float u, const RabDraw& d, uint16_t col,
                          uint16_t dim, uint16_t bg, uint8_t numFont, uint16_t numBg) {
  const int cyc = cy - (int)lroundf(RAB_UP * u);
  const int ro = (int)lroundf(RAB_R * u), ri = (int)lroundf(RAB_RI * u);
  const float roadW = RAB_ROAD_W * u;
  float inX0, inY0, inX1, inY1;
  rabPt_(cx, cyc, u, 180.0f, RAB_RM, &inX0, &inY0);
  rabPt_(cx, cyc, u, 180.0f, RAB_IN_R1, &inX1, &inY1);

  if (d.aim == HUD_RB_ANGLE_NONE) {
    tft.drawArc(cx, cyc, ro, ri, 0, 360, col, bg, true);
    tft.drawWideLine(inX0, inY0, inX1, inY1, roadW, col, bg);
    tft.drawArc(cx, cyc, ro, ri, 0, 360, col, bg, false);
    return;
  }

  float aim = d.aim;
  if (aim >  RAB_MAX_AIM) aim =  RAB_MAX_AIM;
  if (aim < -RAB_MAX_AIM) aim = -RAB_MAX_AIM;

  // The bold arc runs past the middle of the road in and of the shaft by half
  // a road and a bit, so both join the ring entirely inside the bold part.
  const float over = ((RAB_ROAD_W * 0.5f + 1.5f) / RAB_RM) / DEG_TO_RAD;
  float from, to;                                   // the bold arc, clockwise
  if (d.left) { from = 180.0f - over; to = aim + over; }        // clockwise ring
  else        { from = aim - over;    to = 180.0f + over; }     // anticlockwise ring

  float sx0, sy0, sx1, sy1, tx, ty, nx, ny, w1x, w1y, w2x, w2y;
  rabPt_(cx, cyc, u, aim, RAB_RM, &sx0, &sy0);
  rabPt_(cx, cyc, u, aim, RAB_HEAD_R0 + 2, &sx1, &sy1);
  rabPt_(cx, cyc, u, aim, RAB_TIP_R, &tx, &ty);
  rabPt_(cx, cyc, u, aim, RAB_HEAD_R0, &nx, &ny);
  const float a = aim * DEG_TO_RAD;
  const float px = cosf(a) * RAB_HEAD_W * u, py = sinf(a) * RAB_HEAD_W * u;
  w1x = nx + px; w1y = ny + py;
  w2x = nx - px; w2y = ny - py;

  // A U-turn drives nearly all of it: a sliver of the dim ring between the
  // road in and the arrow would read as a flaw, not as information.
  const float rest = fmodf(fmodf(from - to, 360.0f) + 360.0f, 360.0f);
  if (rest < 2.0f * over) { from = 0.0f; to = 360.0f; }

  // ---- pass 1: smooth ----------------------------------------------------
  rabArc_(cx, cyc, ro, ri, to, from, dim, bg, true);     // the rest of the ring
  rabArc_(cx, cyc, ro, ri, from, to, col, bg, true);     // the part you drive
  tft.drawWideLine(inX0, inY0, inX1, inY1, roadW, col, bg);       // the road in
  tft.drawWideLine(sx0, sy0, sx1, sy1, roadW, col, bg);           // the shaft

  // ---- pass 2: the band, plain ---------------------------------------------
  // The only seams are where the road in and the shaft cross the band: their
  // edges were blended against `bg` on top of it.
  rabArc_(cx, cyc, ro, ri, from, to, col, bg, false);

  // ---- where the shaft and the road in leave the ring ----------------------
  // Just outside the band the road's soft edge was blended against `bg` on
  // top of the ring's own soft edge: one darker pixel in the corner. Both
  // edges repainted as one there (the ring exactly as drawArc drew it). The
  // ring is bold for well over half a road either side of both, so `col` is
  // right for every pixel this touches.
  {
    const AaPrim band = aaRing(cx, cyc, (ro + ri) * 0.5f, (ro - ri) * 0.5f + 0.5f);
    const float half = roadW * 0.5f + 2.0f;
    float ex, ey;
    rabPt_(cx, cyc, u, aim, RAB_R, &ex, &ey);
    aaMendSeam(band, aaCap(sx0, sy0, sx1, sy1, roadW * 0.5f), ex, ey, half, col, bg);
    rabPt_(cx, cyc, u, 180.0f, RAB_R, &ex, &ey);
    aaMendSeam(band, aaCap(inX0, inY0, inX1, inY1, roadW * 0.5f), ex, ey, half, col, bg);
  }

  // ---- the head, last, as one shape with the end of the shaft -------------
  rabHead_(tx, ty, w1x, w1y, w2x, w2y, sx0, sy0, sx1, sy1, roadW, col, bg,
           cx, cyc, ro + 1.0f);

  // The number sits in the hole, in the path's colour, with no box: the hole
  // is `bg` already, so there is nothing to paint behind it.
  if (d.exitNo >= 1) {
    tft.setTextDatum(MC_DATUM);
    tft.setTextColor(col, numBg);
    tft.setTextPadding(0);
    tft.drawNumber(d.exitNo, cx, cyc, numFont);
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
static void arriveArt(int cx, int cy, float u, uint16_t col, uint16_t bg) {
  const int rr = (int)(34 * u);
  const int rw = (int)(9 * u);
  // Smooth arcs: the ring rw px thick (the radii are inclusive), and the dot
  // as an arc with no hole. Stacked one-pixel circles left pinholes.
  tft.drawArc(cx, cy, rr, rr - rw + 1, 0, 360, col, bg, true);
  tft.drawArc(cx, cy, (int)(14 * u), 0, 0, 360, col, bg, true);
}

/**
 * Draw the manoeuvre. `size` is the box the glyph lives in; everything scales
 * from it, so one number moves the whole family.
 */
static void arrowArt(int cx, int cy, int size, uint8_t man, const RabDraw& rb,
                     uint16_t col, uint16_t dim, uint16_t bg, uint8_t numFont) {
  const float u = size / 120.0f;
  const int   w = (int)(18 * u);

  switch (man) {
    case MAN_ROUNDABOUT:
      roundaboutArt(cx, cy, u, rb, col, dim, bg, numFont, bg);
      return;

    case MAN_ARRIVE:
      arriveArt(cx, cy, u, col, bg);
      return;

    case MAN_UTURN:
      drawUturnArt(cx, cy, (int)(22 * u), w, col, bg);
      return;

    case MAN_LEFT:
    case MAN_RIGHT: {
      // An elbow: up the near lane, then across. Reads as a junction rather
      // than as a generic arrow, which is the whole point of drawing 90-degree
      // turns differently from the shallow ones. Round-ended strokes, so the
      // corner is round like a kerb (the flat strokes left a notch in it),
      // and the stem's free end is pulled in by its radius to end where the
      // flat one did. Anti-aliased, one shape (hud_aa.h).
      const float s = (man == MAN_RIGHT) ? 1.0f : -1.0f;
      const float hw = w * 0.5f;
      AaPrim p[3];
      p[0] = aaCap(cx - s * 14 * u, cy + 46 * u - hw, cx - s * 14 * u, cy - 8 * u, hw);
      p[1] = aaCap(cx - s * 14 * u, cy - 8 * u,       cx + s * 12 * u, cy - 8 * u, hw);
      p[2] = aaTri(cx + s * 6  * u, cy - 32 * u,
                   cx + s * 44 * u, cy - 8  * u,
                   cx + s * 6  * u, cy + 16 * u);
      aaFill(p, 3, col, bg);
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

      // Round-ended strokes drawn as one anti-aliased shape (hud_aa.h): the
      // bend comes out round with no disc to fill it, and nothing is drawn
      // over anything else, so there is no seam. The stem's free end is
      // pulled in by its radius to end where the flat one did.
      const float hw = w * 0.5f;
      AaPrim p[3];
      p[0] = aaCap(cx, cy + 46 * u - hw, cx, elbowY, hw);          // the road you are on
      p[1] = aaCap(cx, elbowY, cx + dx * branch, elbowY + dy * branch, hw);   // the turn
      // Measured from the elbow, not from the branch end, so the head's base
      // lands exactly where the line stops. Measuring from the tip left a few
      // pixels of daylight between shaft and head at every angle.
      p[2] = aaHead(cx, elbowY, ang, branch + headL, headL);
      aaFill(p, 3, col, bg);
      return;
    }
  }
}

#endif  // HUD_ARROWS_H
