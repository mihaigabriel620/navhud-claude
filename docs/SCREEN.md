# Which screen, and how big?

The short answer: **a 2.8" or 3.5" IPS TFT LCD, the brightest you can find
(look for 800–1000 cd/m² or higher), mounted on the dash as a direct-view
second screen.** Not OLED. Not a projector. And if you want a real reflected
head-up image, add a **separate combiner** rather than bouncing it off the
windscreen.

The rest of this explains why, because the reasoning decides several other
things about the build.

> **What this build actually uses.** A 4.0" ST7796S, 480×320, lying flat on the
> dash and read as a reflection in the windscreen — which is option 2 below
> without the combiner, and therefore the honest expectation is: excellent at
> night and dusk, readable on an overcast day, washed out in direct sun. That is
> a deliberate trade for not having a screen in your line of sight, and it is
> the reason the theme is amber on black: on a reflection, black is
> *transparent*, so the only thing on the glass is the lit pixels.
>
> Two consequences the firmware handles rather than ignores. The reflection
> flips the image, so the panel is mirrored in hardware. And a dash is rarely
> level with the screen on it, so the reflection is a trapezoid — see
> **Keystone** below, and `docs/BUILD.md` for how the correction works.
>
> If you later add a combiner, everything else stays the same; you will just be
> able to read it at noon.

## The arithmetic that settles it

