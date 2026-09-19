package com.mihai.navhud.link

interface SerialLink {
    val isOpen: Boolean
    val description: String

    /**
     * Called on a background thread for every complete line the HUD sends back,
     * newline stripped. The board announces itself with `$HELLO` and, if an IMU
     * is fitted, streams `$IMU` — see PROTOCOL.md.
     */
    var onLine: ((String) -> Unit)?

    /** @return true if the port is now open. */
    fun open(): Boolean
    fun write(line: String)
    fun close()
}

/**
 * Splits an incoming byte stream into `$...` sentences.
 *
 * Serial arrives in whatever chunks the driver feels like handing over, which
 * is essentially never one line at a time, so the split has to be stateful.
 * Kept out of the transports so USB and Bluetooth share it, and so it can be
 * tested without a cable.
 */
class LineAssembler(private val maxLine: Int = 160) {

    private val buf = StringBuilder(maxLine)

    /** True while skipping the remains of an over-long line. */
    private var overflowed = false

    fun feed(bytes: ByteArray, length: Int, out: (String) -> Unit) {
        for (i in 0 until length) {
            val c = (bytes[i].toInt() and 0xFF).toChar()
            if (c == '\n' || c == '\r') {
                // A terminator ends the skip as well as a line: whatever was
                // truncated is discarded whole rather than emitted as a
                // fragment that would fail its checksum anyway.
                if (overflowed) { overflowed = false; buf.setLength(0); continue }
                if (buf.isNotEmpty()) {
                    out(buf.toString())
                    buf.setLength(0)
                }
                continue
            }
            if (overflowed) continue
            // A line this long is a wedged board or line noise, not a sentence.
            if (buf.length >= maxLine) { overflowed = true; buf.setLength(0); continue }
            buf.append(c)
        }
    }

    fun reset() {
        buf.setLength(0)
        overflowed = false
    }
}
