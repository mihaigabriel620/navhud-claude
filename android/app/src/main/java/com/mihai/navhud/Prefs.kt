package com.mihai.navhud

import android.content.Context
import com.mihai.navhud.alerts.CameraPolicy
import com.mihai.navhud.map.ParkedHeading
import com.mihai.navhud.nav.LatLon
import com.mihai.navhud.voice.Phrases

/**
 * Settings that have to survive a restart.
 *
 * The Mapbox token lives here rather than only in BuildConfig because an APK
 * you hand to someone else cannot have a token baked into it -- they need to
 * paste their own in on the Setup screen. The build-time value is still used as
 * a default, so a local `local.properties` build works with no typing at all.
 */
object Prefs {
    private const val FILE = "navhud"
    private const val KEY_TOKEN = "mapbox_token"
    private const val KEY_VOICE = "voice"
    private const val KEY_BT = "use_bt"
    private const val KEY_LANG = "voice_lang"
    private const val KEY_HOME_COUNTRY = "home_country"
    private const val KEY_SHOW_PERF = "show_perf"
    private const val KEY_DEST_LAT = "dest_lat"
    private const val KEY_DEST_LON = "dest_lon"
    private const val KEY_DEST_LABEL = "dest_label"
    private const val KEY_CAMERA_PREF = "camera_pref"
    private const val KEY_GOOGLE = "google_key"
    private const val KEY_HEAD_OFFSET = "heading_offset"
    private const val KEY_HEAD_AXIS = "heading_axis"
    private const val KEY_HEAD_DONE = "heading_calibrated"
    private const val KEY_DECLINATION = "declination"
    private const val KEY_BOOT = "start_on_boot"
    private const val KEY_KEYSTONE = "keystone"
    private const val KEY_KEYSTONE_H = "keystone_h"
    private const val KEY_KEYSTONE_V = "keystone_v"
    private const val KEY_KEYSTONE_CUSTOM = "keystone_custom"
    private const val KEY_PARK_LAT = "parked_lat"
    private const val KEY_PARK_LON = "parked_lon"
    private const val KEY_PARK_HEAD = "parked_heading"
    private const val KEY_PARK_AT = "parked_at"
    private const val KEY_DEST_AT = "dest_at"
    // v2: a new key so every install, including those that stored the old
    // default of false, switches to snapping.
    private const val KEY_SNAP = "snap_to_road_v2"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun mapboxToken(ctx: Context): String {
        val saved = sp(ctx).getString(KEY_TOKEN, null)
        if (!saved.isNullOrBlank()) return saved.trim()
        return BuildConfig.MAPBOX_TOKEN.trim()
    }

    fun setMapboxToken(ctx: Context, token: String) {
        sp(ctx).edit().putString(KEY_TOKEN, token.trim()).apply()
    }

    fun hasToken(ctx: Context) = mapboxToken(ctx).isNotBlank()

    var voiceDefault = true

