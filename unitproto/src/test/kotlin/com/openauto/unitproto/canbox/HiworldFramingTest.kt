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

/** The AA 55 framing, against literal bytes a real head unit and box put on the wire. */
class HiworldFramingTest {

    @Test
    fun aRealCapturedFrame_parses() {
        // THE REGRESSION TEST. Not built by encode() -- these are the literal
        // bytes a real head unit puts on the wire to reset the box into its
        // bootloader, quoted in hiworld-iap-update-sequence.md and matched by
        // the decompiled firmware, the vendor's own plugin and every capture.
        //
        // AA 55 | len 02 (payload only) | cmd E0 | 00 00 | sum E1
        //   AA+55+02+E0+00+00 = 0x1E1 -> 0xE1
        //
        // Until 2026-10-21 this returned null, and so did ~99.6% of real
        // traffic, because encode() and parse() agreed with each other on a
        // convention the box does not use. Any test built from encode() is
        // blind to that; this one is not. See canbus-to-canbox-signal-map.md
        // section 0.1.
        val frame = HiworldFraming.parse(bytes(0xAA, 0x55, 0x02, 0xE0, 0x00, 0x00, 0xE1))
        assertNotNull("the box's own upgrade-trigger frame must parse", frame)
        assertEquals(0xE0, frame!!.cmd)
        assertArrayEquals(bytes(0x00, 0x00), frame.payload)
        // And we reproduce it byte for byte.
        assertArrayEquals(
            bytes(0xAA, 0x55, 0x02, 0xE0, 0x00, 0x00, 0xE1),
            HiworldFraming.encode(0xE0, bytes(0x00, 0x00))
        )
    }

    @Test
    fun aValidFrame_parsesIntoCmdAndPayload() {
        // AA 55 | len 3 (PAYLOAD only) | cmd 0xB3 | 01 02 03 |
        // sum = AA+55+03+B3+01+02+03 = 0xBB -- the header counts.
        val frame = HiworldFraming.parse(bytes(0xAA, 0x55, 0x03, 0xB3, 0x01, 0x02, 0x03, 0xBB))
        assertNotNull(frame)
        assertEquals(Jpf20lCmd.DRIVETRAIN, frame!!.cmd)
        assertArrayEquals(bytes(0x01, 0x02, 0x03), frame.payload)
    }

    @Test
    fun aFrameWithNoPayload_isStillLegal() {
        // len 0 = no payload at all; sum = AA+55+00+B0 = 0xAF.
        val frame = HiworldFraming.parse(bytes(0xAA, 0x55, 0x00, 0xB0, 0xAF))
        assertNotNull(frame)
        assertEquals(Jpf20lCmd.HEARTBEAT, frame!!.cmd)
        assertEquals(0, frame.payload.size)
    }

    @Test
    fun aBadChecksum_isRejected() {
        // The good frame is AA 55 03 21 01 02 03 29.
        assertNull(HiworldFraming.parse(bytes(0xAA, 0x55, 0x03, 0x21, 0x01, 0x02, 0x03, 0x2A)))
        assertNull(HiworldFraming.parse(bytes(0xAA, 0x55, 0x03, 0x21, 0x01, 0x02, 0x03, 0x00)))
        // One flipped payload bit invalidates it too.
        assertNull(HiworldFraming.parse(bytes(0xAA, 0x55, 0x03, 0x21, 0x01, 0x02, 0x83, 0x29)))
        // And the OLD convention -- len counting cmd+payload, header left out of
        // the sum -- must not be accepted. This is the regression that mattered.
        assertNull(HiworldFraming.parse(bytes(0xAA, 0x55, 0x04, 0x21, 0x01, 0x02, 0x03, 0x2B)))
    }

    @Test
    fun aTruncatedFrame_isRejected() {
        val good = bytes(0xAA, 0x55, 0x03, 0x21, 0x01, 0x02, 0x03, 0x29)
        for (n in 0 until good.size) {
            assertNull("cut to $n bytes", HiworldFraming.parse(good.copyOf(n)))
        }
        // A length that promises more than the buffer holds.
        assertNull(HiworldFraming.parse(bytes(0xAA, 0x55, 0x30, 0x21, 0x01, 0x02, 0x03, 0x29)))
        // Beyond what the box itself will parse, rejected on the length alone.
        assertNull(HiworldFraming.parse(bytes(0xAA, 0x55, 0x38, 0x21) + ByteArray(0x3A)))
        // len 0 is legal, but this one's checksum is still wrong.
        assertNull(HiworldFraming.parse(bytes(0xAA, 0x55, 0x00, 0x00, 0x00)))
        assertNull(HiworldFraming.parse(null))
        assertNull(HiworldFraming.parse(ByteArray(0)))
    }

