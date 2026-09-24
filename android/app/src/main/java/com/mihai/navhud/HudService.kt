package com.mihai.navhud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mihai.navhud.alerts.CameraAlert
import com.mihai.navhud.alerts.CameraPolicy
import com.mihai.navhud.alerts.CameraWatcher
import com.mihai.navhud.alerts.CountryRules
import com.mihai.navhud.alerts.LowEmissionZones
import com.mihai.navhud.alerts.RoadAhead
import com.mihai.navhud.alerts.SpeedCamera
import com.mihai.navhud.alerts.SpeedCameras
import com.mihai.navhud.link.BtSerialLink
import com.mihai.navhud.link.SerialLink
import com.mihai.navhud.link.UsbSerialLink
import com.mihai.navhud.location.FixFilter
import com.mihai.navhud.location.Fixes
import com.mihai.navhud.location.CarLink
import com.mihai.navhud.map.ParkedHeading
import com.mihai.navhud.nav.Area
import com.mihai.navhud.nav.AreaRoads
import com.mihai.navhud.nav.LaneGuidance
import com.mihai.navhud.nav.LatLon
import com.mihai.navhud.nav.MapboxProvider
import com.mihai.navhud.nav.NavProvider
import com.mihai.navhud.nav.Route
import com.mihai.navhud.nav.SpeedDefaults
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import com.mihai.navhud.nav.AreaCache
import java.io.File

/**
 * The engine room. Owns the GPS subscription, the route, the tracker, the
 * cable and the voice, and pushes a frame to the display four times a second.
 */
class HudService : Service(), LocationListener {

