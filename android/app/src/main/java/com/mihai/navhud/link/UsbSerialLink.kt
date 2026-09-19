package com.mihai.navhud.link

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber

/**
 * The phone is the USB *host*; the Arduino/ESP32 is the device. Works with
 * CH340, CP210x, FTDI, PL2303 and plain CDC-ACM (ATmega32U4, ESP32-S3 native).
 *
 * IMPORTANT, and the thing that ruins most builds: while the phone is acting as
 * a USB host it is *supplying* 5 V, not receiving it. On a two-hour drive that
 * flattens the battery. Use a USB-C hub with PD passthrough and feed the hub
 * from the car -- see docs/BUILD.md.
 */
class UsbSerialLink(
    private val ctx: Context,
    private val baud: Int = 115200
) : SerialLink {

    companion object {
        private const val TAG = "UsbSerialLink"
        private const val ACTION_PERMISSION = "com.mihai.navhud.USB_PERMISSION"
        private const val WRITE_TIMEOUT_MS = 300

        /**
         * Don't ask for USB permission more than this often. The service
         * retries a closed link on a timer, and each retry used to tear down
         * and re-register the receiver and fire another `requestPermission` --
         * a dialog storm while the user was still reading the first one.
         */
        private const val PERMISSION_RETRY_MS = 8000L
    }

    /** Guards open/close against the permission callback racing the retry loop. */
    private val lock = Any()

    @Volatile private var port: UsbSerialPort? = null
    private var permissionReceiver: BroadcastReceiver? = null
    private var reader: Thread? = null
    private var lastPermissionAskMs = 0L
    @Volatile private var readerStop = false

    override val isOpen: Boolean get() = port?.isOpen == true
    override var description: String = "not connected"
        private set

    override var onLine: ((String) -> Unit)? = null

    override fun open(): Boolean = synchronized(lock) { openLocked() }

    private fun openLocked(): Boolean {
        closeLocked()
        val manager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        if (drivers.isEmpty()) {
            description = "no USB serial device found"
            return false
        }
        val driver = drivers[0]
        val device = driver.device

        if (!manager.hasPermission(device)) {
            val now = System.currentTimeMillis()
            // closeLocked() above has just unregistered whatever receiver was
            // listening. If the throttle stops us registering a new one, the
            // driver's answer to a dialog that is still on screen lands with
            // nobody listening and the link stays down until the throttle
            // happens to expire -- so re-register either way, and only rate
            // limit the *asking*.
            if (now - lastPermissionAskMs > PERMISSION_RETRY_MS) {
                lastPermissionAskMs = now
                requestPermission(manager, ask = true)
            } else {
                requestPermission(manager, ask = false)
            }
            description = "waiting for USB permission"
            return false
        }

        val connection = manager.openDevice(device)
        if (connection == null) {
            description = "could not open USB device"
            return false
        }
        return try {
            val p = driver.ports[0]
            p.open(connection)
            p.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // DTR/RTS matter for CDC-ACM boards and reset some Arduinos -- that
            // reset is fine, the sketch re-announces itself with $HELLO.
            runCatching { p.dtr = true }
            runCatching { p.rts = true }
            port = p
            description = "%s @ %d baud (VID %04X PID %04X)".format(java.util.Locale.US,
                driver.javaClass.simpleName.removeSuffix("SerialDriver"),
                baud, device.vendorId, device.productId
            )
            startReader(p)
            true
        } catch (e: Exception) {
            Log.w(TAG, "open failed", e)
            description = "open failed: ${e.message}"
            runCatching { connection.close() }
            false
        }
    }

    override fun write(line: String) {
        val p = port ?: return
        try {
            p.write(line.toByteArray(Charsets.US_ASCII), WRITE_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.w(TAG, "write failed, dropping port", e)
            description = "write failed: ${e.message}"
            close()
        }
    }

    /**
     * A plain blocking read thread rather than usb-serial-for-android's
     * SerialInputOutputManager: the HUD sends a handful of short lines a second
     * at most, and one thread with a 200 ms timeout is easier to reason about
     * than another executor with its own lifecycle.
     */
    private fun startReader(p: UsbSerialPort) {
        readerStop = false
        val assembler = LineAssembler()
        reader = Thread({
            val buf = ByteArray(256)
            while (!readerStop) {
                val n = try {
                    p.read(buf, 200)
                } catch (e: Exception) {
                    if (!readerStop) Log.w(TAG, "read failed", e)
                    break
                }
                if (n > 0) {
                    assembler.feed(buf, n) { line ->
                        runCatching { onLine?.invoke(line) }
                    }
                }
            }
        }, "navhud-usb-read").also { it.isDaemon = true; it.start() }
    }

    override fun close() = synchronized(lock) { closeLocked() }

    private fun closeLocked() {
        readerStop = true
        val t = reader
        reader = null
        runCatching { port?.close() }      // unblocks a reader sitting in read()
        port = null
        // Never join our own thread: close() can be reached from the reader's
        // own error path, and joining there would deadlock for the timeout.
        if (t != null && t !== Thread.currentThread()) runCatching { t.join(400) }
        permissionReceiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        permissionReceiver = null
        // A fresh link is constructed on every start, and each one that saw a
        // permission broadcast left a live core thread behind otherwise.
        openerDown = true
        runCatching { opener.shutdown() }
    }

    /**
     * Set before [opener] is shut down, so the permission receiver knows not to
     * post to it.
     *
     * The order matters and it is why this is a separate flag rather than a
     * call to isShutdown(): the receiver checks this, and the window between
     * checking and executing is closed by the runCatching at the call site.
     */
    @Volatile private var openerDown = false

    /** Serialises the blocking open that a permission answer triggers. */
    private val opener = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "navhud-usb-open").apply { isDaemon = true }
    }

    private fun requestPermission(manager: UsbManager, ask: Boolean) {
        val device = UsbSerialProber.getDefaultProber()
            .findAllDrivers(manager).firstOrNull()?.device ?: return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action != ACTION_PERMISSION) return
                runCatching { ctx.unregisterReceiver(this) }
                synchronized(lock) {
                    if (permissionReceiver === this) permissionReceiver = null
                    lastPermissionAskMs = 0L            // answered: retry at once
                }
                // A BroadcastReceiver runs on the main thread and openLocked()
                // does blocking USB control transfers with multi-second driver
                // timeouts, inside the lock -- an ANR on a marginal cable, and
                // a frozen map on a good one.
                //
                // Guarded, because this is a race the user actually hit and it
                // killed the process. Android holds the permission dialog open
                // for as long as it takes someone to read it; if the link is
                // closed in that window -- the service restarting, the activity
                // going away, the cable being pulled -- closeLocked() shuts the
                // executor down, and then the answer arrives and posts to a
                // terminated pool. RejectedExecutionException thrown out of
                // onReceive is not an error the framework catches: it comes
                // back as "Error receiving broadcast Intent" and takes the app
                // with it. There is nothing to do about a permission answer for
                // a link that no longer exists except ignore it.
                if (openerDown) return
                runCatching { opener.execute { synchronized(lock) { openLocked() } } }
                    .onFailure {
                        android.util.Log.w("UsbSerialLink",
                            "permission answered after the link closed; ignoring", it)
                    }
            }
        }
        permissionReceiver = receiver
        val filter = IntentFilter(ACTION_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(receiver, filter)
        }

        if (!ask) return                 // listening again, but not re-prompting
        val intent = Intent(ACTION_PERMISSION).setPackage(ctx.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        manager.requestPermission(device, PendingIntent.getBroadcast(ctx, 0, intent, flags))
    }
}
