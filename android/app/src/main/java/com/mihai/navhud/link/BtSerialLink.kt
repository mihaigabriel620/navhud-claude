package com.mihai.navhud.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.OutputStream
import java.util.UUID

/**
 * The escape hatch. Identical protocol, identical Arduino sketch (swap
 * `Serial` for `SerialBT`), but the phone charges from its own cable and the
 * ESP32 runs off the car -- which sidesteps the whole USB-host power problem.
 *
 * Pair the ESP32 in Android's Bluetooth settings first, then pass its name.
 */
class BtSerialLink(
    private val ctx: Context,
    private val deviceName: String = "NavHUD"
) : SerialLink {

    companion object {
        private const val TAG = "BtSerialLink"
        // Standard Serial Port Profile UUID.
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var out: OutputStream? = null
    private var reader: Thread? = null
    @Volatile private var readerStop = false

    override val isOpen: Boolean get() = socket?.isConnected == true
    override var description: String = "not connected"
        private set

    override var onLine: ((String) -> Unit)? = null

    /**
     * Everything below is serialised on this.
     *
     * open() runs on the link executor, write() on the 4 Hz tick, and close()
     * from the tick's error path, from the service teardown, and from open()
     * itself. Unsynchronised, a write failing at the same moment a retry
     * succeeded could tear down the connection that had just come up and then
     * report "link up" for a socket that was already closed.
     */
    private val lock = Any()

    /**
     * Writes take *this*, not `lock`.
     *
     * open() holds `lock` across BluetoothSocket.connect(), which blocks until
     * the connection is made or fails -- about twelve seconds when the board is
     * unpowered. Putting write() under the same lock parked the 4 Hz tick for
     * that whole time, and because GPS fixes are delivered on the same looper,
     * it stopped the location callback too: the app decided it had no fix while
     * the only thing wrong was an unplugged HUD.
     */
    private val writeLock = Any()

    override fun open(): Boolean = synchronized(lock) { openLocked() }

    /**
     * BLUETOOTH_CONNECT is requested in MainActivity, and every call below that
     * lint flags is inside a try/catch or a runCatching -- including
     * cancelDiscovery(), which needs BLUETOOTH_SCAN (API 31+) or
     * BLUETOOTH_ADMIN (below it) and is deliberately best-effort: this app
     * never starts a discovery, it looks the board up among bonded devices.
     */
    @SuppressLint("MissingPermission")
    private fun openLocked(): Boolean {
        closeLocked()
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            description = "Bluetooth off"
            return false
        }
        val device = try {
            adapter.bondedDevices.firstOrNull { it.name == deviceName }
        } catch (e: SecurityException) {
            description = "missing BLUETOOTH_CONNECT permission"
            return false
        }
        if (device == null) {
            description = "\"$deviceName\" is not paired"
            return false
        }
        var s: BluetoothSocket? = null
        return try {
            s = device.createRfcommSocketToServiceRecord(SPP)
            // Best effort. cancelDiscovery() needs BLUETOOTH_ADMIN below API 31
            // and BLUETOOTH_SCAN above it; we hold neither, because scanning is
            // not something this app does -- the board is a bonded device we
            // look up by name. Letting the SecurityException escape aborted the
            // whole connection before connect() was even reached, so Bluetooth
            // never worked at all on the head units that enforce it.
            runCatching { adapter.cancelDiscovery() }
            s.connect()
            socket = s
            out = s.outputStream
            description = "Bluetooth SPP to ${device.name}"
            startReader(s)
            true
        } catch (e: Exception) {
            Log.w(TAG, "connect failed", e)
            description = "connect failed: ${e.message}"
            // The socket that failed is not `socket` -- that is only assigned
            // once connect() has returned -- so closeLocked() cannot reach it.
            // Leaking one per retry, every few seconds with the board off, ran
            // the process out of file descriptors on a long drive.
            runCatching { s?.close() }
            closeLocked()
            false
        }
    }

    override fun write(line: String) {
        val stream = out ?: return
        val failed = synchronized(writeLock) {
            try {
                stream.write(line.toByteArray(Charsets.US_ASCII))
                stream.flush()
                false
            } catch (e: Exception) {
                Log.w(TAG, "write failed", e)
                description = "write failed: ${e.message}"
                true
            }
        }
        // Tear down outside writeLock and without waiting on `lock`: a connect
        // may be in progress on the link executor, and this is the tick thread.
        if (failed && closing.compareAndSet(false, true)) {
            closer.execute {
                try { close() } finally { closing.set(false) }
            }
        }
    }

    private val closing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val closer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "navhud-bt-close").apply { isDaemon = true }
    }

    private fun startReader(s: BluetoothSocket) {
        readerStop = false
        val assembler = LineAssembler()
        reader = Thread({
            val input = runCatching { s.inputStream }.getOrNull() ?: return@Thread
            val buf = ByteArray(256)
            while (!readerStop) {
                val n = try {
                    input.read(buf)
                } catch (e: Exception) {
                    if (!readerStop) Log.w(TAG, "read failed", e)
                    break
                }
                if (n < 0) break
                if (n > 0) assembler.feed(buf, n) { line -> runCatching { onLine?.invoke(line) } }
            }
        }, "navhud-bt-read").also { it.isDaemon = true; it.start() }
    }

    override fun close() = synchronized(lock) { closeLocked() }

    private fun closeLocked() {
        readerStop = true
        val t = reader
        reader = null
        runCatching { out?.close() }
        runCatching { socket?.close() }   // unblocks the reader's blocking read
        // The reader's own error path calls close(); joining ourselves there
        // would stall for the timeout every time the board is unplugged.
        if (t != null && t !== Thread.currentThread()) runCatching { t.join(400) }
        out = null
        socket = null
    }
}
