package com.mihai.navhud

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mihai.navhud.alerts.CameraPolicy
import com.mihai.navhud.alerts.SpeedCamera
import com.mihai.navhud.location.FixFilter
import com.mihai.navhud.location.Fixes
import com.mihai.navhud.map.HeadingFusion
import com.mihai.navhud.map.Compass
import com.mihai.navhud.map.MapIds
import com.mihai.navhud.nav.AreaRoads
import com.mihai.navhud.map.NavCamera
import com.mihai.navhud.map.FreeDriveMotion
import com.mihai.navhud.map.PuckMotion
import com.mihai.navhud.map.RoadLock
import com.mihai.navhud.map.RouteLine
import com.mihai.navhud.nav.RoadSigns
import com.mihai.navhud.nav.RoadFeature
import com.mihai.navhud.nav.Route
import com.mihai.navhud.nav.RouteTrait
import com.mihai.navhud.ui.JunctionView
import com.mihai.navhud.ui.LaneView
import com.mihai.navhud.ui.ManeuverView
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.style.layers.LayoutPropertyValue
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PaintPropertyValue
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.TransitionOptions
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.android.style.expressions.Expression
import org.maplibre.geojson.Point

/**
 * The driving screen.
 *
 * The map is MapLibre rather than a raster tile view because the two things
 * that make a nav map feel right -- pitch and smooth rotation -- are simply not
 * possible with raster tiles. The style is ours (res/raw/style_e60.json) on
 * OpenFreeMap's vector tiles, which need no key at all.
 */
class MapActivity : AppCompatActivity() {

    companion object {

        /**

         * Whether the crash dialog has already been shown in this process.

         *

         * The file itself is deleted on a finishing onDestroy, so a config

         * change -- a head unit switching to night mode, which this manifest

         * does not list in configChanges -- re-shows it rather than losing the

         * one artefact that turns "it closed" into a line number.

         */

        @JvmStatic var crashShown = false

        /**
         * Outlives the activity (it holds only the application context), so a
         * night-mode recreation does not start the current chunk over.
         */
        private var offlineRoutes: com.mihai.navhud.map.OfflineRoutes? = null

        /** Fast enough that a GPS bearing is the car's real direction. */
        /**
         * Learn the mounting offset from this speed up. 14 km/h.
         *
         * Was 25 km/h, from when the compass only had to be right while
         * parked. Now that the compass turns the arrow all the way up to
         * 70 km/h, the offset is the thing keeping it honest, so it wants
         * every fix that carries a trustworthy bearing.
         */
        const val LEARN_MIN_MPS = 4.0

        /**
         * A traffic run shorter than this is folded into its neighbour.
         *
         * About the width the route line is drawn at, at navigation zooms --
         * so nothing that could have been seen is lost.
         */
        const val MIN_TRAFFIC_RUN_M = 40.0

        /**
         * Floor on the gap between camera frames, below one vsync interval on
         * any panel we meet -- so vsync alone decides the rate.
         *
         * It was 30 ms, and on a 60 Hz panel that is not a 30 fps cap: 30 ms
         * is *just* under two vsyncs, so the gate lets through two frames,
         * then three, then two. The average is 33 fps and it arrives as an
         * uneven skip, which reads worse than either a flat 30 or a flat 60
         * would have. Moving to Choreographer was meant to stop beating
         * against the display clock; this gate was still doing it, one layer
         * up.
         */
        const val FRAME_MIN_NS = 8_000_000L

        /**
         * Below these, a change is not worth a redraw.
         *
         * Sized against the *worst* case, which is a 3x phone at the zoom the
         * camera boosts to on a junction approach (18.8). There a metre is
         * about twelve physical pixels, so 20 cm would have been five of them
         * -- the marker advancing in visible steps at crawl speed. Five
         * centimetres is under a pixel there, and still enormous next to what
         * these filters settle to: NavCamera and the puck both smooth
         * exponentially, so a stationary car converges to nanometres within a
         * couple of seconds and stops asking for frames altogether.
         */
        const val PUCK_EPSILON_M = 0.05
        const val PUCK_EPSILON_DEG = 0.05
        const val CAM_EPSILON_M = 0.05
        const val CAM_EPSILON_DEG = 0.03
        const val CAM_EPSILON_ZOOM = 0.0005

        /** How hard each fix pulls the learned offset. */
        const val LEARN_BLEND = 0.12

        /**
         * Reject a rotation-vector sample whose own error bound is worse than
         * this, radians. 0.35 rad is 20 degrees — past that the sensor is
         * telling you it does not know, and it is right.
         */
        const val MAX_HEADING_ERROR_RAD = 0.35f

        /** Recompute the declination after moving this far, metres. */
        const val DECLINATION_REFRESH_M = 50_000.0

        /** How long the chosen heading axis must be unusable before switching. */
        const val AXIS_SWITCH_AFTER_MS = 1000L        // 25 km/h

        /**
         * Ceiling on MapLibre's ambient tile cache. 512 MB.
         *
         * The default is 50 MB, which is about one city at nav zooms: drive
         * across town and the tiles behind you have already been evicted, so
         * the road you came in on is blank on the way back. Raising it makes
         * driving a road the thing that downloads it -- the cache fills with
         * exactly the area you use, without anyone picking regions off a map
         * beforehand, and a tunnel or a dead spot draws from it instead of
         * from nothing. Half a gigabyte is a rounding error on a head unit
         * and roughly a country's worth of the roads one car actually drives.
         */
        const val AMBIENT_CACHE_BYTES = 512L * 1024 * 1024

        private const val REQ_SEARCH = 11

        private const val TAG = "MapActivity"

        /** Show the closure chip from this far out. */
        private const val ROADWORKS_SHOW_M = 5000

        // Style ids live in MapIds, where a unit test can prove they are all
        // different from each other. See the note in that file.
        private const val ROUTE_NEAR_SOURCE = MapIds.ROUTE_NEAR_SOURCE
        private const val ROUTE_FAR_SOURCE = MapIds.ROUTE_FAR_SOURCE
        private const val ALT_SOURCE = MapIds.ALT_SOURCE
        private const val ALT_LAYER = MapIds.ALT_LAYER
        private const val ROUTE_CASING = MapIds.ROUTE_CASING
        private const val ROUTE_CASING_FAR = MapIds.ROUTE_CASING_FAR
        private const val ROUTE_LAYER = MapIds.ROUTE_LAYER
        private const val ROUTE_LAYER_FAR = MapIds.ROUTE_LAYER_FAR
        private const val PUCK_LAYER = MapIds.PUCK_LAYER
        private const val PUCK_ICON = MapIds.PUCK_ICON
        private const val PUCK_DOT_ICON = MapIds.PUCK_DOT_ICON
        private const val CAM_SOURCE = MapIds.CAM_SOURCE
        private const val CAM_LAYER = MapIds.CAM_LAYER
        private const val CAM_ICON = MapIds.CAM_ICON
        private const val CAM_ANPR_ICON = MapIds.CAM_ANPR_ICON
        /** Cap the drawn polyline; a full geometry can be many thousands of points. */
        private const val MAX_POLY_POINTS = 1500

        /**
         * The route line starts under the arrow, Waze-style: nothing is drawn
         * behind the car. The first [NEAR_WINDOW_M] or so ahead are one
         * full-resolution source, rebuilt from the frame loop; the rest is a
         * second, thinned source whose start moves on in [FAR_STEP_M] steps,
         * so it is rebuilt every 500 m rather than every frame. The near piece
         * ends exactly where the far one starts. See RouteLine.
         */
        const val NEAR_WINDOW_M = 2000.0
        const val FAR_STEP_M = 500.0

        /**
         * Rebuild the near piece at most this often, ns (~15 Hz), and only
         * after the arrow has moved this far. The cut sits under the arrow,
         * which covers a couple of metres of it at any nav zoom.
         */
        const val ROUTE_NEAR_MIN_NS = 66_000_000L
        const val ROUTE_NEAR_EPSILON_M = 0.5

        /** No `$IMU` for this long and the HUD's sensor is treated as gone. */
        private const val IMU_TIMEOUT_MS = 1500L

        /**
         * A fix older than this is gone, and the marker coasts.
         *
         * Three seconds is three missed fixes at 1 Hz: long enough that a
         * single dropped one is still extrapolated through, short enough that
         * a tunnel switches to running on the car's speed promptly. Until then
         * the marker is extrapolated from the fix continuously -- see
         * PuckMotion.
         */
        private const val FIX_HOLD_MS = 3000L

        /**
         * How long to keep the marker moving on nothing but the car's speed.
         *
         * Freezing when the fix goes is the honest answer in a car park. It is
         * the wrong one in a tunnel, where the car is demonstrably still doing
         * 80 and the driver still needs to know which exit is coming. So the
         * marker coasts instead, and the two numbers differ because the two
         * situations differ in how much the dead reckoning can be trusted:
         *
         * - on a route there is a line to run along, so the only unknown is
         *   how far -- three minutes covers the Mont Blanc tunnel and most of
         *   what Europe has under a mountain;
         * - free driving there is at best the last matched road, and past its
         *   end only the last heading, so every bend is error that never
         *   comes back. Thirty seconds is an underpass or a covered junction,
         *   and past that the marker has invented enough.
         *
         * Catching up afterwards, and the 300 m past which the marker simply
         * jumps, live in PuckMotion.
         */
        const val COAST_ON_ROUTE_MS = 180_000L
        const val COAST_FREE_MS = 30_000L
    }

    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null

    private lateinit var maneuverView: ManeuverView
    private lateinit var laneView: LaneView
    private lateinit var distView: TextView
    private lateinit var streetView: TextView
    private lateinit var speedGauge: com.mihai.navhud.ui.SpeedGauge
    private lateinit var tripMain: TextView
    private lateinit var tripSub: TextView
    private lateinit var searchIcon: ImageView
    private lateinit var statusChip: TextView
    private lateinit var perfOverlay: TextView
    private lateinit var arrowDebugView: TextView
    private lateinit var waitingGpsView: View

    // ---- frame timing, for when "it stutters" needs to become a number ----
    private var frameCount = 0
    private var frameWorstMs = 0.0
    private var frameSumMs = 0.0
    private var framesDrawn = 0
    private var frameWindowStartNs = 0L
    /** Rolling summary, published so the Setup screen can show it too. */
    @Volatile private var perfLine = "--"
    private lateinit var quickScrim: View
    private lateinit var tripPill: View
    private lateinit var cancelRouteButton: TextView
    private lateinit var voiceLabel: TextView
    private lateinit var statusView: TextView
    private lateinit var followButton: ImageView
    private lateinit var voiceButton: ImageView
    private lateinit var routesTile: View
    private lateinit var routesLabel: TextView
    private lateinit var instructionCard: View
    private lateinit var speedCluster: View
    private lateinit var junctionBox: LinearLayout
    private lateinit var roadworksChip: TextView
    private lateinit var junctionView: JunctionView
    private lateinit var exitNumberView: TextView
    private lateinit var exitDestView: TextView
    private lateinit var cameraBox: LinearLayout
    private lateinit var cameraLabel: TextView
    private lateinit var cameraDist: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val navCamera = NavCamera()
    private var lastCamFrameMs = 0L

    private var drawnRoute: Route? = null
    private var drawnCameras: List<SpeedCamera> = emptyList()
    private var following = true
    /** A pinch / two-finger tilt is in progress: the frame loop leaves the camera alone. */
    private var scaling = false
    private var shoving = false
    private var voiceOn = true

    // ---- the map is a moving map whether or not a route is running ---------
    //
    // It used to read HudService.lastLocation and nothing else, so pressing
    // Stop left it frozen on the final fix. Waze and Google Maps both keep
    // showing you moving with no destination set, and so does this now: the
    // screen holds its own GPS subscription and only *prefers* the service's
    // fix while one is arriving, which keeps the marker and the route tracker
    // in step during navigation.
    private val ownFilter = FixFilter()
    @Volatile private var ownFix: Location? = null
    private var locationManager: LocationManager? = null

    private val fusion = HeadingFusion()

    /** The stored heading is adopted once per run, not re-applied every frame. */
    private var seededFromParked = false

    /** See Prefs.snapToRoad. Read on resume; this loop runs at 30 Hz. */
    private var snapToRoad = true
    private var sensors: SensorManager? = null
    /**
     * Gravity direction in device axes; Android reports it pointing up.
     *
     * Raw device axes, deliberately: Android never rotates sensor readings to
     * follow the screen, and neither do we. Projecting the turn rate onto this
     * vector is what makes the heading independent of how the phone is held --
     * flat on the dash, upright in a cradle, portrait, landscape, upside down.
     */
    private val gravity = floatArrayOf(0f, 0f, 9.81f)
    private val rotM = DoubleArray(9)
    private val absM = DoubleArray(9)
    @Volatile private var hasAbsolute = false
    private var lastRotNs = 0L
    private var lastAbsNs = 0L
    private var deltaSensorType = -1

    /** Is the ambient field plausibly the Earth's? See Compass.fieldPlausible. */
    @Volatile private var fieldPlausible = true

    /** Magnetic-to-true correction for where we are, degrees. */
    /**
     * Seeded from the last value we computed, not from zero.
     *
     * It is only updated once a GPS fix arrives, and the whole point of the
     * compass is the period *before* that -- so starting at zero meant the
     * standing-still arrow was magnetic rather than true (six degrees out in
     * Romania) and disagreed with the calibration screen, which does read the
     * stored value, until the first fix landed and stepped it.
     */
    @Volatile private var declinationDeg = 0.0
    private var declinationAtLat = Double.NaN
    private var declinationAtLon = Double.NaN
    @Volatile
    private var axisBadSinceMs = 0L
    private var lastImuAtMs = 0L
    private var cameraLoopFailed = false

    /** Live compass reading of the chosen device axis, before the offset. */
    @Volatile private var deviceAzimuth = Double.NaN
    @Volatile
    private var headingOffset = 0.0
    @Volatile
    private var headingAxis = Compass.AXIS_TOP
    @Volatile
    private var learnedThisDrive = false
    @Volatile
    private var savedOffset = 0.0
    private var gravitySamples = 0
    private var lastGyroNs = 0L
    private var lastFusedFixNs = 0L
    @Volatile private var externalImu = false
    /** False on a head unit with no gyroscope, which is a normal setup. */
    private var phoneGyroPresent = false

    /** Opening the app is a fresh start; returning to it is not. */
    private var firstResume = true

    /** Last route generation we swung the camera round for. */
    private var facedRouteGeneration = -1

    /** Smoothed drawing state, so the marker glides instead of stepping. */
    private var puckAlong = Double.NaN
    private var puckBearing = Double.NaN
    private val routeMotion = PuckMotion(COAST_ON_ROUTE_MS / 1000.0)
    private val freeMotion = FreeDriveMotion(COAST_FREE_MS / 1000.0, FreeTracker.SNAP_TRUST_M)
    /** The route [routeMotion]'s distances belong to. */
    private var motionRoute: Route? = null
    /** The road the arrow is on off the route line; fed one GPS fix at a time. */
    private val roadLock = RoadLock()
    /** The fix [roadLock] last saw. */
    private var lockedFixNs = 0L
    /** Whether the fix being drawn came from GPS, and when the last fresh one did. */
    private var fixIsGps = false
    private var lastGpsAtMs = 0L
    /** "Waiting for GPS" is up: no fix worth drawing an arrow at. */
    private var waitingForGps = false
    /** What the puck layer's visibility was last set to; null = not since the style loaded. */
    private var puckHidden: Boolean? = null

    private val amber = Color.parseColor("#FF9D00")
    private val amberDim = Color.parseColor("#C07200")
    private val red = Color.parseColor("#FF3B20")

