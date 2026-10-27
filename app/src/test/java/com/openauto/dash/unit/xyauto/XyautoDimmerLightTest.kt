package com.openauto.dash.unit.xyauto

import org.junit.Assert.assertEquals
import org.junit.Test

class XyautoDimmerLightTest {
    @Test
    fun theCarsSixStepsMapToBacklightLevels() {
        val expected = mapOf(0.0 to 43, 17.0 to 43, 34.0 to 87, 51.0 to 130, 82.0 to 209, 100.0 to 255)
        for ((pct, level) in expected) assertEquals("pct $pct", level, XyautoDimmerLight.level(pct))
    }

    @Test
    fun outOfRangeIsClamped() {
        assertEquals(43, XyautoDimmerLight.level(-5.0))
        assertEquals(255, XyautoDimmerLight.level(250.0))
    }
}
