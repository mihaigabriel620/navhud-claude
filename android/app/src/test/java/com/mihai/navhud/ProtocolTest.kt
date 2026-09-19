package com.mihai.navhud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.mihai.navhud.voice.English
import com.mihai.navhud.voice.FrenchBelgium
import org.junit.Test

/**
 * The encoder side of the wire protocol. The checksums asserted here are the
 * same ones the Arduino parser's C++ tests assert, so if either side drifts,
 * one of the two suites goes red.
 */
class ProtocolTest {

    @Test fun `encodes the frames quoted in PROTOCOL_md, checksums and all`() {
        assertEquals(
            "\$HUD,72,50,2,0,180,845,7300,5,RUE DE LA LOI*62\r\n",
            HudFrame(72, 50, Man.RIGHT, 0, 180, 845, 7300, 5, "RUE DE LA LOI").encode()
        )
        assertEquals(
            "\$HUD,118,-1,8,0,4200,1810,52000,4,A4*21\r\n",
            HudFrame(118, -1, Man.STRAIGHT, 0, 4200, 1810, 52000, 4, "A4").encode()
        )
        assertEquals(
            "\$HUD,31,30,13,3,90,1204,9100,4,N9*00\r\n",
            HudFrame(31, 30, Man.ROUNDABOUT, 3, 90, 1204, 9100, 4, "N9").encode()
        )
        assertEquals("\$HUD,-1,0,0,0,0,0,0,0,*59\r\n", HudFrame().encode())
        assertEquals("\$PING*10\r\n", HudFrame.ping())
    }

    @Test fun `the checksum is NMEA's XOR over the body`() {
        assertEquals(0x10, HudFrame.checksum("PING"))
        assertEquals(0x73, HudFrame.checksum("HELLO,NAVHUD,1"))
    }

    @Test fun `street names are folded to plain ASCII for the display fonts`() {
        assertEquals("Chaussee d'Ixelles", HudFrame.sanitize("Chaussée d'Ixelles"))
        assertEquals("Rue de l'Ecuyer", HudFrame.sanitize("Rue de l'Écuyer"))
        assertEquals("Bruggestraat", HudFrame.sanitize("Brüggestraat"))
    }

    @Test fun `separators inside a street name cannot break the frame`() {
        val nasty = "A,B*C\$D"
        val out = HudFrame.sanitize(nasty)
        assertTrue("comma survived: $out", !out.contains(','))
        assertTrue("star survived: $out", !out.contains('*'))
        assertTrue("dollar survived: $out", !out.contains('$'))
        // and the encoded frame must still have exactly the right field count
        val body = HudFrame(1, 2, 3, 0, 4, 5, 6, 7, nasty).encode()
            .removePrefix("\$").substringBefore('*')
        assertEquals(10, body.split(",").size)
    }

