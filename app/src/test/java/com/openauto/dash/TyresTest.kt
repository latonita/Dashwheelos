package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The TPMS app's tyres, as Dashwheel reads and judges them. */
class TyresTest {

    private fun tyre(kPa: Int, celsius: Int? = 25, leak: Boolean = false, noSignal: Boolean = false) =
        Tyre(kPa, celsius, sensorBatteryLow = false, leak = leak, noSignal = noSignal, at = 0)

    @Test
    fun theTpmsAppsTyreNumbers() {
        assertEquals(TyrePos.REAR_LEFT, tyrePosition(0))
        assertEquals(TyrePos.FRONT_LEFT, tyrePosition(1))
        assertEquals(TyrePos.FRONT_RIGHT, tyrePosition(2))
        assertEquals(TyrePos.REAR_RIGHT, tyrePosition(3))
        assertEquals(TyrePos.SPARE, tyrePosition(5))
        assertNull(tyrePosition(4))
    }

    @Test
    fun problemsWithTheTpmsAppsDefaults() {
        assertNull(tyreProblem(tyre(230)))
        assertEquals(TyreProblem.LOW, tyreProblem(tyre(TYRE_LOW_KPA)))
        assertEquals(TyreProblem.HIGH, tyreProblem(tyre(TYRE_HIGH_KPA)))
        assertEquals(TyreProblem.HOT, tyreProblem(tyre(230, celsius = TYRE_HOT_C)))
        // A leak is the worst, whatever the pressure says.
        assertEquals(TyreProblem.LEAK, tyreProblem(tyre(150, leak = true)))
        // A sensor not heard says nothing about the tyre.
        assertNull(tyreProblem(tyre(0, noSignal = true)))
        assertNull(tyreProblem(tyre(0)))
    }

    @Test
    fun aTyreAtTheLimitIsOneAlertNotAFlicker() {
        // Low at 180, then 181, 180, 181, 190: low throughout, until clearly back.
        var before: TyreProblem? = null
        listOf(180, 181, 180, 181, 190).forEach { kPa ->
            before = tyreProblem(tyre(kPa), before)
            assertEquals("at $kPa kPa", TyreProblem.LOW, before)
        }
        assertNull(tyreProblem(tyre(TYRE_LOW_KPA + TYRE_CLEAR_KPA), before))
        // Hot the same way.
        assertEquals(TyreProblem.HOT, tyreProblem(tyre(230, celsius = 73), TyreProblem.HOT))
        assertNull(tyreProblem(tyre(230, celsius = 70), TyreProblem.HOT))
    }

    @Test
    fun anUnknownTemperatureIsNeverHot() {
        assertNull(tyreProblem(tyre(230, celsius = null)))
        assertNull(tyreProblem(tyre(230, celsius = null), TyreProblem.HOT))
        assertEquals(TyreProblem.LOW, tyreProblem(tyre(170, celsius = null)))
        // Without a problem before, 181 kPa is fine.
        assertNull(tyreProblem(tyre(181)))
    }

    @Test
    fun theLowLimitFollowsTheCarsOwnPressures() {
        val car = CarProfile.PRESET.copy(tyreFrontBar = 2.5, tyreRearBar = 2.2)
        assertEquals(200, tyreLowKpa(TyrePos.FRONT_LEFT, car))
        assertEquals(176, tyreLowKpa(TyrePos.REAR_RIGHT, car))
        assertEquals(TYRE_LOW_KPA, tyreLowKpa(TyrePos.SPARE, car))
        assertEquals(TYRE_LOW_KPA, tyreLowKpa(TyrePos.FRONT_LEFT, car.copy(tyreFrontBar = null)))
        // 195 kPa is low on a front tyre meant for 2.5 bar, fine on a rear one meant for 2.2.
        val tyres = mapOf(TyrePos.FRONT_LEFT to tyre(195), TyrePos.REAR_LEFT to tyre(195))
        assertEquals(mapOf(TyrePos.FRONT_LEFT to TyreProblem.LOW), tyreProblems(tyres, emptyMap(), now = 1_000, car = car))
    }

    @Test
    fun aSensorThatWentQuietHoldsNoWarningUp() {
        val car = CarProfile.PRESET.copy(tyreFrontBar = null, tyreRearBar = null)
        val tyres = mapOf(TyrePos.FRONT_LEFT to tyre(150))
        assertEquals(mapOf(TyrePos.FRONT_LEFT to TyreProblem.LOW), tyreProblems(tyres, emptyMap(), now = TYRE_STALE_MS, car = car))
        assertTrue(tyreProblems(tyres, emptyMap(), now = TYRE_STALE_MS + 1, car = car).isEmpty())
    }

    @Test
    fun pressureInTheDriversUnit() {
        Locale.setDefault(Locale.UK)
        assertEquals("2.3 bar", formatPressure(230, PressureUnit.BAR))
        assertEquals("230 kPa", formatPressure(230, PressureUnit.KPA))
        assertEquals("33 psi", formatPressure(230, PressureUnit.PSI))
        assertEquals(PressureUnit.KPA, pressureUnit("Kpa"))
        assertEquals(PressureUnit.BAR, pressureUnit("Bar"))
        assertNull(pressureUnit(null))
    }

    @Test
    fun theUnitsNumberedKeys() {
        assertEquals(1, HeadUnitKeys.customNumber(354))
        assertEquals(15, HeadUnitKeys.customNumber(368))
        assertNull(HeadUnitKeys.customNumber(298))
    }
}
