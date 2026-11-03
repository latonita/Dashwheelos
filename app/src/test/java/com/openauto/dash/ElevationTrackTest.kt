package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevationTrackTest {
    @Test fun oneKilometreStoresTenPoints() {
        val t = ElevationTrack()
        repeat(100) { t.add(10.0, 100.0) }
        assertEquals(10, t.points.size)
        assertEquals(1000.0, t.lengthM, 0.001)
    }

    @Test fun stepsAccumulateBetweenPoints() {
        val t = ElevationTrack()
        assertFalse(t.add(60.0, 50.0))
        assertTrue(t.add(60.0, 50.0))
        assertEquals(1, t.points.size)
        // 20 m carried over, so 80 m more completes the next one.
        assertFalse(t.add(70.0, 50.0))
        assertTrue(t.add(10.0, 50.0))
        assertEquals(2, t.points.size)
    }

    @Test fun cappedAtFortyKilometres() {
        val t = ElevationTrack()
        repeat(1000) { t.add(100.0, it.toDouble()) }
        assertEquals(ElevationTrack.MAX_POINTS, t.points.size)
        assertEquals(400, t.points.size)
        // The newest are kept: the last height is the last stored.
        assertTrue(t.points.last() >= 995f)
        assertTrue(t.points.first() >= 590f)
    }

    @Test fun stationaryJitterIsIgnored() {
        val t = ElevationTrack()
        repeat(10_000) { t.add(1.5, 100.0 + it % 7) }
        assertTrue(t.points.isEmpty())
    }

    @Test fun medianDropsAnAltitudeSpike() {
        val t = ElevationTrack()
        listOf(100.0, 100.0, 180.0, 100.0, 100.0).forEach { t.add(20.0, it) }
        assertEquals(listOf(100f), t.points)
    }

    @Test fun aGapStoresOnePointNotALine() {
        val t = ElevationTrack()
        t.add(20.0, 100.0)
        assertTrue(t.add(5_000.0, 300.0))
        assertEquals(1, t.points.size)
    }

    @Test fun badAltitudeIsIgnored() {
        val t = ElevationTrack()
        assertFalse(t.add(200.0, Double.NaN))
        assertTrue(t.points.isEmpty())
    }

    @Test fun serializationRoundTrip() {
        val t = ElevationTrack()
        repeat(37) { t.add(100.0, 120.0 + it * 1.37) }
        t.add(40.0, 170.0)
        val back = ElevationTrack.fromString(t.encode())
        assertEquals(t.points.size, back.points.size)
        t.points.zip(back.points).forEach { (a, b) -> assertEquals(a, b, 0.06f) }
        // The partial distance survives too: 60 m more completes the next point.
        assertTrue(back.add(60.0, 170.0))
    }

    @Test fun damagedStringsGiveAnEmptyTrack() {
        for (s in listOf(null, "", "v2;0;1", "v1;x;1,2", "v1;0;1,a", "junk")) {
            assertTrue(s.toString(), ElevationTrack.fromString(s).points.isEmpty())
        }
        assertTrue(ElevationTrack.fromString(ElevationTrack().encode()).points.isEmpty())
    }
}
