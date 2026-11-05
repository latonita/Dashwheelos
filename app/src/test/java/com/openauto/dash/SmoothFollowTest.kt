package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothFollowTest {
    private val d = 1e-9

    @Test fun lerpIsLinear() {
        assertEquals(15.0, SmoothFollow.lerp(10.0, 20.0, 0.5), d)
    }

    @Test fun bearingTakesTheShortArcAcrossNorth() {
        assertEquals(0.0, SmoothFollow.lerpBearing(359.0, 1.0, 0.5), d)
        assertEquals(359.0, SmoothFollow.lerpBearing(1.0, 357.0, 0.5), d)
        assertEquals(90.0, SmoothFollow.lerpBearing(80.0, 100.0, 0.5), d)
        assertEquals(1.0, SmoothFollow.lerpBearing(359.0, 1.0, 1.0), d)
    }

    @Test fun intervalAveragesAndClamps() {
        assertEquals(1000.0, SmoothFollow.nextInterval(1000.0, 1000), d)
        assertEquals(2000.0, SmoothFollow.nextInterval(2000.0, 2900), d)
        assertEquals(500.0, SmoothFollow.nextInterval(500.0, 100), d)
        assertEquals(1150.0, SmoothFollow.nextInterval(1000.0, 1500), d)
    }

    @Test fun snapRules() {
        assertFalse(SmoothFollow.shouldSnap(1000, 20.0, 15.0))
        assertTrue(SmoothFollow.shouldSnap(3500, 20.0, 15.0))
        assertTrue(SmoothFollow.shouldSnap(1000, 400.0, 15.0))
        assertTrue(SmoothFollow.shouldSnap(1000, 1.0, 0.2))
    }

    @Test fun firstFixIsShownAtOnce() {
        val t = FollowTrack()
        assertNull(t.at(0))
        t.feed(48.0, 11.0, 90.0, 10.0, 1000)
        assertEquals(48.0, t.at(1000)!!.lat, d)
        assertTrue(t.done(1000))
    }

    @Test fun glidesLinearlyToTheNextFixAndHoldsThere() {
        val t = FollowTrack()
        t.feed(48.0000, 11.0, 90.0, 10.0, 1000)
        t.feed(48.0001, 11.0, 90.0, 10.0, 2000)
        assertEquals(48.00005, t.at(2500)!!.lat, 1e-9)
        assertFalse(t.done(2500))
        // a late fix: it holds at the last one, no extrapolation
        assertEquals(48.0001, t.at(3000)!!.lat, 1e-9)
        assertEquals(48.0001, t.at(5000)!!.lat, 1e-9)
        assertTrue(t.done(3000))
    }

    @Test fun anInterruptedGlideContinuesFromWhereItIs() {
        val t = FollowTrack()
        t.feed(48.0000, 11.0, 0.0, 10.0, 1000)
        t.feed(48.0001, 11.0, 0.0, 10.0, 2000)
        t.feed(48.0002, 11.0, 0.0, 10.0, 2500)
        assertEquals(48.00005, t.at(2500)!!.lat, 1e-9)
    }

    @Test fun stoppedHoldsBearingAndSnapsPosition() {
        val t = FollowTrack()
        t.feed(48.0, 11.0, 80.0, 10.0, 1000)
        t.feed(48.00005, 11.0, 80.0, 10.0, 2000)
        t.feed(48.00006, 11.0, 200.0, 0.2, 3000)
        val p = t.at(3000)!!
        assertEquals(48.00006, p.lat, 1e-9)
        assertEquals(80.0, p.bearing, 1e-9)
    }

    @Test fun aLongGapSnaps() {
        val t = FollowTrack()
        t.feed(48.0, 11.0, 80.0, 10.0, 1000)
        t.feed(48.0001, 11.0, 80.0, 10.0, 6000)
        assertEquals(48.0001, t.at(6000)!!.lat, 1e-9)
    }
}
