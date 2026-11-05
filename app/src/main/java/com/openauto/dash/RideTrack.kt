package com.openauto.dash

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * One kept fix of the ride: position in 1e-5 degrees (about 1.1 m), speed in km/h, time in seconds, and the
 * altitude in whole metres ([altM], null when the fix had none). Recorded only; nothing reads it yet.
 * On the API 27 unit, `Location.getAltitude()` is the WGS84 ellipsoid height (no geoid correction), so it
 * runs tens of metres off sea level.
 */
internal data class RidePoint(val latE5: Int, val lonE5: Int, val kmh: Int, val sec: Long, val altM: Int? = null)

/**
 * The path of the current ride ([RideTrail] records it, the map tile draws it): the fixes the trip computer
 * counts, thinned to one per [MIN_M] metres (or sooner in a bend) and capped at [MAX_POINTS].
 * Pure logic, no Android types; see RideTrackTest.
 *
 * A track belongs to one drive, named by [driveId] (the trip's start time). A point of another drive wipes it
 * first, so a finished ride stays on the map until the next one begins.
 */
internal class RideTrack(points: List<RidePoint> = emptyList(), driveId: Long = 0L) {
    private val list = ArrayList<RidePoint>(points)

    /** The drive the points belong to; 0 for none. */
    var driveId: Long = driveId
        private set

    val size: Int get() = list.size

    /** A copy of the kept points, oldest first. */
    val points: List<RidePoint> get() = ArrayList(list)

    /** One more fix of drive [id]. Returns true when the track changed (a point was kept, or a new drive wiped it). */
    fun add(id: Long, p: RidePoint): Boolean {
        if (id != driveId) {
            list.clear()
            driveId = id
        }
        val last = list.lastOrNull()
        if (last == null) {
            list.add(p)
            return true
        }
        val d = distanceM(last, p)
        if (d < NOISE_M) return false
        val keep = d >= MIN_M || (d >= BEND_MIN_M && turnDeg(list, p) > BEND_DEG)
        if (!keep) return false
        list.add(p)
        if (list.size > MAX_POINTS) thinOldestHalf()
        return true
    }

    /** Keeps every second point of the older half, so the far past gets coarser instead of vanishing. */
    private fun thinOldestHalf() {
        val half = list.size / 2
        val out = ArrayList<RidePoint>(list.size - half / 2)
        for (i in 0 until half) if (i % 2 == 0) out.add(list[i])
        for (i in half until list.size) out.add(list[i])
        list.clear()
        list.addAll(out)
    }

    /** A copy with at most [max] points, evenly strided, the first and last kept: for the archive of a past drive. */
    fun reduced(max: Int): RideTrack {
        if (list.size <= max || max < 2) return RideTrack(list, driveId)
        val out = ArrayList<RidePoint>(max)
        val stride = (list.size - 1).toDouble() / (max - 1)
        for (i in 0 until max) out.add(list[(i * stride).roundToInt().coerceAtMost(list.size - 1)])
        return RideTrack(out, driveId)
    }

    /**
     * "v2;driveId;firstSec;lat,lon,kmh,dsec,dalt,..." with the first point absolute and the rest as differences.
     * dalt is the altitude change in metres against the last point that had one (0 before the first), or empty
     * for a point without altitude; an empty field does not move that reference, so the chain survives gaps.
     */
    fun encode(): String {
        val sb = StringBuilder(list.size * 16 + 32)
        sb.append("v2;").append(driveId).append(';').append(list.firstOrNull()?.sec ?: 0L).append(';')
        var prev: RidePoint? = null
        var lastAlt = 0
        for (p in list) {
            if (prev != null) sb.append(',')
            sb.append(p.latE5 - (prev?.latE5 ?: 0)).append(',').append(p.lonE5 - (prev?.lonE5 ?: 0)).append(',')
                .append(p.kmh).append(',').append(p.sec - (prev?.sec ?: p.sec)).append(',')
            p.altM?.let { sb.append(it - lastAlt); lastAlt = it }
            prev = p
        }
        return sb.toString()
    }