A head-up display is not competing with darkness. It is competing with the road
in front of you. Daytime ambient luminance in a car reaches around
**10,000 cd/m²**, and the image has to sit at roughly **1.15 to 1.5 times** the
background before you can read it comfortably —
[Analog Devices' HUD design note](https://www.analog.com/en/resources/design-notes/come-rain-or-come-shine1--automotive-headsup-displays-must-work-everyday.html)
gives both numbers.

Now the losses. Clear glass reflects about **4% per surface** at a shallow
angle. So bouncing a display off a windscreen delivers roughly **5%** of its
light to your eye. Run that backwards:

| | |
|---|---|
| Needed at the eye, bright day | ~2,000–3,000 cd/m² over background |
| Windscreen reflectance | ~5% |
| **Required panel brightness** | **~40,000–60,000 cd/m²** |
| A typical hobby ILI9341 module | **200–300 cd/m²** |

That is a factor of two hundred. It is not a matter of picking a slightly
better module — it is why production HUDs use a purpose-built projection unit
with a high-power LED backlight and a folded mirror path, not a screen pointed
at the glass.

So there are three honest options, and one that is not.

## 1. Direct view on the dash — recommended

Mount the screen where you can see it, in your peripheral vision above the
instrument binnacle or on top of the dash. No reflection, no optical loss, no
ghosting. Every one of those 300 nits reaches your eye.

This is not technically a head-up display, and it is what almost every
successful DIY build actually ends up being. It works in full sun, it costs
nothing extra, and the amber-on-black theme was designed for exactly this.

**Size: 3.5", 480×320** (ILI9488). Large enough to read at a glance from 70 cm,
small enough not to block anything. The 2.8" works too and looks tidier; the
sketch supports both — see `arduino/config/User_Setup.h`.

**Do not go above 3.5"** for a dash-top mount. A 5" screen at that distance is
a television in your field of view, and at night it will light up the
windscreen.

## 2. A proper combiner — if you want a real HUD

A combiner is a small piece of glass or acrylic with a partially reflective
coating, angled at about 30° between you and the windscreen. This is what
Peugeot, Citroën and MINI use, and what the pop-up panel in an aftermarket HUD
is.

Two things it fixes:

- **Reflectance.** A combiner film reflects **20–30%** instead of 4%, which is
  a five- to seven-fold improvement — the difference between "unreadable at
  noon" and "readable except in direct low sun".
- **Ghosting.** A windscreen has two glass surfaces, so it gives you **two
  images** a few centimetres apart. Production cars hide this with a
  wedge-shaped interlayer in the laminate; your car does not have one. A
  single-surface combiner has one reflection and one image.

**Size: 2.4" or 2.8" panel.** The virtual image appears roughly twice as far
away as the panel and about the same angular size, so a 2.8" screen at 30 cm
gives an image about the size of a postcard at arm's length. That is right.
Bigger starts to obstruct the road, which is the opposite of the point.

**Panel: the brightest transmissive IPS TFT you can get.** Search for
"sunlight readable TFT" or "high brightness TFT" — 800 to 1500 cd/m² modules
exist at 2.4"–3.5" for €25–60, against €8 for the standard one. With a
combiner, that gets you a usable daytime image. Optical bonding helps too, by
removing an internal air gap that scatters sunlight back at you.

**Mount it in a matte black hood.** Any stray light falling on the panel is
subtracted directly from your contrast.

Expect: excellent at night and dusk, good on an overcast day, marginal with the
sun low and behind you. That is the honest range for a DIY combiner, and it is
still genuinely useful — the times you most want to keep your eyes up are the
times it works best.

## 3. OLED — no

Two reasons, either of which is enough.

**Burn-in.** This display shows a speed-limit roundel in the same place, with
the same digits, for hours at a time, every day. That is the textbook worst
case for OLED. Within months there will be a ghost "50" permanently visible.

**Brightness.** Small OLED modules peak at a few hundred nits *for a small
bright area*, and much less full-field, because the panel is power- and
thermal-limited. A strongly backlit LCD showing mostly black — which this theme
is — puts all of its backlight behind the few lit pixels. For this specific
content, LCD wins on the one axis that matters.

Add that an OLED in a car in a Belgian August sits at 70 °C on the dash, which
is at the edge of its rating, and it is not a close call.

## 4. Projector — no

Pico projectors are 100–500 lumens spread over a large image, which works out
dimmer per unit area than the LCD you already have. Worse, a projector needs a
*screen* at a fixed focal distance; a windscreen is neither. You would be
projecting onto glass that light passes straight through.

The laser-scanning units in high-end factory HUDs are a different technology
entirely — a MEMS mirror drawing the image, with a real optical path behind
it — and they are not something you can buy as a module.

## What to buy

**The straightforward build, €8:**
2.8" ILI9341 SPI TFT, 320×240, dash-mounted. This is what the sketch defaults
to and what `docs/BUILD.md` wires up.

**The nicer build, about €50:**
3.5" high-brightness IPS TFT (ILI9488, 480×320, 800+ nits), dash-mounted, in a
printed hood.

**The real HUD, about €70:**
2.8" high-brightness IPS TFT plus a combiner — either an acrylic sheet with
automotive HUD reflective film, or a salvaged combiner from a cheap aftermarket
HUD unit, which is often the easiest way to get a properly coated one.

Whichever you pick, keep the E60 theme's black background. On a reflection,
black is *transparent* — the only thing you see is the lit pixels. A pale
interface would put a glowing rectangle across your view of the road.

## Keystone, and what it costs

A screen lying flat and a windscreen raked at 25–30° are not parallel, so the
reflected rectangle arrives as a trapezoid. The app's **Align the screen** page
corrects it, and the correction is applied to coordinates before anything is
rasterised rather than to a finished image, because a 480×320 framebuffer at
16 bpp is 307,200 bytes and the microcontroller has about 40 KB of heap.

The part worth knowing before you dial in a large correction: **every pixel of
keystone is a pixel of screen you no longer have.** Shrinking the top edge by a
quarter of the width leaves 75 % of the panel in use, and on a 4" screen read
as a dim reflection that is a real loss of legibility. The alignment page shows
the figure live for exactly this reason.

So the order to do things in is: mount the panel as close to parallel with the
windscreen as the dash allows *first* — a printed wedge costs nothing and loses
no pixels — and use the keystone for what is left. The sliders reach 25 % and
the per-corner nudges 35 %, which is far more than a sane mount ever needs.

There is one more thing a windscreen does that no amount of keystone fixes:
**ghosting.** Laminated glass has two surfaces, so it gives you two images a
few centimetres apart. Production cars hide this with a wedge-shaped interlayer
in the laminate; yours does not have one. A combiner is the only real cure.

## Sources

- [Automotive head-up displays must work every day — Analog Devices](https://www.analog.com/en/resources/design-notes/come-rain-or-come-shine1--automotive-headsup-displays-must-work-everyday.html)
  (ambient luminance and the contrast ratio the image has to beat)
- [Sunlight readable TFT displays — DisplayModule](https://www.displaymodule.com/blogs/knowledge/sunlight-readable-tft-displays-nits-brightness-contrast-anti-glare)
- [Sunlight readable display: optical bonding](https://saiweiglass.com/blog/sunlight-readable-display-optical-bonding/)
- [3M automotive HUD films](https://www.3m.com/3M/en_US/oem-tier-us/applications/human-machine-interface-solutions/head-up-display/)
- [Automotive HUD TFT LCD guide](https://successlcd.com/automotive-hud-tft-lcd-display-technology/)
