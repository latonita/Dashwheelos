package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WheelMapTest {
    private val fl = TyrePos.FRONT_LEFT
    private val fr = TyrePos.FRONT_RIGHT
    private val rl = TyrePos.REAR_LEFT
    private val rr = TyrePos.REAR_RIGHT
    private val wheels = listOf(fl, fr, rl, rr)

    private fun tyre(kPa: Int) = Tyre(kPa, 25, sensorBatteryLow = false, leak = false, noSignal = false, at = 0)
    private val raw = mapOf(fl to tyre(1), fr to tyre(2), rl to tyre(3), rr to tyre(4), TyrePos.SPARE to tyre(9))

    @Test
    fun identityChangesNothing() {
        assertTrue(WheelMap.IDENTITY.isIdentity)
        assertEquals(raw, WheelMap.IDENTITY.apply(raw))
        assertEquals("FL,FR,RL,RR", WheelMap.IDENTITY.format())
    }

    @Test
    fun everySwapPairMovesTheTwoAndOnlyThem() {
        for (a in wheels) for (b in wheels) {
            val out = WheelMap.IDENTITY.swap(a, b).apply(raw)
            if (a == b) { assertEquals(raw, out); continue }
            assertEquals(raw[b], out[a])
            assertEquals(raw[a], out[b])
            for (c in wheels - a - b) assertEquals(raw[c], out[c])
            assertEquals(raw[TyrePos.SPARE], out[TyrePos.SPARE])
        }
    }

    @Test
    fun swappingTwiceIsTheIdentity() {
        for (a in wheels) for (b in wheels) assertTrue(WheelMap.IDENTITY.swap(a, b).swap(a, b).isIdentity)
    }

    @Test
    fun theSpecExample() {
        val m = WheelMap.IDENTITY.swap(fl, rr)
        assertEquals("RR,FR,RL,FL", m.format())
        assertEquals(tyre(4), m.apply(raw)[fl])
    }

    @Test
    fun chainedSwapsStayAPermutation() {
        val m = WheelMap.IDENTITY.swap(fl, fr).swap(fl, rl).swap(rr, fr)
        assertEquals(wheels.toSet(), m.from.values.toSet())
        assertEquals(WheelMap.parse(m.format()), m)
    }

    @Test
    fun theSpareIsNeverRemapped() {
        assertTrue(WheelMap.IDENTITY.swap(fl, TyrePos.SPARE).isIdentity)
    }

    @Test
    fun aMissingWheelStaysMissing() {
        val partial = mapOf(fl to tyre(1), rr to tyre(4))
        val out = WheelMap.IDENTITY.swap(fl, fr).apply(partial)
        assertNull(out[fl])
        assertEquals(tyre(1), out[fr])
        assertEquals(tyre(4), out[rr])
    }

    @Test
    fun parseAndFormatRoundTrip() {
        val m = WheelMap.IDENTITY.swap(fl, rl)
        assertEquals(m, WheelMap.parse(m.format()))
        assertEquals(m, WheelMap.parse(" rl, fr , fl,rr "))
    }

    @Test
    fun aBadStoredValueLoadsAsTheIdentity() {
        for (bad in listOf(null, "", "FL,FR,RL", "FL,FL,RL,RR", "FL,FR,RL,XX", "FL,FR,RL,RR,FL", "SPARE,FR,RL,RR")) {
            assertTrue(bad, WheelMap.parse(bad).isIdentity)
        }
    }

    @Test
    fun mapsAreKeptPerSourceAndOthersAreUntouched() {
        val saved = mapOf<String, Any?>("tpms.usb" to "RL,FR,FL,RR", "tpms" to "FL,FR,RL,RR", "junk" to "nope", "n" to 3)
        val maps = WheelMaps.parseAll(saved)
        assertEquals(setOf("tpms.usb"), maps.keys)
        assertEquals(tyre(3), WheelMaps.apply(maps, "tpms.usb", raw)[fl])
        assertEquals(raw, WheelMaps.apply(maps, "tpms", raw))
        assertEquals(raw, WheelMaps.apply(maps, null, raw))
        assertFalse(WheelMaps.of(maps, "tpms.usb").isIdentity)
    }

    @Test
    fun aFrontTyreShownAsRearIsJudgedByTheRearLimit() {
        // Front wants 2.5 bar (limit 200 kPa), rear 2.0 bar (limit 160 kPa); 180 kPa is low on a front, fine on a rear.
        val car = CarProfile.PRESET.copy(tyreFrontBar = 2.5, tyreRearBar = 2.0)
        val src = mapOf(fl to tyre(180), rl to tyre(230))
        assertEquals(setOf(fl), tyreProblems(src, emptyMap(), 0, car).keys)
        val swapped = WheelMap.IDENTITY.swap(fl, rl).apply(src)
        assertEquals(tyre(180), swapped[rl])
        assertTrue(tyreProblems(swapped, emptyMap(), 0, car).isEmpty())
    }

    @Test
    fun theWheelMenuIsForNormalModeOnly() {
        assertTrue(wheelMenuAllowed(editing = false))
        assertFalse(wheelMenuAllowed(editing = true))
    }
}