    companion object {
        const val MIN_M = 18.0
        /** Closer than this is a parked car's drift. */
        const val NOISE_M = 3.0
        /** A turn of more than [BEND_DEG] keeps a point once the car has gone this far. */
        const val BEND_MIN_M = 6.0
        const val BEND_DEG = 20.0
        const val MAX_POINTS = 20_000
        /** What an archived drive keeps. */
        const val ARCHIVE_POINTS = 2_000

        private const val M_PER_E5 = 1.1132

        fun distanceM(a: RidePoint, b: RidePoint): Double {
            val dy = (b.latE5 - a.latE5) * M_PER_E5
            val dx = (b.lonE5 - a.lonE5) * M_PER_E5 * cos(Math.toRadians((a.latE5 + b.latE5) / 2e5))
            return hypot(dx, dy)
        }

        private fun bearing(a: RidePoint, b: RidePoint): Double {
            val dy = (b.latE5 - a.latE5).toDouble()
            val dx = (b.lonE5 - a.lonE5) * cos(Math.toRadians((a.latE5 + b.latE5) / 2e5))
            return Math.toDegrees(atan2(dx, dy))
        }

        /** The turn from the last kept segment to the one that would end in [p]; 0 with fewer than two points. */
        private fun turnDeg(list: List<RidePoint>, p: RidePoint): Double {
            if (list.size < 2) return 0.0
            val a = list[list.size - 2]
            val b = list[list.size - 1]
            val diff = abs(bearing(b, p) - bearing(a, b)) % 360.0
            return if (diff > 180.0) 360.0 - diff else diff
        }

        /** The track [encode] wrote (v2), or an older v1 file (no altitude); empty when [s] is missing or damaged. */
        fun decode(s: String?): RideTrack {
            val parts = s?.split(';') ?: return RideTrack()
            if (parts.size != 4) return RideTrack()
            val width = when (parts[0]) { "v1" -> 4; "v2" -> 5; else -> return RideTrack() }
            val id = parts[1].toLongOrNull() ?: return RideTrack()
            var sec = parts[2].toLongOrNull() ?: return RideTrack()
            if (parts[3].isEmpty()) return RideTrack(emptyList(), id)
            val f = parts[3].split(',')
            if (f.size % width != 0) return RideTrack()
            val out = ArrayList<RidePoint>(f.size / width)
            var lat = 0
            var lon = 0
            var alt = 0
            for (i in f.indices step width) {
                lat += f[i].toIntOrNull() ?: return RideTrack()
                lon += f[i + 1].toIntOrNull() ?: return RideTrack()
                val kmh = f[i + 2].toIntOrNull() ?: return RideTrack()
                sec += f[i + 3].toLongOrNull() ?: return RideTrack()
                var altM: Int? = null
                if (width == 5 && f[i + 4].isNotEmpty()) {
                    alt += f[i + 4].toIntOrNull() ?: return RideTrack()
                    altM = alt
                }
                out.add(RidePoint(lat, lon, kmh, sec, altM))
            }
            return RideTrack(out, id)
        }

        fun pointOf(lat: Double, lon: Double, kmh: Float, timeMs: Long, altM: Double? = null) =
            RidePoint((lat * 1e5).roundToInt(), (lon * 1e5).roundToInt(), kmh.roundToInt().coerceIn(0, 400), timeMs / 1000,
                altM?.takeIf { it.isFinite() }?.roundToInt())

        /** Drive starts whose archive files go: all but the newest [max] (the file name is the start time). */
        fun archiveVictims(starts: List<Long>, max: Int): List<Long> = starts.sortedDescending().drop(max)

        /** How far the points reach along the path, in metres. */
        fun lengthM(points: List<RidePoint>): Double = points.zipWithNext().sumOf { distanceM(it.first, it.second) }

        /**
         * [track] is a finished drive to put in the archive: it has a path, is at least the drive log's minimum
         * [DriveLogRules.MIN_KM] long (a shorter one is never logged) and is not [archivedId], already done.
         */
        fun worthArchiving(track: RideTrack, archivedId: Long): Boolean =
            track.driveId != 0L && track.driveId != archivedId && track.size >= 2 &&
                lengthM(track.points) >= DriveLogRules.MIN_KM * 1000

        /** The finished drive [startedAt] is the one [track] holds, and it went somewhere. */
        fun isDriveOf(track: RideTrack, startedAt: Long): Boolean = track.driveId == startedAt && track.size >= 2
    }
}

/** One stretch of the ribbon: constant speed [bucket] and age band [age], as the points it runs through. */
internal class RideRun(val bucket: Int, val age: Int, val points: List<RidePoint>)

