package com.openauto.unitproto.canbox

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bytes written the way the protocol table does, so a test reads like the frame on the wire. */
private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

/** The stock JPF2 commands, byte by byte. */
class HiworldJpf2DecoderTest {

    @Test
    fun stockDoors_decodeFromTheHighBitsOf0x12PayloadByte2() {
        // Stock 0x12 is 10 bytes; the door bitfield is byte 2 and its layout is
        // the vendor's: 80 FL, 40 FR, 20 RL, 10 RR, 08 tailgate. NOT the 0xB6
        // order, and there is no bonnet bit because the car has no switch.
        fun d(bits: Int) = HiworldJpf2Signals.doors(bytes(0x00, 0x00, bits, 0, 0, 0, 0, 0, 0, 0))!!

        assertFalse(d(0x00).anyOpen)
        assertTrue(d(0x80).frontLeft)
        assertTrue(d(0x40).frontRight)
        assertTrue(d(0x20).rearLeft)
        assertTrue(d(0x10).rearRight)
        assertTrue(d(0x08).tailgate)
        // One bit at a time means one door at a time: a crossed pair would show
        // the wrong door open, which is the bug this table exists to prevent.
        assertEquals(1, listOf(d(0x20).frontLeft, d(0x20).frontRight, d(0x20).rearLeft,
            d(0x20).rearRight, d(0x20).tailgate).count { it })
        // No bonnet switch on this bus, ever.
        assertFalse(d(0xFF).bonnet)
        assertTrue(d(0xF8).anyOpen)
        assertNull(HiworldJpf2Signals.doors(bytes(0x00, 0x00)))
        assertNull(HiworldJpf2Signals.doors(null))
    }

