package com.openauto.dash.diag

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElmReplyTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun outcome(text: String?, service: Int, headersOn: Boolean = false, rx: Int? = null) =
        ElmReply.outcome(ElmReply.parse(text, headersOn, rx), service)

    private fun reply(text: String?, service: Int, headersOn: Boolean = false, rx: Int? = null): RawReply =
        (outcome(text, service, headersOn, rx) as ElmReply.Outcome.Final).reply

    private val absBytes = b(0x58, 0x03, 0xC1, 0x26, 0x20, 0xC1, 0x14, 0x20, 0xD5, 0x04, 0x20)

    @Test
    fun absReplyAsRawFramesWithSpaces() {
        val r = reply("10 0B 58 03 C1 26 20 C1\r21 14 20 D5 04 20 00 00", 0x18)
        assertEquals(RawReply.Positive(absBytes, true), r)
    }

    @Test
    fun absReplyAsRawFramesWithoutSpaces() {
        val r = reply("100B5803C12620C1\r211420D504200000", 0x18)
        assertEquals(RawReply.Positive(absBytes, true), r)
    }

    @Test
    fun absReplyInElmFormattedStyle() {
        val r = reply("00B\r0: 58 03 C1 26 20 C1\r1: 14 20 D5 04 20\r", 0x18)
        assertEquals(RawReply.Positive(absBytes, true), r)
    }

    @Test
    fun singleFrameWithCafOn() {
        assertEquals(RawReply.Positive(b(0x58, 0x00), true), reply("58 00", 0x18))
        assertEquals(RawReply.Positive(b(0x58, 0x00), true), reply("02 58 00", 0x18))
    }

    @Test
    fun firstFrameOnlyIsIncomplete() {
        val r = reply("10 0B 58 03 C1 26 20 C1", 0x18) as RawReply.Positive
        assertEquals(false, r.complete)
        assertArrayEquals(b(0x58, 0x03, 0xC1, 0x26, 0x20, 0xC1), r.bytes)
        val formatted = reply("00B\r0: 58 03 C1 26 20 C1", 0x18) as RawReply.Positive
        assertEquals(false, formatted.complete)
    }

    @Test
    fun consecutiveFrameOutOfSequenceIsIgnored() {
        val r = reply("10 0B 58 03 C1 26 20 C1\r22 14 20 D5 04 20 00 00", 0x18) as RawReply.Positive
        assertEquals(false, r.complete)
    }

    @Test
    fun pendingThenFinalInOneReply() {
        assertEquals(RawReply.Positive(b(0x54, 0xFF, 0x00), true), reply("7F 14 78\r54 FF 00", 0x14))
        assertEquals(RawReply.Positive(b(0x54, 0xFF, 0x00), true), reply("03 7F 14 78\r03 54 FF 00", 0x14))
    }

    @Test
    fun pendingAloneKeepsWaiting() {
        assertEquals(ElmReply.Outcome.Pending, outcome("7F 14 78", 0x14))
    }

    @Test
    fun otherNegativeIsAResult() {
        assertEquals(RawReply.Negative(0x18, 0x11), reply("7F 18 11", 0x18))
        assertEquals(RawReply.Negative(0x14, 0x22), reply("7F 14 22", 0x14))
    }

    @Test
    fun noDataIsNoResponse() {
        assertEquals(ElmReply.Outcome.None, outcome("NO DATA", 0x18))
        assertEquals(ElmReply.Outcome.None, outcome("SEARCHING...\rUNABLE TO CONNECT", 0x18))
        assertEquals(ElmReply.Outcome.None, outcome(null, 0x18))
        assertEquals(ElmReply.Outcome.None, outcome("", 0x18))
    }

    @Test
    fun adapterFaultsAreErrors() {
        assertTrue(reply("?", 0x18) is RawReply.Error)
        assertTrue(reply("CAN ERROR", 0x18) is RawReply.Error)
        assertTrue(reply("BUS BUSY", 0x18) is RawReply.Error)
    }

    @Test
    fun headersOnDropsAForeignSender() {
        val text = "7E8 03 7F 18 78\r785 10 0B 58 03 C1 26 20 C1\r785 21 14 20 D5 04 20 00 00\r620 06 41 00 00 00 00 00"
        val r = reply(text, 0x18, headersOn = true, rx = 0x785)
        assertEquals(RawReply.Positive(absBytes, true), r)
    }

    @Test
    fun headersOnForeignFramesInterleavedWithTheReply() {
        val text = "7E8 10 0B 58 03 AA AA AA\r785 10 0B 58 03 C1 26 20 C1\r7E8 21 11 11 11 11 11 11 11\r785 21 14 20 D5 04 20 00 00"
        val r = reply(text, 0x18, headersOn = true, rx = 0x785)
        assertEquals(RawReply.Positive(absBytes, true), r)
    }

    @Test
    fun headersOnOnlyForeignIsNoResponse() {
        assertEquals(ElmReply.Outcome.None, outcome("7E8 03 7F 18 78", 0x18, headersOn = true, rx = 0x785))
    }

    @Test
    fun headersOnFirstFrameOnly() {
        val r = reply("785 10 0B 58 03 C1 26 20 C1", 0x18, headersOn = true, rx = 0x785) as RawReply.Positive
        assertEquals(false, r.complete)
    }

    @Test
    fun headersOnSinglePendingThenFinal() {
        val text = "504 03 7F 14 78\r504 03 54 FF 00"
        assertEquals(RawReply.Positive(b(0x54, 0xFF, 0x00), true), reply(text, 0x14, headersOn = true, rx = 0x504))
    }

    @Test
    fun anotherServicesReplyIsNotTheAnswer() {
        assertEquals(ElmReply.Outcome.None, outcome("41 0D 00", 0x18))
    }
}