    override fun onCreate(savedInstanceState: Bundle?) {
        // MapLibre must be initialised before any MapView is inflated.
        MapLibre.getInstance(this)
        // ...and the cache limit before the file source starts serving tiles.
        //
        // runCatching because this is not worth an activity that will not
        // open: the failure mode of not setting it is a smaller cache, which
        // is exactly where we were before.
        runCatching {
            OfflineManager.getInstance(this)
                .setMaximumAmbientCacheSize(AMBIENT_CACHE_BYTES,
                    object : OfflineManager.FileSourceCallback {
                        override fun onSuccess() {
                            android.util.Log.i("NavHUD", "tile cache limit set to 512 MB")
                        }
                        override fun onError(message: String) {
                            android.util.Log.w("NavHUD",
                                "could not raise the tile cache limit: $message")
                        }
                    })
        }
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_map)

        mapView = findViewById(R.id.map)
        mapView.onCreate(savedInstanceState)

        maneuverView = findViewById(R.id.maneuver)
        laneView = findViewById(R.id.lanes)
        distView = findViewById(R.id.dist)
        streetView = findViewById(R.id.street)
        speedGauge = findViewById(R.id.speedGauge)
        statusView = findViewById(R.id.mapStatus)
        statusChip = findViewById(R.id.statusChip)
        perfOverlay = findViewById(R.id.perfOverlay)
        arrowDebugView = findViewById(R.id.arrowDebugLine)
        waitingGpsView = findViewById(R.id.waitingForGps)
        followButton = findViewById(R.id.follow)
        voiceButton = findViewById(R.id.voiceButton)
        voiceLabel = findViewById(R.id.voiceState)
        routesTile = findViewById(R.id.tileRoutes)
        routesLabel = findViewById(R.id.routesLabel)
        tripMain = findViewById(R.id.tripMain)
        tripSub = findViewById(R.id.tripSub)
        quickScrim = findViewById(R.id.quickScrim)
        tripPill = findViewById(R.id.tripPill)
        // The map's OSM credit sits on the pill's top edge, wherever layout puts it.
        tripPill.addOnLayoutChangeListener { _, _, top, _, _, _, oldTop, _, _ ->
            if (top != oldTop) placeAttribution()
        }
        cancelRouteButton = findViewById(R.id.cancelRoute)
        instructionCard = findViewById(R.id.instructionCard)
        junctionBox = findViewById(R.id.junction)
        roadworksChip = findViewById(R.id.roadworks)
        junctionView = findViewById(R.id.junctionView)
        exitNumberView = findViewById(R.id.exitNumber)
        exitDestView = findViewById(R.id.exitDest)
        cameraBox = findViewById(R.id.cameraAlert)
        cameraLabel = findViewById(R.id.cameraLabel)
        cameraDist = findViewById(R.id.cameraDist)

        speedCluster = speedGauge

        // The whole left half of the pill, not just the icon. A 52dp glyph is
        // a fine target when you know it is there; the point of this change is
        // that you should not have to know.
        findViewById<View>(R.id.searchBlock).setOnClickListener { openSearch() }
        searchIcon = findViewById(R.id.go)

        // ---- quick actions --------------------------------------------------
        findViewById<ImageView>(R.id.quickActions).setOnClickListener { showQuickActions() }
        findViewById<ImageView>(R.id.quickClose).setOnClickListener { hideQuickActions() }
        quickScrim.setOnClickListener { hideQuickActions() }
        // The sheet itself must swallow the tap, or every press inside it also
        // lands on the scrim behind and closes the thing you were pressing.
        findViewById<View>(R.id.quickSheet).setOnClickListener { }

        findViewById<View>(R.id.tileSearch).setOnClickListener {
            hideQuickActions()
            openSearch()
        }
        findViewById<View>(R.id.tileSettings).setOnClickListener {
            hideQuickActions()
            startActivity(Intent(this, MainActivity::class.java))
        }
        cancelRouteButton.setOnClickListener {
            hideQuickActions()
            // Ends the route, not the app. Speed, limits and camera warnings
            // carry on, which is what "stop navigating" means in every other
            // nav app and what it should have meant here.
            startService(Intent(this, HudService::class.java)
                .setAction(HudService.ACTION_CLEAR_ROUTE))
            // Wipe the overlays here rather than leaving it to refresh(). The
            // service clears the route on this same thread within a millisecond,
            // so by the next refresh both sides read null, "changed?" is false
            // and the amber line would stay painted for the rest of the drive.
            style?.let { s -> drawRoute(s, null); drawCameras(s, emptyList()) }
            drawnRoute = null
            drawnCameras = emptyList()
            following = true
            navCamera.reset()
            forgetDrawnCamera()
            updateFollowButton()
        }
        followButton.setOnClickListener {
            following = !following
            if (following) { navCamera.reset(); forgetDrawnCamera() }
            updateFollowButton()
        }
        findViewById<View>(R.id.tileVoice).setOnClickListener {
            voiceOn = !voiceOn
            HudService.voiceEnabled = voiceOn
            Prefs.setVoice(this, voiceOn)
            updateVoiceButton()
        }
        // Bound before the listener that reads them. It only fires after
        // onCreate so the old order was safe, but it was one reorder away from
        // a lateinit crash, and nothing in the code said so.
        routePicker = findViewById(R.id.routePicker)
        routeCards = findViewById(R.id.routeCards)
        routesTile.setOnClickListener {
            hideQuickActions()
            if (routePicker.visibility == View.VISIBLE) hideRoutePicker() else showRoutePicker()
        }
        findViewById<ImageView>(R.id.routePickerClose).setOnClickListener { hideRoutePicker() }

        voiceOn = Prefs.voice(this)
        HudService.voiceEnabled = voiceOn
        updateFollowButton()
        updateVoiceButton()