    fun voice(ctx: Context) = sp(ctx).getBoolean(KEY_VOICE, true)
    fun setVoice(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean(KEY_VOICE, on).apply()

    fun useBluetooth(ctx: Context) = sp(ctx).getBoolean(KEY_BT, false)
    fun setUseBluetooth(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean(KEY_BT, on).apply()

    /** Optional Google Geocoding key: search only, never the map. */
    fun googleKey(ctx: Context): String = sp(ctx).getString(KEY_GOOGLE, "") ?: ""

    fun setGoogleKey(ctx: Context, key: String) =
        sp(ctx).edit().putString(KEY_GOOGLE, key.trim()).apply()

    /**
     * Start the service when the device boots.
     *
     * On by default, because the app's home is a car head unit that powers up
     * with the ignition and has nobody to tap an icon. Harmless on a phone —
     * it only starts the service, which is the thing that was going to be
     * started the moment the app opened anyway.
     */
    fun startOnBoot(ctx: Context) = sp(ctx).getBoolean(KEY_BOOT, true)

    fun setStartOnBoot(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean(KEY_BOOT, on).apply()

    // ---- which way the phone is pointing ------------------------------------

    /**
     * Degrees to add to the phone's compass reading to get the car's heading.
     *
     * Absorbs the mounting angle -- flat in a cradle, upright in a windscreen
     * mount, rotated 180 in the same cradle -- and the car's own magnetic
     * distortion, both of which are constants for a given car and holder. Zero
     * until something has learned it.
     */
    fun headingOffset(ctx: Context): Double =
        sp(ctx).getFloat(KEY_HEAD_OFFSET, 0f).toDouble()

    fun setHeadingOffset(ctx: Context, deg: Double) =
        sp(ctx).edit().putFloat(KEY_HEAD_OFFSET, deg.toFloat()).apply()

    /** Which device axis the offset was measured against. See Compass. */
    fun headingAxis(ctx: Context): Int = sp(ctx).getInt(KEY_HEAD_AXIS, 0)

    fun setHeadingAxis(ctx: Context, axis: Int) =
        sp(ctx).edit().putInt(KEY_HEAD_AXIS, axis).apply()

    fun headingCalibrated(ctx: Context) = sp(ctx).getBoolean(KEY_HEAD_DONE, false)

    fun setHeadingCalibrated(ctx: Context, done: Boolean) =
        sp(ctx).edit().putBoolean(KEY_HEAD_DONE, done).apply()

    /**
     * Magnetic-to-true correction last computed for where the car was.
     *
     * Stashed so the calibration screen and the map apply the same one. They
     * did not: the map added the declination and the calibration screen did
     * not, so an offset nudged into place by hand came out wrong by the local
     * declination -- two and a half degrees in Belgium, six in Romania.
     */
    fun declination(ctx: Context): Double =
        sp(ctx).getFloat(KEY_DECLINATION, 0f).toDouble()

    fun setDeclination(ctx: Context, deg: Double) =
        sp(ctx).edit().putFloat(KEY_DECLINATION, deg.toFloat()).apply()

    fun clearHeadingCalibration(ctx: Context) = sp(ctx).edit()
        .remove(KEY_HEAD_OFFSET).remove(KEY_HEAD_AXIS).remove(KEY_HEAD_DONE).apply()

    // ---- where the car was left --------------------------------------------

    /**
     * The heading the car had when it was last moving, and where it was.
     *
     * Read on startup so the map can point the right way before the car has
     * moved -- see [com.mihai.navhud.map.ParkedHeading] for why this is the
     * only way to answer that without a magnetometer, and there is not one in
     * this system.
     *
     * The position goes in as raw double bits rather than the float the
     * destination uses. A destination is somewhere you are routing *to* and a
     * metre either way is nothing; this one is compared against a 15 m radius
     * to decide whether the car has been moved, and float32 quantises a
     * latitude to about 0.9 m. Not fatal, just needless when a Long is exact
     * and costs the same.
     */
    fun parkedHeading(ctx: Context): ParkedHeading.Record? {
        val p = sp(ctx)
        if (!p.contains(KEY_PARK_LAT) || !p.contains(KEY_PARK_HEAD)) return null
        return ParkedHeading.Record(
            lat = Double.fromBits(p.getLong(KEY_PARK_LAT, 0L)),
            lon = Double.fromBits(p.getLong(KEY_PARK_LON, 0L)),
            headingDeg = p.getFloat(KEY_PARK_HEAD, 0f).toDouble(),
            savedAtMs = p.getLong(KEY_PARK_AT, 0L),
        )
    }

    fun setParkedHeading(ctx: Context, rec: ParkedHeading.Record) =
        sp(ctx).edit()
            .putLong(KEY_PARK_LAT, rec.lat.toRawBits())
            .putLong(KEY_PARK_LON, rec.lon.toRawBits())
            .putFloat(KEY_PARK_HEAD, rec.headingDeg.toFloat())
            .putLong(KEY_PARK_AT, rec.savedAtMs)
            .apply()

    fun clearParkedHeading(ctx: Context) = sp(ctx).edit()
        .remove(KEY_PARK_LAT).remove(KEY_PARK_LON)
        .remove(KEY_PARK_HEAD).remove(KEY_PARK_AT).apply()

    /**
     * Pull the marker onto the road, or draw it where the receiver says it is.
     *
     * On (1.27). Unsnapped, the arrow sat at the raw fix pushed along the
     * compass heading: 5-10 m off the road, biased to one side, and jerky,
     * which on the road read as broken rather than as honest. Both snaps have
     * their own trust gates (RouteTracker.SNAP_TRUST_M, FreeTracker's), so a
     * slip road or a car park still falls back to the fix.
     *
     * Kept as a setting so the unsnapped path can still be chosen.
     */
    fun snapToRoad(ctx: Context) = sp(ctx).getBoolean(KEY_SNAP, true)

    fun setSnapToRoad(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean(KEY_SNAP, on).apply()

    // ---- language ----------------------------------------------------------

    /** "", "en" or "fr". Empty means follow the device. */
    fun languageCode(ctx: Context): String = sp(ctx).getString(KEY_LANG, "") ?: ""

    fun setLanguageCode(ctx: Context, code: String) =
        sp(ctx).edit().putString(KEY_LANG, code).apply()

    fun phrases(ctx: Context): Phrases {
        val c = languageCode(ctx)
        return if (c.isBlank()) Phrases.forDevice() else Phrases.forCode(c)
    }

    // ---- speed cameras -----------------------------------------------------

    /**
     * Where you mostly drive. Used until a reverse geocode says otherwise, so
     * the first minutes of a drive behave sensibly instead of defaulting to the
     * strictest rules in Europe.
     */
    fun homeCountry(ctx: Context): String = sp(ctx).getString(KEY_HOME_COUNTRY, "BE") ?: "BE"

    fun setHomeCountry(ctx: Context, iso2: String) =
        sp(ctx).edit().putString(KEY_HOME_COUNTRY, iso2.uppercase().take(2)).apply()

    /**
     * The user can ask for *less* than the local law allows, never more --
     * CountryRules.effective enforces that.
     */
    fun cameraPreference(ctx: Context): CameraPolicy? {
        val s = sp(ctx).getString(KEY_CAMERA_PREF, null) ?: return null
        return runCatching { CameraPolicy.valueOf(s) }.getOrNull()
    }

    fun setCameraPreference(ctx: Context, p: CameraPolicy?) {
        sp(ctx).edit().apply {
            if (p == null) remove(KEY_CAMERA_PREF) else putString(KEY_CAMERA_PREF, p.name)
        }.apply()
    }

    // ---- the destination that is live right now ----------------------------

    /**
     * Survives a process kill so a sticky restart can put the route back.
     *
     * Deliberately *not* the redelivered start intent: redelivery is only
     * finished by `stopSelf(startId)`, which the service never calls, so the
     * intent stayed armed forever and resurrected a destination the driver had
     * cancelled. This is cleared when the route is.
     */
    fun setActiveDestination(ctx: Context, lat: Double, lon: Double, label: String?) =
        sp(ctx).edit()
            .putFloat(KEY_DEST_LAT, lat.toFloat())
            .putFloat(KEY_DEST_LON, lon.toFloat())
            .putString(KEY_DEST_LABEL, label)
            .putLong(KEY_DEST_AT, System.currentTimeMillis())
            .apply()

    /**
     * How long a stored destination stays eligible for a sticky restart.
     *
     * The restore exists so that a service Android killed mid-drive comes back
     * still routing. It is not meant to resurrect this morning's trip when you
     * open the app in the evening -- which is what happened: the app crashed on
     * a USB permission answer, Android restarted the service with a null
     * intent, the old destination came back out of preferences, and a manoeuvre
     * arrow appeared on the glass for a journey nobody had asked for.
     *
     * Two hours is longer than any interruption a live drive survives and
     * shorter than "later today".
     */
    const val DEST_MAX_AGE_MS = 2L * 3600 * 1000

    fun activeDestination(ctx: Context): LatLon? {
        val p = sp(ctx)
        if (!p.contains(KEY_DEST_LAT) || !p.contains(KEY_DEST_LON)) return null
        // No timestamp means it was stored by a build that predates this, so
        // treat it as expired rather than trusting it.
        val at = p.getLong(KEY_DEST_AT, 0L)
        val age = System.currentTimeMillis() - at
        if (at == 0L || age > DEST_MAX_AGE_MS || age < -DEST_MAX_AGE_MS) return null
        return LatLon(p.getFloat(KEY_DEST_LAT, 0f).toDouble(),
                      p.getFloat(KEY_DEST_LON, 0f).toDouble())
    }

    fun activeDestinationLabel(ctx: Context): String? =
        sp(ctx).getString(KEY_DEST_LABEL, null)

    fun clearActiveDestination(ctx: Context) = sp(ctx).edit()
        .remove(KEY_DEST_AT)
        .remove(KEY_DEST_LAT).remove(KEY_DEST_LON).remove(KEY_DEST_LABEL).apply()

    /**
     * Show the frame-timing overlay in the quick-actions sheet.
     *
     * Off by default. On, it turns "it stutters" into vsync rate, camera-frame
     * cost, worst frame in the last second, tick cost and GPS state -- which is
     * the difference between fixing it and guessing at it.
     */
    /**
     * The screen alignment, as the phone last saw it.
     *
     * This is a cache, not the record. The settings live in the display's own
     * flash so they survive being used with a different phone, and the app asks
     * for them with `$GEOM?` whenever a board says hello. What is kept here is
     * the slider positions, which the board has no way to know: it stores four
     * corners, and "which two sliders would produce those corners" has no
     * unique answer.
     */
    fun keystone(ctx: Context): com.mihai.navhud.hud.Keystone? =
        com.mihai.navhud.hud.Keystone.deserialize(sp(ctx).getString(KEY_KEYSTONE, null))

    fun keystoneSliderH(ctx: Context): Int = sp(ctx).getInt(KEY_KEYSTONE_H, 0)
    fun keystoneSliderV(ctx: Context): Int = sp(ctx).getInt(KEY_KEYSTONE_V, 0)
    fun keystoneCustom(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_KEYSTONE_CUSTOM, false)

    fun setKeystone(ctx: Context, k: com.mihai.navhud.hud.Keystone,
                    sliderH: Int, sliderV: Int, custom: Boolean) =
        sp(ctx).edit()
            .putString(KEY_KEYSTONE, com.mihai.navhud.hud.Keystone.serialize(k))
            .putInt(KEY_KEYSTONE_H, sliderH)
            .putInt(KEY_KEYSTONE_V, sliderV)
            .putBoolean(KEY_KEYSTONE_CUSTOM, custom)
            .apply()

    fun showPerf(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_SHOW_PERF, false)

    fun setShowPerf(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean(KEY_SHOW_PERF, on).apply()
}
