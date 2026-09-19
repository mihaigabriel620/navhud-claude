package com.mihai.navhud.hud

/**
 * The same square-to-quad map the firmware uses, so the preview on the phone
 * is the shape that will actually be on the glass.
 *
 * This is a second implementation of hud_geom.h's `rebuild`/`map`, which is a
 * thing worth being uneasy about: two copies of a formula drift. The defence is
 * HomographyTest, which checks a set of points against values produced by
 * running the C++ -- so if one side changes, the Kotlin side fails.
 *
 * Heckbert's closed form, "Fundamentals of Texture Mapping and Image Warping"
 * (1989) section 2.2. The source is always the unit square, so there is no 8x8
 * solve:
 *
 *     X = (a*u + b*v + c) / (g*u + h*v + 1)
 *     Y = (d*u + e*v + f) / (g*u + h*v + 1)
 */
class Homography private constructor(
    private val a: Double, private val b: Double, private val c: Double,
    private val d: Double, private val e: Double, private val f: Double,
    private val g: Double, private val h: Double,
    private val w: Double, private val hgt: Double,
    val identity: Boolean
) {

    /** Layout point in, panel point out, into `out` as [x, y]. */
    fun map(x: Double, y: Double, out: DoubleArray) {
        if (identity) { out[0] = x; out[1] = y; return }
        val u = x / w
        val v = y / hgt
        var den = g * u + h * v + 1.0
        if (den < 0.05 && den > -0.05) den = if (den < 0) -0.05 else 0.05
        out[0] = (a * u + b * v + c) / den
        out[1] = (d * u + e * v + f) / den
    }

    companion object {
        private const val EPS = 1e-6

        /** Null when the quad is degenerate or turned inside out. */
        fun of(k: Keystone): Homography? = of(
            DoubleArray(4) { k.quadX(it).toDouble() },
            DoubleArray(4) { k.quadY(it).toDouble() },
            Keystone.SCR_W.toDouble(), Keystone.SCR_H.toDouble(),
            k.isIdentity
        )

        fun of(qx: DoubleArray, qy: DoubleArray, w: Double, hgt: Double,
               identity: Boolean): Homography? {
            if (identity) {
                return Homography(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, w, hgt, true)
            }
            val dx1 = qx[1] - qx[2]; val dx2 = qx[3] - qx[2]
            val dx3 = qx[0] - qx[1] + qx[2] - qx[3]
            val dy1 = qy[1] - qy[2]; val dy2 = qy[3] - qy[2]
            val dy3 = qy[0] - qy[1] + qy[2] - qy[3]

            val a: Double; val b: Double; val c: Double
            val d: Double; val e: Double; val f: Double
            val g: Double; val hh: Double

            if (near0(dx3) && near0(dy3)) {
                a = qx[1] - qx[0]; b = qx[2] - qx[1]; c = qx[0]
                d = qy[1] - qy[0]; e = qy[2] - qy[1]; f = qy[0]
                g = 0.0; hh = 0.0
            } else {
                val den = dx1 * dy2 - dx2 * dy1
                if (near0(den)) return null
                g = (dx3 * dy2 - dx2 * dy3) / den
                hh = (dx1 * dy3 - dx3 * dy1) / den
                a = qx[1] - qx[0] + g * qx[1]
                b = qx[3] - qx[0] + hh * qx[3]
                c = qx[0]
                d = qy[1] - qy[0] + g * qy[1]
                e = qy[3] - qy[0] + hh * qy[3]
                f = qy[0]
            }
            return Homography(a, b, c, d, e, f, g, hh, w, hgt, false)
        }

        private fun near0(v: Double) = v > -EPS && v < EPS
    }
}
