package com.openauto.dash

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.openauto.dash.link.ConversationLine
import com.openauto.dash.link.PhoneNotification
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Source
import com.openauto.dash.vehicle.SourceKind
import com.openauto.dash.vehicle.Vehicle
import com.openauto.dash.vehicle.reversingNow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import java.util.Base64
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Live data sources shared by the dashboard widgets. Each feed is a process
 * singleton that composables acquire / release, so one GPS or sensor
 * subscription serves every tile that needs it and stops when none do.
 */

internal fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * Running totals since the last reset, fed by GPS fixes. Saved by [DriveLog],
 * which also ends a trip by itself once the car has stood still a while, so
 * each drive is logged and sent to the linked phone.
 */
data class TripState(
    val startedAt: Long = System.currentTimeMillis(),
    val distanceM: Double = 0.0,
    val movingMs: Long = 0L,
    val maxSpeedKmh: Float = 0f,
    /** When distance was last added: the car last moved. */
    val updatedAt: Long = startedAt
) {
    val elapsedMs: Long get() = System.currentTimeMillis() - startedAt
    val avgSpeedKmh: Double get() = if (movingMs > 0L) (distanceM / 1000.0) / (movingMs / 3_600_000.0) else 0.0
}

/** GPS position plus the trip computer that accumulates from it. */
object LocationFeed {
    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location

    /**
     * Whether there is a [location] at all. It moves with the first fix (or the
     * demo), not with each fix, so a tile that only says "waiting for GPS"
     * doesn't redraw every second.
     */
    private val _hasFix = MutableStateFlow(false)
    val hasFix: StateFlow<Boolean> = _hasFix

    /**
     * [location], passed on only when the car leaves its cell of 1/[perDegree]
     * degree (of each of them when several are given): the position a weather
     * or fuel lookup keyed on that rounding would use, without the fixes in
     * between (one a second) that change nothing for it.
     */
    fun cell(vararg perDegree: Int): Flow<Location?> =
        location.distinctUntilChanged { old, new -> perDegree.all { sameCell(old, new, it) } }

    /** Whether [a] and [b] round to the same 1/[perDegree] degree in latitude and longitude; two missing fixes count as the same. */
    internal fun sameCell(a: Location?, b: Location?, perDegree: Int): Boolean {
        if (a == null || b == null) return a == null && b == null
        return (a.latitude * perDegree).roundToInt() == (b.latitude * perDegree).roundToInt() &&
            (a.longitude * perDegree).roundToInt() == (b.longitude * perDegree).roundToInt()
    }

    private fun setLocation(l: Location?) {
        _location.value = l
        _hasFix.value = l != null
    }

    private val _trip = MutableStateFlow(TripState())
    val trip: StateFlow<TripState> = _trip

    /**
     * Where the car points, from its GPS fixes ([HeadingTracker]): kept while
     * stopped, and across a restart of the app, so a parked car's map still
     * faces the right way at the next start.
     */
    private val _headingDeg = MutableStateFlow<Float?>(null)
    val headingDeg: StateFlow<Float?> = _headingDeg

    private val heading = HeadingTracker()
    @Volatile private var prefs: android.content.SharedPreferences? = null
    /** Where the heading restored at start was saved; checked against the first GPS fix. */
    private var restoredAt: HeadingTracker.Fix? = null
    private var headingSavedAt = 0L

    /** The GPS on the vehicle bus: its speed, raw (the driver's correction is applied where it is shown). */
    private val busPublisher = Vehicle.bus.register(Source("gps", SourceKind.GPS, "GPS"))

    /**
     * Tells the bus the speed from a fix under [FRESH_MS] old; null (stale, no fix, released) withdraws it. Unlike
     * [location] (the last known position, kept for weather or fuel prices), it goes back to null by itself when
     * fixes stop, in a tunnel or a car park.
     */
    private fun setFreshSpeed(kmh: Int?) {
        // No fix, a stale one or a released GPS: the source is down (its speed is all it says), up with the next fix.
        if (kmh != null) busPublisher.up(true)
        busPublisher.put(K.GPS_SPEED, kmh?.toDouble())
        if (kmh == null) busPublisher.up(false)
    }

    private const val FRESH_MS = 5_000L
    /** A GPS fix this recent stands against a network fix. */
    private const val GPS_KEPT_NS = 10_000_000_000L
    private val main = Handler(Looper.getMainLooper())
    private val expire = Runnable { setFreshSpeed(null) }