/** Cuts a track into [RideRun]s for the map: a new run when the speed bucket or the age band changes. */
internal object RideRuns {
    /** Speed bucket limits in km/h: below 20 crawling (blue), 20-50 green, 50-90 orange, above red. */
    val SPEED_LIMITS = intArrayOf(20, 50, 90)
    /** A bucket changes only when the speed is this far past its limit, so a steady 50 does not flicker. */
    const val HYSTERESIS_KMH = 3
    /** Age bands by distance behind the newest point, in metres: [0,3k) full, [3k,10k), [10k,30k), older. */
    val AGE_LIMITS_M = doubleArrayOf(3_000.0, 10_000.0, 30_000.0)
    /** A hole this long between points (a tunnel, no GPS) is not bridged by a line. */
    const val GAP_M = 1_000.0

    fun rawBucket(kmh: Int): Int = SPEED_LIMITS.count { kmh >= it }

    fun bucket(current: Int, kmh: Int): Int {
        val raw = rawBucket(kmh)
        return when {
            raw > current && kmh >= SPEED_LIMITS[current] + HYSTERESIS_KMH -> raw
            raw < current && kmh < SPEED_LIMITS[current - 1] - HYSTERESIS_KMH -> raw
            else -> current
        }
    }

    fun ageBand(metresBehind: Double): Int = AGE_LIMITS_M.count { metresBehind >= it }

    /** The least spacing of drawn points per age band, in metres: far behind the car the ribbon needs no detail. */
    val DRAW_SPACING_M = doubleArrayOf(0.0, 40.0, 80.0, 150.0)

    /** A run of [age], with its inner points thinned to [DRAW_SPACING_M]; both ends stay, so runs still join. */
    private fun emit(bucket: Int, age: Int, pts: List<RidePoint>): RideRun {
        val min = DRAW_SPACING_M[age]
        if (min == 0.0 || pts.size <= 2) return RideRun(bucket, age, pts)
        val out = ArrayList<RidePoint>()
        out.add(pts.first())
        for (i in 1 until pts.size - 1) if (RideTrack.distanceM(out.last(), pts[i]) >= min) out.add(pts[i])
        out.add(pts.last())
        return RideRun(bucket, age, out)
    }

    fun build(points: List<RidePoint>): List<RideRun> {
        if (points.size < 2) return emptyList()
        // Distance behind the newest point, for each point.
        val behind = DoubleArray(points.size)
        for (i in points.size - 2 downTo 0) behind[i] = behind[i + 1] + RideTrack.distanceM(points[i], points[i + 1])
        val runs = ArrayList<RideRun>()
        var cur: ArrayList<RidePoint>? = null
        var curBucket = 0
        var curAge = 0
        var bucket = rawBucket(points[1].kmh)
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            if (RideTrack.distanceM(a, b) > GAP_M) {
                cur?.let { runs.add(emit(curBucket, curAge, it)) }
                cur = null
                bucket = rawBucket(b.kmh)
                continue
            }
            bucket = bucket(bucket, b.kmh)
            val age = ageBand(behind[i - 1])
            val open = cur
            if (open == null || bucket != curBucket || age != curAge) {
                open?.let { runs.add(emit(curBucket, curAge, it)) }
                cur = arrayListOf(a, b)
                curBucket = bucket
                curAge = age
            } else {
                open.add(b)
            }
        }
        cur?.let { runs.add(emit(curBucket, curAge, it)) }
        return runs
    }

    /** The runs as a GeoJSON FeatureCollection: LineStrings with properties `s` (speed bucket) and `a` (age band). */
    fun geoJson(runs: List<RideRun>): String {
        val sb = StringBuilder(runs.sumOf { it.points.size } * 24 + 64)
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        runs.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append("{\"type\":\"Feature\",\"properties\":{\"s\":").append(r.bucket).append(",\"a\":").append(r.age)
                .append("},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
            r.points.forEachIndexed { j, p ->
                if (j > 0) sb.append(',')
                sb.append('['); e5(sb, p.lonE5); sb.append(','); e5(sb, p.latE5); sb.append(']')
            }
            sb.append("]}}")
        }
        return sb.append("]}").toString()
    }

    private fun e5(sb: StringBuilder, v: Int) {
        if (v < 0) sb.append('-')
        val a = abs(v)
        sb.append(a / 100_000).append('.').append((a % 100_000).toString().padStart(5, '0'))
    }
}