    companion object {
        private const val TAG = "HudService"
        const val ACTION_START = "com.mihai.navhud.START"
        const val ACTION_STOP = "com.mihai.navhud.STOP"
        const val ACTION_PICK_ROUTE = "com.mihai.navhud.PICK_ROUTE"
        /** Cancel the route but keep driving: back to speed, limits and cameras. */
        const val ACTION_CLEAR_ROUTE = "com.mihai.navhud.CLEAR_ROUTE"

        /**
         * The driver tapped "Resume" on the notification after a restart.
         *
         * A route interrupted by a kill is offered, never restored. See
         * onStartCommand for why.
         */
        const val ACTION_RESUME_ROUTE = "com.mihai.navhud.RESUME_ROUTE"

        /**
         * The map screen has really gone away -- Back, or finish() -- as
         * opposed to merely being minimised.
         *
         * Minimising is the whole reason this service is foreground: the panel
         * in the dashboard must keep its arrow while you use another app. But
         * *closing* the app used to leave the last manoeuvre frozen on the
         * glass for ever, because the service kept a live tracker and kept
         * writing full nav frames at 4 Hz, so the board's 3 s link timeout
         * never expired and it never dropped to the car-only view. The only
         * teardown hook the service had was onTaskRemoved, which Android
         * delivers on a swipe out of recents and never on Back.
         */
        const val ACTION_UI_CLOSED = "com.mihai.navhud.UI_CLOSED"
        const val EXTRA_DEST_LAT = "dest_lat"
        const val EXTRA_DEST_LON = "dest_lon"
        const val EXTRA_USE_BT = "use_bt"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_DEST_LABEL = "dest_label"
        const val EXTRA_ROUTE_INDEX = "route_index"

        private const val CHANNEL_ID = "navhud"
        private const val NOTIF_ID = 1

        /**
         * Wake lock timeout, and how often the tick re-arms it. A timeout is
         * insurance against a lost release path; re-arming from a loop that is
         * demonstrably still running makes it effectively indefinite.
         */
        private const val WAKELOCK_MAX_MS = 4L * 60 * 60 * 1000     // 4 hours
        private const val WAKELOCK_REARM_MS = 30L * 60 * 1000       // every 30 min

        private const val TICK_MS = 250L

        /** Older than this, a fix is no evidence for or against a reroute. */
        private const val REROUTE_FIX_MAX_AGE_MS = 2000L

        /**

         * How long the last usable fix may be before the driver is told.

         *

         * Three GPS periods. Below this a rejected fix is just the filter

         * doing its job and is not worth a warning.

         */

        private const val FIX_STALE_MS = 3500L

        /** Nothing usable for this long is worth telling the driver about. */
        private const val FIX_LOST_MS = 8000L

        /**
         * Floor on how often the GPS may report. Not a schedule: the platform
         * delivers whatever the chip produces, and most produce 5-10 Hz.
         */
        private const val GPS_MIN_INTERVAL_MS = 200L

        /** How long to wait before retrying a country lookup that failed. */
        private const val COUNTRY_RETRY_MS = 60_000L

        /** Identifies the tick chain so it can be cancelled without wiping the queue. */

        private val TICK_TOKEN = Any()
        // The reroute timing lives in RerouteRule, where it can be tested.

        /** How often to retry a cable that will not open. */
        private const val LINK_RETRY_MS = 3000L


        /** Floor on how often free drive may ask Overpass for road data. */
        private const val AREA_MIN_INTERVAL_MS = 20_000L

        /** Road data kept on disk ahead of the car on a route, and when to top up. */
        private const val PREFETCH_AHEAD_M = 100_000.0
        private const val PREFETCH_REFILL_M = 50_000.0

        /** Between prefetch requests; Overpass fair use. */
        private const val PREFETCH_GAP_MS = 3_000L
        private const val PREFETCH_RETRY_BASE_MS = 30_000L
        private const val PREFETCH_RETRY_MAX_MS = 600_000L

        /** Re-check which country we are in after moving this far. */
        private const val COUNTRY_RECHECK_M = 25_000.0

        /**
         * Refetch the camera list this often while driving. OSM changes daily,
         * a long drive lasts hours, and the head unit is online anyway -- so
         * there is no reason to run on data loaded before breakfast.
         */
        private const val CAMERA_REFRESH_MS = 30 * 60 * 1000L

        /**
         * How far out lane guidance appears.
         *
         * A fixed 500 m was fine in town and useless on a motorway: at
         * 120 km/h it gives you fifteen seconds to read the sign, decide, check
         * a mirror and cross two lanes. The signs themselves are placed by
         * time, not distance -- the advance board for a Belgian exit stands
         * 1500 m out, which is about 45 seconds -- so this follows the same
         * logic and scales with speed, clamped so it never appears absurdly
         * early in a 30 zone or absurdly late on the motorway.
         */
        const val LANES_MIN_M = 350.0
        const val LANES_MAX_M = 1500.0
        const val LANES_SECONDS = 30.0

        fun laneRange(speedMps: Double): Double =
            (speedMps * LANES_SECONDS).coerceIn(LANES_MIN_M, LANES_MAX_M)

        /**
         * The running service, for settings changes that have to reach it
         * while it is up.
         *
         * The language setting was the case that made this necessary: it was
         * saved to preferences and then read exactly once, in onStartCommand.
         * Free drive starts the service the moment the app opens, so by the
         * time anyone visited Setup the service was already running and never
         * received another start command -- the setting was stored correctly,
         * applied nowhere, and looked broken because it was.
         */
        @Volatile private var self: HudService? = null

        /**
         * Re-read the preferences that can change while driving. Safe to call
         * when nothing is running; it simply does nothing.
         */
        fun applyPrefs(ctx: Context, speakSample: Boolean = false) {
            val s = self ?: return
            voiceEnabled = Prefs.voice(ctx)
            s.voice?.enabled = voiceEnabled
            s.voice?.phrases = Prefs.phrases(ctx)
            // Instruction street names come back from the router in whatever
            // language it was asked for, so the provider has to follow too.
            if (s.provider != null) {
                s.provider = MapboxProvider(Prefs.mapboxToken(ctx), Prefs.phrases(ctx).code)
            }
            cameraPolicy = CountryRules.effective(country, Prefs.cameraPreference(ctx))
            // CameraWatcher captures the policy at construction, so changing
            // the setting mid-route did nothing until the next country change:
            // switching alerts off left the old watcher announcing positions.
            s.watcher = if (cameraPolicy == CameraPolicy.OFF) null
                        else CameraWatcher(s.watcherCameras, cameraPolicy)
            if (speakSample) s.voice?.sample()
        }

        @Volatile var status: String = "idle"; private set
        @Volatile var linkInfo: String = "-"; private set
        @Volatile var lastFrame: HudFrame? = null; private set
        @Volatile var running: Boolean = false; private set

        /**
         * True once the driver has pressed Stop. The map screen starts free
         * drive when it opens, which is right — but not right enough to
         * override someone who has just switched the thing off.
         */
        @Volatile var userStopped: Boolean = false; private set

        /**
         * A route that was live when Android killed us, waiting to be offered
         * back. The label to put on the notification action, or null when there
         * is nothing to resume.
         *
         * The map screen reads it so it can offer the same thing on screen.
         */
        @Volatile var resumeLabel: String? = null; private set

        /** The map screen reads these; nothing writes them but the service. */
        @Volatile var currentRoute: Route? = null; private set
        @Volatile var alternatives: List<Route> = emptyList(); private set

        /**
         * Low-emission zones each alternative drives through, by the same
         * index as [alternatives]. Filled in after the routes arrive, because
         * it takes a second Overpass call and nobody should wait for it before
         * seeing their route.
         */
        @Volatile var routeZones: List<List<String>> = emptyList(); private set

        /**
         * Metres to the next stretch of route the provider reports as closed —
         * roadworks, an accident, an event — or -1 when the way is clear.
         */
        @Volatile var closureAheadM: Int = -1; private set

        /**
         * The next level crossing, speed bump or toll booth, when there is one
         * close enough to be worth saying. Null the rest of the time.
         */
        @Volatile var featureAhead: RoadAhead.Hit? = null; private set
        @Volatile var lastLocation: Location? = null; private set
        /**
         * Distance along the route of the last *real* fix -- never a coasted
         * or led-forward position -- and the elapsedRealtime() that fix was
         * taken at, so the map can extrapolate it and knows when to coast.
         */
        @Volatile var alongM: Double = 0.0; private set
        @Volatile var alongAtMs: Long = 0L; private set

        /**
         * Which way the route sets off, and a counter that changes whenever a
         * new route is adopted. The map watches the counter so it can swing
         * round to face the first leg the moment you accept a destination —
         * standing still, before the car has a heading of its own.
         */
        @Volatile var routeInitialBearing: Double? = null; private set
        @Volatile var routeGeneration: Int = 0; private set
        @Volatile var cameraAlert: CameraAlert? = null; private set
        @Volatile var country: String? = null; private set

        /**
         * The country the car is in according to the route itself -- Mapbox's
         * per-intersection `admins` -- or null off a route or when the router
         * did not say. Known before the border and needs no signal, unlike
         * [country], which is a reverse geocode every 25 km.
         */
        @Volatile var routeCountryCode: String? = null; private set
        @Volatile var cameraPolicy: CameraPolicy = CountryRules.DEFAULT; private set
        @Volatile var lanes: LaneGuidance? = null; private set
        @Volatile var destinationLabelPublic: String? = null; private set
        @Volatile var currentRoadName: String? = null; private set
        @Volatile var camerasFetchedAtMs: Long = 0L; private set
        @Volatile var cameraCount: Int = 0; private set

        /**
         * How long the last 4 Hz tick took, in *tenths* of a millisecond.
         *
         * Tenths because the spatial index took the free-drive tick from about
         * 19 ms to 0.13 ms, and whole milliseconds would read "0" for ever --
         * indistinguishable from the tick not running at all, which is one of
         * the states this exists to tell apart.
         */
        @Volatile var tickCostTenthMs: Int = 0; private set

        /** How many OSM ways are loaded around the car right now. */
        @Volatile var areaRoadCount: Int = 0; private set

        /**
         * Where the car sits once pulled onto the route, and which way the road
         * runs there. The map draws the marker from these rather than from the
         * raw fix, so a 10 m urban error no longer parks it in a garden.
         */
        @Volatile var snapLat: Double = 0.0; private set
        @Volatile var snapLon: Double = 0.0; private set
        @Volatile var roadBearing: Double? = null; private set
        @Volatile var snapTrusted: Boolean = false; private set

        /** The matched road's polyline, for re-projecting the drawn marker. */
        @Volatile var roadPts: Array<DoubleArray>? = null; private set
        @Volatile var crossTrackM: Double = 0.0; private set

        /** Plain-language GPS quality, for the Setup screen. */
        @Volatile var fixQuality: String = "no fix"; private set

        /**
         * True once the HUD has answered in this session. "No USB device
         * found" is the normal state when you drive without the display and
         * is not worth a line on the driving screen; losing a cable that *was*
         * working is worth one.
         */
        @Volatile var linkEverUp: Boolean = false; private set

        /**
         * Is the transport open *right now*?
         *
         * Published because the map used to work this out by matching the
         * link's description against "Usb" or "Bluetooth", and the USB link
         * describes itself by its driver class ("CdcAcm @ 115200 baud"), so
         * the answer was always "disconnected".
         */
        @Volatile var linkUp: Boolean = false; private set

        /** Which sensor the map is steering by. Written by the map screen. */
        @Volatile var headingSource: String = "-"

        /**
         * Send one already-framed sentence to the HUD.
         *
         * Goes out on the link executor, never the caller's thread: a
         * Bluetooth write can block for seconds behind a connect, and the
         * caller here is the UI thread of a screen with a slider on it.
         *
         * @return false when there is no open link, so the screen can say so
         *         rather than pretending the board heard it.
         */
        fun sendToBoard(line: String): Boolean {
            val svc = self ?: return false
            val l = svc.link ?: return false
            if (!l.isOpen) return false
            // The null check above and onDestroy() both run on the main thread,
            // so they cannot interleave -- but this is also reachable from the
            // serial reader thread, where they can, and execute() on a shut-down
            // executor throws. There is no handler above either caller.
            return runCatching {
                svc.linkExec.execute { runCatching { l.write(line) } }
            }.isSuccess
        }

        /**
         * Lines the board sent that were not `$IMU`, newest last, capped.
         * The keystone screen reads `$GEOM` and `$GEOMOK` out of here.
         */
        @Volatile var lastBoardLine: String = ""; private set

        /**
         * What the board says its own geometry is, from its reply to `$GEOM?`.
         *
         * The board is the source of truth here, not the phone: the settings
         * live in its flash, so they survive being used with a different phone,
         * and a keystone screen that started from a stale copy on the handset
         * would show sliders that do not match the glass.
         */
        @Volatile var boardGeometry: com.mihai.navhud.hud.Keystone? = null
        @Volatile var boardGeometryAtMs: Long = 0L

        /** Result of the last `$GEOMSAVE`: "1" written, "0" nothing to write. */
        @Volatile var boardSaveResult: String? = null

        /**
         * Yaw rate from the HUD's own IMU, degrees per second, clockwise
         * positive, with the monotonic timestamp it arrived. A module bolted to
         * the car beats a phone in a cradle that gets knocked, so the map
         * prefers this when it is arriving.
         */
        /**
         * One IMU sample, published as a single object.
         *
         * The rate and its timestamp used to be two separate volatiles written
         * one after the other on the serial thread. A reader that took the
         * timestamp, got preempted, and then took the rate would pair sample
         * N+1's rate with sample N's interval -- and then integrate that same
         * rate again on its next pass. Rare, but it applies one piece of
         * rotation twice, and the symptom is an arrow that occasionally
         * over-rotates through a turn for no visible reason. One reference
         * swap cannot tear.
         */
        class ImuSample(val rateDps: Double, val atMs: Long)

        @Volatile var imuSample: ImuSample? = null; private set

        /** Kept for callers that only want the last rate; prefer [imuSample]. */
        val imuYawRateDps: Double get() = imuSample?.rateDps ?: 0.0
        val imuAtMs: Long get() = imuSample?.atMs ?: 0L

        /**
         * Which way the car is pointing, including while it is standing still.
         *
         * The head unit has no sensors of its own, so there is no compass
         * anywhere in this system and nothing can measure an absolute heading
         * directly. This is carried from the last valid GPS course through the
         * gyro instead, saved when the car stops and restored when it comes
         * back to the same place -- see [ParkedHeading]. Null until there is
         * one. [parkedHeadingRestored] says whether it came from storage
         * rather than from this drive's own GPS.
         */
        @Volatile var parkedHeadingDeg: Double? = null; private set
        @Volatile var parkedHeadingRestored: Boolean = false; private set

        /**
         * Road speed from the car's own bus, m/s, scale-corrected -- or null
         * when the board is not reporting.
         *
         * Better than the GPS speed in every way that matters here: measured
         * rather than differentiated, updated every 100-300 ms rather than
         * once a second, exactly zero when parked, and unaffected by having no
         * sky. Prefer it wherever it is non-null.
         *
         * Re-evaluated on every tick, so it goes null within a tick of the
         * data going stale (2 s) or of the bus reading a stuck zero while GPS
         * says the car is moving -- not only when the next line arrives.
         */
        @Volatile var carSpeedMps: Double? = null; private set

        /** The bus has read zero for over a second. Not merely "slow". */
        @Volatile var carStopped: Boolean = false; private set
        @Volatile var carRpm: Int = 0; private set
        @Volatile var carIgnitionOn: Boolean = false; private set

        /**
         * Heading from the board's magnetometer, degrees clockwise from TRUE
         * north -- declination has already been added.
         *
         * The one thing neither the gyro nor GPS can supply: an absolute
         * bearing while standing still. Null until a reading arrives, and
         * treated as gone after [MAG_STALE_MS] rather than left to go stale,
         * because a frozen compass reading is indistinguishable from a
         * correct one.
         */
        @Volatile var magHeadingDeg: Double? = null; private set

        /**
         * True while the board knows how it is mounted in the car.
         *
         * Gates [magHeading]: a compass that does not know which way the car's
         * nose is relative to the chip is not a compass, it is a number.
         */
        @Volatile var magMountOk: Boolean = true; private set
        @Volatile var magAtMs: Long = 0L; private set
        @Volatile var magCalibrating: Boolean = false; private set
        @Volatile var magCalSamples: Int = 0; private set

        /** Magnitude of the corrected field, microtesla. ~49 in Belgium. */
        @Volatile var magFieldUt: Double = 0.0; private set

        /**
         * How much that magnitude moved over the last 30 s, as a percentage.
         *
         * The health number. A correct hard-iron calibration makes the
         * magnitude the same at every heading, so this should sit near zero
         * however much you turn. It does not distinguish a stale calibration
         * from a steel bracket -- both stretch it -- but either way the
         * heading is not to be trusted while it is large.
         */
        @Volatile var magSpreadPct: Int = 0; private set

        const val MAG_STALE_MS = 2_000L

        /** Above this the field magnitude is moving too much to trust. */
        const val MAG_SPREAD_BAD_PCT = 15

        /** Earth's field is 25-65 uT worldwide. */
        const val MAG_FIELD_MIN_UT = 20.0
        const val MAG_FIELD_MAX_UT = 80.0

        /**
         * One line saying whether the compass can be believed, for the
         * diagnostics row and the calibration screen.
         */
        fun magStatusLine(nowMs: Long): String {
            // Before the null check, because magHeading() returns null when the
            // mounting is unknown and "not reporting" would be the wrong story:
            // the compass is reporting perfectly well, it just cannot say which
            // way the CAR points until the board knows how it is bolted in.
            if (!magMountOk && magAtMs != 0L && nowMs - magAtMs < MAG_STALE_MS)
                return "compass: reading, but the board does not know how it is " +
                       "mounted — type 'mount' on the board's serial port"
            val h = magHeading(nowMs) ?: return "compass: not reporting"
            if (magCalibrating) return "compass: calibrating, $magCalSamples samples"
            if (magFieldUt < MAG_FIELD_MIN_UT || magFieldUt > MAG_FIELD_MAX_UT)
                return "compass: field %.0f uT is not the Earth's — something magnetic is close"
                    .format(magFieldUt)
            if (magSpreadPct > MAG_SPREAD_BAD_PCT)
                return "compass: field varies %d%% — recalibrate".format(magSpreadPct)
            return "compass: ok, %.0f°, %.0f uT, %d%%".format(h, magFieldUt, magSpreadPct)
        }

        /** The compass heading, or null if the board has gone quiet. */
        fun magHeading(nowMs: Long): Double? =
            magHeadingDeg?.takeIf {
                magMountOk && magAtMs != 0L && nowMs - magAtMs < MAG_STALE_MS
            }

        /**
         * How old the camera data is. A permanently-connected head unit can
         * keep this at "just now", which is the honest answer to "how do I know
         * this is current?" -- it is refetched from OpenStreetMap on every
         * route and every 30 minutes of driving.
         */
        fun cameraFreshness(): String {
            if (camerasFetchedAtMs == 0L) return "cameras not loaded"
            val ageMin = (System.currentTimeMillis() - camerasFetchedAtMs) / 60000
            val age = when {
                ageMin < 1 -> "just now"
                ageMin < 60 -> "$ageMin min ago"
                else -> "${ageMin / 60} h ago"
            }
            return "$cameraCount cameras, $age"
        }

        @Volatile var voiceEnabled: Boolean = true

        /** Plain-language reason the process ended last time, for the diagnostics. */
        @Volatile var lastExitReason: String? = null

        /**
         * True once the app has been swiped out of the recents list.
         *
         * "If you quit the app the background service needs only to send the
         * speed limit to the HUD; talking or everything else is useless — if a
         * camera appears it shows in the HUD."
         *
         * So quitting is not the same as backgrounding. Press home and the
         * route, the voice and the guidance all carry on, because the screen
         * going dark on a motorway is not a reason to stop navigating. Swipe
         * the app away and it is: the route goes, the voice goes, and what is
         * left is the thing that is useful with no phone in your hand — the
         * limit for the road you are on, your speed against it, and a camera
         * warning when one comes up.
         */
        @Volatile var quietMode: Boolean = false
            private set

        /**
         * The map screen is in front again, so this is not a quit any more.
         *
         * Needed as well as the reset in onStartCommand because `startFreeDrive`
         * short-circuits on an already-running service: after a swipe-away the
         * service is still up, so reopening the app sends no start intent and
         * nothing would ever have cleared the flag.
         */
        fun wakeFromQuiet() {
            if (!quietMode) return
            quietMode = false
            self?.let { s ->
                s.voice?.enabled = voiceEnabled
                runCatching { s.notifier.notify(NOTIF_ID, s.buildNotification()) }
            }
        }

        var statusListener: (() -> Unit)? = null

        private fun setStatus(s: String) {
            status = s
            Log.i(TAG, s)
            // Called from the network threads' catch blocks too: a listener
            // that throws there must not take the thread, and the app, down.
            runCatching { statusListener?.invoke() }
        }
    }

    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler

    /**
     * Routing gets a thread of its own.
     *
     * It used to share one single-thread executor with the Overpass camera
     * fetch, which has a sixty-second server-side timeout. Adopting a route
     * queues a camera fetch; miss a turn three seconds later and the reroute
     * request sat behind it in a FIFO queue for up to a minute — with
     * `rerouting` already latched, so every subsequent attempt was a silent
     * no-op. All the work done on the reroute *timing* was worth nothing
     * against that.
     */
    private val net = Executors.newSingleThreadExecutor()

    /** Cameras, reverse geocoding, opening the cable: slow, and never urgent. */
    private val aux = Executors.newSingleThreadExecutor()

