package com.openauto.unitproto.canbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

/** A frame as the box sends it: AA 55, len, cmd, payload, checksum. */
private fun wire(cmd: Int, vararg payload: Int) = HiworldFraming.encode(cmd, bytes(*payload))

private fun version(text: String) = wire(HiworldJpf2Cmd.VERSION, *text.map { it.code }.toIntArray())

class CanboxRegistryTest {

    @Test
    fun theVersionPicksTheDecoder() {
        val r = CanboxRegistry.DEFAULT
        assertEquals("hiworld-jpf20l", r.select("H1N4JPF20L-211026").id)
        assertEquals("hiworld-jpf20l", r.select("H1N4JPF20L").id)
        // The stock box names itself JPF2..A.
        assertEquals("hiworld-jpf2", r.select("H1N4JPF20A").id)
    }

    @Test
    fun anUnknownVersion_fallsBackOnStock() {
        val r = CanboxRegistry.DEFAULT
        assertSame(r.fallback, r.select("SOMETHING-ELSE"))
        assertEquals("hiworld-jpf2", r.fallback.id)
        assertEquals("hiworld-jpf2", r.select("").id)
    }

    @Test
    fun aDecoderIsFoundById_forTheOverride() {
        val r = CanboxRegistry.DEFAULT
        assertNotNull(r.byId("hiworld-jpf20l"))
        assertNull(r.byId("nope"))
        assertEquals(listOf("hiworld-jpf20l", "hiworld-jpf2"), r.ids)
    }

    @Test
    fun aNewDecoder_isOneLineInARegistry() {
        val other = object : HiworldJpf2Decoder() {
            override val id = "hiworld-other"
            override fun matches(version: String) = version.startsWith("OTHER")
        }
        val stock = HiworldJpf2Decoder()
        val r = CanboxRegistry(listOf(other, stock), fallback = stock)
        assertEquals("hiworld-other", r.select("OTHER-1").id)
        assertEquals("hiworld-jpf2", r.select("H1N4JPF20A").id)
    }

    @Test
    fun theStreamIsReadAsStock_untilTheBoxNamesItself() {
        val p = CanboxPipeline()
        assertEquals("hiworld-jpf2", p.decoder.id)
        // A custom frame is not understood by the stock decoder...
        assertTrue(p.feed(wire(Jpf20lCmd.DRIVETRAIN, 0x00, 0x39, 0x09, 0x92, 0x2D, 0x11))!!.values.isEmpty())

        // ...until the version says it is a JPF20L; the version frame itself still comes out.
        val v = p.feed(version("H1N4JPF20L-211026"))!!
        assertEquals(listOf<CanboxValue>(CanboxValue.Version("H1N4JPF20L-211026")), v.values)
        assertEquals("H1N4JPF20L-211026", p.version)
        assertEquals("hiworld-jpf20l", p.decoder.id)
        val d = p.feed(wire(Jpf20lCmd.DRIVETRAIN, 0x00, 0x39, 0x09, 0x92, 0x2D, 0x11))!!
        assertEquals("drivetrain", d.name)
        assertEquals(CanboxValue.SpeedKmh(57), d.values.first())
    }

    @Test
    fun aStockBox_staysOnTheStockDecoder() {
        val p = CanboxPipeline()
        p.feed(version("H1N4JPF20A"))
        assertEquals("hiworld-jpf2", p.decoder.id)
    }

    @Test
    fun theOverride_winsOverWhatTheBoxSays_andCanBeLiftedAgain() {
        val p = CanboxPipeline(forced = "hiworld-jpf20l")
        assertEquals("hiworld-jpf20l", p.decoder.id)
        p.feed(version("H1N4JPF20A"))
        assertEquals("hiworld-jpf20l", p.decoder.id)

        p.force("hiworld-jpf2")
        assertEquals("hiworld-jpf2", p.decoder.id)
        // Lifted: back to what the box said (the stock one here), and an unknown id is no pin at all.
        p.force(null)
        assertEquals("hiworld-jpf2", p.decoder.id)
        p.feed(version("H1N4JPF20L"))
        assertEquals("hiworld-jpf20l", p.decoder.id)
        p.force("nope")
        assertEquals("hiworld-jpf20l", p.decoder.id)
    }

    @Test
    fun bytesThatAreNotAFrame_areNothing() {
        val p = CanboxPipeline()
        assertNull(p.feed(null))
        assertNull(p.feed(bytes(0xAA, 0x55, 0x03, 0x21, 0x01, 0x02, 0x03, 0x2A)))
        assertNull(p.feed(bytes(0x5A, 0xA5, 0x03, 0x21, 0x01, 0x02, 0x03, 0x29)))
    }
}