        // Cap the render thread at the panel's own rate, and never above 60.
        //
        // It was a flat 30. MapRenderer implements the cap by sleeping after
        // each render until 1/fps has passed, and the camera moves every
        // vsync -- so on a 60 Hz head unit the renderer took every other
        // update, and which one depended on how long the sleep ran: the map
        // advanced two frames, then one, then two. That beat is the stutter.
        // Matching the panel leaves vsync as the only clock; capping at 60
        // still stops a 90/120 Hz phone rendering twice as often as it needs.
        val panelHz = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.refreshRate
            else @Suppress("DEPRECATION") windowManager.defaultDisplay.refreshRate
        }.getOrNull() ?: 60f
        runCatching { mapView.setMaximumFps(Math.round(panelHz).coerceIn(30, 60)) }
        mapView.getMapAsync { m ->
            map = m
            // The OpenFreeMap source is capped at zoom 14 and navigation sits
            // at 15-19, so every displayed tile is already an overzoomed z14.
            // The default prefetch delta of 4 therefore fetches z10 parents,
            // which cover the screen thousands of times over, are useless as a
            // placeholder, and still cost network, decode and cache writes on
            // a slow SoC. One level up is the one that actually helps.
            runCatching { m.prefetchZoomDelta = 1 }
            m.uiSettings.apply {
                // Only once follow mode is off; see the gesture listeners below.
                isRotateGesturesEnabled = !following
                isTiltGesturesEnabled = true
                isCompassEnabled = false
                isAttributionEnabled = true
                isLogoEnabled = false
            }
            placeAttribution()
            // A two-finger drag could flatten the map to a plan view, and once
            // it was flat there was no obvious way back. The gesture stays --
            // some people want a shallower view on a motorway and a steeper
            // one in town -- but it can no longer leave the range the screen
            // is designed for.
            m.setMinPitchPreference(NavCamera.TILT_MIN)
            m.setMaxPitchPreference(NavCamera.TILT_MAX)
            // Follow mode, the Waze way. Any finger on the map used to drop it
            // -- a pinch to see a little further, a tap near the arrow -- and
            // then the map sat still while the car drove off it. Now only a
            // one-finger pan leaves follow mode. A pinch or a two-finger tilt
            // while following adjusts the view and is kept (NavCamera's user
            // zoom offset and tilt); the frame loop stays off the camera while
            // one is in progress so the two do not fight. Rotation is off
            // while following, since the map turns with the car.
            m.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                override fun onMoveBegin(d: org.maplibre.android.gestures.MoveGestureDetector) {
                    if (d.pointersCount == 1 && following) {
                        following = false
                        updateFollowButton()
                    }
                }
                override fun onMove(d: org.maplibre.android.gestures.MoveGestureDetector) {}
                override fun onMoveEnd(d: org.maplibre.android.gestures.MoveGestureDetector) {}
            })
            m.addOnShoveListener(object : MapLibreMap.OnShoveListener {
                override fun onShoveBegin(d: org.maplibre.android.gestures.ShoveGestureDetector) {
                    shoving = true
                }
                override fun onShove(d: org.maplibre.android.gestures.ShoveGestureDetector) {}
                override fun onShoveEnd(d: org.maplibre.android.gestures.ShoveGestureDetector) {
                    shoving = false
                    if (following) {
                        navCamera.setUserTilt(m.cameraPosition.tilt)
                        forgetDrawnCamera()
                    }
                }
            })
            m.addOnScaleListener(object : MapLibreMap.OnScaleListener {
                override fun onScaleBegin(d: org.maplibre.android.gestures.StandardScaleGestureDetector) {
                    scaling = true
                }
                override fun onScale(d: org.maplibre.android.gestures.StandardScaleGestureDetector) {}
                override fun onScaleEnd(d: org.maplibre.android.gestures.StandardScaleGestureDetector) {
                    scaling = false
                    if (following) {
                        navCamera.setUserZoom(m.cameraPosition.zoom)
                        forgetDrawnCamera()
                    }
                }
            })
            val json = resources.openRawResource(R.raw.style_e60)
                .bufferedReader().use { it.readText() }
            m.setStyle(Style.Builder().fromJson(json)) { s ->
                style = s
                installLayers(s)
                // A reloaded style has a brand new puck layer with nothing in
                // it, so what we think is drawn is not drawn.
                forgetDrawnCamera()
                drawnRoute = null
                drawnCameras = emptyList()
            }
        }

        requestPermissions()
        showLastCrash()
    }

    /**
     * If the app died last time, say so and say why.
     *
     * There is no crash reporting on a sideloaded app, so without this a crash
     * is just "it closed" — and the first thing anyone can tell you about it is
     * that it happened, which is the one thing you already knew.
     */
    /**
     * Held so onDestroy can dismiss it.
     *
     * It is setCancelable(false) and shown from onCreate, so a recreation with
     * it on screen -- a night-mode switch at dusk on a head unit, a density or
     * locale change -- destroyed the activity with the dialog still attached
     * and logged android.view.WindowLeaked, keeping the whole activity alive.
     */
    private var crashDialog: AlertDialog? = null

    private fun toast(s: CharSequence) = com.mihai.navhud.ui.Notice.show(this, s)

    private fun showLastCrash() {
        val text = Crash.last(this) ?: return
        // Shown once per *process*, and the file is dropped only when the
        // activity is finishing for real.
        //
        // The dialog is setCancelable(false) and is shown from onCreate, so
        // pressing Home or Recents dismissed it without either button running
        // Crash.clear(). The file then sat in private storage for ever and the
        // same dialog came up on every launch, months and several versions
        // later -- reporting a crash from 1.15 as though it had just happened,
        // and hiding any real one behind it.
        //
        // Deleting it here instead was worse in a different way: the manifest
        // does not list uiMode in configChanges, so a head unit switching to
        // night mode recreates the activity, and the trace would be gone
        // before the driver could copy it. So: mark it seen in memory, and
        // delete on a finishing onDestroy.
        if (crashShown) return
        crashShown = true

        val view = layoutInflater.inflate(R.layout.dialog_crash, null)
        val dialog = AlertDialog.Builder(this, R.style.Theme_NavHUD_Dialog)
            .setView(view)
            .setCancelable(false)
            .create()

        // The first two lines of the report are the version and the timestamp;
        // that is the part a person can act on. The trace is behind a
        // disclosure, because "it closed, here is a wall of Java" is not a
        // sentence anyone wants to be shown on first launch.
        view.findViewById<TextView>(R.id.crashWhen).text =
            text.lineSequence().drop(1).firstOrNull()?.trim().orEmpty()
        view.findViewById<TextView>(R.id.crashTrace).text = text

        val scroll = view.findViewById<View>(R.id.crashScroll)
        val toggle = view.findViewById<TextView>(R.id.crashToggle)
        toggle.setOnClickListener {
            val open = scroll.visibility == View.VISIBLE
            scroll.visibility = if (open) View.GONE else View.VISIBLE
            toggle.setText(
                if (open) R.string.crash_show_details else R.string.crash_hide_details
            )
        }
        view.findViewById<View>(R.id.crashCopy).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("NavHUD crash", text))
            toast(getString(R.string.crash_copied))
        }
        view.findViewById<View>(R.id.crashOk).setOnClickListener { dialog.dismiss() }

        crashDialog = dialog
        dialog.show()
    }

    // ---- lifecycle plumbing MapView insists on -----------------------------

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() {
        super.onResume(); mapView.onResume(); hideSystemBars()
        // Read once here rather than per frame: the camera loop runs at 30 Hz
        // and this is a SharedPreferences hit.
        snapToRoad = Prefs.snapToRoad(this)
        arrowDebug = Prefs.arrowDebug(this)
        if (!arrowDebug) arrowDebugView.visibility = View.GONE
        lastCamFrameMs = 0L
        lastFrameNanos = 0L
        // A gesture cut off by the pause never delivered its end.
        scaling = false
        shoving = false
        // A window that started before a five-minute pause would publish
        // "vsync 0 Hz" on the first frame back, which reads as a hang.
        frameWindowStartNs = 0L
        frameCount = 0
        framesDrawn = 0
        frameSumMs = 0.0
        frameWorstMs = 0.0
        // Sensors first: it is what creates the background looper that the
        // location updates below are then delivered on.
        startSensors()
        startOwnLocation()
        startFreeDrive()
        firstResume = false
        ui.post(tick)
        // onPause's removeCallbacksAndMessages(null) killed this too, and the
        // sheet can still be open: without it the overlay stays on screen
        // showing numbers frozen at the moment the app was backgrounded.
        if (quickScrim.visibility == View.VISIBLE && Prefs.showPerf(this)) ui.post(perfTick)
        android.view.Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /**
     * Open the app and it is already navigating — just without a destination.
     *
     * The service used to exist only to follow a route, so with nothing typed
     * in there was no speed limit, no camera warning and nothing on the HUD.
     * That is backwards: knowing the limit of the road you are on and being
     * told about a camera is most of what this thing is for, and it does not
     * depend on where you are going.
     */
    private fun startFreeDrive() {
        // The service survives the app being swiped away, and comes back to
        // this screen still in quiet mode -- route gone, voice muted. It looks
        // completely normal, and every camera warning is silently dead for the
        // rest of the drive. The map screen being in front is the definition of
        // "not quit", so say so.
        HudService.wakeFromQuiet()
        if (HudService.running) return
        // Someone who just pressed Stop meant it. Coming back to this screen
        // should not switch the service on again behind their back; opening
        // the app afresh will.
        if (HudService.userStopped && !firstResume) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        ContextCompat.startForegroundService(this,
            Intent(this, HudService::class.java)
                .setAction(HudService.ACTION_START)
                .putExtra(HudService.EXTRA_VOICE, voiceOn))
    }
    override fun onPause() {
        ui.removeCallbacksAndMessages(null)
        // A Choreographer callback is not on the Handler's queue, so the line
        // above does not touch it. Left running, it would keep driving the
        // camera against a paused MapView.
        android.view.Choreographer.getInstance().removeFrameCallback(frameCallback)
        // Location off the looper before the looper is quit.
        stopOwnLocation()
        stopSensors()
        mapView.onPause()
        super.onPause()
    }
    override fun onStop() { super.onStop(); mapView.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroy() {
        crashDialog?.let { runCatching { it.dismiss() } }
        crashDialog = null
        // Only when we are really going away. A recreation for night mode
        // must not take the crash report with it.
        if (isFinishing && crashShown) Crash.clear(this)
        // Tell the service the screen has really gone, so the board drops to
        // the car-only view instead of holding the last arrow for ever.
        //
        // isFinishing is the whole distinction. Minimising must change nothing
        // -- keeping the HUD alive with the app in the background is the
        // reason the service is foreground at all -- and a configuration
        // change (night mode flipping at dusk recreates this activity) must
        // not throw the route away either. Both of those leave isFinishing
        // false. Back and finish() set it true, and only then is the route
        // dropped.
        if (isFinishing) {
            runCatching {
                startService(Intent(this, HudService::class.java)
                    .setAction(HudService.ACTION_UI_CLOSED))
            }
        }
        mapView.onDestroy()
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState)
    }

    @Deprecated("startActivityForResult is fine for a single screen")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SEARCH || resultCode != RESULT_OK || data == null) return
        val lat = data.getDoubleExtra(SearchActivity.RESULT_LAT, Double.NaN)
        val lon = data.getDoubleExtra(SearchActivity.RESULT_LON, Double.NaN)
        val label = data.getStringExtra(SearchActivity.RESULT_LABEL) ?: "Destination"
        if (lat.isNaN() || lon.isNaN()) return
        // The same guard startFreeDrive has. Starting a foreground service that
        // then finds it may not claim the location type leaves it unable to go
        // foreground at all, and Android kills the process for it.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions()
            toast(getString(R.string.need_location))
            return
        }

        val intent = Intent(this, HudService::class.java)
            .setAction(HudService.ACTION_START)
            .putExtra(HudService.EXTRA_VOICE, voiceOn)
            .putExtra(HudService.EXTRA_DEST_LAT, lat)
            .putExtra(HudService.EXTRA_DEST_LON, lon)
            .putExtra(HudService.EXTRA_DEST_LABEL, label)
        // Wipe the sources too, not just the "what is drawn" bookkeeping. The
        // service nulls its route synchronously, so the next refresh compares
        // null against null, sees no change, and never redraws -- leaving the
        // previous trip's amber line and camera pin painted over a free-drive
        // map, permanently if the new route request then fails.
        style?.let { st -> drawRoute(st, null); drawCameras(st, emptyList()) }
        drawnRoute = null
        drawnCameras = emptyList()
        following = true
        navCamera.reset()
        forgetDrawnCamera()
        updateFollowButton()
        ContextCompat.startForegroundService(this, intent)
    }

    // ---- style layers ------------------------------------------------------

    /**
     * Add a layer, replacing any layer already holding that id.
     *
     * MapLibre throws `CannotAddLayerException` on a duplicate id, and a throw
     * in the style callback kills the activity. That is a fair rule for a
     * mapping library and a terrible failure mode for a car: the app cannot be
     * opened at all, on the road, with no way back short of a new APK. It
     * happened once, from two constants that had drifted onto the same string.
     *
     * Removing first makes installLayers idempotent, which also covers the
     * legitimate case: MapLibre can deliver the style callback more than once
     * across a style reload, and the second delivery should refresh the layers
     * rather than crash.
     */
    private fun addLayerSafely(s: Style, layer: org.maplibre.android.style.layers.Layer) {
        runCatching { s.addLayer(layer) }.onFailure {
            android.util.Log.e(TAG, "could not add layer ${layer.id}", it)
        }
    }

    private fun addSourceSafely(s: Style, src: GeoJsonSource) {
        runCatching { s.addSource(src) }.onFailure {
            android.util.Log.e(TAG, "could not add source ${src.id}", it)
        }
    }

    /**
     * Take our layers and sources back out of the style, in that order.
     *
     * The order is the whole content of this function. MapLibre refuses to
     * remove a source that a layer still refers to — native returns false and
     * logs "is in use, cannot remove" — but the Java-side peer is dropped
     * anyway, so a later `addSource` throws `CannotAddSourceException` against
     * a native source that is still there. Removing per-source as each one was
     * about to be added had exactly that shape: the alternatives layer was
     * still standing over its own source at the moment we tried to replace it.
     *
     * Doing it here, all layers before any source, makes `installLayers`
     * genuinely repeatable — which it needs to be if the style is ever
     * reloaded, and which is worth having anyway because the alternative is a
     * crash on the first frame in a car.
     */
    private fun clearOurStyle(s: Style) {
        for (id in MapIds.layers) runCatching { s.removeLayer(id) }
        for (id in MapIds.sources) runCatching { s.removeSource(id) }
    }

    /**
     * Complain loudly if anything we are going to reference is not in the style.
     *
     * The dot marker shipped in 1.10 referencing an image that was never
     * registered: an edit that was supposed to add the `addImage` call matched
     * nothing and did nothing, silently. MapLibre does not object to a symbol
     * layer naming an image that does not exist — it just draws nothing — so
     * the marker would simply have vanished whenever the heading was unknown,
     * with no error anywhere. One line of checking turns that into a log entry.
     */
    private fun verifyStyle(s: Style) {
        // Sources first, because they are the category that fails silently all
        // the way down: every consumer does `getSourceAs(...) ?: return`, so a
        // missing source is a marker, a route line or the camera pins simply
        // not being drawn, with nothing anywhere saying why.
        for (id in MapIds.sources) {
            if (runCatching { s.getSource(id) }.getOrNull() == null) {
                android.util.Log.e(TAG, "style source missing: $id")
            }
        }
        for (id in MapIds.images) {
            if (runCatching { s.getImage(id) }.getOrNull() == null) {
                android.util.Log.e(TAG, "style image missing: $id")
            }
        }
        for (id in MapIds.layers) {
            if (runCatching { s.getLayer(id) }.getOrNull() == null) {
                android.util.Log.e(TAG, "style layer missing: $id")
            }
        }
    }

    private fun installLayers(s: Style) {
        clearOurStyle(s)
        // The alternatives go in first so the route you are actually on paints
        // over them, not the other way round.
        addSourceSafely(s, GeoJsonSource(ALT_SOURCE))
        addLayerSafely(s,
            LineLayer(ALT_LAYER, ALT_SOURCE).withProperties(
                PropertyFactory.lineColor(Color.parseColor("#8B93A0")),
                PropertyFactory.lineOpacity(0.85f),
                PropertyFactory.lineWidth(
                    Expression.interpolate(Expression.exponential(1.5f), Expression.zoom(),
                        Expression.stop(10f, 3f), Expression.stop(18f, 12f))),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
        // The route ahead, in two sources (see NEAR_WINDOW_M), stacked casing
        // far, casing near, line far, line near: both casings under both lines,
        // so where the pieces meet no casing is painted across the amber.
        // Nothing is drawn behind the car, which is also what tells the two
        // passes of a route that doubles back apart.
        addSourceSafely(s, GeoJsonSource(ROUTE_FAR_SOURCE))
        addSourceSafely(s, GeoJsonSource(ROUTE_NEAR_SOURCE))
        for ((id, src) in listOf(ROUTE_CASING_FAR to ROUTE_FAR_SOURCE,
                                 ROUTE_CASING to ROUTE_NEAR_SOURCE)) {
            addLayerSafely(s,
                // The casing. Near-black rather than the brown it used to be:
                // two things separate a route from a road, colour and an edge.
                // The map's roads are warm grey now, which gives the colour;
                // this gives the edge, so the amber sits *on* the map instead
                // of being one more line in it, and it still reads where the
                // route runs along a pale road or over a junction full of them.
                LineLayer(id, src).withProperties(
                    PropertyFactory.lineColor(Color.parseColor("#08080A")),
                    PropertyFactory.lineWidth(
                        Expression.interpolate(Expression.exponential(1.5f), Expression.zoom(),
                            Expression.stop(10f, 7f), Expression.stop(18f, 26f))),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                )
            )
        }
        // The line is coloured by live traffic, the way every nav app has done
        // it since TomTom put it on a windscreen: amber running freely, through
        // orange and red, to a dark red for a stretch the provider reports as
        // closed. The levels come from Mapbox's congestion and closure
        // annotations, which is also how roadworks show up.
        for ((id, src) in listOf(ROUTE_LAYER_FAR to ROUTE_FAR_SOURCE,
                                 ROUTE_LAYER to ROUTE_NEAR_SOURCE)) {
            addLayerSafely(s,
                LineLayer(id, src).withProperties(
                    PropertyFactory.lineColor(
                        Expression.step(
                            Expression.toNumber(Expression.get("level")),
                            Expression.color(Color.parseColor("#FFB121")),
                            Expression.stop(2, Expression.color(Color.parseColor("#FF6A00"))),
                            Expression.stop(3, Expression.color(red)),
                            Expression.stop(4, Expression.color(Color.parseColor("#B31200"))),
                            Expression.stop(5, Expression.color(Color.parseColor("#6E0A00")))
                        )
                    ),
                    PropertyFactory.lineWidth(
                        Expression.interpolate(Expression.exponential(1.5f), Expression.zoom(),
                            Expression.stop(10f, 5f), Expression.stop(18f, 20f))),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                )
            )
        }

        runCatching { s.addImage(CAM_ICON, scaledToDp(cameraBitmap(), 30)) }
        runCatching { s.addImage(CAM_ANPR_ICON, scaledToDp(cameraBitmap(anpr = true), 30)) }
        addSourceSafely(s, GeoJsonSource(CAM_SOURCE))
        addLayerSafely(s,
            SymbolLayer(CAM_LAYER, CAM_SOURCE).withProperties(
                // "kind" is SpeedCamera.Kind's name, set in drawCameras.
                PropertyFactory.iconImage(Expression.match(Expression.get("kind"),
                    Expression.literal(CAM_ICON),
                    Expression.stop(SpeedCamera.Kind.ANPR.name, CAM_ANPR_ICON))),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconSize(1.0f)
            )
        )

        runCatching { s.addImage(PUCK_DOT_ICON, scaledToDp(puckDotBitmap(), 34)) }
        runCatching { s.addImage(PUCK_ICON, scaledToDp(puckBitmap(), 54)) }
        // Added last, so it stays on top of everything above.
        //
        // The indicator layer draws its image flat on the ground plane, the way
        // Waze and every built-in car navigation lay the arrow, which is what
        // the old map-pitched symbol did too -- so the pre-stretched bitmap
        // (see puckBitmap) still comes out the right shape. 0.9 is the
        // perspective compensation MapLibre's own LocationComponent uses.
        puckLayer = newPuckLayer()?.also { layer ->
            layer.setProperties(
                LayoutPropertyValue("bearing-image", PUCK_DOT_ICON),
                PaintPropertyValue("perspective-compensation", 0.9f),
                PaintPropertyValue("image-tilt-displacement", 0f),
                PaintPropertyValue("bearing-image-size", 1f),
                PaintPropertyValue("accuracy-radius", 0f)
            )
            addLayerSafely(s, layer)
        }
        // No indicator layer means no arrow at all -- on a car display that is
        // not a degraded mode, it is a broken one. Fall back to the old symbol.
        puckFallback = if (puckLayer == null) installFallbackPuck(s) else null
        puckShownDot = null
        puckHidden = null

        verifyStyle(s)
    }

    /**
     * The car marker, as a LocationIndicatorLayer.
     *
     * Its position is a layer property, which MapLibre applies in the same
     * render as the moveCamera beside it. The old puck was a GeoJSON source
     * updated every frame, and a GeoJSON update is parsed on a worker thread
     * and lands a frame or more later -- so the arrow trailed the map and
     * shimmered against it.
     *
     * The class is package-private in MapLibre 11.8 (LocationComponent is its
     * only intended user), hence the reflection; minify is off, so the name
     * survives into the APK. Null, logged, if that ever stops being true.
     */
    private fun newPuckLayer(): org.maplibre.android.style.layers.Layer? = runCatching {
        val cls = Class.forName("org.maplibre.android.location.LocationIndicatorLayer")
        val layer = cls.getDeclaredConstructor(String::class.java)
            .apply { isAccessible = true }
            .newInstance(PUCK_LAYER) as org.maplibre.android.style.layers.Layer
        // No easing: the frame loop already smooths, and the style's default
        // transition would put the arrow 300 ms behind the camera.
        val none = TransitionOptions(0, 0)
        for (setter in listOf("setLocationTransition", "setBearingTransition")) {
            cls.getDeclaredMethod(setter, TransitionOptions::class.java)
                .apply { isAccessible = true }
                .invoke(layer, none)
        }
        layer
    }.onFailure { android.util.Log.e(TAG, "could not create the puck layer", it) }.getOrNull()

    /**
     * The pre-1.27 puck: a map-aligned symbol on a GeoJSON source. It trails
     * the camera by a frame, which is why it is not the default, but it has
     * worked on every MapLibre version -- the one thing the arrow must do.
     */
    private fun installFallbackPuck(s: Style): GeoJsonSource? = runCatching {
        android.util.Log.w(TAG, "using the fallback symbol puck")
        addSourceSafely(s, GeoJsonSource(MapIds.PUCK_FALLBACK_SOURCE))
        addLayerSafely(s,
            SymbolLayer(PUCK_LAYER, MapIds.PUCK_FALLBACK_SOURCE).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                // Flat on the road and turned with it, as the indicator draws it.
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
                PropertyFactory.iconRotate(Expression.get("bearing"))
            )
        )
        s.getSourceAs<GeoJsonSource>(MapIds.PUCK_FALLBACK_SOURCE)
    }.onFailure { android.util.Log.e(TAG, "fallback puck failed too", it) }.getOrNull()

    /** MapLibre treats bitmaps as raw pixels, so scale by screen density. */
    private fun scaledToDp(src: android.graphics.Bitmap, dp: Int): android.graphics.Bitmap {
        val px = (dp * resources.displayMetrics.density).toInt().coerceAtLeast(24)
        return android.graphics.Bitmap.createScaledBitmap(src, px, px, true)
    }

    /**
     * The car marker, lying on the road.
     *
     * This is a top-down arrowhead, not a raised wedge seen from the front.
     * The previous one was drawn as a little 3D object with a lit face, a
     * shaded face and a skirt, which is the right way to draw a marker that
     * stands up to the camera — and the wrong way to draw one lying on the
     * ground, where all that shading turns into a smear of light and dark that
     * reads as dirt. Flat on the map, what you want is a shape: a clean
     * arrowhead with a bright rim and a soft shadow under it, exactly what
     * Waze puts on the road.
     *
     * ## The stretch
     *
     * The layer draws this in the map plane, so the tilt foreshortens it: at
     * 40 degrees a circle becomes an ellipse 77% as tall as it is wide, and an
     * arrow drawn in proper proportion arrives on screen looking squat. So the
     * bitmap is drawn *longer* than it should be, by 1 / cos(tilt), and the
     * foreshortening puts it back. Draw it correct and it looks wrong; draw it
     * wrong and it looks correct. That is the whole trick, and it only works
     * because the tilt is now a constant.
     */
    private fun puckBitmap(): android.graphics.Bitmap {
        val size = 160
        val bmp = android.graphics.Bitmap.createBitmap(
            size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        // Rounded corners on every layer, so the marker is a moulded object
        // rather than a cut-out. Set once and shared: the rim, the body and
        // the shadow have to round identically or the rim goes uneven.
        p.pathEffect = android.graphics.CornerPathEffect(9f)

        val cx = size / 2f
        // Pre-stretched along the direction of travel; see above.
        val stretch = (1.0 / Math.cos(Math.toRadians(NavCamera.TILT))).toFloat()
        // Waze's marker is a wide, squat arrowhead rather than a spike --
        // roughly as wide as it is long once the tilt has had its way with it.
        val halfW = 46f
        val halfL = (halfW * 1.05f * stretch).coerceAtMost(cx - 8f)
        val tipY = cx - halfL
        val backY = cx + halfL
        // A shallow notch. Deep enough that it is an arrow and not a
        // triangle, shallow enough that it still reads as a solid object at
        // 46dp on a moving map -- cut it much further and it becomes a thin
        // chevron with a hole in it.
        val notchY = cx + halfL * 0.66f

        fun arrow(inset: Float): android.graphics.Path =
            android.graphics.Path().apply {
                moveTo(cx, tipY + inset * 1.6f)
                lineTo(cx + halfW - inset, backY - inset * 0.5f)
                lineTo(cx, notchY - inset * 1.2f)
                lineTo(cx - halfW + inset, backY - inset * 0.5f)
                close()
            }

        // 1. A soft shadow on the road, offset a little so the marker looks
        //    like it is sitting a few centimetres proud of the tarmac.
        p.color = Color.parseColor("#7A000000")
        p.maskFilter = android.graphics.BlurMaskFilter(
            9f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        c.save()
        c.translate(0f, 5f)
        c.drawPath(arrow(-2f), p)
        c.restore()
        p.maskFilter = null

        // 2. A dark rim.
        //
        //    This is the way round it has to be now that the marker lies on
        //    the map. The route line is bright amber and so was the marker, so
        //    an amber body with a white rim put bright on bright and the shape
        //    dissolved into the line it was standing on. A dark border with a
        //    pale body reads on both: light against the amber route, and light
        //    against the dark tarmac in free drive, where there is no line at
        //    all.
        p.style = android.graphics.Paint.Style.FILL
        p.color = Color.parseColor("#17110A")
        c.drawPath(arrow(-7f), p)

        // 3. The body: near-white at the tip, warm amber at the tail. A
        //    gradient along the axis rather than across it -- a left/right
        //    split reads as a fold, and there is no fold in a shape lying flat.
        p.shader = android.graphics.LinearGradient(
            cx, tipY, cx, backY,
            Color.parseColor("#FFFFFF"), Color.parseColor("#FFB43C"),
            android.graphics.Shader.TileMode.CLAMP
        )
        c.drawPath(arrow(0f), p)
        p.shader = null
        return bmp
    }

    /**
     * The marker for "I know where you are, not which way you face".
     *
     * A disc rather than an arrow, with a soft halo standing in for the
     * accuracy of the fix. Deliberately smaller than the arrow, so the moment a
     * heading arrives and it becomes a chevron, you can see that something got
     * better rather than merely different.
     */
    private fun puckDotBitmap(): android.graphics.Bitmap {
        val size = 128
        val bmp = android.graphics.Bitmap.createBitmap(
            size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val cx = size / 2f

        p.color = Color.parseColor("#33FF9D00")
        c.drawCircle(cx, cx, 56f, p)
        p.color = Color.parseColor("#70000000")
        p.maskFilter = android.graphics.BlurMaskFilter(
            8f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        c.drawCircle(cx, cx + 4f, 30f, p)
        p.maskFilter = null
        p.color = Color.parseColor("#F2F4F7")
        c.drawCircle(cx, cx, 30f, p)
        p.color = Color.parseColor("#E08A00")
        c.drawCircle(cx, cx, 22f, p)
        return bmp
    }

    /**
     * @param anpr a number-plate camera: amber instead of red, since it
     *        enforces no speed, with the camera raised over a plate.
     */
    private fun cameraBitmap(anpr: Boolean = false): android.graphics.Bitmap {
        val size = 56
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = Color.parseColor("#F0120A08")
        c.drawCircle(size / 2f, size / 2f, size / 2f - 3, p)
        p.style = android.graphics.Paint.Style.STROKE
        p.strokeWidth = 4f
        p.color = if (anpr) amber else red
        c.drawCircle(size / 2f, size / 2f, size / 2f - 4, p)
        p.style = android.graphics.Paint.Style.FILL
        val dy = if (anpr) -6f else 0f
        // a small camera body
        c.drawRect(16f, 24f + dy, 36f, 38f + dy, p)
        val lens = android.graphics.Path()
        lens.moveTo(36f, 27f); lens.lineTo(44f, 22f); lens.lineTo(44f, 40f); lens.lineTo(36f, 35f)
        lens.close()
        lens.offset(0f, dy)
        c.drawPath(lens, p)
        // the number plate it reads
        if (anpr) c.drawRoundRect(17f, 37f, 39f, 44f, 2f, 2f, p)
        return bmp
    }

    // ---- the refresh loop --------------------------------------------------

    private val tick = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 200) }
    }

    private fun refresh() {
        val f = HudService.lastFrame
        val route = HudService.currentRoute
        val s = style

        // Also how the line is cleared when the route ends: route is null.
        if (s != null && route !== drawnRoute) { drawRoute(s, route); drawnRoute = route }
        // Keep the next stretch of the route downloaded. See OfflineRoutes.
        runCatching {
            val off = offlineRoutes
                ?: com.mihai.navhud.map.OfflineRoutes(applicationContext).also { offlineRoutes = it }
            off.update(route, HudService.alongM, SystemClock.elapsedRealtime())
        }
        if (s != null) {
            // Not in zone mode. Everything else about a danger zone is careful
            // not to publish a position -- the distance is blurred, the text
            // says "enforcement likely", the HUD prints ZONE with no metres --
            // and then the map dropped a pin on the radar itself, which is the
            // one surface that gives the whole thing away. ZONE is also the
            // default for every country we have not verified, not a rare case.
            val cams = HudService.cameraAlert
                ?.takeIf { !it.zoneMode }
                ?.let { listOfNotNull(it.camera) } ?: emptyList()
            if (cams != drawnCameras) { drawCameras(s, cams); drawnCameras = cams }
        }


        val status = driverStatus(route)
        statusView.setIfChanged(status)
        // Computed once. driverProblems() called driverStatus() a second time,
        // rebuilding the list and repeating every getString lookup, 5 times a
        // second, to answer a question the first call had already answered.
        val problem = status.takeIf { hadProblems }
        if (problem == null) statusChip.visibility = View.GONE
        else {
            statusChip.visibility = View.VISIBLE
            statusChip.setIfChanged(problem)
        }

        val onRoad = HudService.currentRoadName

        val alts = HudService.alternatives
        routesTile.visibility = if (alts.size > 1) View.VISIBLE else View.GONE
        if (alts.size < 2 && routePicker.visibility == View.VISIBLE) hideRoutePicker()
        // The row already says "Routes"; the value column says how many.
        routesLabel.setIfChanged(alts.size.toString())

        // Nothing to cancel when there is no route.
        cancelRouteButton.visibility = if (route != null) View.VISIBLE else View.GONE

        laneView.guidance = HudService.lanes
        updateCameraAlert()
        updateRoadworks()

        if (f == null) {
            instructionCard.visibility = View.GONE
            speedGauge.speedKph = -1
            speedGauge.limitKph = 0
            speedGauge.over = false
            setTrip(getString(R.string.where_to), onRoad, prompting = true)
            junctionBox.visibility = View.GONE
            junctionView.guidance = null
            return
        }

        // With no route there is nothing to instruct. The whole card goes --
        // hiding only the arrow inside it left an empty box floating in the
        // corner, which is what it looked like on the road.
        val guiding = route != null
        instructionCard.visibility = if (guiding) View.VISIBLE else View.GONE
        maneuverView.maneuver = f.maneuver
        maneuverView.roundaboutExit = f.roundaboutExit
        maneuverView.roundaboutBearing = f.roundaboutBearing
        maneuverView.color = if (f.distToManeuverM in 1..150) Color.parseColor("#FFD24D") else amber

        distView.setIfChanged(when {
            !guiding -> ""
            f.distToManeuverM <= 0 -> getString(R.string.now)
            f.distToManeuverM < 1000 -> "${f.distToManeuverM} m"
            f.distToManeuverM < 10000 -> "%.1f km".format(f.distToManeuverM / 1000.0)
            else -> "${f.distToManeuverM / 1000} km"
        })
        streetView.setIfChanged(f.street)
        streetView.visibility = if (f.street.isBlank()) View.GONE else View.VISIBLE

        val over = f.flags and HudFrame.FLAG_OVER_LIMIT != 0
        // One widget: the number, the ring and the roundel all come from the
        // same frame, so they can never disagree with each other.
        speedGauge.speedKph = f.speedKph
        speedGauge.limitKph = f.limitKph
        speedGauge.over = over

        // "18 h 33" was the *duration* formatted like a clock time, which read
        // as an arrival time of half past six. Show the arrival time, because
        // that is the number you actually want, and the duration beside it.
        val mins = f.etaSeconds / 60
        val duration = formatDuration(mins)
        val rem = if (f.remainingM < 1000) "${f.remainingM} m"
                  else "%.0f km".format(f.remainingM / 1000.0)
        val delayMin = ((route?.trafficDelayS ?: 0.0) / 60.0).toInt()
        // The trip pill. Driving somewhere, it is the three numbers you keep
        // glancing at -- time left, distance left, time of arrival. Driving
        // nowhere, it is the road you are on, because that is the only useful
        // thing it can say and an empty slab looks broken.
        if (guiding && f.remainingM > 0) {
            setTrip(duration, buildString {
                append(rem).append("  ·  ").append(arrivalClock(f.etaSeconds))
                if (delayMin >= 2) append("  ·  +").append(delayMin).append(" min")
            })
        } else {
            setTrip(getString(R.string.where_to), onRoad, prompting = true)
        }

        val next = HudService.currentRoute?.let { r ->
            r.maneuvers.firstOrNull { it.alongM > HudService.alongM + 8.0 }
        }
        val sign = next?.sign

        // The full junction view earns its space only where it says something
        // the lane strip cannot: a motorway exit, close enough to matter, with
        // real lane data behind it. Everywhere else it would be a picture of a
        // road, which the map already is.
        val lanes = HudService.lanes
        val isExit = f.maneuver == Man.RAMP_LEFT || f.maneuver == Man.RAMP_RIGHT ||
            f.maneuver == Man.FORK_LEFT || f.maneuver == Man.FORK_RIGHT ||
            f.maneuver == Man.KEEP_LEFT || f.maneuver == Man.KEEP_RIGHT ||
            !(sign?.exitNumber).isNullOrBlank()
        val bigJunction = guiding && lanes != null && lanes.count >= 2 && isExit &&
            f.distToManeuverM <= HudService.laneRange(maxOf(0, f.speedKph) / 3.6) &&
            f.distToManeuverM >= 1

        if (bigJunction) {
            junctionView.signColour = RoadSigns.junctionPanel(
                HudService.country,
                onMotorway = RoadSigns.looksLikeMotorway(HudService.currentRoadName) ||
                    (f.limitKph >= 100),
                ontoMotorway = RoadSigns.looksLikeMotorway(next?.name)
            )
            junctionView.sign = sign
            junctionView.guidance = lanes          // sets visibility
        } else {
            junctionView.guidance = null
        }

        // The lane strip and the junction view say the same thing; showing both
        // is repetition, and the strip is the one that loses.
        laneView.visibility = if (bigJunction || lanes == null) View.GONE else View.VISIBLE

        if (sign != null && !sign.isEmpty && !bigJunction && f.distToManeuverM in 1..3000) {
            junctionBox.visibility = View.VISIBLE
            // Same colour rule as the full junction view, which is the whole
            // point of having a rule: the small board and the big one are the
            // same sign. This one was a fixed amber drawable, so on the R0 the
            // card showed a green Belgian board and the corner showed an amber
            // one for the identical exit.
            val panel = RoadSigns.junctionPanel(
                HudService.country,
                onMotorway = RoadSigns.looksLikeMotorway(HudService.currentRoadName) ||
                    (f.limitKph >= 100),
                ontoMotorway = RoadSigns.looksLikeMotorway(next?.name)
            )
            val text = RoadSigns.textColourFor(panel)
            junctionBox.background?.mutate()?.let {
                androidx.core.graphics.drawable.DrawableCompat.setTint(it, panel)
            }
            exitNumberView.setTextColor(text)
            exitDestView.setTextColor(text)
            exitNumberView.setIfChanged(sign.exitNumber ?: "")
            exitNumberView.visibility = if (sign.exitNumber.isNullOrBlank()) View.GONE else View.VISIBLE
            exitDestView.setIfChanged(sign.destinations ?: "")
            exitDestView.visibility = if (sign.destinations.isNullOrBlank()) View.GONE else View.VISIBLE
        } else {
            junctionBox.visibility = View.GONE
        }
    }


    /**
     * The one line of small print along the bottom.
     *
     * It used to be a debug dump -- link state, rejected-fix accuracy, heading
     * source, country code, camera count -- permanently on the driving screen.
     * All of that is worth having, and none of it is worth a driver's
     * attention while the car is moving, so it lives on the Setup screen now
     * and this says something only when there is something to say.
     */

    private var hadProblems = false

    private fun driverStatus(route: Route?): String {
        val problems = ArrayList<String>(2)
        if (HudService.fixQuality.startsWith("ignored") || HudService.lastFrame == null) {
            // A cold start is a black screen and two dashes for ten or twenty
            // seconds, which reads as broken rather than as working-on-it. Say
            // which of the two it is.
            problems += if (HudService.lastFrame == null && !HudService.linkEverUp)
                getString(R.string.finding_you) else getString(R.string.waiting_for_gps)
        }
        when (HudService.cameraPolicy) {
            CameraPolicy.OFF -> problems += getString(R.string.cameras_off_here)
            CameraPolicy.ZONE -> problems += getString(R.string.danger_zones_only)
            CameraPolicy.EXACT -> {}
        }
        // Gated the same way the roadworks banner is. Without it a closure
        // 144 km down the motorway was reported from the first tick as "Road
        // closed in 143820 m", and because any problem suppresses the summary
        // line, the trip pill never showed the destination for the whole drive.
        route?.metresToClosure(HudService.alongM)
            ?.takeIf { it >= 0 && it <= ROADWORKS_SHOW_M }
            ?.let { problems += getString(R.string.road_closed_in, it.toInt()) }
        // A cable that was never plugged in is not a fault; one that was
        // working and has stopped is.
        // Ask the service whether the transport is open. Matching on the
        // description text did not work: the USB link describes itself by its
        // driver class -- "CdcAcm @ 115200 baud", "Ch34x...", "Ftdi..." -- so
        // the test "does it start with Usb" was false for every working cable,
        // and the chip read HUD DISCONNECTED for the whole drive while the
        // display was working perfectly.
        if (HudService.linkEverUp && HudService.running && !HudService.linkUp) {
            problems += getString(R.string.hud_disconnected)
        }
        hadProblems = problems.isNotEmpty()
        if (hadProblems) return problems.joinToString("   ·   ")

        // Nothing wrong: say where we are going, and otherwise stay quiet.
        route?.summary?.takeIf { it.isNotBlank() }?.let { return getString(R.string.via, it) }
        HudService.destinationLabelPublic?.takeIf { it.isNotBlank() }?.let { return it }
        return getString(R.string.press_go)
    }

    /** "arrive 14:05" — the clock time you get there, which is the useful one. */
    /**
     * One way of saying a duration.
     *
     * The route card said "1 h 20" and the trip pill said "1 h 20 min" for the
     * same journey, on the same screen, two seconds apart.
     */
    private fun formatDuration(mins: Int): String =
        if (mins < 60) "$mins min" else "${mins / 60} h ${mins % 60} min"

    private fun arrivalClock(etaSeconds: Int): String {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.SECOND, etaSeconds)
        return String.format(java.util.Locale.UK, "%02d:%02d",
            cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
    }

    /**
     * "Closed road in 800 m", when the provider says so.
     *
     * Only from far enough out to be worth reading: a closure five kilometres
     * ahead on a motorway is a thing you plan around, one at 100 m is a thing
     * you are already in.
     */
    private fun updateRoadworks() {
        // One chip, worst first. A closure you can still route around beats a
        // speed bump you are about to go over -- but only just, so the bump
        // gets the chip whenever there is no closure in range.
        val d = HudService.closureAheadM
        if (d in 0..ROADWORKS_SHOW_M) {
            roadworksChip.visibility = View.VISIBLE
            setChipIcon(0)
            roadworksChip.setIfChanged(getString(R.string.closure_ahead, formatKm(d.toDouble())))
            return
        }
        val f = HudService.featureAhead
        if (f == null) { roadworksChip.visibility = View.GONE; return }
        val name = when (f.feature.kind) {
            RoadFeature.LEVEL_CROSSING -> getString(R.string.feat_crossing)
            RoadFeature.SPEED_BUMP -> getString(R.string.feat_bump)
            RoadFeature.TOLL_BOOTH -> getString(R.string.feat_toll)
            else -> { roadworksChip.visibility = View.GONE; return }
        }
        roadworksChip.visibility = View.VISIBLE
        // The sign itself, for the ones the icon pack has; a toll booth has none.
        setChipIcon(when (f.feature.kind) {
            RoadFeature.LEVEL_CROSSING -> R.drawable.ic_feat_crossing
            RoadFeature.SPEED_BUMP -> R.drawable.ic_feat_bump
            else -> 0
        })
        roadworksChip.setIfChanged("$name · ${f.distanceM} m")
    }

    /** The drawable on the road-ahead chip, or 0. Set only when it changes:
     *  this runs five times a second. */
    private var chipIcon = 0

    private fun setChipIcon(res: Int) {
        if (res == chipIcon) return
        chipIcon = res
        val icon = if (res == 0) null else ContextCompat.getDrawable(this, res)?.apply {
            val px = (24 * resources.displayMetrics.density).toInt()
            setBounds(0, 0, px, px)
        }
        roadworksChip.setCompoundDrawablesRelative(icon, null, null, null)
    }

    private fun updateCameraAlert() {
        val a = HudService.cameraAlert
        if (a == null) { cameraBox.visibility = View.GONE; return }
        cameraBox.visibility = View.VISIBLE
        if (a.zoneMode) {
            cameraLabel.setIfChanged(getString(R.string.danger_zone))
            cameraDist.setIfChanged(getString(R.string.zone_hint))
        } else {
            cameraLabel.setIfChanged(when (a.camera.kind) {
                SpeedCamera.Kind.AVERAGE -> getString(R.string.cam_average)
                SpeedCamera.Kind.TRAFFIC_LIGHT -> getString(R.string.cam_light)
                SpeedCamera.Kind.ANPR -> getString(R.string.cam_anpr)
                else -> getString(R.string.cam_fixed)
            })
            val limit = if (a.camera.limitKph > 0) " · ${a.camera.limitKph}" else ""
            cameraDist.setIfChanged("${a.distanceM} m$limit")
        }
    }

    // ---- our own position, independent of the service ----------------------

    private val ownLocationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            Fixes.accept(ownFilter, loc)?.let { ownFix = it }
        }

        @Deprecated("required by the old LocationListener interface")
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    private fun startOwnLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        locationManager = lm
        // Start from a clean slate: after a long pause the filter's last fix is
        // history, and comparing against it would refuse perfectly good fixes.
        ownFilter.reset()
        try {
            // Wrapped like the network one below: requestLocationUpdates throws
            // IllegalArgumentException when the provider does not exist, and a
            // wifi-only head unit genuinely has no GNSS.
            // Delivered on the sensor thread, not the main one. This is a
            // second GNSS subscription alongside the service's, and at 1 Hz it
            // is not much -- but it lands in the same queue the camera frames
            // are trying to keep time in, and the service already sets the
            // precedent by using its own worker looper.
            val looper = sensorHandler?.looper ?: android.os.Looper.getMainLooper()
            runCatching {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 0f, ownLocationListener, looper)
            }
            runCatching {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 2000L, 0f, ownLocationListener, looper
                )
            }
            // Something to draw immediately rather than a blank map while the
            // first fix comes in.
            if (ownFix == null) {
                runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }
                    .getOrNull()?.let { ownFix = it }
            }
        } catch (e: SecurityException) {
            // Permission was revoked between the check and the call. Nothing to do.
        }
    }

    private fun stopOwnLocation() {
        runCatching { locationManager?.removeUpdates(ownLocationListener) }
    }

    // ---- gyroscope ---------------------------------------------------------

    private val sensorListener = object : SensorEventListener {
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_GRAVITY -> {
                    gravity[0] = e.values[0]; gravity[1] = e.values[1]; gravity[2] = e.values[2]
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    // No gravity sensor on this head unit, so low-pass the
                    // accelerometer into one -- which is all the gravity sensor
                    // is, plus gyro help we do not get here.
                    //
                    // Two speeds, because the two jobs conflict. Settling on
                    // the mounting angle wants a fast filter; staying there
                    // wants a slow one, since a long corner or a hard brake
                    // adds a real horizontal acceleration that a fast filter
                    // would mistake for the phone having tilted. So: converge
                    // in a couple of seconds, then hold on tightly.
                    val a = if (gravitySamples < 40) 0.15f else 0.006f
                    for (i in 0..2) gravity[i] += a * (e.values[i] - gravity[i])
                    if (gravitySamples < 1000) gravitySamples++
                }
                Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                    val m = FloatArray(9)
                    val ok = runCatching {
                        SensorManager.getRotationMatrixFromVector(m, e.values)
                    }.isSuccess
                    if (!ok) return

                    // Deltas come from **one** sensor and one only.
                    //
                    // Both of these are registered: the game vector for smooth
                    // turn rates, the full one for north. They report the same
                    // physical orientation in two different yaw frames — the
                    // game vector has no magnetometer, so its zero is arbitrary
                    // and drifts. Feeding both into the same difference means
                    // every time the stream alternates, the "turn since the
                    // last sample" is really the constant angle between two
                    // reference frames, tens of degrees, fifteen times a
                    // second. That is what made the arrow spin on the spot when
                    // the phone was tilted.
                    if (e.sensor.type == deltaSensorType) {
                        val dt = if (lastRotNs == 0L) 0.0 else (e.timestamp - lastRotNs) / 1e9
                        lastRotNs = e.timestamp
                        for (i in 0..8) rotM[i] = m[i].toDouble()
                        fusion.onOrientation(rotM, dt, deltaSensorType)
                    }

                    // ...and north comes from the full rotation vector only.
                    if (e.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                        val absDt =
                            if (lastAbsNs == 0L) 0.2 else (e.timestamp - lastAbsNs) / 1e9
                        lastAbsNs = e.timestamp

                        // Three gates before this reading is allowed near the
                        // arrow, cheapest first. Together they are the answer to
                        // "why does it jump twenty or forty degrees".
                        //
                        // 1. The platform says the sensor needs calibrating.
                        val usable = e.accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE &&
                            // 2. The field we are sitting in is not the Earth's,
                            //    so it is the car: a door pillar, a speaker, the
                            //    mount, the charging coil.
                            fieldPlausible &&
                            // 3. The sensor's own 95% heading error bound, which
                            //    it publishes and almost nobody reads.
                            (e.values.size < 5 || e.values[4] < 0f ||
                                e.values[4] <= MAX_HEADING_ERROR_RAD)

                        if (usable) {
                            // Only a *gated* reading is allowed to become the
                            // reference the mounting offset is learned from.
                            // Copying it in before the gates meant the offset
                            // could be learned from a sample the compass path
                            // had just thrown away -- and it is persisted, so a
                            // first fix over a tram line poisoned every later
                            // drive.
                            synchronized(absLock) {
                                for (i in 0..8) absM[i] = m[i].toDouble()
                            }
                            hasAbsolute = true
                            holdAxis(absM)
                            fusion.setClock(android.os.SystemClock.elapsedRealtime())
                            Compass.headingFor(absM, displayRotation(), headingAxis)?.let { az ->
                                deviceAzimuth = az
                                // Magnetic north is not true north, and a GPS
                                // bearing is true north; comparing them without
                                // this leaves a constant error. +2.6 degrees in
                                // Brussels.
                                val trueAz = az + declinationDeg + headingOffset
                                fusion.onCompass(Geo.normalizeDeg(trueAz), absDt)
                            }
                        }
                    }
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    fieldPlausible = Compass.fieldPlausible(
                        e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())
                }
                Sensor.TYPE_GYROSCOPE -> {
                    val dt = if (lastGyroNs == 0L) 0.0 else (e.timestamp - lastGyroNs) / 1e9
                    lastGyroNs = e.timestamp
                    // A rate sensor bolted to the car beats one in a cradle, so
                    // the HUD's own IMU wins when it is reporting.
                    if (!externalImu) {
                        fusion.onGyro(
                            e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble(),
                            gravity[0].toDouble(), gravity[1].toDouble(), gravity[2].toDouble(),
                            dt
                        )
                    }
                }
            }
        }
    }

    /**
     * Sensor callbacks do not belong on the main looper.
     *
     * Five sensors at GAME and UI rates is about 150 messages a second, and
     * they share the main queue with the 30 Hz camera frames. The maths in the
     * callback is cheap; the *dispatch jitter* is not -- every camera frame has
     * to queue behind whatever sensor events arrived first, so the frames land
     * at irregular intervals. cameraFrame() measures the real dt, so the
     * position stays correct and only the timing wobbles, which is exactly
     * what a stutter looks like.
     */
    private var sensorThread: android.os.HandlerThread? = null
    private var sensorHandler: Handler? = null

    private fun startSensors() {
        val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        sensors = sm
        // Everything the sensor thread owns is reset *before* the thread is
        // started, so Thread.start()'s happens-before edge publishes it. These
        // are plain fields, read and written only on that thread once it is
        // running.
        lastGyroNs = 0L
        gravitySamples = 0
        lastRotNs = 0L
        lastAbsNs = 0L
        deltaSensorType = -1
        if (sensorThread == null) {
            sensorThread = android.os.HandlerThread(
                "nav-sensors", android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE
            ).also { it.start() }
            sensorHandler = Handler(sensorThread!!.looper)
        }
        hasAbsolute = false
        learnedThisDrive = false
        headingOffset = Prefs.headingOffset(this)
        savedOffset = headingOffset
        headingAxis = Prefs.headingAxis(this)
        declinationDeg = Prefs.declination(this)
        // A stale stamp from before the pause would let the very first bad
        // sample after a resume switch the axis immediately, skipping the one
        // second of hysteresis this exists to provide.
        axisBadSinceMs = 0L

        // The orientation sensor first, because it is the only one that can
        // answer "which way is the car pointing" while the car is not moving.
        //
        // GAME_ROTATION_VECTOR ahead of ROTATION_VECTOR on purpose: the
        // difference between them is the magnetometer, and a car is the worst
        // place on earth for a magnetometer -- a steel shell, speaker magnets,
        // and a couple of hundred amps going past the dashboard. The game
        // variant leaves it out. That makes its absolute yaw drift, which
        // would matter if we used the absolute yaw, and we do not: only the
        // change between two samples, with GPS supplying north. So we get the
        // stable one and lose nothing.
        val rot = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        // Whichever we got is the one allowed to move the heading. On a device
        // with no game vector that is the full one, and the two paths below
        // collapse onto a single sensor, which is fine -- what must never
        // happen is deltas taken across two.
        deltaSensorType = rot?.type ?: -1
        if (rot != null)
            sm.registerListener(sensorListener, rot, SensorManager.SENSOR_DELAY_GAME, sensorHandler)

        // ...and the magnetometer-backed one as well, slowly, purely to know
        // which way is north before the car has moved. It is a second listener
        // on a second sensor and it costs almost nothing at 5 Hz; without it
        // the arrow has no absolute reference at all until you drive off.
        if (rot?.type != Sensor.TYPE_ROTATION_VECTOR) {
            sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
                sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI, sensorHandler)
            }
        }

        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        phoneGyroPresent = gyro != null || rot != null
        // Still registered even with a rotation vector present: it is what
        // keeps the drift-bias estimate warm, and it is the fallback on a head
        // unit whose vendor never implemented the fused sensors.
        if (gyro != null)
            sm.registerListener(sensorListener, gyro, SensorManager.SENSOR_DELAY_GAME, sensorHandler)
        // Not for a heading -- the rotation vector already fuses it -- but to
        // know when the field around the phone stopped being the Earth's.
        fieldPlausible = true
        sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI, sensorHandler)
        }

        val grav = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (grav != null)
            sm.registerListener(sensorListener, grav, SensorManager.SENSOR_DELAY_UI, sensorHandler)
    }

    private fun stopSensors() {
        runCatching { sensors?.unregisterListener(sensorListener) }
        // After unregistering, so no callback can arrive on a dead looper.
        runCatching { sensorThread?.quitSafely() }
        sensorThread = null
        sensorHandler = null
    }

    /** The fix to draw from: the service's while it is navigating, else ours. */
    private fun currentFix(nowMs: Long): Location? {
        val svc = HudService.lastLocation
        if (svc != null) {
            val ageMs = nowMs - svc.elapsedRealtimeNanos / 1_000_000L
            if (ageMs in 0..5_000) return svc
        }
        return ownFix
    }


    /**
     * Set text only when it differs.
     *
     * TextView.setText has no equality check of its own, and every one of
     * these is wrap_content -- so AOSP's checkForRelayout() takes the "dynamic
     * width, so we have no choice" branch, throws away the StaticLayout and
     * calls requestLayout(). Writing eleven of them five times a second forced
     * a full measure-and-layout pass over the whole screen at 5 Hz, for text
     * that is usually identical, and that pass also delayed the next camera
     * frame -- so it stuttered twice.
     */
    private fun android.widget.TextView.setIfChanged(v: CharSequence) {
        if (!android.text.TextUtils.equals(text, v)) text = v
    }

    // ---- camera and overlays ----------------------------------------------

    /**
     * The camera runs at ~30 fps from its own loop, with plain moveCamera
     * jumps and time-based smoothing in NavCamera.
     *
     * The once-a-second stutter had a specific cause: a 260 ms camera ease was
     * being restarted from a 200 ms UI timer towards positions that only
     * changed every 250 ms. Three clocks that close together beat against each
     * other at ~1 Hz, and every beat visibly paused the animation. Now there
     * is one clock: each frame extrapolates the car forward from the age of
     * the last fix (which also smooths live GPS, arriving at only 1 Hz) and
     * jumps the camera there; the easing lives in NavCamera's time constants.
     *
     * That clock is now the display's own. A 33 ms postDelayed against a
     * 16.67 ms vsync is *just* short of two frames, so the phase drifts and
     * about every three and a half seconds a camera update misses its vsync
     * and the map advances three frames instead of two -- a beat, and a
     * visible hitch, on a device that is otherwise keeping up perfectly.
     * Choreographer fires exactly once per displayed frame, phase-locked, so
     * there is nothing left to beat against. Frames are then gated by elapsed
     * time rather than by counting every other callback: "every other frame"
     * is 30 Hz on a 60 Hz panel but 60 Hz on the 120 Hz phone and 45 on a 90,
     * which is main-thread work the fastest device has the least reason to
     * spend. Vsync still decides *when*; this only decides how often.
     */
    // There used to be a skipNextFrame here, dropping the frame after any that
    // overran its vsync interval so the loop could "catch up". Choreographer
    // has no backlog to catch up on: a callback that overruns just misses the
    // vsyncs it overlapped, and the next one arrives at the first vsync after
    // it returns. The renderer coalesces camera updates the same way. So the
    // skip bought nothing and cost a second dropped frame after every slow
    // one -- a hitch doubled -- and it is gone.
    private var lastFrameNanos = 0L

    private val frameCallback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            android.view.Choreographer.getInstance().postFrameCallback(this)
            // Count *every* vsync, not just the ones we act on: a frame the
            // system never delivered is exactly what a stutter is.
            if (frameWindowStartNs == 0L) frameWindowStartNs = frameTimeNanos
            frameCount++
            if (frameTimeNanos - frameWindowStartNs >= 1_000_000_000L) {
                val secs = (frameTimeNanos - frameWindowStartNs) / 1e9
                val hz = frameCount / secs
                val avg = if (framesDrawn > 0) frameSumMs / framesDrawn else 0.0
                perfLine = "vsync %.0f Hz · cam %d/s avg %.1f ms worst %.1f ms · %s"
                    .format(hz, framesDrawn, avg, frameWorstMs, HudService.fixQuality)
                frameCount = 0; framesDrawn = 0
                frameSumMs = 0.0; frameWorstMs = 0.0
                frameWindowStartNs = frameTimeNanos
            }
            if (lastFrameNanos != 0L && frameTimeNanos - lastFrameNanos < FRAME_MIN_NS) return
            lastFrameNanos = frameTimeNanos
            val workStart = System.nanoTime()
            // Logged, and only once. This runs every vsync; an unguarded throw
            // here freezes the marker and the camera for the rest of the
            // drive, and swallowing it silently means there is nothing to find
            // afterwards. Once is enough to know.
            val outcome = runCatching { cameraFrame() }
            outcome.onFailure {
                if (!cameraLoopFailed) {
                    cameraLoopFailed = true
                    android.util.Log.e(TAG, "camera frame failed", it)
                }
            }
            val ms = (System.nanoTime() - workStart) / 1e6
            framesDrawn++
            frameSumMs += ms
            if (ms > frameWorstMs) frameWorstMs = ms
        }
    }

    private fun cameraFrame() {
        val m = map ?: return
        val now = SystemClock.elapsedRealtime()
        val loc = currentFix(now) ?: run { showWaitingForGps(true); return }

        val dt = if (lastCamFrameMs == 0L) 0.033
                 else (now - lastCamFrameMs).coerceIn(10L, 200L) / 1000.0
        lastCamFrameMs = now

        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
        val gpsBrg = if (loc.hasBearing()) loc.bearing.toDouble() else null

        // Every new fix is a correction for the heading filter. Driving this
        // from the fix's own timestamp means demo mode and live GPS take the
        // identical path through the code.
        val fixNs = loc.elapsedRealtimeNanos
        if (fixNs != lastFusedFixNs) {
            val dtFix = if (lastFusedFixNs == 0L) 1.0
                        else ((fixNs - lastFusedFixNs) / 1e9).coerceIn(0.05, 5.0)
            fusion.setClock(android.os.SystemClock.elapsedRealtime())
            // The car's speed when the bus reports one, so "moving" and
            // "stationary" mean the same thing here as everywhere else.
            fusion.onFix(gpsBrg, HudService.carSpeedMps ?: speed, dtFix)
            updateDeclination(loc.latitude, loc.longitude, loc.altitude)
            learnMountingOffset(gpsBrg, speed)
            lastFusedFixNs = fixNs
            // A real GPS fix, and a fresh one: the last-known position handed
            // over at start-up and cell/wifi fixes do not count (1.29).
            fixIsGps = Fixes.of(loc, now).isGps
            val ageMs = now - fixNs / 1_000_000L
            if (fixIsGps && ageMs in 0..FIX_HOLD_MS) lastGpsAtMs = fixNs / 1_000_000L
        }
        // The HUD's own IMU when one is fitted and reporting, the phone's own
        // when it is not, and plain GPS heading when the device has no
        // gyroscope at all -- which plenty of Android head units do not.
        val wasExternal = externalImu
        // One read of one object: rate and timestamp can no longer be paired
        // from two different samples. See HudService.ImuSample.
        val imu = HudService.imuSample
        externalImu = imu != null && now - imu.atMs < IMU_TIMEOUT_MS
        // Only a *new* IMU sample. The rate was re-applied every frame for as
        // long as the link had not timed out, so a cable that stalled mid-turn
        // kept the arrow rotating at the last rate for a second and a half --
        // up to sixty degrees of turn the car never made.
        val imuAt = imu?.atMs ?: 0L
        if (imu != null && externalImu && imuAt != lastImuAtMs) {
            // Integrate over the interval this sample actually covers, not
            // over the camera frame. The board sends at 20 Hz and this loop
            // runs at 30, so charging each sample 33 ms instead of 50 turned
            // every 90-degree roundabout into a 60-degree one, with no GPS
            // bearing below 0.7 m/s to pull it back.
            // Clamped to the fusion's own maximum step rather than to an
            // arbitrary 0.5 s. The two used to disagree -- this clamped to 0.5,
            // the fusion rejected anything over 0.25 -- which opened a band
            // where the sample was silently dropped after lastImuAtMs had
            // already advanced past it, so the rotation was lost outright.
            val imuDt = if (lastImuAtMs == 0L) dt
                        else ((imuAt - lastImuAtMs) / 1000.0)
                            .coerceIn(0.005, HeadingFusion.MAX_GYRO_STEP_S)
            lastImuAtMs = imuAt
            fusion.onExternalYawRate(imu.rateDps, imuDt)
        }
        // Only on a real transition. This used to fire whenever a camera frame
        // found no *new* IMU sample -- about one frame in three at 20 Hz into a
        // 30 Hz loop -- which demoted the source ten times a second and let the
        // phone's own rotation vector integrate the same turn the board was
        // already integrating.
        else if (wasExternal && !externalImu) {
            lastImuAtMs = 0L
            fusion.onExternalLost(phoneGyroPresent)
        }

        // Every frame, not only when something arrives to feed the filter.
        //
        // The clock is what the fusion ages its own state against -- how long
        // since the last correction, how stale the compass is, whether the
        // heading has decayed past being worth showing. It was only being set
        // on a GPS fix and on a rotation-vector sample, which is fine on a
        // phone in a city and is the entire problem on a head unit: no sensors
        // at all, and often no fix for minutes. The clock then never advanced,
        // so the fusion believed no time had passed and held a heading from
        // before the tunnel as though it had just been measured.
        fusion.setClock(now)

        // Stationary comes from the car, not from GPS, whenever the car is
        // talking. A parked receiver reports a metre or two a second of
        // multipath wander, which is above the fusion's stationary threshold,
        // so the parked deadband never engaged and the arrow crept while the
        // handbrake was on. The bus reads a flat zero.
        HudService.carSpeedMps?.let { fusion.noteVehicleSpeed(it) }

        // The board's compass, when one is fitted. This is the only absolute
        // heading in the system on a head unit -- it has no sensors of its own
        // -- so it is what makes the arrow point the right way before the car
        // has moved.
        HudService.magHeading(now)?.let { fusion.onExternalCompass(it, now) }

        // Seed the arrow from the heading the car was left with.
        //
        // Without this the fusion starts at null and the marker is drawn at
        // zero -- which is not "unknown", it is "pointing north" said with
        // total confidence, and it is wrong roughly three times in four. The
        // service has been carrying the heading through the gyro and across
        // the shutdown, so by the time the map opens there is usually a real
        // answer waiting. Once only: after that the fusion owns it.
        if (!seededFromParked) {
            HudService.parkedHeadingDeg?.let { fusion.set(it); seededFromParked = true }
        }

        // A new route: turn the map to face the way it sets off, without
        // waiting for the car to move. Standing still there is no heading to
        // filter -- the car has none -- but the route does, and that is the
        // first thing you want to see after accepting a destination.
        if (HudService.routeGeneration != facedRouteGeneration) {
            facedRouteGeneration = HudService.routeGeneration
            HudService.routeInitialBearing?.let { navCamera.faceBearing(it); forgetDrawnCamera() }
        }

        val route = HudService.currentRoute
        // Both snaps are on by default again (1.27) -- see Prefs.snapToRoad.
        val snapWanted = snapToRoad
        // Read once: the service writes snapOnRoute before snapTrusted.
        val trusted = HudService.snapTrusted
        val onLine = HudService.snapOnRoute
        val onRouteSnap = snapWanted && route != null && trusted && onLine
        // Free drive snaps too, to the road network rather than to a route
        // line, so the marker never sits in somebody's living room -- and so
        // does a route, once the car is off its line. Since 1.29 that road is
        // the map's own lock (map/RoadLock), set below, not the service's
        // per-fix match: that one let go when a parked fix wandered.
        var freeSnap = false

        // ---- no GPS, no arrow (1.29) ----------------------------------------
        // Before the first real GPS fix the arrow was drawn at the last-known
        // or cell position, in the wrong street; and once GPS is gone for
        // longer than the arrow may coast, it is a guess again. Say so instead.
        // A tunnel keeps its arrow: coasting counts as a position.
        val coastMs = if (route == null) COAST_FREE_MS
            else if (HudService.carSpeedMps != null) COAST_ON_ROUTE_MS
            else RouteTracker.COAST_NO_BUS_MAX_MS
        val noGps = lastGpsAtMs == 0L || now - lastGpsAtMs > FIX_HOLD_MS + coastMs
        if (!noGps && waitingForGps) {
            // GPS is back: put the arrow on it rather than glide it from the guess.
            routeMotion.reset()
            freeMotion.reset()
        }
        showWaitingForGps(noGps)

        // ---- where to draw the car ------------------------------------------
        var lat: Double
        var lon: Double
        var roadBrg: Double? = null

        // The car's own speed in preference to the GPS one, because in a
        // tunnel the GPS speed is as stale as the position it came with, while
        // the bus keeps reporting. Falls back to the fix's speed on a phone,
        // where there is no bus -- and with the fix gone that is its last one.
        // The service's fix first: in a tunnel it holds the last GPS one, where
        // our own may by then be a cell fix with a made-up speed.
        val refSpeed = HudService.carSpeedMps
            ?: HudService.lastLocation?.takeIf { it.hasSpeed() }?.speed?.toDouble() ?: speed
        val fixAgeMs = (now - fixNs / 1_000_000L).coerceAtLeast(0L)

        if (onRouteSnap && route != null) {
            // Move *along the route*, not across open ground. This is the fix
            // for the marker sitting beside the road: the position drawn is a
            // point on the road geometry by construction, so a fix that lands
            // in the building next door cannot move it off the tarmac.
            //
            // A new route is a new set of distances; carrying the old drawn
            // position over would leave the marker waiting kilometres ahead.
            if (route !== motionRoute) { routeMotion.reset(); motionRoute = route }
            val alongAgeMs = (now - HudService.alongAtMs).coerceAtLeast(0L)
            // Stale either way means a tunnel: coast along the line on the
            // car's speed, for as long as the service does. See PuckMotion.
            puckAlong = routeMotion.step(dt, HudService.alongM, alongAgeMs / 1000.0, refSpeed,
                fixAvailable = alongAgeMs <= FIX_HOLD_MS && fixAgeMs <= FIX_HOLD_MS,
                limitS = (if (HudService.carSpeedMps != null) COAST_ON_ROUTE_MS
                          else RouteTracker.COAST_NO_BUS_MAX_MS) / 1000.0)
            val p = Geo.pointAlong(route.pts, route.cum, puckAlong)
            lat = p[0]; lon = p[1]
            roadBrg = Geo.bearingAlong(route.pts, route.cum, puckAlong)
            // Free motion picks up from here if the car leaves the line, and
            // the road lock picks its road afresh.
            freeMotion.reset(lat, lon)
            roadLock.reset()
            lockedFixNs = 0L
        } else {
            // Free driving, or off the route: the same rubber band, run along
            // the heading -- or along the locked road, which is where the
            // marker is put back last. See FreeDriveMotion.
            routeMotion.reset()
            puckAlong = Double.NaN
            // GPS only: a cell fix is no position to draw or to lock on to.
            val live = fixAgeMs <= FIX_HOLD_MS && fixIsGps
            if (!snapWanted) roadLock.reset()
            else if (live && fixNs != lockedFixNs) {
                lockedFixNs = fixNs
                roadLock.update(HudService.roadArea, loc.latitude, loc.longitude,
                    HudService.carSpeedMps ?: speed, gpsBrg, fusion.heading)
            }
            // Kept through an underpass, which is exactly when the coast
            // wants it: the lock only changes on a live fix.
            val road = roadLock.road
            freeSnap = road != null
            if (road != null) roadBrg = roadLock.bearingDeg
            // Standing still, the lock holds the position; so must the band,
            // or it runs ahead on the GPS speed's wander.
            val held = live && roadLock.holding
            val p = freeMotion.step(dt,
                if (road != null) roadLock.lat else loc.latitude,
                if (road != null) roadLock.lon else loc.longitude,
                fixAgeMs / 1000.0, if (held) 0.0 else refSpeed, live,
                fusion.heading ?: gpsBrg ?: roadBrg, road?.pts)
            lat = p[0]; lon = p[1]
        }
        val snapped = onRouteSnap || freeSnap

        // ---- which way it points --------------------------------------------
        //
        // On the route, the road direction wins: it is exactly aligned with the
        // tarmac under the marker and it has none of the noise a GPS bearing
        // carries. Off it, the gyro-corrected heading is the best we have.
        // Cold start with no fix history: point along the road we are sitting
        // on, so the first frame is plausible rather than due north. The
        // orientation sensor takes it from there, and GPS corrects it the
        // moment we move.
        if (fusion.heading == null) (roadBrg ?: gpsBrg)?.let { fusion.set(it) }

        // Standing still, the *car* wins over the road. A road bearing is the
        // direction of the tarmac, which is the right answer at 50 km/h and
        // the wrong one on a driveway: it cannot tell which of the two ways
        // along the road you are facing, and it does not move when you swing
        // the car round into a space. The orientation sensor can and does.
        //
        // Moving (over 5 km/h, see HeadingFusion.moving) the fused heading is
        // the smoothed GPS course alone, and the road bearing wins whenever the
        // marker is snapped -- for the map and the arrow both.
        val moving = fusion.moving
        // The car's speed, as `moving` uses: parked, GPS wanders at 1-2 m/s.
        val stopped = (HudService.carSpeedMps ?: speed) < HeadingFusion.STATIONARY_MPS
        val targetHeading = if (snapped && (moving || !stopped)) (roadBrg ?: fusion.heading ?: gpsBrg)
                            else (fusion.heading ?: roadBrg ?: gpsBrg)

        // What the *arrow* points at, which is not always what turns the map.
        //
        // The map wants a stable heading: the road's own bearing, so it does
        // not wobble with every sensor twitch while you drive down a straight
        // street. The arrow wants the compass -- that is the whole point of
        // having one, and it is how you check the thing works: turn the phone
        // in your hand and the arrow turns.
        //
        // These were the same value, and the road bearing won whenever the car
        // was snapped to a road and moving. So below 70 km/h the compass was
        // in charge of a number nothing displayed: turning the phone at 23 km/h
        // did precisely nothing, which is exactly what was reported. With a
        // learned mounting offset the compass reads the car's heading anyway,
        // so in a cradle the arrow still points up the road -- it only diverges
        // when the phone is picked up, which is when you want it to.
        // ...but only once the mounting offset means something. Unlearned, the
        // compass reads the *phone's* heading, not the car's, and the two
        // differ by however the phone happens to be sitting -- so at 10 km/h
        // on a matched road the map would point along the street while the
        // arrow pointed up to 45 degrees off it, which reads as broken rather
        // than as uncalibrated. Above 14 km/h the first GPS bearing takes the
        // offset outright, so this resolves itself within a few seconds of
        // real driving.
        val arrowHeading = com.mihai.navhud.map.Marker.headingFor(
            compassHeading = fusion.heading,
            mapHeading = targetHeading,
            compassDriving = fusion.usingCompass,
            mountKnown = learnedThisDrive || Prefs.headingCalibrated(this),
            moving = moving
        )
        if (arrowHeading != null) {
            // Eased, and never faster than NavCamera.MAX_TURN_DPS -- a U-turn
            // or the first fix after a tunnel swings round instead of
            // flipping, in step with the map. Only the very first heading is
            // taken whole.
            puckBearing = if (puckBearing.isNaN()) arrowHeading
                          else NavCamera.turnToward(puckBearing, arrowHeading, dt, 0.2)
        }

        updatePuck(lat, lon, puckBearing)
        if (arrowDebug) showArrowDebug(now, onRouteSnap, freeSnap, noGps, trusted, loc)
        // The route line starts under the arrow: from the arrow's own distance
        // while it runs on the line, else from the service's projection.
        maybeDrawRouteLine(route, if (onRouteSnap) puckAlong else HudService.alongM,
            System.nanoTime())
        HudService.headingSource = fusion.describe()
        // A pinch or tilt in progress owns the camera until it ends.
        if (!following || scaling || shoving) return

        val manDist = HudService.lastFrame
            ?.takeIf { it.distToManeuverM > 0 }?.distToManeuverM?.toDouble() ?: -1.0
        navCamera.update(speed, targetHeading, dtSeconds = dt, maneuverDistM = manDist,
            // Snapped to a route the heading is the road's own direction, and
            // a gyro heading is noise-free too -- both are worth following
            // through a slow turn instead of freezing the map.
            headingTrusted = snapped ||
                fusion.source != HeadingFusion.Source.GPS_ONLY)
        val aim = navCamera.lookAhead(lat, lon, speed, targetHeading)
        val topPad = navCamera.topPaddingPx(mapView.height, bottomChromePx())
        val botPad = bottomChromePx()
        // Same reasoning as updatePuck: moveCamera() has no equality check of
        // its own, and NavCamera's smoothing is exponential, so an untouched
        // camera still "changed" every frame and the map re-rendered at 30 Hz
        // for ever. Everything below the thresholds is sub-pixel at nav zooms.
        val moved = camShownLat.isNaN() ||
            Geo.haversine(camShownLat, camShownLon, aim[0], aim[1]) > CAM_EPSILON_M ||
            kotlin.math.abs(Geo.signedDelta(navCamera.bearing, camShownBrg)) > CAM_EPSILON_DEG ||
            kotlin.math.abs(navCamera.zoom - camShownZoom) > CAM_EPSILON_ZOOM ||
            kotlin.math.abs(navCamera.tilt - camShownTilt) > CAM_EPSILON_DEG ||
            topPad != camShownTopPad || botPad != camShownBotPad
        if (!moved) return
        camShownLat = aim[0]; camShownLon = aim[1]
        camShownBrg = navCamera.bearing; camShownZoom = navCamera.zoom
        camShownTilt = navCamera.tilt; camShownTopPad = topPad; camShownBotPad = botPad

        val pos = CameraPosition.Builder()
            .target(LatLng(aim[0], aim[1]))
            .zoom(navCamera.zoom)
            .bearing(navCamera.bearing)
            .tilt(navCamera.tilt)
            // top = (2f-1)H + B and bottom = B anchor the car at exactly f*H.
            .padding(0.0, topPad.toDouble(), 0.0, botPad.toDouble())
            .build()
        m.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
    }

    /**
     * The (i) is the OpenStreetMap credit the licence requires, so it stays --
     * but it sat on the trip pill's top-left corner. Now just above the pill,
     * at the screen edge: clear of the pill, the dial and the cards (1.29).
     */
    private fun placeAttribution() {
        val m = map ?: return
        if (tripPill.height == 0) return
        val d = resources.displayMetrics.density
        m.uiSettings.setAttributionMargins((4 * d).toInt(), 0, 0,
            mapView.height - tripPill.top + (4 * d).toInt())
    }

    /**
     * How much of the map is covered by chrome along the bottom. The camera
     * padding uses this so the car marker sits clear of the strip instead of
     * disappearing behind it.
     */
    private fun bottomChromePx(): Int {
        // The trip pill and the speed dial are the only things down there now,
        // and they are corner-anchored rather than a full-width strip, so the
        // marker only has to clear the taller of the two plus its margin.
        val d = resources.displayMetrics.density
        val pill = if (::tripPill.isInitialized && tripPill.height > 0) tripPill.height
                   else (68 * d).toInt()
        val cluster = if (speedCluster.height > 0) speedCluster.height else (94 * d).toInt()
        return maxOf(pill, cluster) + (14 * d).toInt()
    }

    /**
     * Work out, while driving, how the phone is mounted.
     *
     * At road speed a GPS bearing is the direction the car actually moved, so
     * the difference between it and the compass reading *is* the mounting
     * offset — the cradle angle, which way up the phone sits, and whatever the
     * car's bodywork does to the magnetic field, all rolled into one number.
     * Learn it once per drive and keep it; the driver never has to do anything.
     *
     * The calibration screen exists for the case this cannot handle: a phone
     * that has been moved to a different holder since the last drive, on a
     * journey that has not yet reached road speed.
     */
    /** Surface.ROTATION_0/90/180/270 for the window this activity is in. */
    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            display?.rotation ?: android.view.Surface.ROTATION_0
        else
            @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation

    /**
     * Magnetic north to true north, for where we actually are.
     *
     * Recomputed when the car has moved far enough to matter rather than once
     * per fix — the model is a polynomial evaluation and the answer changes by
     * a degree over hundreds of kilometres. OsmAnd computes it once on the
     * first fix of the process and never again, which is fine for a commute
     * and wrong on the drive to Romania.
     */
    private fun updateDeclination(lat: Double, lon: Double, altM: Double) {
        if (!declinationAtLat.isNaN() &&
            Geo.haversine(declinationAtLat, declinationAtLon, lat, lon) < DECLINATION_REFRESH_M) return
        runCatching {
            android.hardware.GeomagneticField(
                lat.toFloat(), lon.toFloat(), altM.toFloat(), System.currentTimeMillis()
            ).declination.toDouble()
        }.onSuccess {
            declinationDeg = it
            Prefs.setDeclination(this, it)
            declinationAtLat = lat
            declinationAtLon = lon
        }
    }

    /**
     * Keep the same axis until it genuinely stops working.
     *
     * The axis is which end of the phone we take the heading from, and the two
     * candidates disagree by the phone's roll angle. Re-deciding per sample
     * turns a leaning cradle into an arrow that flips through a right angle on
     * every bump; so it is decided once, persisted, and only reconsidered
     * after the current one has been unusable for a full second — which means
     * the phone was picked up and put somewhere else, not that it was jostled.
     */
    private fun holdAxis(r: DoubleArray) {
        val rot = displayRotation()
        if (Compass.axisUsable(r, rot, headingAxis)) { axisBadSinceMs = 0L; return }
        val now = android.os.SystemClock.elapsedRealtime()
        if (axisBadSinceMs == 0L) { axisBadSinceMs = now; return }
        if (now - axisBadSinceMs < AXIS_SWITCH_AFTER_MS) return
        axisBadSinceMs = 0L
        val fresh = Compass.bestAxis(r, rot)
        if (fresh == headingAxis) return
        // This runs on the sensor thread; learnMountingOffset runs on the main
        // one, and between them they read-modify-write the same three fields
        // and then persist them. @Volatile gives visibility, not atomicity, so
        // an interleaving could store an offset measured against one axis
        // alongside the *other* axis's id -- a pair wrong by whatever rotation
        // the mount applies, and wrong for every later drive. One owner thread
        // for the triple instead.
        ui.post { switchHeadingAxis(fresh) }
    }

    /** Main thread only. See [holdAxis]. */
    private fun switchHeadingAxis(fresh: Int) {
        if (fresh == headingAxis) return
        headingAxis = fresh
        // The learned mounting offset was measured against the *old* axis and
        // means nothing against the new one -- the two differ by whatever
        // rotation the mount applies, up to a right angle. Rather than blanking
        // it (which points the arrow somewhere arbitrary until the next fix)
        // or keeping it (which points it somewhere confidently wrong), mark it
        // unlearned: the next GPS bearing then takes the offset outright
        // instead of blending into it, which is a second or two of driving.
        learnedThisDrive = false
        Prefs.setHeadingAxis(this, headingAxis)
        Prefs.setHeadingCalibrated(this, false)
    }

    /** Guards absM, which the sensor thread writes and the frame loop reads. */
    private val absLock = Any()
    private val absCopy = DoubleArray(9)

    private fun learnMountingOffset(gpsBrg: Double?, speed: Double) {
        if (gpsBrg == null || speed < LEARN_MIN_MPS) return
        if (!hasAbsolute) return
        // A copy, under the lock. Reading absM directly from this thread while
        // the sensor thread was halfway through writing it would mix two
        // attitudes into one matrix -- and whatever offset that produced would
        // be persisted.
        synchronized(absLock) { System.arraycopy(absM, 0, absCopy, 0, 9) }
        val az = Compass.headingFor(absCopy, displayRotation(), headingAxis) ?: return
        // Against the declination-corrected reading, so the offset is purely
        // the mounting angle and does not quietly absorb the declination too.
        val fresh = Compass.offsetFor(Geo.normalizeDeg(az + declinationDeg), gpsBrg)
        headingOffset =
            if (!learnedThisDrive) fresh
            else Compass.blendOffset(headingOffset, fresh, LEARN_BLEND)
        val first = !learnedThisDrive
        learnedThisDrive = true
        // Written on the first learn of a drive, and after that only when it
        // has actually moved. A GPS fix arrives every second for hours.
        if (first || Math.abs(Geo.signedDelta(headingOffset, savedOffset)) > 2.0) {
            savedOffset = headingOffset
            Prefs.setHeadingOffset(this, headingOffset)
            Prefs.setHeadingAxis(this, headingAxis)
            Prefs.setHeadingCalibrated(this, true)
        }
    }

    // ---- quick actions -----------------------------------------------------

    private fun showQuickActions() {
        updateVoiceButton()
        // The numbers, if they were asked for. Refreshed while the sheet is up.
        if (Prefs.showPerf(this)) {
            perfOverlay.visibility = View.VISIBLE
            perfOverlay.text = perfSummary()
            ui.post(perfTick)
        } else {
            perfOverlay.visibility = View.GONE
        }
        quickScrim.visibility = View.VISIBLE
    }

    private val perfTick = object : Runnable {
        override fun run() {
            if (quickScrim.visibility != View.VISIBLE) return
            perfOverlay.text = perfSummary()
            ui.postDelayed(this, 500)
        }
    }

    /**
     * Everything worth knowing when something feels slow, in four lines.
     *
     * Screenshot this instead of describing it. "Stutters" can mean the frames
     * are late, the frames are fine but the map is not moving, the GPS is not
     * feeding it, or the CPU is busy elsewhere -- and those have nothing in
     * common except how they look from the driver's seat.
     */
    /** See Prefs.arrowDebug. Read on resume; the frame loop runs at 30 Hz. */
    private var arrowDebug = false
    private var arrowDebugAtMs = 0L

    /**
     * One line on the map saying why the arrow is where it is: on the route
     * line, LOCKed to an OSM road (which, and whether held still), or on the
     * raw fix and why -- with the distance from that line or road, the
     * service's trust flag, the fix provider and accuracy -- and the camera
     * alert, to catch a late warning in the act. Twice a second.
     */
    private fun showArrowDebug(
        now: Long, onRoute: Boolean, locked: Boolean, noGps: Boolean, trusted: Boolean,
        loc: Location
    ) {
        if (now - arrowDebugAtMs < 500L) return
        arrowDebugAtMs = now
        arrowDebugView.visibility = View.VISIBLE
        val r = roadLock.road
        val mode = when {
            onRoute -> "ROUTE"
            locked && r != null -> "LOCK " + r.label.ifBlank { "#${r.id}" } +
                (if (roadLock.holding) " held" else "")
            HudService.roadArea == null -> "RAW no road data"
            else -> "RAW no road ≤${RoadLock.PICK_M.toInt()} m"
        }
        arrowDebugView.text = "%s%s · cross %.0f m · trusted %s · %s acc %s".format(
            if (noGps) "WAIT no GPS · " else "", mode,
            if (locked) roadLock.crossM else HudService.crossTrackM,
            if (trusted) "yes" else "no", loc.provider ?: "?",
            if (loc.hasAccuracy()) "%.0f m".format(loc.accuracy) else "?") +
            // Which camera the alert is on, how far, which stage: a late
            // warning shows up here as the id changing close in.
            (HudService.cameraAlert?.let {
                "\ncam #${it.camera.id} ${it.distanceM} m stage ${it.stage + 1}" +
                    (if (it.passed) " passed" else "")
            } ?: "\ncam none")
    }

    private fun perfSummary(): String = buildString {
        append("NavHUD ").append(BuildConfig.VERSION_NAME)
        append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
        append(perfLine).append('\n')
        append("tick ").append("%.1f".format(HudService.tickCostTenthMs / 10.0))
        append(" ms · roads ")
        append(HudService.areaRoadCount).append(" · cams ").append(HudService.cameraCount)
        append('\n')
        append(HudService.headingSource).append(" · ").append(HudService.fixQuality)
    }

    private fun hideQuickActions() {
        quickScrim.visibility = View.GONE
    }

    /**
     * The two lines in the trip pill. Kept in one place so the "no route" and
     * "no frame yet" paths cannot disagree about what an empty pill looks like.
     */
    private fun openSearch() {
        startActivityForResult(Intent(this, SearchActivity::class.java), REQ_SEARCH)
    }

    /**
     * The two lines in the trip pill, and how loud the search button is.
     *
     * With a destination the pill is an instrument: time left, distance,
     * arrival, and the magnifier steps back to a plain glyph you use to change
     * where you are going. Without one it is an invitation, and it has to look
     * like one — this is the only route into the app's main function, and the
     * previous version buried it as a grey icon next to a road name.
     */
    private var trippedPrompting: Boolean? = null
    private val tintPrompting by lazy {
        android.content.res.ColorStateList.valueOf(amber)
    }
    private val tintPlain by lazy {
        android.content.res.ColorStateList.valueOf(Color.parseColor("#E6E9EE"))
    }

    private fun setTrip(main: String, sub: String?, prompting: Boolean = false) {
        // Only when it changes. This ran on every 200 ms tick, allocating a
        // fresh ColorStateList each time, and ImageView.setImageTintList always
        // re-applies and invalidates whether or not the value differs.
        if (trippedPrompting != prompting) {
            trippedPrompting = prompting
            searchIcon.setBackgroundResource(
                if (prompting) R.drawable.bg_search_cta else 0)
            searchIcon.imageTintList = if (prompting) tintPrompting else tintPlain
            tripMain.setTextColor(if (prompting) amber else Color.WHITE)
        }
        tripMain.setIfChanged(main)
        if (sub.isNullOrBlank()) {
            tripSub.visibility = View.GONE
        } else {
            tripSub.visibility = View.VISIBLE
            tripSub.setIfChanged(sub)
        }
    }

    /**
     * @param bearing the heading, or NaN when we genuinely do not know one
     */
    /**
     * Forget what we last pushed to the map.
     *
     * The no-change early-out compares against the last values *we* applied.
     * Anything that moves MapLibre's camera without going through
     * cameraFrame() -- a drag or a pinch while follow is off, a style reload,
     * a resize -- leaves those values describing a camera that is no longer
     * there. Tapping follow then compared the freshly reset NavCamera against
     * the stale cache, found them equal to within a fifth of a metre, and
     * never called moveCamera at all: the map simply stayed where the finger
     * left it until the car drove off. Same for the marker.
     */
    private fun forgetDrawnCamera() {
        camShownLat = Double.NaN
        puckShownLat = Double.NaN
    }

    private var camShownLat = Double.NaN
    private var camShownLon = Double.NaN
    private var camShownBrg = Double.NaN
    private var camShownZoom = Double.NaN
    private var camShownTilt = Double.NaN
    private var camShownTopPad = -1
    private var camShownBotPad = -1

    private var puckShownLat = Double.NaN
    private var puckShownLon = Double.NaN
    private var puckShownBrg = Double.NaN
    /** Which image the puck layer holds; null = not set since the style loaded. */
    private var puckShownDot: Boolean? = null
    /** The puck layer of the current style. See newPuckLayer. */
    private var puckLayer: org.maplibre.android.style.layers.Layer? = null
    /** Set instead of [puckLayer] when the indicator layer is unavailable. */
    private var puckFallback: GeoJsonSource? = null

    /**
     * No GPS worth drawing: hide the arrow and say "Waiting for GPS" in the
     * middle of the map, rather than show a car at a guessed position (1.29).
     * The layer is looked up by id, so the fallback symbol puck hides too.
     */
    private fun showWaitingForGps(waiting: Boolean) {
        waitingForGps = waiting
        val v = if (waiting) View.VISIBLE else View.GONE
        if (waitingGpsView.visibility != v) waitingGpsView.visibility = v
        if (puckHidden == waiting) return
        val layer = map?.style?.getLayer(PUCK_LAYER) ?: return
        runCatching {
            layer.setProperties(PropertyFactory.visibility(
                if (waiting) Property.NONE else Property.VISIBLE))
            puckHidden = waiting
        }.onFailure { android.util.Log.e(TAG, "puck visibility failed", it) }
    }

    private fun updatePuck(lat: Double, lon: Double, bearing: Double) {
        if (puckLayer == null && puckFallback == null) return
        // Setting a layer property marks the map dirty, and MapLibre renders
        // when dirty -- so re-setting this every frame was, by itself, enough
        // to keep the GPU redrawing the whole map while the car was parked at
        // a red light. Both smoothing filters here approach their target and
        // never reach it: the position kept "changing" by nanometres for ever.
        val samePlace = !puckShownLat.isNaN() &&
            Geo.haversine(puckShownLat, puckShownLon, lat, lon) < PUCK_EPSILON_M &&
            (bearing.isNaN() == puckShownBrg.isNaN()) &&
            (bearing.isNaN() ||
                kotlin.math.abs(Geo.signedDelta(bearing, puckShownBrg)) < PUCK_EPSILON_DEG)
        if (samePlace) return
        puckShownLat = lat; puckShownLon = lon; puckShownBrg = bearing

        // An arrow is a claim about which way you are facing. Before the first
        // fix there is nothing to base that claim on, and the map used to draw
        // the arrow at zero -- which does not read as "unknown", it reads as
        // "pointing north", stated with complete confidence. A plain dot says
        // the true thing: here you are, direction not known yet. Google Maps
        // does the same, and switches to a chevron the moment it can.
        val dot = bearing.isNaN()
        val brg = if (dot) 0.0 else Geo.normalizeDeg(bearing)
        val layer = puckLayer
        if (layer != null) {
            val ok = runCatching {
                if (dot != puckShownDot) {
                    puckShownDot = dot
                    layer.setProperties(
                        LayoutPropertyValue("bearing-image", if (dot) PUCK_DOT_ICON else PUCK_ICON))
                }
                // The bearing is the absolute heading, clockwise from true
                // north; the layer applies the camera's own rotation itself.
                layer.setProperties(
                    PaintPropertyValue("location", arrayOf(lat, lon, 0.0)),
                    PaintPropertyValue("bearing", brg)
                )
            }.onFailure { android.util.Log.e(TAG, "puck layer update failed", it) }.isSuccess
            if (ok) return
            // Reflection into a package-private class: if a property ever stops
            // being accepted, swap to the symbol rather than lose the arrow.
            puckLayer = null
            map?.style?.let { s ->
                runCatching { s.removeLayer(layer) }
                puckFallback = installFallbackPuck(s)
                puckHidden = null
            }
        }
        puckFallback?.let { src ->
            val f = Feature.fromGeometry(Point.fromLngLat(lon, lat))
            f.addStringProperty("icon", if (dot) PUCK_DOT_ICON else PUCK_ICON)
            f.addNumberProperty("bearing", brg)
            runCatching { src.setGeoJson(f) }
        }
    }

    /**
     * Paint the routes you are *not* on, in grey, under the one you are.
     *
     * This is the half of the picker that the card list cannot do: two routes
     * that differ by four minutes may differ by forty kilometres and a
     * completely different half of the country, and no amount of "via E40"
     * conveys that.
     */
    private fun drawAlternatives(alts: List<Route>, current: Route?) {
        val src = map?.style?.getSourceAs<GeoJsonSource>(ALT_SOURCE) ?: return
        val feats = ArrayList<Feature>()
        for (r in alts) {
            if (r === current || r.pts.size < 2) continue
            val stride = maxOf(1, r.pts.size / MAX_POLY_POINTS)
            val pts = ArrayList<Point>(r.pts.size / stride + 2)
            var k = 0
            while (k < r.pts.size) { pts.add(Point.fromLngLat(r.pts[k][1], r.pts[k][0])); k += stride }
            pts.add(Point.fromLngLat(r.pts.last()[1], r.pts.last()[0]))
            if (pts.size >= 2) feats.add(Feature.fromGeometry(LineString.fromLngLats(pts)))
        }
        src.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(feats))
    }

    private fun clearAlternatives() {
        map?.style?.getSourceAs<GeoJsonSource>(ALT_SOURCE)?.setGeoJson(FeatureCollectionEmpty)
    }

    // ---- the route line ----------------------------------------------------

    /** The route the line is drawn from, and its per-segment traffic levels. */
    private var lineRoute: Route? = null
    private var lineLevels = IntArray(0)
    private var lineStride = 1
    /** Where the near piece last started, metres; NaN = not drawn. */
    private var nearDrawnFrom = Double.NaN
    private var nearDrawnAtNs = 0L
    /** Which FAR_STEP_M step the far piece was built for. */
    private var farDrawnStep = Long.MIN_VALUE

    /** A new route, or none: work out its colours and draw it from the car. */
    private fun drawRoute(s: Style, route: Route?) {
        lineRoute = route?.takeIf { it.pts.size >= 2 }
        nearDrawnFrom = Double.NaN
        farDrawnStep = Long.MIN_VALUE
        val r = lineRoute
        if (r == null) {
            s.getSourceAs<GeoJsonSource>(ROUTE_NEAR_SOURCE)?.setGeoJson(FeatureCollectionEmpty)
            s.getSourceAs<GeoJsonSource>(ROUTE_FAR_SOURCE)?.setGeoJson(FeatureCollectionEmpty)
            return
        }
        lineStride = maxOf(1, r.pts.size / MAX_POLY_POINTS)
        lineLevels = RouteLine.trafficLevels(r, lineStride, MIN_TRAFFIC_RUN_M)
        drawRouteLine(s, r, HudService.alongM)
    }

    /**
     * From the frame loop: move the start of the line to [alongM], at most
     * ~15 times a second. Only for the route [drawRoute] was given -- a new
     * one, or none, is refresh()'s to install.
     */
    private fun maybeDrawRouteLine(route: Route?, alongM: Double, nowNs: Long) {
        val s = style ?: return
        val r = lineRoute ?: return
        if (route !== r || alongM.isNaN()) return
        if (!nearDrawnFrom.isNaN() &&
            (kotlin.math.abs(alongM - nearDrawnFrom) < ROUTE_NEAR_EPSILON_M ||
                nowNs - nearDrawnAtNs < ROUTE_NEAR_MIN_NS)) return
        nearDrawnAtNs = nowNs
        drawRouteLine(s, r, alongM)
    }

    private fun drawRouteLine(s: Style, r: Route, alongM: Double) {
        val from = alongM.coerceIn(0.0, r.cum.last())
        val step = kotlin.math.floor(from / FAR_STEP_M).toLong()
        val farFrom = step * FAR_STEP_M + NEAR_WINDOW_M
        if (step != farDrawnStep) {
            farDrawnStep = step
            s.getSourceAs<GeoJsonSource>(ROUTE_FAR_SOURCE)?.setGeoJson(
                routeFeatures(RouteLine.routeSlice(r, farFrom, r.cum.last(), lineLevels, lineStride)))
        }
        nearDrawnFrom = from
        s.getSourceAs<GeoJsonSource>(ROUTE_NEAR_SOURCE)?.setGeoJson(
            routeFeatures(RouteLine.routeSlice(r, from, farFrom, lineLevels)))
    }

    private fun routeFeatures(runs: List<RouteLine.Run>) =
        org.maplibre.geojson.FeatureCollection.fromFeatures(runs.map { run ->
            Feature.fromGeometry(LineString.fromLngLats(
                run.pts.map { Point.fromLngLat(it[1], it[0]) })).apply {
                addNumberProperty("level", run.level)
            }
        })

    private fun drawCameras(s: Style, cams: List<SpeedCamera>) {
        val src = s.getSourceAs<GeoJsonSource>(CAM_SOURCE) ?: return
        if (cams.isEmpty()) { src.setGeoJson(FeatureCollectionEmpty); return }
        val feats = cams.map { cam ->
            Feature.fromGeometry(Point.fromLngLat(cam.lon, cam.lat))
                .also { it.addStringProperty("kind", cam.kind.name) }
        }
        src.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(feats))
    }

    private val FeatureCollectionEmpty =
        org.maplibre.geojson.FeatureCollection.fromFeatures(emptyList<Feature>())

    // ---- route picker ------------------------------------------------------

    private lateinit var routePicker: View
    private lateinit var routeCards: LinearLayout

    /**
     * The route picker, as a sheet over the map rather than a modal list.
     *
     * The dialog this replaces printed "42 min · 61 km via E40" three times
     * and left you to work out the difference. What you actually want to know
     * before committing to a route is when you get there, how much of the
     * duration is traffic rather than distance, and what is on it that you
     * would rather avoid — a toll, a ferry, roadworks, a low-emission zone
     * your car may not be welcome in. So the card says all of that, and the
     * sheet leaves the map visible behind it with the alternatives drawn, so
     * "via E40" is a line on a map and not a piece of trivia.
     */
    private fun showRoutePicker() {
        val alts = HudService.alternatives
        if (alts.size < 2) return
        val current = HudService.currentRoute
        val zones = HudService.routeZones

        routeCards.removeAllViews()
        val inflater = layoutInflater
        val best = alts.minOfOrNull { it.totalDurationS } ?: 0.0
        for ((i, r) in alts.withIndex()) {
            val card = inflater.inflate(R.layout.item_route_card, routeCards, false)
            if (i > 0) {
                (card.layoutParams as LinearLayout.LayoutParams).marginStart =
                    (10 * resources.displayMetrics.density).toInt()
            }
            val mins = (r.totalDurationS / 60).toInt()
            card.findViewById<TextView>(R.id.cardDuration).text =
                formatDuration(mins)

            // How much worse than the best, not the absolute duration again:
            // "+6 min" is the number you compare on.
            val worse = ((r.totalDurationS - best) / 60).toInt()
            val delay = (r.trafficDelayS / 60).toInt()
            card.findViewById<TextView>(R.id.cardDelay).text = when {
                worse >= 1 -> "+$worse min"
                delay >= 2 -> getString(R.string.route_delay, delay)
                else -> ""
            }

            card.findViewById<TextView>(R.id.cardWhen).text = getString(
                R.string.route_arrive,
                arrivalClock(r.totalDurationS.toInt()),
                formatKm(r.totalDistanceM)
            )
            val via = card.findViewById<TextView>(R.id.cardVia)
            via.text = when {
                r === current -> getString(R.string.route_current)
                r.summary.isNotBlank() -> getString(R.string.route_via, r.summary)
                else -> ""
            }

            val warn = card.findViewById<TextView>(R.id.cardWarn)
            val text = warningsFor(r, zones.getOrNull(i).orEmpty())
            warn.text = text
            warn.visibility = if (text.isBlank()) View.GONE else View.VISIBLE

            card.isSelected = r === current
            card.setOnClickListener {
                hideRoutePicker()
                startService(
                    Intent(this, HudService::class.java)
                        .setAction(HudService.ACTION_PICK_ROUTE)
                        .putExtra(HudService.EXTRA_ROUTE_INDEX, i)
                )
                drawnRoute = null
            }
            routeCards.addView(card)
        }
        routePicker.visibility = View.VISIBLE
        drawAlternatives(alts, current)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back closes the sheet before it closes the map. Anything else and a
        // driver who opened the picker to look, and pressed back to stop
        // looking, loses the navigation screen instead.
        if (quickScrim.visibility == View.VISIBLE) { hideQuickActions(); return }
        if (routePicker.visibility == View.VISIBLE) { hideRoutePicker(); return }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun hideRoutePicker() {
        routePicker.visibility = View.GONE
        clearAlternatives()
    }

    /**
     * Everything wrong with a route, in one line, worst first.
     *
     * Kept to two entries: a chip that lists five things is a wall of text at
     * a glance, and the two that matter most are the ones that cost money.
     */
    private fun warningsFor(r: Route, lez: List<String>): String {
        val out = ArrayList<String>(3)
        if (lez.isNotEmpty()) {
            out.add(getString(R.string.warn_lez, lez.joinToString(", ")))
        } else if (r.has(RouteTrait.LEZ)) {
            out.add(getString(R.string.warn_lez_plain))
        }
        if (r.has(RouteTrait.CLOSURE)) out.add(getString(R.string.warn_closure))
        if (r.has(RouteTrait.FERRY)) out.add(getString(R.string.warn_ferry))
        if (r.has(RouteTrait.TOLL)) out.add(getString(R.string.warn_toll))
        if (r.has(RouteTrait.RESTRICTED)) out.add(getString(R.string.warn_restricted))
        if (r.has(RouteTrait.UNPAVED)) out.add(getString(R.string.warn_unpaved))
        return out.take(2).joinToString(" · ")
    }

    private fun formatKm(m: Double): String =
        if (m < 1000) "${m.toInt()} m"
        else if (m < 10_000) String.format(java.util.Locale.UK, "%.1f km", m / 1000.0)
        else "${(m / 1000).toInt()} km"

    // ---- chrome ------------------------------------------------------------

    private fun updateFollowButton() {
        // Off means "tap me to come back to the car", so it has to look
        // available rather than switched off: full strength when you have
        // panned away, dimmed while it is already following you.
        // Colour only. Fading the whole view took the chip's background and
        // border with it, so one button looked unlike its four neighbours.
        followButton.setColorFilter(if (following) amberDim else amber)
        // Every change of `following` comes through here.
        map?.uiSettings?.isRotateGesturesEnabled = !following
    }

    private fun updateVoiceButton() {
        // A speaker with the sound waves struck through says "muted" without a
        // caption; a dimmed musical note said nothing at all.
        voiceButton.setImageResource(
            if (voiceOn) R.drawable.ic_volume_on else R.drawable.ic_volume_off)
        // Sygic labels the tile with the state it is *in*, not the state
        // pressing it would produce. Both conventions exist and both are
        // defensible; matching the app he is comparing it to is worth more
        // than my opinion about which is clearer.
        voiceLabel.setText(if (voiceOn) R.string.sound_on else R.string.sound_off)
    }

    private fun requestPermissions() {
        val want = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            want += Manifest.permission.POST_NOTIFICATIONS
        val missing = want.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 7)
        }
    }
}