    private var refs = 0
    private var manager: LocationManager? = null
    private var lastFix: Location? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    /**
     * Starts the GPS for one more user. Returns false (and counts nothing)
     * without the permission; call [release] only after a true.
     */
    @SuppressLint("MissingPermission")
    fun acquire(context: Context): Boolean {
        // Only count the ref once we really register; if permission is missing
        // the next acquire (after the grant) must be allowed to try again.
        if (!hasLocationPermission(context)) return false
        val lm = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        if (refs++ > 0) return true
        manager = lm
        restoreHeading(context)
        ElevationRecorder.restore(context)
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
        }
        runCatching {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, listener, Looper.getMainLooper())
        }
        runCatching {
            (lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))?.let { setLocation(it) }
        }
        return true
    }

    fun release() {
        if (--refs > 0) return
        refs = 0
        manager?.removeUpdates(listener)
        manager = null
        ElevationRecorder.save(force = true)
        main.removeCallbacks(expire)
        setFreshSpeed(null)
    }

    fun resetTrip() {
        _trip.value = TripState()
    }

    /** The trip saved when the unit was last switched off, carried on (see [DriveLog]). */
    internal fun restoreTrip(trip: TripState) {
        _trip.value = trip
    }

    /** The heading after the GPS fix [l] ([HeadingTracker]), saved now and then for the next start. */
    private fun trackHeading(l: Location, speedKmh: Float) {
        val fix = HeadingTracker.Fix(l.latitude, l.longitude, l.bearing.takeIf { l.hasBearing() }, speedKmh, if (l.hasAccuracy()) l.accuracy else Float.MAX_VALUE)
        // The heading saved at the last switch-off holds only where the car was then.
        restoredAt?.let { at ->
            restoredAt = null
            if (HeadingTracker.distanceM(at, fix) > RESTORE_WITHIN_M) _headingDeg.value = null
        }
        val next = heading.next(_headingDeg.value, fix, reversingNow())
        if (next == _headingDeg.value) return
        _headingDeg.value = next
        val now = SystemClock.elapsedRealtime()
        if (next != null && now - headingSavedAt >= SAVE_HEADING_MS) {
            headingSavedAt = now
            prefs?.edit()?.putFloat(KEY_HEADING, next)?.putString(KEY_HEADING_AT, "${l.latitude},${l.longitude}")?.apply()
        }
    }

    /** The heading saved before, while none is known yet: the car may not have moved since. */
    private fun restoreHeading(context: Context) {
        val p = prefs ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { prefs = it }
        if (_headingDeg.value != null || !p.contains(KEY_HEADING)) return
        val at = p.getString(KEY_HEADING_AT, null)?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 2 } ?: return
        _headingDeg.value = p.getFloat(KEY_HEADING, 0f)
        restoredAt = HeadingTracker.Fix(at[0], at[1], null, 0f, 0f)
    }

    private const val PREFS = "location_feed"
    private const val KEY_HEADING = "heading"
    private const val KEY_HEADING_AT = "heading_at"
    /** A heading is saved at most this often: it changes every second on the road. */
    private const val SAVE_HEADING_MS = 5_000L
    /** The first fix farther than this from where the saved heading was taken drops it: the car was moved. */
    private const val RESTORE_WITHIN_M = 50.0

    /** [DemoMode]'s position and trip (and, when it ends, the real ones back); its speed goes through the demo source. */
    internal fun demoWrite(location: Location?, trip: TripState, heading: Float?) {
        setLocation(location)
        _trip.value = trip
        _headingDeg.value = heading
    }

    /** Publishes [l]'s speed if the fix is recent, and schedules it to expire. */
    private fun publishSpeed(l: Location?) {
        // By the time since start, on both sides: the fix's own time is the
        // satellites', and the unit's clock a few seconds off it made every
        // fix look stale (no GPS speed at all) or come from the future.
        val age = if (l == null) Long.MAX_VALUE else (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
        when (val reading = speedReading(l != null, l?.hasSpeed() == true, l?.speed ?: 0f, age)) {
            SpeedReading.Keep -> Unit
            SpeedReading.None -> {
                main.removeCallbacks(expire)
                setFreshSpeed(null)
            }
            is SpeedReading.Kmh -> {
                main.removeCallbacks(expire)
                setFreshSpeed(reading.value)
                main.postAtTime(expire, SystemClock.uptimeMillis() + (FRESH_MS - age.coerceAtLeast(0L)))
            }
        }
    }

    /** What a fix does to the GPS speed on the bus: a new value, nothing at all, or none. */
    internal sealed interface SpeedReading {
        /** The fix has no speed (a Wi-Fi / cell fix between two GPS ones): the shown speed stays. */
        data object Keep : SpeedReading
        /** No fix, or a stale one. */
        data object None : SpeedReading
        data class Kmh(val value: Int) : SpeedReading
    }

    /**
     * Pure decision behind [publishSpeed]. A network fix carries no speed and
     * used to be published as 0 km/h, so the readout blinked "0" every few
     * seconds on GPS-only driving; such a fix now leaves the speed alone.
     * Below [STILL_KMH] the car is taken as stopped: a parked car's GPS drifts
     * a few km/h (it read 1 in a garage, and the second screen 9 with the
     * speed correction added to it).
     */
    internal fun speedReading(hasFix: Boolean, hasSpeed: Boolean, speedMps: Float, ageMs: Long): SpeedReading = when {
        !hasFix || ageMs >= FRESH_MS -> SpeedReading.None
        !hasSpeed -> SpeedReading.Keep
        else -> SpeedReading.Kmh(Math.round(speedMps * 3.6f).takeIf { it >= STILL_KMH } ?: 0)
    }

    /** The GPS speed under which the car counts as stopped, as for the trip ([tripStep]). */
    internal const val STILL_KMH = 3

    private fun onFix(l: Location) {
        if (DemoMode.isOn) return
        val gps = l.provider == LocationManager.GPS_PROVIDER
        // The last GPS fix: a network one in between used to take its place, and
        // the GPS step after it was then lost to the trip (one in five or so).
        val prev = lastFix
        // A network fix is a guess from cells and Wi-Fi, hundreds of metres wide: it
        // fills in where GPS has nothing, but not over a GPS position still fresh
        // (the parking spot and the fuel stations are looked up from it).
        if (!gps && prev != null && l.elapsedRealtimeNanos - prev.elapsedRealtimeNanos < GPS_KEPT_NS) return
        setLocation(l)
        publishSpeed(l)
        val speedKmh = l.speed * 3.6f
        if (!gps) return
        trackHeading(l, speedKmh)
        ElevationRecorder.onFix(prev, l)

        // Only consecutive GPS fixes of decent accuracy count towards the trip.
        if (prev != null && l.accuracy <= 30f) {
            val d = prev.distanceTo(l).toDouble()
            val dt = (l.time - prev.time).coerceIn(0L, 60_000L)
            tripStep(_trip.value, d, dt, speedKmh, System.currentTimeMillis())?.let {
                _trip.value = it
                // The same counted step goes to the map's ride trail.
                RideTrail.onFix(it.startedAt, l, speedKmh)
            }
        }
        lastFix = l
    }

    /**
     * [trip] after a GPS step of [d] metres in [dt] ms at [speedKmh], or null
     * when the step doesn't count. Only a moving car travels: a parked unit's
     * fixes drift metres apart at a near-zero speed, and adding them gave
     * distance with no moving time (a 90 km/h average on a 6 km/h top speed)
     * and kept the trip from ever ending.
     */
    internal fun tripStep(trip: TripState, d: Double, dt: Long, speedKmh: Float, now: Long): TripState? {
        if (d < 2.0 || speedKmh <= 3f) return null
        val t = if (DriveLogRules.startsAfresh(trip, now)) TripState(startedAt = now) else trip
        return t.copy(
            distanceM = t.distanceM + d,
            movingMs = t.movingMs + dt,
            maxSpeedKmh = max(t.maxSpeedKmh, speedKmh),
            updatedAt = now
        )
    }
}

