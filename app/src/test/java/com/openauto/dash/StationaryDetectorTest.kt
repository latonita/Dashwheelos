package com.openauto.dash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationaryDetectorTest {
    @Test fun startsMoving() = assertFalse(StationaryDetector().stationary)

    @Test fun stopNeedsTheHold() {
        val d = StationaryDetector()
        assertFalse(d.update(0, 1f))
        assertFalse(d.update(1_000, 1f))
        assertTrue(d.update(2_000, 0f))
    }

    @Test fun creepingAboveStopResetsNothingButDoesNotStop() {
        val d = StationaryDetector()
        d.update(0, 1f)
        d.update(1_000, 4f) // between thresholds: the hold goes on
        assertTrue(d.update(2_500, 1f))
    }

    @Test fun movingAgainOnlyFromGoSpeed() {
        val d = StationaryDetector()
        d.update(0, 0f); d.update(2_000, 0f)
        assertTrue(d.update(2_100, 5f))
        assertFalse(d.update(2_200, 6f))
    }

    @Test fun fastReadingCancelsHold() {
        val d = StationaryDetector()
        d.update(0, 0f)
        d.update(1_000, 10f)
        assertFalse(d.update(2_500, 0f))
        assertTrue(d.update(4_500, 0f))
    }
}
