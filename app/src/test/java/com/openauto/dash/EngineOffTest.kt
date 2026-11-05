package com.openauto.dash

import com.openauto.dash.vehicle.engineOffOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineOffTest {
    @Test fun noAnswerOrZeroIsOff() {
        assertTrue(engineOffOf(null))
        assertTrue(engineOffOf(0.0))
        assertTrue(engineOffOf(0.4))
    }

    @Test fun anyRevsIsRunning() {
        assertFalse(engineOffOf(750.0))
        assertFalse(engineOffOf(0.6))
    }
}
