package com.openauto.dash

import android.content.Context
import android.location.Location
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Records the road's height for the GPS tile's Elevation design ([ElevationTrack]). It is fed by
 * [LocationFeed] from every GPS fix, so it keeps recording while no tile is on screen, and its
 * track is saved to a private preference (the unit kills apps at ACC off, a trip resumes the same chart).
 *
 * The height is [Location.getAltitude]: on Android 8.1 (API 27) that is the WGS84 ellipsoid height, not
 * the height above sea level (there is no MSL API before API 34), so it can sit tens of metres off a map's.
 * It is shown as it is; the shape of the climb is right either way.
 */
object ElevationRecorder {
    private val _profile = MutableStateFlow<List<Float>>(emptyList())

    /** The recorded heights in metres, oldest first; changes once per 100 m driven. */
    val profile: StateFlow<List<Float>> = _profile

    private val _zoomKm = MutableStateFlow(ElevationZoom.DEFAULT_KM)

    /** How many of the newest kilometres the chart shows ([ElevationZoom]); kept across restarts. */
    val zoomKm: StateFlow<Int> = _zoomKm

    /** The tap on the chart: the next zoom, saved. */
    fun cycleZoom() {
        _zoomKm.value = ElevationZoom.next(_zoomKm.value)
        prefs?.edit()?.putInt(KEY_ZOOM, _zoomKm.value)?.apply()
    }

    @Volatile private var prefs: android.content.SharedPreferences? = null
    private var current = ElevationTrack()
    private var dirty = false
    private var savedAt = 0L

    /** Loads the saved track, once. Main thread. */
    internal fun restore(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        current = ElevationTrack.fromString(p.getString(KEY, null))
        _profile.value = current.points
        _zoomKm.value = ElevationZoom.valid(p.getInt(KEY_ZOOM, ElevationZoom.DEFAULT_KM))
    }

    /** A GPS fix [l] after [prev], the one before it. */
    internal fun onFix(prev: Location?, l: Location) {
        if (prev == null || !l.hasAltitude() || !l.hasAccuracy() || l.accuracy > MAX_ACCURACY_M) return
        if (l.hasVerticalAccuracy() && l.verticalAccuracyMeters > MAX_VERTICAL_M) return
        // A parked car's fixes drift metres apart at a near-zero speed.
        if (l.hasSpeed() && l.speed * 3.6f <= LocationFeed.STILL_KMH) return
        if (current.add(prev.distanceTo(l).toDouble(), l.altitude)) {
            _profile.value = current.points
            dirty = true
        }
        save(force = false)
    }

    /** Writes the track when it changed: at most every [SAVE_MS] unless [force]. */
    internal fun save(force: Boolean) {
        val p = prefs ?: return
        val now = SystemClock.elapsedRealtime()
        if (!dirty || (!force && now - savedAt < SAVE_MS)) return
        savedAt = now
        dirty = false
        p.edit().putString(KEY, current.encode()).apply()
    }

    private const val PREFS = "elevation_track"
    private const val KEY = "track"
    private const val KEY_ZOOM = "zoom_km"
    private const val MAX_ACCURACY_M = 30f
    private const val MAX_VERTICAL_M = 30f
    private const val SAVE_MS = 30_000L
}
