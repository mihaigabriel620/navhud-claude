# Reference icons (design inspiration only)

Icons the owner extracted from Waze and Google Maps, kept here so any session
can look at them when designing HUD or app graphics. **Reference only**: do not
copy these files into the app or the firmware. Draw our own versions that match
the HUD's amber-on-black theme and its drawing primitives.

`owner-picks/` — **start here for the roundabout**: `00-…photo.jpg` is the
real HUD today (dotted, unpolished ring); `01…16-liked.png` are the looks the
owner wants, to be redrawn in the HUD's amber-on-black theme.

`roundabout/`
- `big_trans_directions_roundabout_*` / `car_big_trans_*` (Google Maps style):
  thick ring, the path actually driven (entry → around the ring → exit) drawn
  bold, the rest of the ring faded, a big exit arrow. `_l/_r/_s/_u` = left,
  right, straight, U-turn exit; `_lhs` = left-hand traffic.
- `big_directions_roundabout_*` / `car_dark_big_directions_*` (Waze style),
  `_uk` = left-hand traffic.
- `ic_roundabout_*.svg`, `ic_turn_*.svg`, `ic_u_turn.svg`, `lane_*.svg`:
  24×24 Material-style vector paths (clean geometry to learn proportions from).