    @Test fun `street names are truncated to what the display can show`() {
        val out = HudFrame.sanitize("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        assertEquals(HudFrame.STREET_MAX, out.length)
        assertEquals("ABCDEFGHIJKLMNOPQRST", out)
    }

    @Test fun `every maneuver code round-trips through the wire format`() {
        for (m in 0..19) {
            val f = HudFrame(50, 50, m, if (m == Man.ROUNDABOUT) 3 else 0,
                             100, 60, 500, 4, "X")
            val body = f.encode().removePrefix("\$").substringBefore('*')
            val fields = body.split(",")
            assertEquals(10, fields.size)
            assertEquals(m.toString(), fields[3])
        }
    }

    @Test fun `mapbox maneuver types map onto the wire codes`() {
        assertEquals(Man.RIGHT, Man.fromMapbox("turn", "right"))
        assertEquals(Man.SHARP_LEFT, Man.fromMapbox("turn", "sharp left"))
        assertEquals(Man.UTURN, Man.fromMapbox("turn", "uturn"))
        assertEquals(Man.ROUNDABOUT, Man.fromMapbox("roundabout", "right"))
        assertEquals(Man.ROUNDABOUT, Man.fromMapbox("rotary", "left"))
        assertEquals(Man.ROUNDABOUT, Man.fromMapbox("exit roundabout", ""))
        assertEquals(Man.MERGE_LEFT, Man.fromMapbox("merge", "slight left"))
        assertEquals(Man.MERGE_RIGHT, Man.fromMapbox("merge", "right"))
        assertEquals(Man.RAMP_RIGHT, Man.fromMapbox("on ramp", "slight right"))
        assertEquals(Man.RAMP_LEFT, Man.fromMapbox("off ramp", "left"))
        assertEquals(Man.FORK_LEFT, Man.fromMapbox("fork", "left"))
        assertEquals(Man.DEPART, Man.fromMapbox("depart", "straight"))
        assertEquals(Man.ARRIVE, Man.fromMapbox("arrive", "right"))
        assertEquals(Man.STRAIGHT, Man.fromMapbox("continue", "straight"))
        assertEquals(Man.LEFT, Man.fromMapbox("end of road", "left"))
        // Unknown input must degrade to something drawable, never crash.
        assertEquals(Man.STRAIGHT, Man.fromMapbox(null, null))
        assertEquals(Man.STRAIGHT, Man.fromMapbox("nonsense", "sideways"))
    }

    @Test fun `English phrasing reads like a person, not a template`() {
        val p = English
        assertEquals("turn right onto Rue de la Loi", p.instruction(Man.RIGHT, 0, "Rue de la Loi"))
        assertEquals("at the roundabout, take the second exit onto Avenue de Tervueren",
            p.instruction(Man.ROUNDABOUT, 2, "Avenue de Tervueren"))
        assertEquals("make a U-turn", p.instruction(Man.UTURN, 0, "Rue Neuve"))
        assertEquals("turn left", p.instruction(Man.LEFT, 0, ""))
        assertEquals("bear right onto E40", p.instruction(Man.SLIGHT_RIGHT, 0, "E40"))
    }

    @Test fun `French phrasing is written as French, not translated word for word`() {
        val p = FrenchBelgium
        assertEquals("tournez à droite sur Rue de la Loi", p.instruction(Man.RIGHT, 0, "Rue de la Loi"))
        assertEquals("au rond-point, prenez la deuxième sortie sur Avenue de Tervueren",
            p.instruction(Man.ROUNDABOUT, 2, "Avenue de Tervueren"))
        assertEquals("faites demi-tour", p.instruction(Man.UTURN, 0, "Rue Neuve"))
        // "keep left" is "serrez à gauche", not "gardez la gauche"
        assertEquals("serrez à gauche", p.instruction(Man.SLIGHT_LEFT, 0, ""))
        assertEquals("prenez la sortie à droite sur Exit 22", p.instruction(Man.RAMP_RIGHT, 0, "Exit 22"))
        assertEquals("Dans trois cents mètres, tournez à droite.",
            p.advance(p.distance(300), p.instruction(Man.RIGHT, 0, "")))
    }

    @Test fun `neither language produces stray or doubled spaces`() {
        for (p in listOf(English, FrenchBelgium)) {
            for (m in 0..19) {
                for (street in listOf("", "E40")) {
                    val s = p.instruction(m, 2, street)
                    assertTrue("${p.code} bad phrasing: '$s'",
                        s.isNotBlank() && !s.startsWith(" ") && !s.contains("  ") && !s.endsWith(" "))
                }
            }
        }
    }

    @Test fun `distances are spoken the way each language says them`() {
        assertEquals("2 kilometres", English.distance(2000))
        assertEquals("1 kilometre", English.distance(1000))
        assertEquals("1.5 kilometres", English.distance(1500))
        assertEquals("700 metres", English.distance(700))
        assertEquals("80 metres", English.distance(80))

        // French numbers are spelled out -- see FrenchNumbersTest for why.
        assertEquals("deux kilomètres", FrenchBelgium.distance(2000))
        assertEquals("un kilomètre", FrenchBelgium.distance(1000))
        assertEquals("un kilomètre et demi", FrenchBelgium.distance(1500))
        assertEquals("deux mille quatre cents mètres", FrenchBelgium.distance(2400))
        assertEquals("sept cents mètres", FrenchBelgium.distance(700))
        assertEquals("nonante mètres", FrenchBelgium.distance(90))
    }
}