    @Test
    fun stock0x72_carriesTheLightsAndTheDimmer() {
        // ACC + illumination, dimmer at the WK's middle step (raw 0x66 -> 51 %).
        // Checksum by hand: 0E + 72 + 03 + 33 - 1 = B5.
        val f = HiworldFraming.parse(bytes(
            0xAA, 0x55, 0x0E, 0x72, 0x03, 0x00, 0x00, 0x33, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xB5
        ))!!
        assertEquals(HiworldJpf2Cmd.STATE, f.cmd)
        assertEquals(true, HiworldJpf2Signals.lightsOn(f.payload))
        assertEquals(51, HiworldJpf2Signals.dimmerPct(f.payload))

        // Lights off: the flag clears and the dimmer reads 0, a value, not a blank.
        val off = bytes(0x01, 0, 0, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        assertEquals(false, HiworldJpf2Signals.lightsOn(off))
        assertEquals(0, HiworldJpf2Signals.dimmerPct(off))
        // The firmware clamps at 100; anything past it is not a dimmer.
        assertEquals(100, HiworldJpf2Signals.dimmerPct(bytes(0x02, 0, 0, 100)))
        assertNull(HiworldJpf2Signals.dimmerPct(bytes(0x02, 0, 0, 101)))
        assertNull(HiworldJpf2Signals.dimmerPct(bytes(0x02, 0, 0)))
        assertNull(HiworldJpf2Signals.lightsOn(null))
    }

    @Test
    fun stock0x32_carriesTheSelectorLever() {
        // D at 2701 rpm. Checksum by hand: 0E + 32 + 04 + 0A + 8D - 1 = DA.
        val f = HiworldFraming.parse(bytes(
            0xAA, 0x55, 0x0E, 0x32, 0x00, 0x04, 0x0A, 0x8D, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xDA
        ))!!
        assertEquals(HiworldJpf2Cmd.DRIVE, f.cmd)
        assertEquals('D', HiworldJpf2Signals.gear(f.payload))

        fun g(code: Int) = HiworldJpf2Signals.gear(bytes(0x00, code, 0x00, 0x00))
        assertEquals('P', g(1))
        assertEquals('N', g(2))
        assertEquals('R', g(3))
        assertEquals('S', g(5))
        // 0 is the box saying it has no reading; 6+ it never sends.
        assertNull(g(0))
        assertNull(g(6))
        assertNull(HiworldJpf2Signals.gear(bytes(0x00)))
    }

    @Test
    fun stock0xF0_isTheBoxesVersionString() {
        val f = HiworldFraming.parse(bytes(
            0xAA, 0x55, 0x11, 0xF0, 0x48, 0x31, 0x4E, 0x34, 0x4A, 0x50, 0x46, 0x32, 0x30, 0x4C,
            0x2D, 0x32, 0x31, 0x31, 0x30, 0x32, 0x36, 0xE2
        ))!!
        assertEquals(HiworldJpf2Cmd.VERSION, f.cmd)
        assertEquals("H1N4JPF20L-211026", HiworldJpf2Signals.version(f.payload))
        // NUL padding ends it; a non-printable byte means it is not a version at all.
        assertEquals("H1N4", HiworldJpf2Signals.version(bytes(0x48, 0x31, 0x4E, 0x34, 0x00, 0x00)))
        assertNull(HiworldJpf2Signals.version(bytes(0x48, 0x01, 0x4E)))
        assertNull(HiworldJpf2Signals.version(bytes(0x00, 0x00)))
        assertNull(HiworldJpf2Signals.version(null))
    }

    // ------------------------------------------------- the decoder over frames

    private fun decode(cmd: Int, vararg payload: Int) =
        HiworldJpf2Decoder().decode(CanFrame(cmd, bytes(*payload)))

    @Test
    fun theLeverAndTheRevs_comeOutOf0x32() {
        assertEquals(
            listOf(CanboxValue.Gear(GearPosition.DRIVE), CanboxValue.Rpm(2701)),
            decode(HiworldJpf2Cmd.DRIVE, 0x00, 0x04, 0x0A, 0x8D)
        )
        assertEquals(CanboxValue.Gear(GearPosition.PARK), decode(HiworldJpf2Cmd.DRIVE, 0x00, 0x01).first())
        assertEquals(CanboxValue.Gear(GearPosition.MANUAL), decode(HiworldJpf2Cmd.DRIVE, 0x00, 0x05).first())
        // A blank withdraws the lever, so the gear worked out from the revs can take over;
        // FFFF revs are "unavailable", not a number.
        assertEquals(listOf(CanboxValue.Gear(null)), decode(HiworldJpf2Cmd.DRIVE, 0x00, 0x00, 0xFF, 0xFF))
        assertNull(HiworldJpf2Signals.rpm(bytes(0x00, 0x04, 0x2E, 0xE1)))
    }

    @Test
    fun stock0x72_alsoSaysReverse() {
        assertEquals(
            listOf(CanboxValue.Reverse(true), CanboxValue.Lights(false), CanboxValue.Dimmer(0)),
            decode(HiworldJpf2Cmd.STATE, 0x05, 0x00, 0x00, 0x00)
        )
        assertEquals(CanboxValue.Reverse(false), decode(HiworldJpf2Cmd.STATE, 0x03, 0x00, 0x00, 0x33).first())
    }

    @Test
    fun theBodyAndTheVersion_decodeToValues() {
        assertEquals(
            CanboxValue.Doors(DoorBits(frontLeft = true, frontRight = false, rearLeft = false, rearRight = false, tailgate = true, bonnet = false)),
            decode(HiworldJpf2Cmd.BODY, 0x00, 0x00, 0x88).single()
        )
        assertEquals(
            listOf(CanboxValue.Version("H1N4JPF20A")),
            decode(HiworldJpf2Cmd.VERSION, 0x48, 0x31, 0x4E, 0x34, 0x4A, 0x50, 0x46, 0x32, 0x30, 0x41)
        )
        assertEquals("stock_version", HiworldJpf2Decoder().commandName(HiworldJpf2Cmd.VERSION))
        assertEquals("cmd_A6", HiworldJpf2Decoder().commandName(0xA6))
        // What it has nothing to say about: the amplifier, the zero filler, the custom block.
        assertTrue(decode(0xA6, 0x14, 0, 0, 0, 0, 0).isEmpty())
        assertTrue(decode(0x73, 0, 0, 0, 0, 0, 0, 0, 0).isEmpty())
        assertTrue(decode(0xB3, 0x00, 0x39, 0x09, 0x92, 0x2D, 0x11).isEmpty())
    }
}
