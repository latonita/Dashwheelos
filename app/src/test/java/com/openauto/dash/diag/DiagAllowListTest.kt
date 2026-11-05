package com.openauto.dash.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagAllowListTest {
    private fun bytes(hex: String) = DiagAllowList.parseHex(hex)!!

    @Test
    fun allowsTheReadAndClearRequests() {
        for (hex in listOf(
            "18 00 FF 00", "17", "17 00", "1A 87", "21 01", "3E 02", "3E 01", "10 92", "14 FF 00",
            "01 0D", "03", "04", "07", "0A", "09 02"
        )) assertTrue(hex, DiagAllowList.allowed(bytes(hex)))
    }

    @Test
    fun refusesEveryOtherService() {
        val allowed = setOf(0x18, 0x17, 0x1A, 0x21, 0x3E, 0x14, 0x10, 0x01, 0x03, 0x04, 0x07, 0x0A, 0x09)
        for (service in 0..0xFF) {
            if (service in allowed) continue
            val p = byteArrayOf(service.toByte(), 0x01, 0x02)
            assertNotNull("%02X".format(service), DiagAllowList.refusal(p))
        }
    }

    @Test
    fun namedDangerousServicesAreRefused() {
        for (hex in listOf(
            "27 01", "27 02 11 22", "2E F1 90 00", "3B 90 00", "2F 01 03 00", "30 01 01", "31 01 FF 00", "32 01", "33 01",
            "11 01", "11 02", "23 00 00 00 00 10", "34 00 00", "35 00 00", "36 01 00", "37", "28 00", "85 02", "83 00", "A0 00"
        )) assertFalse(hex, DiagAllowList.allowed(bytes(hex)))
    }

    @Test
    fun onlyTheExtendedSessionMayBeOpened() {
        assertNull(DiagAllowList.refusal(bytes("10 92")))
        for (hex in listOf("10 01", "10 02", "10 03", "10 81", "10 83", "10 85", "10 C0", "10 FB", "10", "10 92 00")) {
            assertNotNull(hex, DiagAllowList.refusal(bytes(hex)))
        }
    }

    @Test
    fun emptyAndBadHexAreRefused() {
        assertNotNull(DiagAllowList.refusal(ByteArray(0)))
        assertNull(DiagAllowList.parseHex("18 0"))
        assertNull(DiagAllowList.parseHex("ATZ"))
        assertNull(DiagAllowList.parseHex(""))
        assertEquals(4, DiagAllowList.parseHex("1800ff00")!!.size)
    }
}
