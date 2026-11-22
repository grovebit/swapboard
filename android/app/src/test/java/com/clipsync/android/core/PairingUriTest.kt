package com.clipsync.android.core

import com.clipsync.android.core.Vectors.str
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PairingUriTest {
    private val vector = Vectors.obj("pairing_uri")
    private val uri = vector.str("uri")

    @Test
    fun parsesVectorUri() {
        val p = PairingUri.parse(uri)
        assertEquals(vector.str("pid"), p.pid)
        assertEquals("Test Mac + Ü", p.name)
        assertEquals(vector.str("name"), p.name)
        assertArrayEquals(Base64Url.decode(vector.str("psk")), p.psk)
        assertArrayEquals(Vectors.psk, p.psk)
        assertEquals(vector["hosts"]!!.jsonArray.map { it.jsonPrimitive.content }, p.hosts)
        assertEquals(Vectors.pairingPort, p.port)
    }

    @Test
    fun buildRoundTripsToTheSameUri() {
        assertEquals(uri, PairingUri.build(PairingUri.parse(uri)))
    }

    @Test
    fun defaultsPortAndKeepsLiteralPlus() {
        val p = PairingUri.parse("clipsync://pair?v=1&pid=abc&name=A+B&k=${vector.str("psk")}&h=host.local")
        assertEquals(Protocol.DEFAULT_PORT, p.port)
        assertEquals("A+B", p.name)
        assertEquals(listOf("host.local"), p.hosts)
    }

    @Test
    fun rejectsInvalidLinks() {
        val k = vector.str("psk")
        val bad = listOf(
            "https://example.com/pair?v=1&pid=abc&k=$k&h=h",
            "clipsync://pair?v=2&pid=abc&k=$k&h=h",
            "clipsync://pair?v=1&k=$k&h=h",
            "clipsync://pair?v=1&pid=abc&k=AAAA&h=h",
            "clipsync://pair?v=1&pid=abc&k=$k&h=",
            "clipsync://pair?v=1&pid=abc&k=$k&h=h&p=70000",
            "clipsync://pair?v=1&pid=a%2Fb&k=$k&h=h",
            "clipsync://pair?v=1&pid=abc&k=$k&h=h&name=%E",
        )
        for (link in bad) {
            try {
                PairingUri.parse(link)
                fail("accepted $link")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }
}
