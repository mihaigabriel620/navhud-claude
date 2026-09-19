package com.mihai.navhud

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crash: NavHUD 1.6 closed the moment you opened it.
 *
 * From Android 14 `startForeground` verifies that you are entitled to the
 * service type you ask for. The service asked for `location|connectedDevice`
 * unconditionally, and `connectedDevice` additionally requires one of the
 * Bluetooth runtime permissions to be *granted* — declaring
 * FOREGROUND_SERVICE_CONNECTED_DEVICE in the manifest is necessary but not
 * sufficient. Nothing ever requested BLUETOOTH_CONNECT on the driving screen,
 * so the claim was refused and the exception took the app with it.
 *
 * It had been wrong since 1.4 and never fired, because the service only used
 * to start once you had searched for a destination. Free drive starts it as
 * the map opens, which turned a latent bug into the first thing that happens.
 */
class ForegroundTypeTest {

    private val LOCATION = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
    private val DEVICE = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

    private fun types(loc: Boolean, bt: Boolean, usb: Boolean, sdk: Int = 34) =
        ForegroundType.typesFor(loc, bt, usb, sdk)

    @Test fun `the exact case that crashed`() {
        // Android 14, location granted, Bluetooth never asked for, nothing
        // plugged into the USB port. This used to claim connectedDevice.
        assertEquals(LOCATION, types(loc = true, bt = false, usb = false))
    }

    @Test fun `the cable earns the connected-device claim`() {
        assertEquals(LOCATION or DEVICE, types(loc = true, bt = false, usb = true))
    }

    @Test fun `so does a granted Bluetooth permission`() {
        assertEquals(LOCATION or DEVICE, types(loc = true, bt = true, usb = false))
    }

    @Test fun `no location permission means no location claim`() {
        assertEquals(DEVICE, types(loc = false, bt = true, usb = false))
        assertEquals(ForegroundType.NONE, types(loc = false, bt = false, usb = false))
    }

    @Test fun `before Android 10 there are no types to claim`() {
        assertEquals(ForegroundType.NONE, types(loc = true, bt = true, usb = true, sdk = 28))
        assertEquals(ForegroundType.NONE, types(loc = true, bt = true, usb = true, sdk = 24))
    }

    @Test fun `a refused claim narrows instead of crashing`() {
        // connectedDevice is the one Android is most likely to refuse, so it
        // goes first; navigation survives without it.
        assertEquals(LOCATION, ForegroundType.narrow(LOCATION or DEVICE))
        assertEquals(ForegroundType.NONE, ForegroundType.narrow(LOCATION))
        assertEquals(ForegroundType.NONE, ForegroundType.narrow(DEVICE))
        assertEquals(ForegroundType.NONE, ForegroundType.narrow(ForegroundType.NONE))
    }

    @Test fun `narrowing always terminates`() {
        for (start in listOf(LOCATION or DEVICE, LOCATION, DEVICE, ForegroundType.NONE)) {
            var t = start
            var steps = 0
            while (t != ForegroundType.NONE && steps < 10) { t = ForegroundType.narrow(t); steps++ }
            assertTrue("narrowing from $start did not terminate", steps < 10)
        }
    }

    @Test fun `location is never dropped before connected-device`() {
        // Losing location would leave a navigation service that may not use
        // location, which is worse than useless.
        val once = ForegroundType.narrow(LOCATION or DEVICE)
        assertTrue("location must survive the first narrowing", once and LOCATION != 0)
    }
}
