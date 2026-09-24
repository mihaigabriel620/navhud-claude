package com.mihai.navhud.map

/**
 * Every id the app adds to the MapLibre style, in one place.
 *
 * These used to be a column of `private const val` inside MapActivity, and on
 * 21 August 2026 two of them ended up holding the same string. MapLibre's
 * `addLayer` throws `CannotAddLayerException` on a duplicate id, so the app
 * crashed on the first frame after the style loaded — every launch, no way in.
 *
 * A list of thirteen constants is exactly the kind of thing a person's eye
 * slides over and a test does not, which is why they live here: this file has
 * no Android dependencies, so [all] can be asserted unique by a unit test that
 * runs on every build. Add an id here and to [all], or the test will not
 * protect it.
 */
object MapIds {

    // sources
    // The route ahead, in two pieces (see RouteLine): NEAR is the arrow to
    // about 2 km on, full resolution, rebuilt as the car moves; FAR is the
    // rest, thinned, rebuilt every 500 m.
    const val ROUTE_NEAR_SOURCE = "navhud-route-near"
    const val ROUTE_FAR_SOURCE = "navhud-route-far"
    const val ALT_SOURCE = "navhud-alts"
    const val CAM_SOURCE = "navhud-cams"

    // layers, in the order they are stacked
    const val ALT_LAYER = "navhud-alts-line"
    const val ROUTE_CASING_FAR = "navhud-route-casing-far"
    const val ROUTE_CASING = "navhud-route-casing"
    const val ROUTE_LAYER_FAR = "navhud-route-line-far"
    const val ROUTE_LAYER = "navhud-route-line"
    const val CAM_LAYER = "navhud-cams-layer"
    // A LocationIndicatorLayer: it has no source, its position is a property.
    const val PUCK_LAYER = "navhud-puck-layer"

    // images
    const val PUCK_ICON = "navhud-puck-icon"
    const val PUCK_DOT_ICON = "navhud-puck-dot"
    const val CAM_ICON = "navhud-cam-icon"

    val sources = listOf(ROUTE_NEAR_SOURCE, ROUTE_FAR_SOURCE, ALT_SOURCE, CAM_SOURCE)

    val layers = listOf(
        ALT_LAYER, ROUTE_CASING_FAR, ROUTE_CASING, ROUTE_LAYER_FAR, ROUTE_LAYER,
        CAM_LAYER, PUCK_LAYER
    )

    val images = listOf(PUCK_ICON, PUCK_DOT_ICON, CAM_ICON)

    val all: List<String> get() = sources + layers + images
}
