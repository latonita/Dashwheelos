package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RideTrackTest {

    /** A point [northM] metres north of the origin. */
    private fun p(northM: Double, kmh: Int = 50, sec: Long = 0, eastM: Double = 0.0) =
        RidePoint(Math.round(northM / 1.1132).toInt() + 5_000_000, Math.round(eastM / 1.1132 / Math.cos(Math.toRadians(50.0))).toInt() + 1_000_000, kmh, sec)

    @Test
    fun keepsOnePointPerEighteenMetres() {
        val t = RideTrack()
        var kept = 0
        for (i in 0..100) if (t.add(1, p(i * 5.0, sec = i.toLong()))) kept++
        // 500 m at 5 m steps: the first, then about one in every 4 fixes (20 m).
        assertTrue("kept $kept", kept in 25..30)
    }

    @Test
    fun dropsParkedDrift() {
        val t = RideTrack()
        assertTrue(t.add(1, p(0.0)))
        assertFalse(t.add(1, p(2.0)))
        assertEquals(1, t.size)
    }

    @Test
    fun aBendKeepsAPointSooner() {
        val t = RideTrack()
        t.add(1, p(0.0)); t.add(1, p(20.0)); t.add(1, p(40.0))
        // 8 m on, but 90 degrees to the east.
        assertTrue(t.add(1, p(40.0, eastM = 8.0)))
        // 8 m straight on after that: no bend, too close.
        assertFalse(t.add(1, p(40.0, eastM = 16.0)))
    }

    @Test
    fun aStraightShortStepIsDropped() {
        val t = RideTrack()
        t.add(1, p(0.0)); t.add(1, p(20.0)); t.add(1, p(40.0))
        assertFalse(t.add(1, p(48.0)))
    }

    @Test
    fun pastTheCapTheOldestHalfIsThinned() {
        val t = RideTrack()
        val n = RideTrack.MAX_POINTS + 1
        for (i in 0 until n) t.add(1, RidePoint(5_000_000 + i * 20, 1_000_000, 50, i.toLong()))
        // 20_001 points: the older 10_000 halve, the newer half stays.
        assertEquals(15_001, t.size)
        val pts = t.points
        assertEquals(0L, pts.first().sec)
        assertEquals(n - 1L, pts.last().sec)
        assertEquals(2L, pts[1].sec)
        assertEquals(10_000L, pts[5_000].sec)
        assertEquals(10_001L, pts[5_001].sec)
    }

    @Test
    fun aNewDriveWipesTheOldTrack() {
        val t = RideTrack()
        t.add(1, p(0.0)); t.add(1, p(20.0)); t.add(1, p(40.0))
        assertTrue(t.add(2, p(5_000.0)))
        assertEquals(2L, t.driveId)
        assertEquals(1, t.size)
    }

    @Test
    fun theSameDriveContinuesAfterARestart() {
        val t = RideTrack()
        for (i in 0..10) t.add(7, p(i * 20.0, sec = 100L + i))
        val back = RideTrack.decode(t.encode())
        assertEquals(7L, back.driveId)
        // The resumed trip has the same start time: the trail goes on.
        assertTrue(back.add(7, p(220.0, sec = 120)))
        assertEquals(12, back.size)
        // A drive that began since: wiped.
        assertTrue(back.add(8, p(500.0)))
        assertEquals(1, back.size)
    }

    @Test
    fun encodingRoundTrips() {
        val t = RideTrack()
        for (i in 0..50) t.add(1_700_000_000_000L, RidePoint(5_012_345 - i * 17, 1_998_765 + i * 31 * (if (i % 7 == 0) -1 else 1), (i * 3) % 130, 1_700_000_000L + i * 2L))
        val back = RideTrack.decode(t.encode())
        assertEquals(t.driveId, back.driveId)
        assertEquals(t.points, back.points)
    }

    @Test
    fun damagedOrMissingTextGivesAnEmptyTrack() {
        assertEquals(0, RideTrack.decode(null).size)
        assertEquals(0, RideTrack.decode("").size)
        assertEquals(0, RideTrack.decode("v2;1;0;1,2,3,4").size)
        assertEquals(0, RideTrack.decode("v1;1;0;1,2,3").size)
        assertEquals(0, RideTrack.decode("v1;1;0;1,2,x,4").size)
        assertEquals(0, RideTrack.decode("v1;x;0;").size)
    }

    @Test
    fun v2RoundTripsAltitudeWithGapsAndNegatives() {
        val alts = listOf(120, null, null, 118, -5, null, -5, 0, 300, null)
        val t = RideTrack()
        alts.forEachIndexed { i, a -> t.add(3, RidePoint(5_000_000 + i * 20, 1_000_000, 50, 100L + i, a)) }
        val text = t.encode()
        assertTrue(text, text.startsWith("v2;3;100;"))
        assertEquals(alts, RideTrack.decode(text).points.map { it.altM })
        assertEquals(t.points, RideTrack.decode(text).points)
        // the gap does not move the reference: 120, (empty), (empty), then 118 is -2
        assertTrue(text, text.contains(",-2,"))
    }

    @Test
    fun v1FilesStillReadWithoutAltitude() {
        val back = RideTrack.decode("v1;5;1000;5000000,1000000,50,0,18,3,52,2")
        assertEquals(2, back.size)
        assertEquals(listOf<Int?>(null, null), back.points.map { it.altM })
        assertEquals(5_000_018, back.points[1].latE5)
        assertEquals(1002L, back.points[1].sec)
        assertEquals(0, RideTrack.decode("v2;1;0;1,2,3,4").size)
    }

    @Test
    fun pointOfRoundsAltitude() {
        assertEquals(124, RideTrack.pointOf(50.0, 20.0, 50f, 0L, 123.6).altM)
        assertEquals(-3, RideTrack.pointOf(50.0, 20.0, 50f, 0L, -2.6).altM)
        assertEquals(null, RideTrack.pointOf(50.0, 20.0, 50f, 0L).altM)
        assertEquals(null, RideTrack.pointOf(50.0, 20.0, 50f, 0L, Double.NaN).altM)
    }

    @Test
    fun reducedCarriesAltitude() {
        val t = RideTrack()
        for (i in 0 until 5_000) t.add(1, RidePoint(5_000_000 + i * 20, 1_000_000, 50, i.toLong(), if (i % 10 == 3) null else i / 10))
        val r = RideTrack.decode(t.reduced(2_000).encode())
        assertEquals(2_000, r.size)
        assertEquals(4_999 / 10, r.points.last().altM)
        assertTrue(r.points.any { it.altM != null } && r.points.any { it.altM == null })
        assertEquals(t.points.first().altM, r.points.first().altM)
    }

    @Test
    fun a300KmRideStaysSmall() {
        val t = RideTrack()
        var sec = 0L
        // 300 km at 20 m steps, speed wandering between 30 and 120 km/h.
        for (i in 0 until 15_000) {
            sec += 1 + (i % 3)
            t.add(1, RidePoint(5_000_000 + i * 18, 1_000_000 + (i % 13) * 4 + i * 11, 30 + (i / 7) % 90, sec, 200 + ((i / 5) % 40) - (i % 3)))
        }
        val text = t.encode()
        println("300 km ride: ${t.size} points, ${text.length} bytes; archive ${t.reduced(RideTrack.ARCHIVE_POINTS).encode().length} bytes")
        assertTrue(text.length < 300_000)
        assertTrue(t.size <= RideTrack.MAX_POINTS)
    }

    @Test
    fun reducedKeepsTheEndsAndTheLimit() {
        val t = RideTrack()
        for (i in 0 until 5_000) t.add(1, RidePoint(5_000_000 + i * 20, 1_000_000, 50, i.toLong()))
        val r = t.reduced(2_000)
        assertEquals(2_000, r.size)
        assertEquals(t.points.first(), r.points.first())
        assertEquals(t.points.last(), r.points.last())
        assertEquals(10, t.reduced(50).reduced(10).size)
    }

    @Test
    fun archiveKeepsTheNewestFifty() {
        val starts = (1L..60L).toList().shuffled()
        assertEquals((1L..10L).toSet(), RideTrack.archiveVictims(starts, 50).toSet())
        assertTrue(RideTrack.archiveVictims(listOf(3L, 4L), 50).isEmpty())
    }

    @Test
    fun onlyTheHeldDriveIsArchived() {
        val t = RideTrack()
        t.add(5, p(0.0)); t.add(5, p(30.0))
        assertTrue(RideTrack.isDriveOf(t, 5))
        assertFalse(RideTrack.isDriveOf(t, 6))
        assertFalse(RideTrack.isDriveOf(RideTrack(listOf(p(0.0)), 5), 5))
    }

    // ---- runs ----

    private fun line(kmhs: List<Int>, stepM: Double = 20.0): List<RidePoint> =
        kmhs.mapIndexed { i, k -> p(i * stepM, k, i.toLong()) }

    @Test
    fun speedBucketsFollowTheLimits() {
        assertEquals(listOf(0, 1, 1, 2, 2, 3), listOf(10, 20, 49, 50, 89, 90).map { RideRuns.rawBucket(it) })
    }

    @Test
    fun hysteresisStopsFlicker() {
        // 49 and 51 around the 50 limit: stays green until clearly past it.
        assertEquals(1, RideRuns.bucket(1, 51))
        assertEquals(1, RideRuns.bucket(1, 52))
        assertEquals(2, RideRuns.bucket(1, 53))
        assertEquals(2, RideRuns.bucket(2, 48))
        assertEquals(1, RideRuns.bucket(2, 46))
    }

    @Test
    fun runsSplitAtABucketChangeAndShareTheJoint() {
        val runs = RideRuns.build(line(listOf(10, 10, 10, 60, 60, 60)))
        assertEquals(listOf(0, 2), runs.map { it.bucket })
        // 10, 10, 10 then 60: the segment into the first 60 is already orange.
        assertEquals(3, runs[0].points.size)
        assertEquals(runs[0].points.last(), runs[1].points.first())
        assertEquals(4, runs[1].points.size)
    }

    @Test
    fun aSteadySpeedIsOneRun() {
        val runs = RideRuns.build(line(List(200) { 70 }, stepM = 10.0))
        // 2 km, so all in the newest age band: one feature.
        assertEquals(1, runs.size)
        assertEquals(200, runs[0].points.size)
    }

    @Test
    fun olderStretchesGetOlderAgeBands() {
        // 20 km of 20 m steps: bands 0 (last 3 km), 1 (3-10), 2 (10-20).
        val runs = RideRuns.build(line(List(1_001) { 70 }))
        assertEquals(listOf(2, 1, 0), runs.map { it.age })
        assertEquals(0, RideRuns.ageBand(2_999.0))
        assertEquals(3, RideRuns.ageBand(30_000.0))
    }

    @Test
    fun aGapIsNotBridged() {
        val pts = line(listOf(60, 60, 60)) + listOf(p(1_540.0, 60, 9), p(1_560.0, 60, 10))
        val runs = RideRuns.build(pts)
        assertEquals(2, runs.size)
        assertEquals(3, runs[0].points.size)
        assertEquals(2, runs[1].points.size)
    }

    @Test
    fun tooFewPointsDrawNothing() {
        assertTrue(RideRuns.build(emptyList()).isEmpty())
        assertTrue(RideRuns.build(line(listOf(50))).isEmpty())
    }

    @Test
    fun geoJsonCarriesPropertiesAndSignedCoordinates() {
        val pts = listOf(RidePoint(-1_234_567, 2_000_005, 40, 0), RidePoint(-1_234_000, 2_000_100, 40, 1))
        val json = RideRuns.geoJson(RideRuns.build(pts))
        assertTrue(json, json.contains("\"s\":1,\"a\":0"))
        assertTrue(json, json.contains("[20.00005,-12.34567],[20.00100,-12.34000]"))
        assertEquals("{\"type\":\"FeatureCollection\",\"features\":[]}", RideRuns.geoJson(emptyList()))
    }

    @Test
    fun aLongRideMakesFewFeatures() {
        // 300 km, speed cycling slowly through the buckets every ~5 km.
        val pts = List(15_001) { i -> RidePoint(5_000_000 + i * 18, 1_000_000, listOf(15, 40, 70, 110)[(i / 250) % 4], i.toLong()) }
        val runs = RideRuns.build(pts)
        println("300 km: ${runs.size} features, geojson ${RideRuns.geoJson(runs).length} bytes")
        assertTrue(runs.size < 200)
    }

    @Test
    fun olderStretchesAreDrawnWithFewerPoints() {
        val runs = RideRuns.build(line(List(1_001) { 70 }))
        val newest = runs.last()
        val oldest = runs.first()
        assertEquals(0, newest.age)
        // The newest keeps every point (20 m apart); the stretch 10 km behind about one in four (80 m).
        assertTrue(newest.points.size > 100)
        val oldSpan = RideTrack.distanceM(oldest.points.first(), oldest.points.last())
        assertTrue(oldest.points.size < oldSpan / 60)
        // Runs still join.
        assertEquals(runs[0].points.last(), runs[1].points.first())
    }

    @Test
    fun aFinishedDriveIsArchivedOnlyOnce() {
        val t = RideTrack()
        for (i in 0..60) t.add(5, p(i * 20.0))
        assertTrue(RideTrack.worthArchiving(t, 0L))
        assertFalse(RideTrack.worthArchiving(t, 5L))
    }

    @Test
    fun aDriveUnderHalfAKilometreIsNotArchived() {
        val t = RideTrack()
        for (i in 0..20) t.add(5, p(i * 20.0))
        // 400 m: the drive log would not log it.
        assertFalse(RideTrack.worthArchiving(t, 0L))
        t.add(5, p(520.0))
        assertTrue(RideTrack.worthArchiving(t, 0L))
    }

    @Test
    fun noDriveOrOnePointIsNotArchived() {
        assertFalse(RideTrack.worthArchiving(RideTrack(), 0L))
        assertFalse(RideTrack.worthArchiving(RideTrack(listOf(p(0.0)), 5), 0L))
    }
}
