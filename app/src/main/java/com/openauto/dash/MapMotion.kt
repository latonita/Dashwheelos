package com.openauto.dash

/**
 * Whether the car stands still, for the map: GPS bearing is noise at zero
 * speed, so a stationary map must stop following it. Stationary below
 * [STOP_KMH] held for [HOLD_MS]; moving again from [GO_KMH] up. Starts out
 * "moving" (the map's normal heading-follow) until a stop is proven.
 */
internal class StationaryDetector {
    var stationary = false
        private set
    private var slowSince = -1L

    /** Feeds one speed reading taken at [nowMs]; returns [stationary]. */
    fun update(nowMs: Long, speedKmh: Float): Boolean {
        when {
            speedKmh >= GO_KMH -> {
                stationary = false
                slowSince = -1L
            }
            speedKmh < STOP_KMH -> {
                if (slowSince < 0) slowSince = nowMs
                if (nowMs - slowSince >= HOLD_MS) stationary = true
            }
            // Between the two: keep the state, and a started hold goes on.
        }
        return stationary
    }

    companion object {
        const val STOP_KMH = 3f
        const val GO_KMH = 6f
        const val HOLD_MS = 2_000L

        /** Speed in km/h of [l]: its own when it has one, else from the move since [prev]; null when neither. */
        fun speedKmh(l: android.location.Location, prev: android.location.Location?): Float? {
            if (l.hasSpeed()) return l.speed * 3.6f
            if (prev == null) return null
            val dt = (l.time - prev.time) / 1000f
            if (dt <= 0f) return null
            return l.distanceTo(prev) / dt * 3.6f
        }
    }
}
