package com.openauto.dash

/**
 * The height of the road over the last [CAP_M] of driving, one point per [STEP_M], newest last.
 * Pure logic (no Android types) behind [ElevationRecorder]; see ElevationTrackTest.
 *
 * Distance, not time, is the axis: a stop adds no points, and a slow climb is as long on the chart as a fast one.
 * GPS altitude is noisy (several metres from fix to fix), so each stored point is the median of the last
 * [WINDOW] fixes rather than a single reading.
 */
internal class ElevationTrack(points: List<Float> = emptyList(), private var sinceM: Double = 0.0) {
    private val ring = ArrayDeque<Float>().apply { addAll(points.takeLast(MAX_POINTS)) }
    private val window = ArrayDeque<Float>()

    /** The stored heights in metres, oldest first. */
    val points: List<Float> get() = ring.toList()

    /** How far the stored points reach, in metres. */
    val lengthM: Double get() = ring.size * STEP_M

    /**
     * One more fix: the car moved [distM] metres since the last counted one, to a height of [altM].
     * Returns true when a point was stored. A step under [MIN_STEP_M] is a parked car's drift and
     * counts for nothing; one over [GAP_M] (a tunnel, a lost fix) stores a single point rather than
     * a straight line of them.
     */
    fun add(distM: Double, altM: Double): Boolean {
        if (distM < MIN_STEP_M || altM.isNaN() || altM.isInfinite()) return false
        window.addLast(altM.toFloat())
        if (window.size > WINDOW) window.removeFirst()
        sinceM += distM
        if (distM > GAP_M) {
            sinceM = 0.0
            push(median())
            return true
        }
        var stored = false
        while (sinceM >= STEP_M) {
            sinceM -= STEP_M
            push(median())
            stored = true
        }
        return stored
    }

    private fun push(v: Float) {
        ring.addLast(v)
        while (ring.size > MAX_POINTS) ring.removeFirst()
    }

    private fun median(): Float = window.sorted().let { it[it.size / 2] }

    /** "v1;metres since the last point;decimetres,decimetres,..." for [fromString]. */
    fun encode(): String = "v1;" + "%.1f".format(java.util.Locale.ROOT, sinceM) + ";" +
        ring.joinToString(",") { Math.round(it * 10f).toString() }

    companion object {
        const val STEP_M = 100.0
        const val CAP_M = 40_000.0
        const val MAX_POINTS = (CAP_M / STEP_M).toInt()
        const val MIN_STEP_M = 2.0
        const val GAP_M = 500.0
        const val WINDOW = 5

        /** The track [encode] wrote, or an empty one when [s] is missing or damaged. */
        fun fromString(s: String?): ElevationTrack {
            val parts = s?.split(';') ?: return ElevationTrack()
            if (parts.size != 3 || parts[0] != "v1") return ElevationTrack()
            val since = parts[1].toDoubleOrNull() ?: return ElevationTrack()
            val pts = if (parts[2].isEmpty()) emptyList() else parts[2].split(',').map { (it.toIntOrNull() ?: return ElevationTrack()) / 10f }
            return ElevationTrack(pts, since.coerceIn(0.0, STEP_M))
        }
    }
}
