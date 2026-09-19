package com.mihai.navhud.map

/**
 * Which way the arrow on the map points.
 *
 * Split out of MapActivity as a pure function for one reason: it is the answer
 * to "when I turn my phone the arrow does not turn at all", and while it lived
 * as three lines inside a three-hundred-line Activity method there was nothing
 * a test could call. Version 1.18 shipped a changelog claiming the compass
 * turned the arrow while the road bearing was quietly overriding it, and a
 * green test suite could not tell the difference.
 *
 * The arrow and the *map* deliberately use different headings:
 *
 *  - The map wants the road's own bearing when the car is snapped to a road and
 *    moving. That is stable, so the view does not wobble with every sensor
 *    twitch while you drive down a straight street.
 *  - The arrow wants the compass, because that is what a compass is for. In a
 *    cradle, with the mounting offset learned, the two agree and the arrow
 *    points up the road as before; pick the phone up and turn it and the arrow
 *    turns, which is both what was asked for and how you check the thing works.
 */
object Marker {

    /**
     * @param compassHeading   the fused heading, compass-driven
     * @param mapHeading       what the camera is using: road bearing, or GPS
     * @param compassDriving   [HeadingFusion.usingCompass] -- fresh, trusted,
     *                         and under the speed changeover
     * @param mountKnown       the mounting offset has been learned or restored;
     *                         without it the compass reads the *phone's*
     *                         heading rather than the car's, which would put
     *                         the arrow at an angle to a correctly aligned map
     */
    fun headingFor(
        compassHeading: Double?,
        mapHeading: Double?,
        compassDriving: Boolean,
        mountKnown: Boolean
    ): Double? =
        if (compassDriving && mountKnown) (compassHeading ?: mapHeading) else mapHeading
}