/** Lateral / longitudinal acceleration in g, from the head unit's accelerometer. */
data class GForce(
    val lateral: Float = 0f,
    val longitudinal: Float = 0f,
    val peakLateral: Float = 0f,
    val peakLongitudinal: Float = 0f
)

object GForceFeed : SensorEventListener {
    private val _g = MutableStateFlow(GForce())
    val g: StateFlow<GForce> = _g

    private var refs = 0
    private var manager: SensorManager? = null
    private val gravity = FloatArray(3)
    private var gravitySeeded = false
    private var lat = 0f
    private var lon = 0f
    private var peakLat = 0f
    private var peakLon = 0f
    private var lastPublishNs = 0L

    /** The filters run on every sample; the tiles only need ~15 frames a second. */
    private const val PUBLISH_EVERY_NS = 66_000_000L

    fun acquire(context: Context) {
        val sm = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        if (refs++ > 0) return
        manager = sm
        gravitySeeded = false
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun release() {
        if (--refs > 0) return
        refs = 0
        manager?.unregisterListener(this)
        manager = null
    }

    fun resetPeaks() {
        peakLat = 0f
        peakLon = 0f
        _g.value = _g.value.copy(peakLateral = 0f, peakLongitudinal = 0f)
    }

    /** [DemoMode]'s forces (and, when it ends, the real ones back). */
    internal fun demoWrite(g: GForce) {
        _g.value = g
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (DemoMode.isOn) return
        // Low-pass to isolate gravity, subtract it for linear acceleration.
        // Seeded from the first sample: starting from 0 read as a hard jolt.
        if (!gravitySeeded) {
            for (i in 0..2) gravity[i] = event.values[i]
            gravitySeeded = true
        }
        for (i in 0..2) gravity[i] = 0.9f * gravity[i] + 0.1f * event.values[i]
        val lx = event.values[0] - gravity[0]
        val lz = event.values[2] - gravity[2]
        // The unit is fixed in the dash with its screen facing the cabin: X runs
        // across the car (lateral) and Z points out of the screen towards the
        // rear, so forward acceleration shows up as -Z.
        lat = 0.7f * lat + 0.3f * (lx / SensorManager.GRAVITY_EARTH)
        lon = 0.7f * lon + 0.3f * (-lz / SensorManager.GRAVITY_EARTH)
        peakLat = max(peakLat, abs(lat))
        peakLon = max(peakLon, abs(lon))
        if (event.timestamp - lastPublishNs < PUBLISH_EVERY_NS) return
        lastPublishNs = event.timestamp
        _g.value = GForce(lateral = lat, longitudinal = lon, peakLateral = peakLat, peakLongitudinal = peakLon)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/** Where the car was left, so the parking widget can lead back to it. */
data class ParkingSpot(val lat: Double, val lng: Double, val savedAt: Long)

object ParkingStore {
    private const val PREFS = "parking_spot"
    private val _spot = MutableStateFlow<ParkingSpot?>(null)
    val spot: StateFlow<ParkingSpot?> = _spot
    // The driver's real spot, kept while the demo shows its own and put back when it ends.
    @Volatile private var realSpot: ParkingSpot? = null

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains("lat")) return
        realSpot = ParkingSpot(
            lat = java.lang.Double.longBitsToDouble(p.getLong("lat", 0L)),
            lng = java.lang.Double.longBitsToDouble(p.getLong("lng", 0L)),
            savedAt = p.getLong("at", 0L)
        )
        if (!DemoMode.isOn) _spot.value = realSpot
    }

    fun save(context: Context, location: Location) {
        val s = ParkingSpot(location.latitude, location.longitude, System.currentTimeMillis())
        _spot.value = s
        // A spot saved during the demo is somewhere in its made-up Paris: shown, never kept.
        if (DemoMode.isOn) return
        realSpot = s
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("lat", java.lang.Double.doubleToRawLongBits(s.lat))
            .putLong("lng", java.lang.Double.doubleToRawLongBits(s.lng))
            .putLong("at", s.savedAt)
            .apply()
    }

    fun clear(context: Context) {
        _spot.value = null
        if (DemoMode.isOn) return
        realSpot = null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** [DemoMode]'s spot, where the made-up drive set off. */
    internal fun demoWrite(spot: ParkingSpot?) {
        _spot.value = spot
    }

    /** The demo is over: the driver's own spot back. */
    internal fun endDemo() {
        _spot.value = realSpot
    }
}

/** One notification as shown by the notifications widget. */
data class NotifItem(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val icon: Bitmap?,
    val contentIntent: PendingIntent?,
    /** Came from the driver's phone over [PhoneLink] rather than from this head unit. */
    val fromPhone: Boolean = false,
    val canReply: Boolean = false,
    val canMarkRead: Boolean = false,
    /** The latest lines of a phone conversation, oldest first. */
    val messages: List<ConversationLine> = emptyList()
)

/**
 * Recent notifications from other apps, fed by the notification listener,
 * plus the driver's phone's while [PhoneLink] is connected.
 */
object NotificationFeed {
    private const val MAX = 20
    private val _items = MutableStateFlow<List<NotifItem>>(emptyList())
    val items: StateFlow<List<NotifItem>> = _items

    /**
     * The real notifications. Kept up to date while the demo shows its own,
     * so what was posted or went away meanwhile is right when it ends.
     */
    private var real: List<NotifItem> = emptyList()

    /** Applies [change] to the real list; published unless the demo is on. */
    @Synchronized
    private fun edit(change: (List<NotifItem>) -> List<NotifItem>) {
        real = change(real)
        if (!DemoMode.isOn) _items.value = real
    }

    /** [DemoMode]'s notifications. */
    internal fun demoWrite(items: List<NotifItem>) {
        _items.value = items
    }

    /** The demo is over: the real notifications back, as they are now. */
    @Synchronized
    internal fun endDemo() {
        _items.value = real
    }

    fun onPosted(context: Context, sbn: StatusBarNotification) {
        if (sbn.packageName == context.packageName) return
        if (sbn.isOngoing) return
        val n = sbn.notification ?: return
        val title = n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)
            ?: n.extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT))?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val (label, icon) = appIdentity(context, sbn.packageName)
        val item = NotifItem(sbn.key, sbn.packageName, label, title, text, sbn.postTime, icon, n.contentIntent)
        edit { items -> (listOf(item) + items.filter { it.key != sbn.key }).take(MAX) }
    }

    fun onRemoved(sbn: StatusBarNotification) {
        edit { items -> items.filter { it.key != sbn.key } }
    }

    // --- The phone's notifications (keys prefixed so they never clash with this head unit's) ---

    private const val PHONE = "phone:"

    /** The phone's own key for a phone item's [NotifItem.key]. */
    fun phoneKey(key: String): String = key.removePrefix(PHONE)

    /** Everything the phone shows right now, sent when the link comes up. */
    fun phoneSync(notifications: List<PhoneNotification>) {
        val fromPhone = notifications.map(::fromPhone)
        edit { items -> (fromPhone + items.filter { !it.fromPhone }).sortedByDescending { it.postedAt }.take(MAX) }
    }

    fun phonePosted(notification: PhoneNotification) {
        val item = fromPhone(notification)
        var fresh = false
        edit { items ->
            // A conversation's notification is posted again for each message, and for other reasons too.
            fresh = item.canReply && items.firstOrNull { it.key == item.key }?.text != item.text
            (listOf(item) + items.filter { it.key != item.key }).take(MAX)
        }
        if (fresh && !DemoMode.isOn) _arrived.tryEmit(item)
    }

    private val _arrived = MutableSharedFlow<NotifItem>(extraBufferCapacity = 8)
    /**
     * A message as it arrives on the phone (one that can be answered), for the
     * voice; not the ones already there when the link came up.
     */
    val arrived: SharedFlow<NotifItem> = _arrived

    /** The latest message from the phone still on the card, to be read out; null when there is none. */
    fun latestMessage(): NotifItem? = _items.value.filter { it.fromPhone && it.canReply }.maxByOrNull { it.postedAt }

    fun phoneRemoved(key: String) {
        edit { items -> items.filter { it.key != PHONE + key } }
    }

    /** The link ended: the phone's notifications are no longer current. */
    fun phoneClear() {
        edit { items -> items.filter { !it.fromPhone } }
    }

    private val phoneIcons = HashMap<String, Bitmap?>()

    private fun fromPhone(n: PhoneNotification): NotifItem {
        val icon = synchronized(phoneIcons) {
            phoneIcons.getOrPut(n.packageName) {
                n.iconPng?.let { png ->
                    runCatching {
                        val bytes = Base64.getDecoder().decode(png)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.getOrNull()
                }
            }
        }
        return NotifItem(
            key = PHONE + n.key, packageName = n.packageName, appLabel = n.appName,
            title = n.title, text = n.text, postedAt = n.postedAt, icon = icon, contentIntent = null,
            fromPhone = true, canReply = n.canReply, canMarkRead = n.canMarkRead, messages = n.messages
        )
    }

    // Label + icon rasterisation per package, done once: this runs on the
    // notification listener's main thread for every notification any app posts.
    private val identityCache = HashMap<String, Pair<String, Bitmap?>>()

    @Synchronized
    private fun appIdentity(context: Context, packageName: String): Pair<String, Bitmap?> =
        identityCache.getOrPut(packageName) {
            val pm = context.packageManager
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }
                .getOrDefault(packageName)
            val icon = runCatching { pm.getApplicationIcon(packageName).toBitmap(96, 96) }.getOrNull()
            label to icon
        }

    /** Clears the card; during the demo only the demo's items go, the real ones come back when it ends. */
    @Synchronized
    fun dismissAll() {
        if (DemoMode.isOn) _items.value = emptyList() else edit { emptyList() }
    }

    /** Takes one item off the card (a phone message answered or dismissed from the car). */
    @Synchronized
    fun remove(key: String) {
        if (DemoMode.isOn) _items.update { items -> items.filter { it.key != key } }
        edit { items -> items.filter { it.key != key } }
    }
}
