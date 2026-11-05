package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaultMemoryTest {
    private val day = 24L * 60 * 60 * 1000
    private val t0 = 1_791_500_000_000L

    @Test
    fun aScanThatMissedTheCodeDoesNotMakeItNewAgain() {
        // P1352 found, then a scan with nothing (adapter half connected, a code that comes and goes), then P1352 again.
        var known = AiMechanic.remembered(listOf("P1352"), emptyMap(), t0)
        known = AiMechanic.remembered(emptyList(), known, t0 + 60_000)
        assertTrue(AiMechanic.newCodes(listOf("P1352"), known, t0 + 120_000).isEmpty())
    }

    @Test
    fun aCodeUnseenForDaysIsNewAgain() {
        val known = AiMechanic.remembered(listOf("P1352"), emptyMap(), t0)
        assertEquals(listOf("P1352"), AiMechanic.newCodes(listOf("P1352"), known, t0 + 4 * day))
        // And it is dropped from memory once that old.
        assertTrue(AiMechanic.remembered(emptyList(), known, t0 + 4 * day).isEmpty())
    }

    @Test
    fun aDifferentCodeIsNew() {
        val known = AiMechanic.remembered(listOf("P1352"), emptyMap(), t0)
        assertEquals(listOf("P0380"), AiMechanic.newCodes(listOf("P1352", "P0380"), known, t0 + 60_000))
    }

    @Test
    fun knownCodesAreKeptPerModuleAndOldOnesBecomeTheEngines() {
        val known = AiMechanic.parseKnown("P1352:$t0,ABS:U0126:${t0 + day},P0380,PCM:P0676", t0 + 2 * day)
        assertEquals(
            mapOf("ENGINE:P1352" to t0, "ABS:U0126" to t0 + day, "ENGINE:P0380" to t0 + 2 * day, "PCM:P0676" to t0 + 2 * day),
            known
        )
        assertEquals(known, AiMechanic.parseKnown(AiMechanic.formatKnown(known), t0 + 3 * day))
        // The same code in another module is new.
        assertEquals(listOf("PCM:U0126"), AiMechanic.newCodes(listOf("ABS:U0126", "PCM:U0126"), known, t0 + 2 * day))
    }

    @Test
    fun codesSavedBeforeTheDatesAreTakenAsSeenNow() {
        val known = AiMechanic.parseKnown("P1352,P0380", t0)
        assertEquals(mapOf("ENGINE:P1352" to t0, "ENGINE:P0380" to t0), known)
        assertEquals(known, AiMechanic.parseKnown(AiMechanic.formatKnown(known), t0 + day))
    }
}
