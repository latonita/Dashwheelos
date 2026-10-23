package com.openauto.unitproto.xyauto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XyautoProtocolTest {

    @Test
    fun printdataIsPartOfTheContract_andEveryActionIsListed() {
        // Forgetting printdata is the mistake this set exists to stop.
        assertTrue(XyautoProtocol.ACTION_PRINTDATA in XyautoProtocol.BROADCASTS)
        assertTrue(XyautoProtocol.ACTION_RX in XyautoProtocol.BROADCASTS)
        assertTrue(XyautoProtocol.ACTION_TX in XyautoProtocol.BROADCASTS)
        assertTrue(XyautoProtocol.ACTION_CONNECTION in XyautoProtocol.BROADCASTS)
        assertTrue(XyautoProtocol.ACTION_RPM in XyautoProtocol.BROADCASTS)
        assertTrue(XyautoProtocol.ACTION_SPEED in XyautoProtocol.BROADCASTS)
        assertTrue(XyautoProtocol.ACTION_KEY in XyautoProtocol.BROADCASTS)
        assertEquals(7, XyautoProtocol.BROADCASTS.size)
        assertEquals(setOf("com.kyhero.car.myhost", "com.kyhero.car.myplugin.canbus"), XyautoProtocol.PACKAGES)
        // The two pre-decoded broadcasts share one extra name.
        assertEquals("canbus_send_data", XyautoProtocol.EXTRA_CANBUS_DATA)
    }
}
