package com.openauto.dash

import kotlin.math.cos
import kotlin.math.hypot

/** A drawn position and heading: degrees, the heading in [0, 360). */
internal data class FollowPoint(val lat: Double, val lon: Double, val bearing: Double)

/**
 * The map draws the car one GPS interval late and glides to each new fix at a constant speed,
 * so the follow camera and the puck never wait for the next fix (a 1 Hz fix made them move in
 * a burst and then freeze). Nothing is extrapolated: a late fix leaves the car at the last one.
 * Pure arithmetic, no Android: [MapLibrePanel] feeds it and reads [FollowTrack.at] on a timer.
 */
internal object SmoothFollow {
    const val DEFAULT_INTERVAL_MS = 1000.0
    const val MIN_INTERVAL_MS = 500.0
    const val MAX_INTERVAL_MS = 2000.0

    /** No fix for this long: the next one is shown at once, not glided to. */
    const val GAP_SNAP_MS = 3000L

    /** A fix this far from the one drawn is a jump (a tunnel, a first fix), not driving. */
    const val JUMP_SNAP_M = 150.0

    /** Under this GPS speed the car counts as stopped: its heading is held and its position snaps. */
    const val STILL_MPS = 1.0

    private const val SMOOTHING = 0.3
    private const val M_PER_DEG = 111_320.0

    fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

    /** Heading [a] to [b] along the shorter arc (359 to 1 turns 2 degrees, not 358), result in [0, 360). */
    fun lerpBearing(a: Double, b: Double, t: Double): Double {
        val delta = ((b - a) % 360.0 + 540.0) % 360.0 - 180.0
        return ((a + delta * t) % 360.0 + 360.0) % 360.0
    }

    fun clampInterval(ms: Double): Double = ms.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)

    /** The moving average of the fix interval after a fix [gapMs] after the last one. */
    fun nextInterval(average: Double, gapMs: Long): Double =
        clampInterval(average + (gapMs - average) * SMOOTHING)

    /** Metres from [a] to a point (flat-earth, good for the short steps here). */
    fun distanceM(a: FollowPoint, lat: Double, lon: Double): Double =
        hypot((lat - a.lat) * M_PER_DEG, (lon - a.lon) * M_PER_DEG * cos(Math.toRadians(a.lat)))

    fun shouldSnap(gapMs: Long, jumpM: Double, speedMps: Double): Boolean =
        gapMs > GAP_SNAP_MS || jumpM > JUMP_SNAP_M || speedMps < STILL_MPS

    fun glide(from: FollowPoint, to: FollowPoint, t: Double): FollowPoint {
        val f = t.coerceIn(0.0, 1.0)
        return FollowPoint(lerp(from.lat, to.lat, f), lerp(from.lon, to.lon, f), lerpBearing(from.bearing, to.bearing, f))
    }
}

/** The glide itself: [feed] each real fix, read [at] for what to draw now. Main thread only. */
internal class FollowTrack {
    private var from: FollowPoint? = null
    private var to: FollowPoint? = null
    private var startMs = 0L
    private var durationMs = SmoothFollow.DEFAULT_INTERVAL_MS
    private var lastFixMs = 0L
    private var average = SmoothFollow.DEFAULT_INTERVAL_MS

    /** The point to draw at [nowMs]; null before the first fix. */
    fun at(nowMs: Long): FollowPoint? {
        val a = from ?: return null
        val b = to ?: return null
        return SmoothFollow.glide(a, b, (nowMs - startMs) / durationMs)
    }

    /** True once the glide has reached the last fix: nothing to draw until the next one. */
    fun done(nowMs: Long): Boolean = from == null || nowMs - startMs >= durationMs

    /** A real fix at [nowMs]: speed in m/s, [course] the GPS bearing or null when it has none. */
    fun feed(lat: Double, lon: Double, course: Double?, speedMps: Double, nowMs: Long) {
        val shown = at(nowMs)
        val gap = if (lastFixMs == 0L) Long.MAX_VALUE else nowMs - lastFixMs
        lastFixMs = nowMs
        // Stopped, or a fix without a course: the shown heading stays.
        val held = shown?.bearing ?: course ?: 0.0
        val bearing = if (course == null || speedMps < SmoothFollow.STILL_MPS) held else course
        val target = FollowPoint(lat, lon, bearing)
        if (shown == null || SmoothFollow.shouldSnap(gap, SmoothFollow.distanceM(shown, lat, lon), speedMps)) {
            from = target; to = target; startMs = nowMs - durationMs.toLong()
            return
        }
        average = SmoothFollow.nextInterval(average, gap)
        from = shown; to = target; startMs = nowMs; durationMs = average
    }
}