    @Test
    fun aWrongHeader_isRejected() {
        // The 5A A5 framing of the other vendors' boxes must not be mistaken for ours.
        assertNull(HiworldFraming.parse(bytes(0x5A, 0xA5, 0x03, 0x21, 0x01, 0x02, 0x03, 0x29)))
        assertNull(HiworldFraming.parse(bytes(0x55, 0xAA, 0x03, 0x21, 0x01, 0x02, 0x03, 0x29)))
        assertNull(HiworldFraming.parse(bytes(0xAA, 0x56, 0x03, 0x21, 0x01, 0x02, 0x03, 0x29)))
        assertNull(HiworldFraming.parse(bytes(0x00, 0xAA, 0x55, 0x03, 0x21, 0x01, 0x02, 0x03, 0x29)))
    }

    @Test
    fun trailingPadding_isTolerated() {
        val padded = bytes(0xAA, 0x55, 0x03, 0x21, 0x01, 0x02, 0x03, 0x29, 0x00, 0x00)
        assertEquals(CanFrame(0x21, bytes(0x01, 0x02, 0x03)), HiworldFraming.parse(padded))
    }

    @Test
    fun theChecksum_isTheLowByteOfTheSumOverEveryByteIncludingTheHeader() {
        val f = bytes(0xAA, 0x55, 0x03, 0x21, 0x01, 0x02, 0x03, 0x29)
        // The frame checksum spans len..payload AND the AA 55 header.
        assertEquals(0x29, HiworldFraming.frameChecksum(f, 7))
        // The raw range sum alone is 2 short, which is exactly what was wrong.
        assertEquals(0x2A, HiworldFraming.checksum(f, 2, 7))
        assertEquals(HiworldFraming.checksum(f, 0, 7), HiworldFraming.frameChecksum(f, 7))
        // The firmware's algebraic form: (len + cmd + payload - 1) and 0xFF.
        assertEquals(HiworldFraming.frameChecksum(f, 7), (0x03 + 0x21 + 0x01 + 0x02 + 0x03 - 1) and 0xFF)
        // It wraps at a byte.
        assertEquals(0x00, HiworldFraming.checksum(bytes(0xFF, 0x01), 0, 2))
        assertEquals(0x7F, HiworldFraming.checksum(bytes(0xFF, 0x80), 0, 2))
        // Empty and out-of-bounds ranges sum to nothing.
        assertEquals(0, HiworldFraming.checksum(f, 3, 3))
        assertEquals(0, HiworldFraming.checksum(f, -1, 4))
        assertEquals(0, HiworldFraming.checksum(f, 2, 99))
    }

    @Test
    fun encodeAndParse_roundTrip() {
        val encoded = HiworldFraming.encode(Jpf20lCmd.TYRE, bytes(0x02, 0x00, 0xE6, 0x50, 0x00))
        assertArrayEquals(bytes(0xAA, 0x55, 0x05, 0xB7, 0x02, 0x00, 0xE6, 0x50, 0x00, 0xF3), encoded)
        assertEquals(
            CanFrame(Jpf20lCmd.TYRE, bytes(0x02, 0x00, 0xE6, 0x50, 0x00)),
            HiworldFraming.parse(encoded)
        )
        for (cmd in Jpf20lCmd.DECODED) {
            val f = HiworldFraming.parse(HiworldFraming.encode(cmd, bytes(0x10, 0x20)))
            assertEquals(cmd, f?.cmd)
        }
    }

    @Test
    fun theFrame_comparesByContentNotIdentity() {
        val a = CanFrame(0x21, bytes(1, 2, 3))
        val b = CanFrame(0x21, bytes(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, CanFrame(0x22, bytes(1, 2, 3)))
        assertNotEquals(a, CanFrame(0x21, bytes(1, 2, 4)))
        assertNotEquals(a, CanFrame(0x21, bytes(1, 2)))
        assertFalse(a.equals(null))
        assertFalse(a.equals("not a frame"))
        assertTrue(a.toString().contains("0x21"))
    }
}
