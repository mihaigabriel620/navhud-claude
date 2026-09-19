package com.mihai.navhud

import android.content.pm.ServiceInfo

/**
 * Which foreground-service types to claim.
 *
 * This is the bug that made 1.6 crash the moment you opened it.
 *
 * From Android 14, `startForeground` does not merely take a type — it checks
 * that you are entitled to it, and throws if you are not. The service was
 * asking for `location|connectedDevice` unconditionally, and `connectedDevice`
 * requires one of the Bluetooth runtime permissions to be *granted*, or a USB
 * device you have been given permission for. Declaring
 * `FOREGROUND_SERVICE_CONNECTED_DEVICE` in the manifest is necessary but not
 * sufficient; the runtime prerequisite is separate.
 *
 * It had been there since 1.4 and never fired, because until 1.6 the service
 * only started once you had searched for a destination. Free drive starts it
 * the moment the map opens, which turned a latent bug into the first thing
 * that happens.
 *
 * Pure, so the decision can be unit-tested; the permission checks live in the
 * service where they belong.
 */
object ForegroundType {

    /** Nothing we are entitled to. The caller must not start foreground at all. */
    const val NONE = 0

    /**
     * @param hasLocation      ACCESS_FINE or COARSE_LOCATION granted
     * @param hasBluetooth     BLUETOOTH_CONNECT granted (or not needed, pre-31)
     * @param usbAttached      a HUD board is plugged in and we may talk to it
     * @param sdkInt           Build.VERSION.SDK_INT
     */
    fun typesFor(
        hasLocation: Boolean,
        hasBluetooth: Boolean,
        usbAttached: Boolean,
        sdkInt: Int
    ): Int {
        // Types only exist from Q, and are only *enforced* from UPSIDE_DOWN_CAKE.
        if (sdkInt < 29) return NONE

        var types = NONE
        if (hasLocation) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (hasBluetooth || usbAttached) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        return types
    }

    /**
     * The next thing to try when a claim is refused: drop the connected-device
     * half and keep location, then give up. Narrowing beats crashing — a nav
     * service that cannot claim the cable can still navigate.
     */
    fun narrow(types: Int): Int = when {
        types and ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE != 0 ->
            types and ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE.inv()
        else -> NONE
    }
}
