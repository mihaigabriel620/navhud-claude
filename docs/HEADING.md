# Which way is the car pointing?

Researched August 2026 before the third attempt at this, because the first two
were built on assumptions that turned out to be wrong.

## The two bugs that prompted it

**1. Turning the phone gave the opposite direction.** The display-rotation remap
added in 1.12 had its two parameters backwards. Android's docs say:

> **X** — defines the axis of the new coordinate system that coincide with the X
> axis of the original coordinate system.
> — <https://developer.android.com/reference/android/hardware/SensorManager#remapCoordinateSystem(float[],%20int,%20int,%20float[])>

That is: the parameter names where the *original device* axis **ends up** in the
new frame. I had read it as naming which device axis **becomes** the new axis —
the transpose. For a quarter turn the transpose is the inverse, so `ROTATION_90`
and `ROTATION_270` swapped places and every reading came out 180° wrong. Held
one way up it was right; held with the USB-C socket on the other side, exactly
backwards.

The canonical table, from the doc for 90 and derived-and-verified against the
AOSP implementation for the others:

| `Display.getRotation()` | call |
|---|---|
| `ROTATION_0` | identity |
| `ROTATION_90` | `remapCoordinateSystem(R, AXIS_Y, AXIS_MINUS_X, out)` |
| `ROTATION_180` | `remapCoordinateSystem(R, AXIS_MINUS_X, AXIS_MINUS_Y, out)` |
| `ROTATION_270` | `remapCoordinateSystem(R, AXIS_MINUS_Y, AXIS_X, out)` |

**2. A phone in a windscreen cradle has no heading at all, by that recipe.**
`getOrientation()` reports the azimuth of the frame's **+Y** axis:

> `values[0]`: Azimuth, angle of rotation about the -z axis. This value
> represents the angle between the device's y axis and the magnetic north pole.
> — <https://developer.android.com/reference/android/hardware/SensorManager#getOrientation(float[],%20float[])>

Stand the phone upright in a cradle and its +Y axis points at the roof. The
azimuth degenerates — simulation against the AOSP implementation returns a
**constant** 0° for a cradle-mounted phone whatever direction the car faces.
This is a real, filed bug in other apps: OsmAnd
[#21283](https://github.com/osmandapp/OsmAnd/issues/21283) and Organic Maps
[#6410](https://github.com/organicmaps/organicmaps/issues/6410) are both this.

The fix is to read a different axis. The direction the **back of the phone**
faces — the device's −Z, which for a phone stood in a cradle facing the driver
is the way the car is going — is horizontal exactly when +Y is useless, and it
has a second property that matters here: **it does not change when you rotate
the phone in its own plane.** Turning the handset end for end in the cradle,
putting the charging socket on the other side, cannot swap it. So the app uses
that axis for an upright phone and the screen-up axis (and hence the display
rotation at all) only for one lying flat.

**Which axis is a decision, not a per-sample choice.** The two disagree by the
phone's roll angle, so a cradle that leans and sits near the changeover would
flip the arrow through a right angle on every bump — a worse jump than the one
this release set out to remove. It is chosen once, stored, and reconsidered
only after the current axis has been unusable for a full second, which means
the phone was moved rather than jostled.

## Arbitrate, do not blend

Every mature open-source nav app converged on the same answer, independently:

- **Organic Maps** `libs/drape_frontend/my_position_controller.cpp`: a GPS fix
  above `kMinSpeedThresholdMps = 0.7` (2.5 km/h) sets the direction and stamps a
  timer; the compass is locked out until `kGpsBearingLifetimeSec = 3.0` has
  passed. **CoMaps** reached the same design seven weeks earlier with a 5 s
  lifetime.
- **OsmAnd** `PointLocationLayer.java`: the arrow uses the GPS bearing only,
  above `BEARING_SPEED_THRESHOLD = 0.1f` m/s. The compass drives a separate
  translucent view cone which is hidden by default the moment a GPS bearing
  exists. It also unregisters the magnetometer entirely above 0.5 m/s.
- **ArduPilot** `AP_AHRS_DCM::use_compass()`: demotes the compass when it
  disagrees with the GPS course by more than **45° for a sustained 2000 ms** —
  the mature version of the same idea, with hysteresis so a transient spike
  never trips it.

Nobody blends the two at the arrow. The compass's job is the standing-still
case; the moment the car is moving, GPS measures the direction of travel from
orbit and nothing in the car can bend it.

The contributor whose report is cited in Organic Maps' own source states the
tuning tension exactly: shortening the lifetime brings the compass back quickly
when you stop, but *"if you make it very small, the map might start twitching
from constant switches during stops."*

## Gate the magnetometer before smoothing it

AOSP's own sensor fusion, `frameworks/native/services/sensorservice/Fusion.cpp`,
throws away readings on field strength alone:

```c
/* The geomagnetic-field should be between 30uT and 60uT.
 * Fields strengths greater than this likely indicate a local magnetic
 * disturbance which we do not want to update into the fused frame. */
static const float MAX_VALID_MAGNETIC_FIELD = 100; // uT
static const float MIN_VALID_MAGNETIC_FIELD = 10;  // uT
```

Earth's field in Brussels is about 49 µT. A steel car body, a speaker magnet, a
suction mount and a charging coil push it outside that window, and *that* is
what produces a 20–40° jump. Neither Organic Maps nor OsmAnd does this check —
and both have open "drunk arrow" bugs.

`TYPE_ROTATION_VECTOR` also carries a per-sample accuracy: `values[4]` is a
95%-confidence heading error bound in radians
(<https://source.android.com/docs/core/interaction/sensors/sensor-types>). It
is the best runtime signal for "do not trust this reading", and it is free.

Then smooth what survives. OsmAnd's filter (`OsmAndLocationProvider.java`) is an
exponential moving average with α = 0.04 applied **separately to sin and cos**,
recovering the angle with `atan2` — which is what makes it correct across the
0/360 wrap. Despite the constant being named `KALMAN_COEFFICIENT` it is a plain
single-pole IIR, and it has not been touched since 2013.

## Magnetic north is not true north

A compass reads magnetic north; a GPS bearing is true north. Mixing them without
correction leaves a constant error. `GeomagneticField.getDeclination()`:

> positive means the magnetic field is rotated east that much from true north

so `trueHeading = magneticHeading + declination`. In Brussels that is
**+2.6°** today (NOAA WMM, 50.8°N 4.3°E). Small compared with the device error,
but free to fix. Organic Maps and CoMaps do not do it at all; OsmAnd does it
once on the first fix and never refreshes.

## How much error to expect

- **5–10°** for a phone compass in the open, measured across 17 devices —
  Bowers (2022), *Compass Errors in Mobile Augmented Reality Navigation Apps*,
  <https://doi.org/10.1007/978-3-031-10467-1_6>
- **Tens of degrees** from local ferrous material and vehicle interference —
  Gade (2016), *The Seven Ways to Find Heading*,
  <https://www.navlab.net/Publications/The_Seven_Ways_to_Find_Heading.pdf>:
  *"Ferrous materials or electromagnetic interference from the vehicle itself
  may give significant errors due to the short distance to the compass."*
- Android's own compatibility rules permit a device's **internal** hard-iron
  offset to be up to **700 µT** — fourteen times Earth's field — and still be a
  compliant phone (CDD §7.3.2).

So: a compass in a car is a coarse instrument. It is worth having for the one
job GPS cannot do — telling you which way you are pointing before you move —
and it should be shown the door as soon as the car is moving.
