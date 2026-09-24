package com.mihai.navhud

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.mihai.navhud.ui.Notice

/**
 * The location permission, asked for and explained the same way everywhere.
 *
 * Android 12+ may ignore a request for FINE on its own; FINE and COARSE have
 * to be asked for together, and the driver can then still pick "Approximate".
 * Navigation needs the precise one, so approximate is treated as missing --
 * but said out loud, with the way to the switch, instead of doing nothing.
 */
object Permissions {

    val LOCATION = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun granted(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun precise(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)

    /** The driver allowed location, but only "Approximate". */
    fun approximateOnly(ctx: Context) =
        !precise(ctx) && granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Android 10+: location while no screen of the app is open ("Allow all the time"). */
    fun background(ctx: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    /**
     * Start-with-the-car cannot work: from Android 11 a service started from
     * the background gets no location without "Allow all the time", and on
     * 14+ its startForeground(location) throws and the service stops.
     */
    fun bootBlocked(ctx: Context) =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !background(ctx)

    /**
     * The phone's Location switch. Off, there are no fixes at all, and on
     * Android 14+ a location foreground service may not even start.
     */
    fun locationOn(ctx: Context): Boolean = runCatching {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
        else lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
             lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
    }.getOrDefault(true)

    /** "Turn on Location", with a tap through to the switch. */
    fun explainLocationOff(activity: Activity) {
        Notice.show(activity, activity.getString(R.string.turn_on_location),
                    activity.getString(R.string.open_location_settings)) {
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
        }
    }

    /** This app's page in the system settings, where every permission lives. */
    fun openAppSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                       Uri.fromParts("package", ctx.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /**
     * After the permission dialog. A refusal used to leave the app quietly
     * doing nothing -- and after "Don't ask again" (Android 11+: a second
     * refusal) the dialog never comes back, so the settings page is the only
     * way left. Say so, with the way there.
     */
    fun onResult(activity: Activity, permissions: Array<out String>) {
        // Empty when the request was cancelled; not ours when location was not asked.
        if (Manifest.permission.ACCESS_FINE_LOCATION !in permissions || precise(activity)) return
        if (approximateOnly(activity)) explainApproximate(activity)
        else Notice.show(activity, activity.getString(R.string.need_location),
                         activity.getString(R.string.open_app_settings)) { openAppSettings(activity) }
    }

    /** "Precise location needed", with a tap through to the settings. */
    fun explainApproximate(activity: Activity) {
        Notice.show(activity, activity.getString(R.string.need_precise_location),
                    activity.getString(R.string.open_app_settings)) { openAppSettings(activity) }
    }
}
