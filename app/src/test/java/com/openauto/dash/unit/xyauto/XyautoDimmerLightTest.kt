package com.openauto.dash.unit.xyauto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XyautoDimmerLightTest {
    @Test
    fun theCarsSixStepsMapToBacklightLevels() {
        val expected = mapOf(0.0 to 255, 17.0 to 5, 34.0 to 8, 51.0 to 20, 82.0 to 64, 100.0 to 255)
        for ((pct, level) in expected) assertEquals("pct $pct", level, XyautoDimmerLight.level(pct))
    }

    @Test
    fun outOfRangeIsClamped() {
        assertEquals(255, XyautoDimmerLight.level(-5.0))
        assertEquals(255, XyautoDimmerLight.level(250.0))
    }

    @Test
    fun aValueBetweenTwoPositionsFallsBetweenTheirLevels() {
        val level = XyautoDimmerLight.level(66.5)
        assertTrue("level $level", level in 21..63)
    }
}