    private val rerouting = AtomicBoolean(false)

    // All of these are written from the main thread (onStartCommand, the
    // actions) and from the network executors, and read on the tick thread.
    // Without volatile there is no happens-before edge, so the tick can keep
    // driving a route that has already been cancelled.
    @Volatile private var link: SerialLink? = null
    @Volatile private var provider: NavProvider? = null
    @Volatile private var tracker: RouteTracker? = null
    @Volatile private var destination: LatLon? = null
    @Volatile private var voice: VoiceGuide? = null
    @Volatile private var watcher: CameraWatcher? = null
    @Volatile private var watcherCameras: List<SpeedCamera> = emptyList()

    /**
     * Bumped whenever the destination changes or the route is cancelled.
     *
     * A route request takes a second or three. Without this, pressing X — or
     * searching for somewhere else — during that window let the reply install
     * a route for the destination you had just abandoned, complete with voice
     * guidance to it. The reply now checks that it is still wanted.
     */
    @Volatile private var routeGen = 0

    private val free = FreeTracker()
    @Volatile private var freeArea: Area? = null
    private val areaFetching = AtomicBoolean(false)
    private var lastAreaFetchAtMs = 0L
    private var destinationLabel: String? = null

    private val fixFilter = FixFilter()
    @Volatile private var tickGen = 0
    private val linkOpening = AtomicBoolean(false)
    private var lastLinkTryMs = 0L
    /** Failed route requests in a row; a retry is already scheduled while > 0. */
    @Volatile private var routeAttempt = 0
    private var lastFix: Location? = null
    private var lastFixAtMs = 0L

    /** See [ParkedHeading]. Lives here, not in the map, because the point of
     *  it is to be right the moment the app opens -- which means it has to
     *  have been running while the app was closed. */
    private val parked = ParkedHeading()

    /** What the car says about itself. See CarLink for why it is worth having. */
    private val carLink = CarLink()
    private var parkedRestoreTried = false
    private var offRouteSinceMs = 0L
    /** Start of the current unbroken run of RerouteRule.turnedOff, 0 if none. */
    private var turnedOffSinceMs = 0L

    /**
     * Where the car last was on the route, and what it drove since: what
     * RouteChoice needs to turn down a reroute that loops back. Worker thread.
     */
    @Volatile private var departure: DoubleArray? = null
    private val breadcrumb = Breadcrumb()

    // Route prefetch state; see maybePrefetch. Worker and aux both touch it.
    @Volatile private var prefetchRoute: Route? = null
    @Volatile private var prefetchNext = 0
    @Volatile private var prefetchUntil = -1
    @Volatile private var prefetchRetryAtMs = 0L
    @Volatile private var prefetchBackoffMs = 0L
    private val prefetching = AtomicBoolean(false)

    /** The fix `alongM` was last published for; 0 to republish. */
    @Volatile private var alongFixAtMs = 0L
    private var lastRouteTickMs = 0L
    private var lastRerouteMs = 0L
    private var lastCountryCheck: LatLon? = null
    private var lastCameraFetchMs = 0L
    private var lastLanesSent: LaneGuidance? = null

    /**
     * camera id -> is it on our road?
     *
     * Concurrent because it is written from the 4 Hz tick and cleared from the
     * area-fetch thread; a plain HashMap resized under a concurrent clear.
     *
     * Cleared whenever the road network is replaced *or* the route changes:
     * the answer depends on how far the camera is from our route, which every
     * new route recomputes, so keying on the camera id alone kept a decision
     * made against a road we are no longer driving.
     */
    private val cameraOnRoute = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()

