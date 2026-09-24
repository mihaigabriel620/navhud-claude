package com.mihai.navhud

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Will this phone actually let the head-up display keep running?
 *
 * The service surviving is not one switch, it is four or five, most of which
 * live in the phone's own settings rather than in this app — and on a OnePlus,
 * two of them get quietly reverted by firmware updates. Rather than let that
 * be discovered on a motorway when the display goes dark, the Setup screen
 * asks the questions and says which answers are wrong.
 *
 * What the app itself does about it is in `HudService`: a partial wake lock,
 * because a foreground service does *not* keep the CPU awake, and
 * `stopWithTask="false"` so swiping the app away cannot take the service with
 * it. Those two are ours. The rest is the driver's, and this tells them which.
 */
object BackgroundHealth {

    data class Check(val label: String, val ok: Boolean, val detail: String)

    /** True when the phone has agreed not to put this app to sleep. */
    fun ignoringBatteryOptimisations(ctx: Context): Boolean =
        runCatching {
            ctx.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(ctx.packageName)
        }.getOrDefault(false)

    /**
     * Low Power Standby is the one documented mechanism that ignores a
     * foreground service's wake lock — network off and wake locks dropped
     * whenever the screen is off. It is disabled in stock Android and an
     * OEM may switch it on.
     */
    fun lowPowerStandby(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return runCatching {
            val pm = ctx.getSystemService(PowerManager::class.java)
            if (!pm.isLowPowerStandbyEnabled) return false
            // Exempt is fine; caught by it is not.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                pm.isExemptFromLowPowerStandby) return false
            true
        }.getOrDefault(false)
    }

    fun backgroundRestricted(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return runCatching {
            ctx.getSystemService(ActivityManager::class.java).isBackgroundRestricted
        }.getOrDefault(false)
    }

    fun powerSaveMode(ctx: Context): Boolean =
        runCatching {
            ctx.getSystemService(PowerManager::class.java).isPowerSaveMode
        }.getOrDefault(false)

    fun checks(ctx: Context): List<Check> = listOf(
        Check(
            "Location",
            Permissions.precise(ctx),
            when {
                Permissions.precise(ctx) -> "Precise. Good."
                Permissions.approximateOnly(ctx) -> "Approximate only. Precise location needed: " +
                    "app settings → Permissions → Location → Use precise location."
                else -> "Not allowed. NavHUD cannot navigate or start with the car."
            }
        ),
        Check(
            "Battery optimisation",
            ignoringBatteryOptimisations(ctx),
            if (ignoringBatteryOptimisations(ctx)) "Off for NavHUD. Good."
            else "On. The phone may suspend the display while you drive."
        ),
        Check(
            "Background activity",
            !backgroundRestricted(ctx),
            if (backgroundRestricted(ctx))
                "Restricted. Allow background activity in the app's settings."
            else "Allowed"
        ),
        Check(
            "Low power standby",
            !lowPowerStandby(ctx),
            if (lowPowerStandby(ctx))
                "On, and NavHUD is not exempt. The display will stop when the screen goes off."
            else "Not in the way"
        ),
        Check(
            "Battery saver",
            !powerSaveMode(ctx),
            if (powerSaveMode(ctx)) "On. Turn it off for a long drive." else "Off"
        )
    )

    /** The one-tap system dialog, with the settings screen as a fallback. */
    fun requestExemption(ctx: Context): Boolean {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${ctx.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(direct); true }.getOrDefault(false)) return true
        // Some OEM builds do not resolve the direct action; the list screen
        // always exists.
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(list); true }.getOrDefault(false)
    }

    /**
     * The settings only the driver can reach, in the order that matters.
     *
     * OnePlus and Oppo share a power manager, and dontkillmyapp.com ranks the
     * pair third worst on the market. Two details from there are worth
     * repeating because they are not obvious: locking the app in the recents
     * list is what stops the phone silently reverting the battery setting, and
     * "Deep optimization" is a separate switch that overrides the per-app one.
     */
    val manualSteps: List<String> = listOf(
        "Battery → Battery optimisation → All apps → NavHUD → Don't optimise",
        "Apps → NavHUD → Battery usage → Allow background activity (or Unrestricted)",
        "Recents → long-press the NavHUD card → Lock. This is also what stops the phone undoing the setting above.",
        "Battery → ⋮ → Advanced optimisation → Deep optimisation → off",
        "Apps → Auto launch → NavHUD → on",
        "Re-check these after a system update: OnePlus resets them."
    )
}
