package com.mihai.navhud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The two chimes are played by the TTS engine, so they have to be files it can
 * read: plain PCM WAV, short enough to be a chime, quiet enough not to startle.
 */
class EarconTest {

    private fun wav(name: String): ByteBuffer {
        val f = File("src/main/res/raw/$name.wav")
        assertTrue("missing ${f.absolutePath}", f.isFile)
        return ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    }

    @Test fun `both chimes are short 16-bit mono PCM at a sensible level`() {
        for (name in listOf("beep_warn", "beep_camera")) {
            val b = wav(name)
            assertEquals("$name format", 1, b.getShort(20).toInt())           // PCM
            assertEquals("$name channels", 1, b.getShort(22).toInt())
            assertEquals("$name rate", 22050, b.getInt(24))
            assertEquals("$name bits", 16, b.getShort(34).toInt())
            val samples = b.getInt(40) / 2
            val ms = samples * 1000 / 22050
            assertTrue("$name is $ms ms", ms in 200..300)
            var peak = 0
            for (i in 0 until samples) peak = maxOf(peak, Math.abs(b.getShort(44 + 2 * i).toInt()))
            val dbfs = 20 * Math.log10(peak / 32767.0)
            assertTrue("$name peaks at $dbfs dBFS", dbfs in -7.0..-5.0)
            // Faded in and out: no click at either end.
            assertEquals(0, b.getShort(44).toInt())
            assertEquals(0, b.getShort(44 + 2 * (samples - 1)).toInt())
        }
    }

    @Test fun `the two chimes are different sounds`() {
        val a = File("src/main/res/raw/beep_warn.wav").readBytes()
        val c = File("src/main/res/raw/beep_camera.wav").readBytes()
        assertTrue(!a.contentEquals(c))
    }
}