    /**
     * Cable work only. `aux` is shared with Overpass, whose calls run to a
     * sixty-second timeout, and a reconnect that queues behind one leaves the
     * HUD blank for a minute with a perfectly good cable plugged in.
     */
    private val linkExec = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * The thing that keeps the display alive with the screen off.
     *
     * A foreground service does **not** keep the CPU running. Android's own
     * guidance is explicit about it — the decision tree on "Choose the right
     * API to keep the device awake" opens with *"Is your app running a
     * foreground service, and you need to keep the device awake when screen is
     * off"*, which would be a strange question if the service did it for you.
     * Without a wake lock the process is suspended when the screen goes off,
     * the 4 Hz tick simply stops advancing, and three seconds later the HUD
     * decides the cable has fallen out and shows NO LINK. Nothing crashes and
     * nothing is logged; it just stops.
     *
     * Location callbacks hold one briefly on our behalf — `requestLocationUpdates`
     * says so — but adds *"not indefinitely… if your application requires a long
     * running wakelock within the location callback, you should acquire it
     * yourself."* A 1 Hz fix cannot carry a 4 Hz render loop across a suspend.
     */
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(android.os.PowerManager::class.java)
            // Constant tag with an app prefix, per the platform's naming note.
            pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "navhud:hud")
                .also { it.setReferenceCounted(false); it.acquire(WAKELOCK_MAX_MS) }
        }.onSuccess { wakeLock = it }
            .onFailure { Log.w(TAG, "no wake lock", it) }
    }

    /** Re-armed from the tick so a long drive cannot outlive the timeout. */
    private fun refreshWakeLock() {
        val w = wakeLock ?: return
        if (SystemClock.elapsedRealtime() - wakeLockArmedMs < WAKELOCK_REARM_MS) return
        wakeLockArmedMs = SystemClock.elapsedRealtime()
        // A timeout rather than a bare acquire, so a bug that loses the release
        // path costs the battery a few hours, not the day. Re-arming keeps it
        // effectively indefinite for as long as we are genuinely running.
        runCatching { w.acquire(WAKELOCK_MAX_MS) }
    }

    private var wakeLockArmedMs = 0L

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wakeLockArmedMs = 0L
    }

    /**
     * Why we stopped last time, if we did.
     *
     * On a OnePlus this is the difference between "the app crashed" and "the
     * system killed it", and there is no other way to tell them apart after
     * the fact. Logged once at startup.
     */
    private fun logLastExit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val am = getSystemService(android.app.ActivityManager::class.java)
            val reasons = am.getHistoricalProcessExitReasons(packageName, 0, 1)
            val r = reasons.firstOrNull() ?: return
            lastExitReason = when (r.reason) {
                android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by the system or the user"
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for memory"
                android.app.ApplicationExitInfo.REASON_CRASH -> "crashed"
                android.app.ApplicationExitInfo.REASON_ANR -> "not responding"
                android.app.ApplicationExitInfo.REASON_OTHER -> "killed by the system"
                android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "exited normally"
                else -> "reason ${r.reason}"
            } + (r.description?.let { " ($it)" } ?: "")
            Log.i(TAG, "previous exit: $lastExitReason")
        }
    }

    override fun onCreate() {
        super.onCreate()
        logLastExit()
        // Before anything can fetch: AreaRoads writes through this, and with no
        // directory set it silently never caches, which is exactly the failure
        // you would not notice until you lost signal.
        AreaCache.dir = File(filesDir, "area")
        worker = HandlerThread("hud-tick").also { it.start() }
        handler = Handler(worker.looper)
        createChannel()
        voice = VoiceGuide(this, Prefs.phrases(this))
        self = this
    }

    /**
     * Turn a tapped "Resume" into the destination intent it stands for.
     *
     * The destination is read back out of preferences rather than carried in
     * the PendingIntent, so a notification left on screen after the route was
     * cancelled from the map resolves to nothing instead of routing to a place
     * that was explicitly abandoned. Null falls through to an ordinary start.
     */
    private fun resumeIntent(): Intent? {
        resumeLabel = null
        val d = Prefs.activeDestination(this) ?: return null
        return Intent(this, HudService::class.java)
            .putExtra(EXTRA_DEST_LAT, d.lat)
            .putExtra(EXTRA_DEST_LON, d.lon)
            .putExtra(EXTRA_DEST_LABEL, Prefs.activeDestinationLabel(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent means Android restarted us after a kill -- a crash, or
        // the system reclaiming memory. The destination that was live at the
        // time is still in preferences, because nothing got the chance to clear
        // it: Stop clears it, cancelling clears it, arriving clears it, and a
        // kill does none of those.
        //
        // It is NOT put back automatically, and that is the whole point of this
        // block. Restoring it silently is how a car standing on the drive ended
        // up with a route and an arrow on the HUD the moment the app was
        // opened -- an itinerary the driver never asked for, from a trip that
        // may have finished days ago. Instead the route is offered as a
        // notification action, and until that action is tapped this is an
        // ordinary free drive: speed and limit, no route, no arrow.
        val restarted = intent == null
        @Suppress("NAME_SHADOWING")
        val intent = if (intent?.action == ACTION_RESUME_ROUTE) resumeIntent() else intent
        when (intent?.action) {
            ACTION_STOP -> {
                // The driver said stop. Do not creep back to life the next time
                // the map screen comes to the front -- and forget the
                // destination, or a later sticky restart would find it in the
                // preferences and route to a place that was explicitly
                // abandoned. That is the exact bug moving off
                // START_REDELIVER_INTENT was meant to kill; it would simply
                // have moved from the intent into Prefs.
                userStopped = true
                resumeLabel = null
                Prefs.clearActiveDestination(this)
                stopSelf()
                return START_NOT_STICKY
            }
            // These two only make sense against a running service. Arriving at
            // a fresh one means Android restarted us to deliver them, and
            // acting on them would leave a service with a notification but no
            // tick, no GPS and no output.
            ACTION_PICK_ROUTE -> {
                if (!running) { stopSelf(); return START_NOT_STICKY }
                val i = intent.getIntExtra(EXTRA_ROUTE_INDEX, 0)
                handler.post {
                    alternatives.getOrNull(i)?.let { adoptRoute(it, "route ${i + 1} chosen") }
                }
                return START_STICKY
            }
            ACTION_CLEAR_ROUTE -> {
                if (!running) { stopSelf(); return START_NOT_STICKY }
                // Posted rather than run here: this arrives on the main thread
                // while step() is running on the worker at 4 Hz, and
                // clearRoute() resets FreeTracker's announcement map and a
                // handful of non-volatile fields the tick is reading.
                handler.post { clearRoute() }
                return START_STICKY
            }
            ACTION_UI_CLOSED -> {
                if (!running) { stopSelf(); return START_NOT_STICKY }
                dropToQuietHud()
                return START_STICKY
            }
        }

        // The map screen is up again, so we are no longer a quiet dashboard
        // display; guidance and voice come back with it.
        quietMode = false
        userStopped = false
        val label = intent?.getStringExtra(EXTRA_DEST_LABEL)
        if (label != null) { destinationLabel = label; destinationLabelPublic = label }
        if (!startForegroundCompat()) { stopSelf(); return START_NOT_STICKY }
        acquireWakeLock()
        running = true

        // Arm the offer, if this start was a restart and there is something to
        // offer. Deliberately after startForegroundCompat(): there is no point
        // advertising a resume on a notification we were not allowed to post.
        if (restarted) {
            resumeLabel = Prefs.activeDestination(this)?.let {
                Prefs.activeDestinationLabel(this) ?: "previous destination"
            }
        }

        val useBt = intent?.getBooleanExtra(EXTRA_USE_BT, Prefs.useBluetooth(this))
            ?: Prefs.useBluetooth(this)
        voiceEnabled = intent?.getBooleanExtra(EXTRA_VOICE, Prefs.voice(this))
            ?: Prefs.voice(this)
        val phrases = Prefs.phrases(this)
        // reset() clears maps the tick thread is writing four times a second,
        // and fixFilter is owned by the location callback. Both belong on the
        // worker -- the same reasoning ACTION_CLEAR_ROUTE above already uses.
        handler.post {
            voice?.phrases = phrases
            voice?.reset()
            fixFilter.reset()
        }

        // Close the previous link before opening another. Pressing Go a second
        // time used to leave the old transport and its reader thread running
        // and unreachable; on Bluetooth the orphan still held the board's only
        // SPP slot, so the new connection could never succeed and the HUD went
        // dark for the rest of the drive.
        link?.let { old -> linkExec.execute { runCatching { old.close() } } }
        link = (if (useBt) BtSerialLink(this) else UsbSerialLink(this)).also { l ->
            // The cable is not one-way: an IMU on the HUD board reports its yaw
            // rate back up it, which the map prefers to the phone's own gyro.
            l.onLine = { line ->
                if (line.startsWith("\$IMU,")) runCatching { onImuLine(line) }
                else if (line.startsWith(CarLink.PREFIX)) runCatching { onCarLine(line) }
                else if (line.startsWith("\$MAG,")) runCatching { onMagLine(line) }
                else runCatching { onBoardLine(line) }
            }
            linkInfo = "opening..."
            // Never on this thread: a Bluetooth connect blocks for as long as
            // ten seconds when the board is off, and this is the main thread.
            linkExec.execute { openLink(l) }
        }

        country = Prefs.homeCountry(this)
        cameraPolicy = CountryRules.effective(country, Prefs.cameraPreference(this))

        provider = MapboxProvider(Prefs.mapboxToken(this), Prefs.phrases(this).code)
        startLocation()

        val lat = intent?.getDoubleExtra(EXTRA_DEST_LAT, Double.NaN) ?: Double.NaN
        val lon = intent?.getDoubleExtra(EXTRA_DEST_LON, Double.NaN) ?: Double.NaN

        if (lat.isNaN() || lon.isNaN()) {
            // No destination in the intent means "make sure you are running",
            // not "cancel my route" — the map screen sends one of these every
            // time it comes to the front. Cancelling is ACTION_CLEAR_ROUTE, and
            // saying so explicitly is what stops the two racing: the map used
            // to fire this immediately after a search result and wipe the
            // destination the search had just set.
            if (destination == null) {
                handler.post {
                    free.resetAnnouncements()
                    freeArea = null
                    cameraOnRoute.clear()
                    lastAreaFetchAtMs = 0L
                    setStatus("free drive")
                }
            }
        } else {
            // A new destination. The old route is history, and the reply to any
            // request still in the air is no longer wanted.
            routeGen++
            rerouting.set(false)
            tracker = null
            currentRoute = null
            // ...and the snap that went with it. `currentRoute` going null here
            // flips the map into its free-drive branch on the very next camera
            // frame, 33 ms away, while `snapTrusted` still says true and
            // `roadPts` still holds a road from before the trip. The map would
            // project onto it and swing the whole camera across the country
            // until the worker's next tick, a quarter of a second later.
            snapTrusted = false
            roadBearing = null
            roadPts = null
            alternatives = emptyList()
            routeZones = emptyList()
            lastFrame = null
            cameraAlert = null
            lanes = null
            watcher = null
            watcherCameras = emptyList()
            cameraCount = 0
            camerasFetchedAtMs = 0L
            cameraOnRoute.clear()
            destination = LatLon(lat, lon)
            // Whatever was on offer is moot: this is the route now.
            resumeLabel = null
            Prefs.setActiveDestination(this, lat, lon, label)
            handler.post {
                lastCameraFetchMs = 0L
                offRouteSinceMs = 0L
                turnedOffSinceMs = 0L
                lastRerouteMs = 0L
                routeAttempt = 0
                requestRoute(reason = "initial")
            }
        }

        startTicking()
        // Deliberately not START_REDELIVER_INTENT. Redelivery is only finished
        // by stopSelf(startId), which this service never calls, so the intent
        // carrying a destination stayed armed for the whole session: cancel the
        // route, get killed, and Android handed the destination back and routed
        // you to a place you had explicitly abandoned. The live destination is
        // in Prefs instead, and is cleared when the route is.
        return START_STICKY
    }

    /**
     * (Re)starts the 4 Hz loop.
     *
     * `removeCallbacksAndMessages` from this thread cannot cancel a tick that
     * the worker is *currently executing* -- and that tick reposts itself after
     * we have already posted a fresh one, which left two self-sustaining chains
     * running at once. Every extra Go added another: 8 Hz frames, doubled voice
     * calls, and a demo drive at twice speed. A generation counter cannot lose
     * that race.
     */
    private fun startTicking() {
        val gen = ++tickGen
        // Not removeCallbacksAndMessages(null): that wipes the *whole* worker
        // queue, and the queue is where clearRoute(), adoptRoute() and the
        // "no GPS fix yet, try routing again in three seconds" retry live. A
        // cold start with a destination and no fix lost its retry here and
        // then waited for a route that was never requested again. The
        // generation counter already makes the old chain harmless.
        // removeCallbacksAndMessages(token) drops everything posted with the
        // tick token and leaves the rest of the queue alone.
        handler.removeCallbacksAndMessages(TICK_TOKEN)
        handler.postAtTime(object : Runnable {
            override fun run() {
                if (gen != tickGen || !running) return
                val t0 = System.nanoTime()
                try { step() } catch (e: Exception) { Log.e(TAG, "tick", e) }
                tickCostTenthMs = ((System.nanoTime() - t0) / 100_000L).toInt()
                handler.postAtTime(this, TICK_TOKEN, SystemClock.uptimeMillis() + TICK_MS)
            }
        }, TICK_TOKEN, SystemClock.uptimeMillis())
    }

    private fun openLink(l: SerialLink) {
        val ok = runCatching { l.open() }.getOrDefault(false)
        if (l !== link) { runCatching { l.close() }; return }   // superseded while opening
        linkInfo = l.description
        linkUp = ok && l.isOpen
        if (ok) linkEverUp = true
        setStatus(if (ok) "link up: ${l.description}" else "link down: ${l.description}")
    }

    /**
     * The app was swiped out of recents. Drop to the quiet HUD.
     *
     * Android delivers this to a foreground service whose task the user
     * dismissed. Stopping outright would be the obvious thing to do and the
     * wrong one — the display in the dashboard is still plugged in and still
     * wants a speed limit.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        dropToQuietHud()
    }

    /**
     * The user has finished with the app but not with the display.
     *
     * Clears the route so the next tick takes [freeStep], which reports
     * `Man.NONE` and an empty street -- and those are what drive the board's
     * per-band clears, so the arrow, the distance and the street name all come
     * off the glass within one tick. Speed, limit and camera warnings carry on,
     * which is the point of the service.
     *
     * Reached from a swipe out of recents and from [ACTION_UI_CLOSED]. Not
     * reached by minimising, and not by a configuration change -- the map
     * screen only sends the action when it is genuinely finishing, so a night
     * mode recreation does not throw the route away.
     */
    private fun dropToQuietHud() {
        if (!running) return
        if (tracker == null && quietMode) return       // already quiet
        quietMode = true
        // No destination to speak of any more, and nothing to speak it with.
        handler.post {
            clearRoute()
            voice?.enabled = false
            voice?.stopNow()
        }
        setStatus("app closed: HUD keeps the limit and camera warnings")
        runCatching { notifier.notify(NOTIF_ID, buildNotification()) }
    }

    override fun onDestroy() {
        // The heading at shutdown is the one that matters and the throttle may
        // be holding it. Nothing tells the app the ignition has gone off --
        // the CAN ignition line is read by the HUD board and never sent up the
        // cable -- so this is the last moment we get.
        runCatching {
            parked.saveNow()
            parked.recordToSave(SystemClock.elapsedRealtime(), System.currentTimeMillis())
                ?.let { Prefs.setParkedHeading(this, it) }
        }.onFailure { Log.w(TAG, "parked heading save failed", it) }

        if (self === this) self = null
        releaseWakeLock()
        running = false
        tickGen++
        tickCostTenthMs = 0
        areaRoadCount = 0
        handler.removeCallbacksAndMessages(null)
        runCatching { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(this) }
        // Both of these block: an RFCOMM write to a board that has stopped
        // acknowledging waits out the link supervision timeout, and close()
        // joins the reader thread. onDestroy() runs on the main thread, so
        // doing it here was an ANR waiting for a wedged sketch.
        link?.let { l ->
            link = null
            linkExec.execute {
                runCatching { l.write(HudFrame().encode()) }
                runCatching { l.close() }
            }
        }
        linkExec.shutdown()
        voice?.shutdown()
        voice = null
        currentRoute = null
        alternatives = emptyList()
        routeZones = emptyList()
        cameraAlert = null
        lanes = null
        // The map screen has its own GPS subscription and carries on as a plain
        // moving map from here; leaving a stale fix behind would freeze it.
        lastLocation = null
        snapTrusted = false
        roadBearing = null
        roadPts = null
        currentRoadName = null
        routeCountryCode = null
        lastFrame = null
        fixQuality = "no fix"
        // Everything the map screen or the Setup screen can still read has to
        // go, or the status line keeps quoting a route that no longer exists.
        alongM = 0.0
        alongAtMs = 0L
        snapLat = 0.0
        snapLon = 0.0
        crossTrackM = 0.0
        camerasFetchedAtMs = 0L
        cameraCount = 0
        imuSample = null
        carSpeedMps = null
        carStopped = false
        carRpm = 0
        carIgnitionOn = false
        carLink.reset()
        magHeadingDeg = null
        magAtMs = 0L
        magCalibrating = false
        magCalSamples = 0
        magFieldUt = 0.0
        magSpreadPct = 0
        destinationLabelPublic = null
        linkInfo = "-"
        linkEverUp = false
        linkUp = false
        tracker = null
        watcher = null
        watcherCameras = emptyList()
        worker.quitSafely()
        net.shutdownNow()
        aux.shutdownNow()
        setStatus("stopped")
        super.onDestroy()
    }

    // ---- location ----------------------------------------------------------

    private fun startLocation() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            // Wrapped like the network one below: requestLocationUpdates throws
            // IllegalArgumentException when the provider does not exist, and a
            // wifi-only head unit genuinely has no GNSS.
            // Ask for everything the chip will give, not one fix a second.
            //
            // minTime is a *floor*, not a schedule -- the platform never
            // delivers faster than the hardware produces -- and most GNSS
            // chips of the last decade run at 5 or 10 Hz. Every extra fix is
            // another chance to notice a wrong turn, and the wrong-turn test
            // is what makes rerouting feel instant or feel like waiting. At
            // 1000 ms, two fixes of evidence is two whole seconds before the
            // request even goes out.
            runCatching {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, GPS_MIN_INTERVAL_MS, 0f, this, worker.looper)
            }
            // The network provider is a fallback for a cold start or a long
            // tunnel, nothing more. FixFilter is what stops a cell-tower fix
            // half a kilometre wide from being taken over a live GPS one --
            // that swap is what threw the marker onto a different street.
            runCatching {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 2000L, 0f, this, worker.looper
                )
            }
        } catch (e: SecurityException) {
            setStatus("location permission missing")
        }
    }

    override fun onLocationChanged(loc: Location) {
        val used = Fixes.accept(fixFilter, loc)
        if (used == null) {
            // A rejection is not automatically a problem, and this one is the
            // filter working exactly as designed.
            //
            // The service subscribes to GPS *and* the network provider, and the
            // network one fires every two seconds. Rule 1 refuses every network
            // fix while GPS is alive -- which is right, a cell-tower fix can be
            // a kilometre wide -- but it was writing "ignored: ..." into
            // fixQuality each time, and the map screen reports any fixQuality
            // starting with "ignored" as WAITING FOR GPS. So with a perfect
            // sky and a solid lock the status line said no signal roughly half
            // the time, flickering at 0.5 Hz, because a fix we deliberately
            // threw away was being reported as the state of the receiver.
            //
            // What matters is whether a *good* fix arrived recently. If one
            // did, say nothing; only if the last usable fix has gone stale is
            // there anything to tell the driver.
            val since = SystemClock.elapsedRealtime() - lastFixAtMs
            if (lastFixAtMs == 0L || since > FIX_STALE_MS) {
                fixQuality = "ignored: ${fixFilter.lastRejection}"
            }
            return
        }
        val f = fixFilter.last!!
        fixQuality = when {
            !f.isGps -> "network fix, ±${f.accuracyM.toInt()} m"
            f.accuracyM <= 8f -> "GPS ±${f.accuracyM.toInt()} m"
            f.accuracyM <= 25f -> "GPS ±${f.accuracyM.toInt()} m, fair"
            else -> "GPS ±${f.accuracyM.toInt()} m, weak"
        }
        lastFix = used
        lastLocation = used
        lastFixAtMs = SystemClock.elapsedRealtime()
        // GPS only: a cell-tower fix is hundreds of metres wide, and a track
        // through it would put the car on roads it never drove.
        if (f.isGps) breadcrumb.add(used.latitude, used.longitude)

        // Which way the car is pointing, and remembering it for next time.
        // The restore gets exactly one attempt, on the first usable fix of the
        // run: retrying on later fixes would let a heading be adopted after
        // the car had already driven away from where it was saved.
        if (!parkedRestoreTried) {
            parkedRestoreTried = true
            runCatching {
                parked.restore(Prefs.parkedHeading(this), used.latitude, used.longitude,
                               System.currentTimeMillis())
            }.onFailure { Log.w(TAG, "parked heading restore failed", it) }
        }
        // The wheel-speed correction is learned here, where a GPS ground speed
        // and a bus speed can be compared against each other.
        if (used.hasSpeed()) {
            carLink.learnScale(used.speed.toDouble(), SystemClock.elapsedRealtime())
        }
        // Prefer the bus. A GPS speed at a standstill is a metre or two a
        // second of multipath, which is enough to keep the gyro's re-zero from
        // ever firing -- the bus reads a flat zero.
        val now2 = SystemClock.elapsedRealtime()
        val speedForHeading = carLink.speedMps(now2)
            ?: if (used.hasSpeed()) used.speed.toDouble() else 0.0
        parked.onFix(
            used.latitude, used.longitude,
            bearingDeg = if (used.hasBearing()) used.bearing.toDouble() else null,
            speedMps = speedForHeading,
        )
        parkedHeadingDeg = parked.heading
        parkedHeadingRestored = parked.restored
        parked.recordToSave(SystemClock.elapsedRealtime(), System.currentTimeMillis())
            ?.let { rec -> runCatching { Prefs.setParkedHeading(this, rec) } }
        // A fix already queued on the worker's looper can arrive after
        // onDestroy has shut the executors down, and a RejectedExecutionException
        // on this thread has no handler above it.
        runCatching { maybeCheckCountry(used) }
            .onFailure { Log.w(TAG, "country check skipped", it) }
    }

    /**
     * Yaw rate arriving from the HUD's own IMU over the cable.
     * `$IMU,<yaw>,<pitch>,<roll>,<rateZ>*CS` — only the rate is used; a bare
     * gyro cannot know absolute yaw, and GPS already does.
     */
    private fun onImuLine(line: String) {
        val star = line.lastIndexOf('*')
        val body = (if (star > 0) line.substring(1, star) else line.substring(1)).trim()
        if (star > 0) {
            val want = line.substring(star + 1).trim().take(2).toIntOrNull(16) ?: return
            if (HudFrame.checksum(body) != want) return      // line noise
        }
        val p = body.split(',')
        if (p.size < 5) return
        val rate = p[4].trim().toDoubleOrNull() ?: return
        // 720 deg/s is two full turns a second: that is not a car.
        if (rate.isNaN() || kotlin.math.abs(rate) > 720.0) return
        val at = SystemClock.elapsedRealtime()
        imuSample = ImuSample(rate, at)
        // This runs on the serial reader thread while fixes arrive on the
        // service looper. Every ParkedHeading entry point is synchronized.
        parked.onYawRate(rate, at)
        parkedHeadingDeg = parked.heading
        parkedHeadingRestored = parked.restored
    }

    /**
     * Send a raw command to the board, checksummed. Used for the compass
     * calibration; the geometry commands have their own paths.
     */
    fun sendBoard(body: String): Boolean =
        runCatching { link?.write(HudFrame.wrap(body)) != null }.getOrDefault(false)

    /**
     * `$MAG,<headingDeg>,<calibrating>,<samples>,<fieldUt>,<spreadPct>,<mount>`
     * -- the board's compass.
     *
     * Magnetic north, not true: declination depends on where you are, which
     * the phone knows and the board does not, so the correction is applied
     * here rather than there.
     */
    private fun onMagLine(line: String) {
        val star = line.lastIndexOf('*')
        if (star > 0) {
            val body = line.substring(1, star)
            val want = line.substring(star + 1).trim().take(2).toIntOrNull(16) ?: return
            if (HudFrame.checksum(body) != want) return
        }
        val body = if (star > 0) line.substring(5, star) else line.substring(5)
        val p = body.split(',')
        val deg = p.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return
        if (deg.isNaN() || deg < -1.0 || deg > 361.0) return
        magHeadingDeg = Geo.normalizeDeg(deg + Prefs.declination(this))
        magAtMs = SystemClock.elapsedRealtime()
        magCalibrating = p.getOrNull(1)?.trim() == "1"
        magCalSamples = p.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
        magFieldUt = p.getOrNull(3)?.trim()?.toDoubleOrNull() ?: 0.0
        magSpreadPct = p.getOrNull(4)?.trim()?.toIntOrNull() ?: 0
        // Sixth field: does the board know how it is bolted into the car?
        //
        // 1 yes, 2 it thought it did and gravity has since disagreed, 0 no.
        // Anything but 1 and the heading above is not a heading -- it is the
        // direction the CHIP is pointing, which is out by however far the
        // sensor is twisted from the car's nose. Stated confidently, to a
        // tenth of a degree, with nothing else to suggest it is wrong.
        //
        // Absent on firmware that predates the field, which is treated as
        // "yes" so an older board keeps working exactly as it did.
        magMountOk = (p.getOrNull(5)?.trim()?.toIntOrNull() ?: 1) == 1
    }

    /**
     * `$CAR` from the board: what the powertrain bus is saying.
     *
     * Checksum-verified first, like every other inbound line -- a corrupted
     * speed is worse than a missing one, because everything downstream is
     * about to trust it more than it trusts GPS.
     */
    private fun onCarLine(line: String) {
        val star = line.lastIndexOf('*')
        if (star > 0) {
            val body = line.substring(1, star)
            val want = line.substring(star + 1).trim().take(2).toIntOrNull(16) ?: return
            if (HudFrame.checksum(body) != want) return
        }
        val now = SystemClock.elapsedRealtime()
        if (!carLink.feed(line, now)) return
        carSpeedMps = carLink.speedMps(now)
        carStopped = carLink.stopped(now)
        carRpm = carLink.rpm
        carIgnitionOn = carLink.ignitionOn
    }

    /**
     * Everything else the board sends back.
     *
     * Only the geometry replies are acted on. The line is kept whole for the
     * diagnostics screen, because "what did the board actually say" is the
     * first question when a cable behaves oddly and there was previously no
     * way to answer it.
     */
    private fun onBoardLine(line: String) {
        if (!line.startsWith("\$")) return
        val star = line.lastIndexOf('*')
        val body = (if (star > 0) line.substring(1, star) else line.substring(1)).trim()
        if (star > 0) {
            val want = line.substring(star + 1).trim().take(2).toIntOrNull(16) ?: return
            if (HudFrame.checksum(body) != want) return      // line noise
        }
        lastBoardLine = body
        when {
            body.startsWith("GEOM,") -> {
                com.mihai.navhud.hud.Keystone.parse(body)?.let {
                    boardGeometry = it
                    boardGeometryAtMs = SystemClock.elapsedRealtime()
                }
            }
            body.startsWith("GEOMOK,") -> boardSaveResult = body.substringAfter(',')
            body.startsWith("GEOMERR,") -> boardSaveResult = "error: " + body.substringAfter(',')
            body.startsWith("HELLO,") -> {
                // A board that has just come up, or been reflashed. Ask what
                // geometry it is using so the keystone screen and the
                // diagnostics both start from the truth.
                runCatching {
                    linkExec.execute {
                        runCatching { link?.write(com.mihai.navhud.hud.Keystone.queryCommand()) }
                    }
                }
            }
        }
    }

    @Deprecated("required by the old LocationListener interface")
    override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) { }
    override fun onProviderEnabled(p: String) { }
    override fun onProviderDisabled(p: String) { setStatus("$p disabled") }

    // ---- country, for the camera-warning rules -----------------------------

    /**
     * Which country you are in decides what the app may legally tell you about
     * cameras, and on a Belgium-to-Romania run that changes eight times. One
     * reverse geocode per 25 km of travel, not per fix.
     */
    private fun maybeCheckCountry(loc: Location) {
        val here = LatLon(loc.latitude, loc.longitude)
        val last = lastCountryCheck
        if (last != null &&
            Geo.haversine(last.lat, last.lon, here.lat, here.lon) < COUNTRY_RECHECK_M) return
        val prov = provider ?: return
        // One at a time, and not again for a minute after a failure.
        //
        // The checkpoint is deliberately not stamped when the lookup fails --
        // otherwise one failed geocode at a border freezes the previous
        // country's camera rules for the next 25 km. The cost of that is that
        // *every* fix re-attempts while it keeps failing, and the fixes now
        // arrive five times a second rather than once. Each attempt is a task
        // on `aux` that can take half a minute to time out, and `aux` is the
        // same single-thread queue the road network, the camera list and the
        // low-emission zones are fetched on -- so an hour out of signal would
        // enqueue thousands of reverse-geocodes and starve the speed limit
        // behind them for the rest of the drive.
        val now = SystemClock.elapsedRealtime()
        if (countryLookupBusy.get()) return
        if (lastCountryTryMs != 0L && now - lastCountryTryMs < COUNTRY_RETRY_MS) return
        lastCountryTryMs = now
        countryLookupBusy.set(true)
        aux.execute {
          try {
            val c = runCatching { prov.countryAt(here) }.getOrNull()
                ?: runCatching { com.mihai.navhud.nav.Geocoders.countryCodeAt(here) }.getOrNull()
            if (c == null) {
                // Do NOT stamp the checkpoint on a failure. It used to, so one
                // failed lookup at a border froze the previous country's rules
                // for the next 25 km -- which is how you end up announcing
                // camera positions inside Germany, where that is illegal.
                Log.w(TAG, "country lookup failed, will retry")
                return@execute
            }
            lastCountryCheck = here
            if (c.equals(country, ignoreCase = true)) return@execute
            country = c
            cameraPolicy = CountryRules.effective(c, Prefs.cameraPreference(this))
            watcher = if (cameraPolicy == CameraPolicy.OFF) null
                      else CameraWatcher(watcherCameras, cameraPolicy)
            setStatus(CountryRules.explain(c))
          } finally {
            countryLookupBusy.set(false)
          }
        }
    }

    private val countryLookupBusy = AtomicBoolean(false)
    private var lastCountryTryMs = 0L

    // ---- routing -----------------------------------------------------------

    /**
     * @param back on a reroute, what the car just did -- so a way back to the
     *             line it left can be turned down. Null for a first route.
     */
    private fun requestRoute(reason: String, back: RouteChoice.Backtrack? = null) {
        val dest = destination ?: return
        val prov = provider ?: return
        if (!rerouting.compareAndSet(false, true)) return
        val gen = routeGen
        setStatus("routing ($reason)...")
        net.execute {
            try {
                val fix = lastFix
                if (fix == null) {
                    setStatus("waiting for a GPS fix before routing")
                    rerouting.set(false)
                    handler.postDelayed({ requestRoute(reason, back) }, 3000)
                    return@execute
                }
                val from = LatLon(fix.latitude, fix.longitude)
                // Route away from where the car is pointing, not from whichever
                // carriageway happens to be nearest the fix -- whenever it is
                // moving, and the bus knows that better than a GPS speed does.
                val moving = bestSpeedMps(if (fix.hasSpeed()) fix.speed else 0f,
                                          SystemClock.elapsedRealtime()) > 2f
                val heading = if (fix.hasBearing() && moving) fix.bearing.toDouble() else null
                val found = prov.routes(from, dest, alternatives = true, headingDeg = heading)
                // Still wanted? The driver may have pressed X, or searched for
                // somewhere else, while this was in the air.
                if (gen != routeGen) {
                    Log.i(TAG, "discarding a route for a cancelled destination")
                    rerouting.set(false)
                    return@execute
                }
                alternatives = found
                routeZones = List(found.size) { emptyList() }
                routeAttempt = 0
                // Not found.first(). The fastest route back onto the itinerary
                // very often begins with a U-turn, which on a motorway is
                // useless advice -- RouteChoice prefers a forward alternative
                // when one exists within a tolerance of the fastest, and falls
                // back to the fastest when none does.
                adoptRoute(RouteChoice.pick(found, back) ?: found.first(), reason)
                fetchZones(found, gen)
            } catch (e: Exception) {
                if (gen != routeGen) { rerouting.set(false); return@execute }
                // Losing signal in a tunnel is the ordinary case here, and it is
                // precisely when a reroute was asked for. Giving up silently
                // left the app navigating the old route to the old destination
                // for the rest of the drive.
                val wait = RerouteRule.retryDelayMs(routeAttempt)
                routeAttempt++
                setStatus("routing failed: ${e.message}. Retrying in ${wait / 1000} s")
                rerouting.set(false)
                handler.postDelayed({
                    if (running && destination != null) requestRoute(reason, back)
                }, wait)
                return@execute
            }
            rerouting.set(false)
        }
    }

    /**
     * Drop the route and carry on driving.
     *
     * The X on the map means "I do not need directions any more", not "switch
     * the app off" — Waze and Google Maps both keep showing you the road. The
     * service stays up in free drive; only the notification's Stop actually
     * shuts it down.
     */
    private fun clearRoute() {
        // Anything still in the air is no longer wanted.
        routeGen++
        rerouting.set(false)
        tracker = null
        currentRoute = null
        alternatives = emptyList()
        routeZones = emptyList()
        destination = null
        destinationLabel = null
        destinationLabelPublic = null
        resumeLabel = null
        Prefs.clearActiveDestination(this)
        lastFrame = null
        // Blank the HUD's camera bar and lane strip *before* forgetting that
        // they were up. Both are edge-triggered -- the clearing frame is only
        // written when the service notices non-null going null -- so nulling
        // them here consumed the edge and the dashboard kept a camera warning
        // and turn-lane arrows painted on it for the rest of the drive.
        if (cameraAlert != null) runCatching { link?.write(HudFrame.wrap("CAM,0,0,0")) }
        if (lanes != null || lastLanesSent != null) {
            runCatching { link?.write(HudFrame.wrap("LANE,0,0")) }
        }
        cameraAlert = null
        lanes = null
        lastLanesSent = null
        watcher = null
        watcherCameras = emptyList()
        cameraOnRoute.clear()
        areaRoadCount = 0
        snapTrusted = false
        roadBearing = null
        roadPts = null
        routeInitialBearing = null
        alongM = 0.0
        routeAttempt = 0
        offRouteSinceMs = 0L
        turnedOffSinceMs = 0L
        departure = null
        alongFixAtMs = 0L
        free.resetAnnouncements()
        // Force a fresh area fetch: the route's camera list is not the same as
        // the one free drive wants, which is everything around us.
        freeArea = null
        lastAreaFetchAtMs = 0L
        voice?.reset()
        setStatus("free drive")
    }

    /**
     * The best speed we have, which is the car's own when the bus is talking.
     *
     * 0x1A6 updates every 100-300 ms, works in tunnels and has none of GPS's lag
     * or its habit of reading 3 km/h at a standstill. CarLink returns null when
     * the frames have gone stale, so falling back to GPS costs nothing and needs
     * no flag. This feeds the display, the HUD frame AND the over-limit rule --
     * they used to disagree because only one of them looked at the bus.
     */
    private fun bestSpeedMps(gpsSpeed: Float, nowMs: Long): Float =
        carLink.speedMps(nowMs)?.toFloat() ?: gpsSpeed

    /**
     * The speed to show, and to judge the limit against: the car's own,
     * *unscaled*, when the bus is fresh and believable, else GPS.
     *
     * Unscaled because the HUD firmware draws the raw bus number whenever it
     * has one. The app gauge, the voice's over-limit chime and the HUD used to
     * show three different speeds -- scaled bus on a route, GPS in free drive,
     * raw bus on the glass. [bestSpeedMps] stays for placing the car.
     */
    private fun displaySpeedMps(gpsSpeed: Float, nowMs: Long): Float =
        carLink.rawSpeedMps(nowMs)?.toFloat() ?: gpsSpeed

    /**
     * Once a tick: age the car data even when no `$CAR` line arrives, so a
     * silent board reads as "no car speed" rather than its last number, and
     * catch the board's stale-frame zero while GPS says the car is moving.
     */
    private fun refreshCarState(nowMs: Long) {
        val fix = lastFix
        val gps = if (fix != null && fix.hasSpeed() && nowMs - lastFixAtMs <= FIX_STALE_MS)
            fix.speed.toDouble() else null
        carLink.checkPlausible(gps, nowMs)
        carSpeedMps = carLink.speedMps(nowMs)
        carStopped = carLink.stopped(nowMs)
    }

    private fun adoptRoute(r: Route, reason: String) {
        // Where the router has no limit, the same OSM window and legal
        // defaults free drive uses. The window keeps being fetched on a route
        // (step() -> maybeFetchArea), so this has data wherever free drive would.
        tracker = RouteTracker(r).also {
            it.limitFallback = { lat, lon, h ->
                SpeedDefaults.limitAt(freeArea, lat, lon, h, routeCountryCode ?: country,
                    Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
            }
        }
        currentRoute = r
        // 30 m in: far enough past the start vertex that a jitter in the first
        // coordinate cannot point the map the wrong way down the street.
        routeInitialBearing = Geo.bearingAlong(r.pts, r.cum, 30.0, spanM = 30.0)
        routeGeneration++
        voice?.reset()
        // Deliberately *not* touching lastRerouteMs here: the cooldown exists to
        // stop repeated failures spinning, and it is armed when a reroute is
        // requested. Arming it on adoption made the first route mute the next
        // fifteen seconds of rerouting, which is exactly when you are most
        // likely to miss the first turn.
        offRouteSinceMs = 0L
        turnedOffSinceMs = 0L
        departure = null
        alongFixAtMs = 0L
        lastLanesSent = null
        // The cached "is this camera on our road" answers were computed against
        // the previous route's geometry. Keep them and a camera correctly
        // rejected before the reroute stays rejected on the road it now sits on.
        cameraOnRoute.clear()
        setStatus(
            "route: %.1f km, %d min%s (%s)".format(
                r.totalDistanceM / 1000.0,
                (r.totalDurationS / 60).toInt(),
                if (r.summary.isNotEmpty()) " via ${r.summary}" else "",
                reason
            )
        )
        fetchCameras(r)
    }

    private fun fetchCameras(r: Route) {
        if (cameraPolicy == CameraPolicy.OFF) {
            watcher = null; watcherCameras = emptyList(); return
        }
        val gen = routeGen
        // Deliberately not on `net`: Overpass can take a minute, and a reroute
        // must never queue behind it.
        aux.execute {
            // The route this was fetched for may already be history -- Overpass
            // runs to a sixty-second timeout, and a missed turn three seconds
            // in queues a second fetch behind this one. Installing route A's
            // cameras while driving route B pinned every countdown to distances
            // measured along a road we are not on.
            if (gen != routeGen) return@execute
            val cams = runCatching { SpeedCameras.fetch(r) }
                .onFailure { Log.w(TAG, "camera fetch failed", it) }
                .getOrNull()
            if (gen != routeGen) return@execute
            if (cams == null) {
                // A failed refresh is not "there are no cameras". Overwriting
                // a good list with an empty one silently disarmed every camera
                // on the route and then reported "0 cameras, just now".
                setStatus("camera refresh failed, keeping the ${cameraCount} we have")
                return@execute
            }
            watcherCameras = cams
            watcher = CameraWatcher(cams, cameraPolicy)
            cameraOnRoute.clear()
            camerasFetchedAtMs = System.currentTimeMillis()
            lastCameraFetchMs = SystemClock.elapsedRealtime()
            cameraCount = cams.size
            if (cams.isNotEmpty()) setStatus("${cams.size} cameras on this route")
        }
    }

    // ---- the 4 Hz tick -----------------------------------------------------

    private fun step() {
        refreshWakeLock()
        val l = link
        linkUp = l?.isOpen == true
        // Say so when nothing has arrived for a while. Since a rejected fix no
        // longer writes fixQuality, the only other thing that ever touches it
        // is an *accepted* fix -- so a receiver that stops delivering entirely
        // (GPS switched off mid-drive, or a head unit with no network provider
        // at all) would otherwise keep its last good string for ever and the
        // chip would never light.
        if (lastFixAtMs != 0L &&
            SystemClock.elapsedRealtime() - lastFixAtMs > FIX_LOST_MS) {
            fixQuality = "ignored: no fix for ${
                (SystemClock.elapsedRealtime() - lastFixAtMs) / 1000
            } s"
        }
        // Retry the cable on a background thread and no more than once every
        // few seconds: a Bluetooth connect blocks for seconds, and this thread
        // is also where GPS fixes and frame production live.
        if (l != null && !l.isOpen && !linkOpening.get()) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastLinkTryMs > LINK_RETRY_MS) {
                lastLinkTryMs = now
                linkOpening.set(true)
                linkExec.execute {
                    try { openLink(l) } finally { linkOpening.set(false) }
                }
            }
        }

        refreshCarState(SystemClock.elapsedRealtime())

        val night = isNight()
        val t = tracker
        if (t == null) { freeStep(l, night); return }

        val frame: HudFrame
        val bearing: Double?

        val now = SystemClock.elapsedRealtime()
        val fix = lastFix
        val age = now - lastFixAtMs
        // Real time since the last route tick, for coasting. Capped, so a
        // stalled looper cannot throw the car a kilometre up the road.
        val tickS = if (lastRouteTickMs == 0L) 0.0
                    else (now - lastRouteTickMs).coerceIn(0L, 1000L) / 1000.0
        lastRouteTickMs = now
        if (fix == null) {
            frame = t.update(0.0, 0.0, 0f, null, hasFix = false, night = night)
            bearing = null
        } else if (age > 8000) {
            // A tunnel. Keep running along the route at the car's speed -- or
            // the last GPS speed, held -- so the HUD keeps counting down to
            // the exit instead of freezing; the tracker gives up after
            // RouteTracker.COAST_MAX_MS. alongM/alongAtMs deliberately stay
            // on the last real fix: the map coasts from that on its own.
            val v = bestSpeedMps(if (fix.hasSpeed()) fix.speed else 0f, now)
            val shown = carLink.rawSpeedMps(now)?.let { Math.round(it * 3.6).toInt() } ?: -1
            frame = t.coast(v * tickS, shown, age, night)
            bearing = null
        } else {
            val dt = age / 1000.0
            val gps = if (fix.hasSpeed()) fix.speed else 0f
            val v = bestSpeedMps(gps, SystemClock.elapsedRealtime())
            val brg = if (fix.hasBearing()) fix.bearing else null
            val lead = if (brg != null && v > 1f) {
                Geo.destination(fix.latitude, fix.longitude, brg.toDouble(), v * dt)
            } else doubleArrayOf(fix.latitude, fix.longitude)
            frame = t.update(lead[0], lead[1], v, brg, hasFix = true, night = night,
                             displayMps = displaySpeedMps(gps, SystemClock.elapsedRealtime()))
            bearing = brg?.toDouble()
            // Still on the line: this is where a wrong turn would have left it.
            if (age <= REROUTE_FIX_MAX_AGE_MS && t.lastCrossM <= RerouteRule.TURNED_OFF_CROSS_M) {
                departure = doubleArrayOf(t.snappedLat, t.snappedLon)
            }
            // Where the *real* fix sits on the route, and when it was taken --
            // once per fix. The tracker was fed a point led forward in time,
            // and stamping that with the tick's clock told the map it had a
            // fresh position four times a second through a tunnel, so the
            // map's own coasting never engaged.
            if (lastFixAtMs != alongFixAtMs) {
                alongM = t.alongOf(fix.latitude, fix.longitude, v, brg)
                alongAtMs = lastFixAtMs
                alongFixAtMs = lastFixAtMs
            }
        }

        lastFrame = frame
        routeCountryCode = t.route.countryAt(t.alongM)
        // Null while a route is running. This used to keep whatever road free
        // drive matched before the trip started -- possibly the origin, three
        // hundred kilometres back -- and the moment anything cleared the route
        // the map projected onto it and jumped there.
        roadPts = null
        snapLat = t.snappedLat
        snapLon = t.snappedLon
        roadBearing = t.roadBearing
        snapTrusted = t.snapTrusted
        crossTrackM = t.lastCrossM
        l?.write(frame.encode())
        // Straight after the frame it belongs to, so the board has the
        // angle before it next repaints the arrow.
        frame.rabLine()?.let { l?.write(it) }
        voice?.enabled = voiceEnabled && !quietMode
        voice?.onFrame(frame, t.nextManeuver?.alongM?.toLong())

        currentRoadName = t.currentRoadName
        // The road network is worth having with a route as well as without
        // one: it is where level crossings, speed bumps and toll booths come
        // from, and the router does not report any of them.
        if (fix != null && age <= 8000) {
            val v = bestSpeedMps(if (fix.hasSpeed()) fix.speed else 0f,
                                 SystemClock.elapsedRealtime()).toDouble()
            runCatching { maybeFetchArea(fix, v, bearing, SystemClock.elapsedRealtime()) }
            // On a route, "our road" is the route line itself.
            pushRoadFeatures(fix.latitude, fix.longitude, bearing, frame.speedKph,
                currentRoute?.pts)
        } else featureAhead = null
        pushClosure(t)
        pushLanes(t, l, frame.speedKph)
        pushCameras(t, l, frame, bearing)

        // Arrived: the trip is over. The frame above has already gone to the
        // HUD and to the voice, so "you have arrived" is queued; clearRoute()
        // only resets the voice's bookkeeping, it does not stop the speech.
        // It used to clear just the stored destination and leave the tracker
        // running, so the car parked a few metres off the line was declared
        // off route and rerouted straight back to where it was standing.
        if (frame.flags and HudFrame.FLAG_ARRIVED != 0) {
            clearRoute()
            setStatus("arrived · free drive")
            return
        }

        maybeReroute(t)
        maybeRefreshCameras(t)
        maybePrefetch(t, SystemClock.elapsedRealtime())
    }

    /**
     * A tick with no destination.
     *
     * Everything except the instructions: speed, the limit of the road under
     * the car, its name, and camera warnings. The HUD gets the same frames it
     * would on a route, with the maneuver field empty, so the display and the
     * cable need to know nothing about the difference.
     */
    private fun freeStep(l: SerialLink?, night: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val fix = lastFix
        val age = now - lastFixAtMs
        val live = fix != null && age <= 8000

        // The same speed the route shows and the HUD draws: the bus when it
        // is talking sense, GPS otherwise. Free drive used GPS only.
        val v = if (live) displaySpeedMps(if (fix!!.hasSpeed()) fix.speed else 0f, now) else 0f
        val brg = if (live && fix!!.hasBearing()) fix.bearing else null

        if (live) maybeFetchArea(fix!!, v.toDouble(), brg?.toDouble(), now)

        free.area = freeArea
        val frame = free.update(
            lat = fix?.latitude ?: 0.0,
            lon = fix?.longitude ?: 0.0,
            speedMps = v,
            bearingDeg = brg,
            hasFix = live,
            nowMs = now,
            policy = cameraPolicy,
            night = night,
            country = country,
            localHour = java.util.Calendar.getInstance()
                .get(java.util.Calendar.HOUR_OF_DAY)
        )

        lastFrame = frame
        routeCountryCode = null
        currentRoadName = free.roadName
        crossTrackM = free.crossM
        // Free drive snaps to the road network the same way a route snaps to
        // the route line, so the marker is never drawn inside a building.
        snapLat = free.snappedLat
        snapLon = free.snappedLon
        roadBearing = free.roadBearing
        snapTrusted = free.snapTrusted
        roadPts = free.roadPts
        alongM = 0.0
        l?.write(frame.encode())
        // Straight after the frame it belongs to, so the board has the
        // angle before it next repaints the arrow.
        frame.rabLine()?.let { l?.write(it) }

        // No maneuvers to announce, but the limit chime and camera warnings are
        // exactly as useful with no destination as with one.
        voice?.enabled = voiceEnabled && !quietMode
        voice?.onFrame(frame, null)

        pushRoadFeatures(fix?.latitude ?: 0.0, fix?.longitude ?: 0.0,
            brg?.toDouble(), if (live) frame.speedKph else -1, free.roadPts)

        val alert = free.alert
        val had = cameraAlert
        cameraAlert = alert
        if (alert == null) {
            if (had != null) l?.write(HudFrame.wrap("CAM,0,0,0"))
        } else {
            l?.write(HudFrame.wrap(
                "CAM,${wireKind(alert)},${if (alert.zoneMode) 0 else alert.distanceM}," +
                    "${alert.camera.limitKph}"
            ))
            if (free.shouldAnnounce(alert)) voice?.announceCamera(alert)
        }

        if (lanes != null) { lanes = null; lastLanesSent = null; l?.write(HudFrame.wrap("LANE,0,0")) }
    }

    /**
     * Keep a window of the road network loaded around the car.
     *
     * Sized by speed -- about a minute of driving -- so a motorway gets the
     * kilometres it needs and a town centre does not pull down half of
     * Brussels. Refetched once the car has left the middle of the old window.
     */
    private fun maybeFetchArea(fix: Location, speedMps: Double, heading: Double?, nowMs: Long) {
        if (areaFetching.get()) return
        val t = tracker
        val r = currentRoute
        val centre: DoubleArray
        val want: Double
        if (t != null && r != null && !t.offRoute) {
            // On a route: the window of the route the car is in, the same
            // centre and radius the prefetch stored -- so this is normally a
            // disk hit, and with no signal it is the only thing that works.
            if (AreaRoads.routeWindowStillGood(freeArea, fix.latitude, fix.longitude)) return
            val w = AreaRoads.routeWindow(r, AreaRoads.routeWindowIndex(t.alongM))
            centre = doubleArrayOf(w.lat, w.lon)
            want = w.radiusM
        } else {
            want = AreaRoads.radiusFor(speedMps)
            if (!AreaRoads.needsRefetch(freeArea, fix.latitude, fix.longitude, want)) return
            centre = AreaRoads.centreFor(fix.latitude, fix.longitude, heading, want)
        }
        // Don't hammer Overpass if it is refusing us.
        if (lastAreaFetchAtMs != 0L && nowMs - lastAreaFetchAtMs < AREA_MIN_INTERVAL_MS) return
        lastAreaFetchAtMs = nowMs
        areaFetching.set(true)
        aux.execute {
            try {
                val a = AreaRoads.fetch(centre[0], centre[1], want, nowMs)
                freeArea = a
                areaRoadCount = a.roads.size
                // A new road network means every cached "is this camera on our
                // road" answer was computed against a different set of roads.
                cameraOnRoute.clear()
                cameraCount = a.cameras.size
                camerasFetchedAtMs = System.currentTimeMillis()
                // On a route the status line belongs to the route.
                if (tracker == null) {
                    setStatus("free drive · ${a.roads.size} roads, ${a.cameras.size} cameras " +
                              "within ${(want / 1000).toInt()} km")
                }
            } catch (e: Exception) {
                Log.w(TAG, "area fetch failed", e)
                if (tracker == null) setStatus("free drive · road data unavailable (${e.message})")
            } finally {
                areaFetching.set(false)
            }
        }
    }

    /**
     * Keep the road data for the route ahead on disk, in rolling chunks.
     *
     * The route windows (AreaRoads.routeWindow) for roughly the next
     * [PREFETCH_AHEAD_M] are downloaded into AreaCache, and topped up again
     * whenever less than [PREFETCH_REFILL_M] of them is left ahead. A dead
     * zone -- a valley, a border, a long tunnel approach -- is then already on
     * disk when the car gets there, and the live lookup for the same window is
     * a cache hit rather than a request.
     *
     * Rolling rather than all at once so a 2000 km trip holds ~100 km of
     * Europe at a time, not the whole corridor: AreaCache's caps evict what
     * is behind. One request at a time on `aux`, at least [PREFETCH_GAP_MS]
     * apart and through AreaRoads' fair-use gate; windows already fresh on
     * disk cost nothing. A failure -- offline, or Overpass saying 429/504 --
     * stops the run and backs off, doubling to [PREFETCH_RETRY_MAX_MS]. A new
     * route or a reroute starts over from where the car is.
     */
    private fun maybePrefetch(t: RouteTracker, nowMs: Long) {
        val r = t.route
        if (prefetchRoute !== r) {
            prefetchRoute = r
            prefetchNext = AreaRoads.routeWindowIndex(t.alongM)
            prefetchUntil = -1
            prefetchBackoffMs = 0L
            prefetchRetryAtMs = 0L
        }
        if (prefetching.get() || nowMs < prefetchRetryAtMs) return
        val todo = AreaRoads.prefetchRange(r, t.alongM, prefetchNext,
                                           PREFETCH_AHEAD_M, PREFETCH_REFILL_M) ?: return
        prefetchNext = todo.first
        prefetchUntil = todo.last
        prefetching.set(true)
        continuePrefetch(r)
    }

    private fun continuePrefetch(r: Route) {
        if (runCatching { aux.execute { prefetchStep(r) } }.isFailure) prefetching.set(false)
    }

    /** One request (after skipping what is already fresh), then yield `aux`. */
    private fun prefetchStep(r: Route) {
        var scheduled = false
        try {
            while (running && currentRoute === r && prefetchNext <= prefetchUntil) {
                val w = AreaRoads.routeWindow(r, prefetchNext)
                if (!AreaCache.covers(w.lat, w.lon, w.radiusM, AreaCache.FRESH_MS,
                                      System.currentTimeMillis())) {
                    AreaRoads.prefetch(w.lat, w.lon, w.radiusM)
                    if (currentRoute === r) prefetchNext++
                    prefetchBackoffMs = 0L
                    scheduled = runCatching {
                        handler.postDelayed({ continuePrefetch(r) }, PREFETCH_GAP_MS)
                    }.getOrDefault(false)
                    return
                }
                prefetchNext++
            }
        } catch (e: Exception) {
            // Offline, or Overpass is busy. Not an error worth a status line:
            // the live lookup falls back to disk on its own.
            prefetchBackoffMs = (prefetchBackoffMs * 2)
                .coerceIn(PREFETCH_RETRY_BASE_MS, PREFETCH_RETRY_MAX_MS)
            prefetchRetryAtMs = SystemClock.elapsedRealtime() + prefetchBackoffMs
            Log.i(TAG, "route prefetch paused ${prefetchBackoffMs / 1000} s: ${e.message}")
        } finally {
            if (!scheduled) prefetching.set(false)
        }
    }

    /**
     * Look up the low-emission zones these routes cross, in the background.
     *
     * Deliberately after the route is already on the screen and being driven:
     * it is a second network call to a public Overpass instance, it can take
     * seconds, and it can fail, and none of that should stand between pressing
     * Go and seeing the road. The picker fills its warning in when the answer
     * lands.
     */
    private fun fetchZones(routes: List<Route>, gen: Int) {
        if (routes.isEmpty()) return
        aux.execute {
            try {
                // One query for the longest route's box covers the others,
                // which run between the same two points.
                val widest = routes.maxByOrNull { it.totalDistanceM } ?: return@execute
                val zones = LowEmissionZones.fetch(widest)
                if (gen != routeGen) return@execute
                if (zones.isEmpty()) return@execute
                routeZones = routes.map { r ->
                    LowEmissionZones.crossed(r, zones).map { it.name }
                }
                Log.i(TAG, "low-emission zones: " +
                    routeZones.joinToString(" | ") { it.joinToString(", ").ifEmpty { "-" } })
            } catch (e: Exception) {
                // A missing warning is a missing warning; it must never take
                // the navigation down with it.
                Log.w(TAG, "low-emission zone lookup failed", e)
            }
        }
    }

    /**
     * The next static road feature, and a word about it the first time.
     *
     * Shared by both modes on purpose: a level crossing does not care whether
     * you typed a destination.
     */
    private fun pushRoadFeatures(
        lat: Double, lon: Double, heading: Double?, speedKph: Int,
        roadPts: Array<DoubleArray>?
    ) {
        if (speedKph < 0) { featureAhead = null; return }
        val hit = RoadAhead.nearest(freeArea, lat, lon, heading, speedKph, roadPts)
        featureAhead = hit
        if (hit != null) voice?.onRoadFeature(hit.feature.kind, hit.feature.id)
    }

    /**
     * Roadworks and closures on the route ahead.
     *
     * The route line already paints a closed stretch dark red, which is the
     * right thing to do and is also completely invisible if you are looking at
     * the road rather than the screen — which you should be. So it gets a
     * distance on the glass and a sentence out loud as well.
     */
    private fun pushClosure(t: RouteTracker) {
        val r = currentRoute
        if (r == null) { closureAheadM = -1; return }
        val d = r.metresToClosure(t.alongM)
        closureAheadM = if (d < 0) -1 else d.toInt()
        if (d >= 0) {
            // Key on where the closure starts along the route, so the same one
            // is not announced twice while approaching it.
            val key = ((t.alongM + d) / 50.0).toLong()
            voice?.onClosure(d.toInt(), key)
        }
    }

    /** Lane guidance only matters near the junction; send it, then clear it. */
    private fun pushLanes(t: RouteTracker, l: SerialLink?, speedKph: Int) {
        val next = t.nextManeuver
        val want = if (next?.lanes != null &&
            (next.alongM - t.alongM) <= laneRange(maxOf(0, speedKph) / 3.6)) next.lanes else null
        lanes = want
        if (want == lastLanesSent) return
        lastLanesSent = want
        l?.write(HudFrame.wrap(want?.encodeBody() ?: "LANE,0,0"))
    }

    private fun pushCameras(t: RouteTracker, l: SerialLink?, f: HudFrame, bearing: Double?) {
        val w = watcher
        if (w == null) {
            if (cameraAlert != null) { cameraAlert = null; l?.write(HudFrame.wrap("CAM,0,0,0")) }
            return
        }
        // A camera belongs to the carriageway it watches. Distance from our
        // route cannot tell that from the road next door -- both are a few tens
        // of metres -- so ask which road in the loaded network the camera is
        // actually nearest to. Memoised: the answer cannot change while the
        // area and the camera stay the same, and this runs four times a second.
        val area = freeArea
        val route = t.route
        val alert = w.update(t.alongM, f.speedKph, bearing) { cam ->
            cameraOnRoute.getOrPut(cam.id) {
                // The window lets onOurRoad tell which OSM ways carry our route
                // near this camera, so it compares way against way instead of
                // way against a generalised route line -- which used to reject
                // cameras that were genuinely ours.
                SpeedCameras.onOurRoad(
                    area, cam.lat, cam.lon, cam.crossM, route.window(cam.alongM, 120.0)
                )
            }
        }
        val had = cameraAlert
        cameraAlert = alert
        if (alert == null) {
            if (had != null) l?.write(HudFrame.wrap("CAM,0,0,0"))
            return
        }
        l?.write(HudFrame.wrap(
            "CAM,${wireKind(alert)},${if (alert.zoneMode) 0 else alert.distanceM}," +
                "${alert.camera.limitKph}"
        ))
        if (w.shouldAnnounce(alert)) voice?.announceCamera(alert)
    }

    /**
     * The camera kind as the HUD firmware understands it.
     *
     * 1 fixed, 2 average-speed, 3 traffic-light, 4 danger zone. This used to be
     * `kind.ordinal + 1`, which sent 4 for `Kind.UNKNOWN` -- and 4 is the
     * danger-zone code. Every camera that came from an `enforcement=maxspeed`
     * node or an enforcement relation (876 of them in Belgium alone) was
     * therefore drawn on the dashboard as a positionless "ZONE" with its
     * distance thrown away, in a country where the countdown is legal, while
     * the phone screen showed the countdown next to it.
     */
    private fun wireKind(alert: CameraAlert): Int = when {
        alert.zoneMode -> 4
        alert.camera.kind == SpeedCamera.Kind.AVERAGE -> 2
        alert.camera.kind == SpeedCamera.Kind.TRAFFIC_LIGHT -> 3
        else -> 1                       // FIXED, and UNKNOWN is a camera too
    }

    /** Keep the camera list current on a long drive. */
    private fun maybeRefreshCameras(t: RouteTracker) {
        if (cameraPolicy == CameraPolicy.OFF) return
        val now = SystemClock.elapsedRealtime()
        if (lastCameraFetchMs != 0L && now - lastCameraFetchMs < CAMERA_REFRESH_MS) return
        lastCameraFetchMs = now                  // set first, so we retry on the timer, not in a loop
        fetchCameras(t.route)
    }

    private fun maybeReroute(t: RouteTracker) {
        val now = SystemClock.elapsedRealtime()
        if (!t.offRoute) offRouteSinceMs = 0L
        else if (offRouteSinceMs == 0L) offRouteSinceMs = now
        // Only a fresh fix is evidence. The tick leads an older fix along its
        // bearing for up to eight seconds, and on a bend that invented point
        // drifts off the line and away from the road's direction on its own.
        val fix = lastFix
        if (fix == null || now - lastFixAtMs > REROUTE_FIX_MAX_AGE_MS) {
            turnedOffSinceMs = 0L
            return
        }
        // How far the car is pointing from the way the route runs here. Only
        // once the car is genuinely moving: below walking pace a GPS bearing
        // is noise, and a noisy bearing would fire the shortcut at every stop.
        val carBrg = if (fix.hasBearing() && fix.hasSpeed() && fix.speed > 2.5f)
            fix.bearing.toDouble() else null
        val routeBrg = t.roadBearing
        val headingOff = if (carBrg != null && routeBrg != null)
            Geo.bearingDelta(routeBrg, carBrg) else null
        // Timed here rather than in the rule, which is stateless: the heading
        // test has to hold for two seconds without a break.
        if (!RerouteRule.turnedOff(t.lastCrossM, headingOff)) turnedOffSinceMs = 0L
        else if (turnedOffSinceMs == 0L) turnedOffSinceMs = now
        if (!t.offLine && turnedOffSinceMs == 0L) return
        // A failed request is already retrying on its own back-off. Starting
        // another every cooldown would hammer a dead network and say
        // "recalculating" every six seconds with no signal; meanwhile the old
        // route keeps guiding. The retry reroutes once the network is back.
        if (routeAttempt > 0) return
        if (!RerouteRule.shouldReroute(
                offRoute = t.offRoute,
                crossM = t.lastCrossM,
                offRouteSinceMs = offRouteSinceMs,
                lastRequestMs = lastRerouteMs,
                nowMs = now,
                headingOffDeg = headingOff,
                turnedOffSinceMs = turnedOffSinceMs
            )
        ) return
        lastRerouteMs = now
        voice?.announceReroute()
        requestRoute("off route by ${t.lastCrossM.toInt()} m",
            RouteChoice.Backtrack(departure, breadcrumb.snapshot()))
    }

    private fun isNight(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h < 7 || h >= 20
    }

    // ---- foreground plumbing ----------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "NavHUD", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    internal fun buildNotification(): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MapActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HudService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // A route interrupted by a kill is offered here rather than restored.
        // Until this is tapped the service is in free drive, so the wording has
        // to be an offer and not a status -- "Interrupted: <where>" would read
        // as though the route were still running.
        val offer = resumeLabel
        val text = when {
            offer != null -> "Resume route to $offer?"
            quietMode -> "Speed limit and camera warnings"
            else -> destinationLabel ?: "Driving the head-up display"
        }
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NavHUD")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_navhud)
            .setContentIntent(open)
        if (offer != null) {
            val resume = PendingIntent.getService(
                this, 2,
                Intent(this, HudService::class.java).setAction(ACTION_RESUME_ROUTE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(0, "Resume", resume)
        }
        return b
            .addAction(0, "Stop", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    internal val notifier: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    /** @return false when we are not entitled to run in the foreground at all. */
    private fun startForegroundCompat(): Boolean {
        val n = buildNotification()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n)
            return true
        }

        var types = ForegroundType.typesFor(
            hasLocation = hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ||
                          hasPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION),
            hasBluetooth = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                           hasPermission(android.Manifest.permission.BLUETOOTH_CONNECT),
            usbAttached = usbDeviceAttached(),
            sdkInt = Build.VERSION.SDK_INT
        )

        // Narrow on refusal rather than dying. Android tells us we may not
        // claim a type by throwing, and the honest response is to claim less.
        while (types != ForegroundType.NONE) {
            try {
                startForeground(NOTIF_ID, n, types)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "foreground type $types refused: ${e.message}")
                types = ForegroundType.narrow(types)
            }
        }

        // Nothing left to claim, so this service has no job. It still has to
        // call startForeground *once*, though: we were started with
        // startForegroundService, which arms a timer in the system, and simply
        // calling stopSelf() without ever going foreground makes Android throw
        // ForegroundServiceDidNotStartInTimeException on our main thread --
        // trading one crash for another. specialUse has no runtime
        // prerequisite, so it always succeeds; we use it purely to shut down
        // tidily and then stop.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        }.onFailure { Log.w(TAG, "could not go foreground at all", it) }

        setStatus("cannot run in the background: location permission is required")
        return false
    }

    private fun hasPermission(p: String) =
        androidx.core.content.ContextCompat.checkSelfPermission(this, p) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** A HUD board we already have permission for counts for connectedDevice. */
    private fun usbDeviceAttached(): Boolean = runCatching {
        val m = getSystemService(USB_SERVICE) as android.hardware.usb.UsbManager
        m.deviceList.values.any { m.hasPermission(it) }
    }.getOrDefault(false)
}
