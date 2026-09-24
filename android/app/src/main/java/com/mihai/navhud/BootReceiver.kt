package com.mihai.navhud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Bring the display up when the head unit does.
 *
 * A car head unit powers on with the ignition and there is nobody to tap an
 * icon, so the useful behaviour is for the speed limit to be on the glass by
 * the time the engine is running.
 *
 * This is one of the places where Android 10 is *easier* than a modern phone.
 * The rule that stops an app starting a foreground service from the background
 * arrived in API 31 and needs both a targetSdk of 31 and a device running it —
 * on API 29 the enforcement code does not exist, so a boot receiver may start a
 * location foreground service directly, with no exemption and no ceremony. On a
 * newer phone the same call needs the battery-optimisation exemption to be
 * legal, which is why the Setup screen asks for it.
 *
 * Two caveats worth knowing. A package in Android's *stopped state* receives no
 * broadcasts at all, so this never fires until the app has been opened once
 * after installing. And on a vendor ROM with an "auto launch" manager, the
 * receiver is exactly what that manager blocks.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON") return

        // LOCKED_BOOT_COMPLETED arrives *before* the user unlocks, and on a
        // file-based-encryption device with a lock credential -- the default
        // since Android 10 -- reading SharedPreferences there throws
        // IllegalStateException ("not available until after user is
        // unlocked"), which took the process down on every boot. Nothing
        // useful could happen on that path anyway: HudService is not
        // directBootAware, so it cannot start before unlock. Wait for the
        // ordinary BOOT_COMPLETED that follows.
        // https://developer.android.com/privacy-and-security/direct-boot
        if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            Log.i(TAG, "locked boot: waiting for the user to unlock")
            return
        }

        val wanted = runCatching { Prefs.startOnBoot(ctx) }.getOrElse {
            Log.w(TAG, "boot: preferences unreadable", it); return
        }
        if (!wanted) return
        // Without location there is nothing to report, and starting a
        // location-typed service without the permission is a crash on 14+.
        // Not silent: the Setup screen's "Location" line says what is missing.
        if (!Permissions.precise(ctx)) {
            Log.i(TAG, "boot: no precise location permission, staying down")
            return
        }

        Log.i(TAG, "boot: starting the HUD service")
        runCatching {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, HudService::class.java).setAction(HudService.ACTION_START)
            )
        }.onFailure { Log.w(TAG, "boot start refused", it) }
    }

    private companion object { const val TAG = "BootReceiver" }
}
