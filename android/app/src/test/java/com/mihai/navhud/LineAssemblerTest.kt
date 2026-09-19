package com.mihai.navhud

import com.mihai.navhud.link.LineAssembler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cable is not one-way any more: a HUD with an IMU fitted streams its yaw
 * rate back. Serial arrives in whatever chunks the driver hands over, so the
 * split into sentences has to survive being cut anywhere.
 */
class LineAssemblerTest {

    private fun collect(a: LineAssembler, s: String): List<String> {
        val out = ArrayList<String>()
        val b = s.toByteArray(Charsets.US_ASCII)
        a.feed(b, b.size) { out.add(it) }
        return out
    }

    @Test fun `whole lines come out whole`() {
        val a = LineAssembler()
        assertEquals(
            listOf("\$HELLO,NAVHUD,2*70", "\$IMU,0.0,0.0,0.0,12.50*3A"),
            collect(a, "\$HELLO,NAVHUD,2*70\r\n\$IMU,0.0,0.0,0.0,12.50*3A\r\n")
        )
    }

    @Test fun `a line split across three reads is reassembled`() {
        val a = LineAssembler()
        assertTrue(collect(a, "\$IMU,1.0").isEmpty())
        assertTrue(collect(a, ",2.0,3.0").isEmpty())
        assertEquals(listOf("\$IMU,1.0,2.0,3.0,4.0*11"), collect(a, ",4.0*11\r\n"))
    }

    @Test fun `bare newlines and bare carriage returns both terminate`() {
        assertEquals(listOf("a", "b", "c"), collect(LineAssembler(), "a\nb\rc\r\n"))
    }

    @Test fun `empty lines are not reported`() {
        assertEquals(listOf("a"), collect(LineAssembler(), "\r\n\r\na\r\n\r\n"))
    }

    @Test fun `line noise cannot grow the buffer without bound`() {
        val a = LineAssembler(maxLine = 16)
        // A board stuck sending garbage with no newline in sight.
        assertTrue(collect(a, "x".repeat(10_000)).isEmpty())
        // ...and it recovers as soon as a real line arrives.
        assertEquals(listOf("\$PING*00"), collect(a, "\n\$PING*00\n"))
    }

    @Test fun `high bytes do not become negative characters`() {
        val a = LineAssembler()
        val bytes = byteArrayOf(0x24, 0x41.toByte(), 0xE9.toByte(), 0x0A)
        val out = ArrayList<String>()
        a.feed(bytes, bytes.size) { out.add(it) }
        assertEquals(1, out.size)
        assertEquals(3, out[0].length)
        assertEquals('é', out[0][2])
    }

    @Test fun `reset drops a half-received line`() {
        val a = LineAssembler()
        collect(a, "\$IMU,1.0")
        a.reset()
        assertEquals(listOf("\$PING*00"), collect(a, "\$PING*00\n"))
    }
}
