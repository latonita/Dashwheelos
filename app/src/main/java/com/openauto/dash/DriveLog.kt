package com.openauto.dash

import android.content.Context
import com.openauto.dash.link.DriveReport
import com.openauto.dash.link.DriveSummaries
import com.openauto.dash.link.DriveSummary
import com.openauto.dash.link.DriveSync
import com.openauto.dash.link.LinkMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

/*
 * The drive log: each drive as the trip computer counted it (distance, time,
 * average and top speed) with the eco-driving card's verdict on it, kept on
 * the head unit and handed to the linked phone, where the companion app shows
 * them.
 *
 * A drive is the trip computer's trip. It ends by itself once the car has
 * stood still for ten minutes, or as soon as the eco-driving card closes its
 * drive because the engine was switched off (OBD), or when the driver resets
 * the trip: it is logged (if it went anywhere) and a fresh trip starts. The
 * trip under way is saved every half minute, so a unit switched off mid-drive
 * carries it on when it comes back within those ten minutes. The drive under
 * way is also reported to the phone as it goes, because the key turned off
 * cuts the link long before the drive is closed.
 */

/** The rules, pure so they're unit-tested. */
internal object DriveLogRules {
    /** Standing still this long ends the drive. */
    const val STOP_MS = 10 * 60_000L
    /** Under this, a shuffle in the car park, not a drive. */
    const val MIN_KM = 0.5
    const val MAX_ITEMS = 50

    /** The car hasn't moved for [STOP_MS]. */
    fun isOver(trip: TripState, now: Long): Boolean = now - trip.updatedAt >= STOP_MS

    fun isWorthLogging(trip: TripState): Boolean = trip.distanceM >= MIN_KM * 1000

    /**
     * The car moves again after standing still: a trip that went nowhere
     * starts afresh from now, so it says when the drive began. While parked it
     * is left alone rather than restarted every ten minutes.
     */
    fun startsAfresh(trip: TripState, now: Long): Boolean = !isWorthLogging(trip) && isOver(trip, now)

    /**
     * The eco-driving card just closed [ended]: the trip is over too when the
     * engine really was switched off (its readings went on until the end; an
     * adapter that dropped out mid-drive is not the end of the drive) and
     * [ended] was this trip's, not one left over from before the unit slept.
     */
    fun endsTrip(ended: Drive, trip: TripState, now: Long): Boolean =
        now - ended.lastAt < CareRules.GAP_MS && ended.lastAt >= trip.startedAt

    /** The eco-driving card's drive that covers [trip]: the one under way or the last one, when their times overlap. */
    fun ecoFor(trip: TripState, care: CareState): Drive? =
        listOfNotNull(care.drive, care.lastDrive).firstOrNull { it.startedAt <= trip.updatedAt && it.lastAt >= trip.startedAt }

    /**
     * [trip] as logged and sent, with [eco]'s figures and the fuel it took at
     * [use] L/100 km and [price] a litre: the car's measured figures from its
     * refuels ([FuelLog]) when known, else [car]'s usual ones.
     */
    fun summary(
        trip: TripState, eco: Drive?, car: CarProfile, ongoing: Boolean,
        use: Double = car.typicalUse, price: Double = car.fuelPrice,
        endLat: Double? = null, endLng: Double? = null
    ): DriveSummary {
        val km = trip.distanceM / 1000.0
        val liters = km * use / 100
        return DriveSummary(
            startedAt = trip.startedAt,
            endedAt = trip.updatedAt,
            distanceKm = km,
            movingMs = trip.movingMs,
            maxSpeedKmh = trip.maxSpeedKmh.roundToInt(),
            ecoScore = eco?.ecoScore,
            sweetPercent = eco?.sweetPercent,
            hardAccel = eco?.hardAccel ?: 0,
            hardBrake = eco?.hardBrake ?: 0,
            clutchHolds = eco?.clutchHolds ?: 0,
            fuelLiters = liters,
            fuelCost = liters * price,
            currency = car.currency,
            ongoing = ongoing,
            endLat = endLat,
            endLng = endLng
        )
    }
}

/** The live side: follows the trip computer and the eco-driving card, keeps the log and reports to the phone. */
internal object DriveLog {
    private const val PREFS = "drive_log"
    private const val TICK_MS = 30_000L

    // Everything here runs on the main thread, where the GPS feed and the tiles live.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var appContext: Context? = null
    private var gps = false
    /** The trip being followed, to notice the driver's reset (a new start time). */
    private var followed: TripState? = null
    /** The last movement saved, so a trip is written only when it moved. */
    private var savedAt = 0L

    private val _drives = MutableStateFlow<List<DriveSummary>>(emptyList())
    /** The drives logged, newest first. */
    val drives: StateFlow<List<DriveSummary>> = _drives.asStateFlow()

    private val _current = MutableStateFlow<DriveSummary?>(null)
    /** The drive under way as last reported; null until it has gone far enough to count. */
    val current: StateFlow<DriveSummary?> = _current.asStateFlow()

    /** Each report for the phone: the drive under way as it goes, and each drive as it ends. */
    private val reports = MutableSharedFlow<DriveSummary>(extraBufferCapacity = 16)

    /** Starts once for the process, after [CarCare] and [CarProfileStore] have their context; main thread. */
    fun start(context: Context) {
        val app = context.applicationContext
        if (appContext == null) {
            appContext = app
            RideTrail.restore(app)
            val p = prefs(app)
            _drives.value = p.getString("drives", null)?.let { DriveSummaries.decode(it) }.orEmpty()
            // The trip under way when the unit was last switched off: carried on
            // when that was moments ago (a reboot mid-drive), logged otherwise.
            val saved = p.getString("trip", null)?.let { runCatching { tripFrom(JSONObject(it)) }.getOrNull() }
            if (saved != null) {
                if (DriveLogRules.isOver(saved, System.currentTimeMillis())) {
                    if (DriveLogRules.isWorthLogging(saved)) log(summary(saved, ongoing = false))
                    p.edit().remove("trip").apply()
                } else {
                    LocationFeed.restoreTrip(saved)
                }
            }
            scope.launch { followTrip() }
            scope.launch { followEngine() }
            scope.launch { tick() }
        }
        // The GPS runs for the whole process, as it does while the trip tile is
        // on the dashboard; without the location permission yet, the tick tries again.
        if (!gps) gps = LocationFeed.acquire(app)
    }

    /** Runs for as long as the link is up (cancel it when the link ends), handing each message to [send]. */
    suspend fun report(send: (LinkMessage) -> Unit) {
        val known = listOfNotNull(_current.value) + _drives.value
        if (known.isNotEmpty()) send(DriveSync.of(known))
        reports.collect { send(DriveReport(it)) }
    }

    /** The driver reset the trip computer: the trip it showed is over. */
    private suspend fun followTrip() {
        LocationFeed.trip.collect { trip ->
            if (DemoMode.isOn) return@collect
            val before = followed
            followed = trip
            if (before != null && before.startedAt != trip.startedAt) close(before, restart = false)
        }
    }

    /** The eco-driving card closed its drive: with the engine switched off, the trip is over too. */
    private suspend fun followEngine() {
        var previous: Drive? = null
        CarCare.state.collect { care ->
            if (DemoMode.isOn) return@collect
            val before = previous
            previous = care.drive
            if (before == null || (care.drive != null && care.drive.startedAt == before.startedAt)) return@collect
            val ended = care.lastDrive?.takeIf { it.startedAt == before.startedAt } ?: before
            val trip = LocationFeed.trip.value
            if (DriveLogRules.endsTrip(ended, trip, System.currentTimeMillis())) close(trip, restart = true)
        }
    }

    /** Every half minute: saves the trip as it moves and reports it, or ends it once the car has stood still long enough. */
    private suspend fun tick() {
        while (true) {
            delay(TICK_MS)
            if (DemoMode.isOn) continue
            val app = appContext ?: continue
            if (!gps) gps = LocationFeed.acquire(app)
            val trip = LocationFeed.trip.value
            if (DriveLogRules.isOver(trip, System.currentTimeMillis())) {
                // An empty trip waits for the car to move ([DriveLogRules.startsAfresh]).
                if (DriveLogRules.isWorthLogging(trip)) close(trip, restart = true)
            } else if (trip.updatedAt != savedAt) {
                save(app, trip)
                if (DriveLogRules.isWorthLogging(trip)) {
                    val report = summary(trip, ongoing = true)
                    _current.value = report
                    reports.tryEmit(report)
                }
            }
        }
    }

    /** Ends [trip]: logged if it went anywhere, and told to the phone; then a fresh trip unless one has already replaced it. */
    private fun close(trip: TripState, restart: Boolean) {
        val app = appContext ?: return
        if (DriveLogRules.isWorthLogging(trip)) {
            val done = summary(trip, ongoing = false)
            log(done)
            reports.tryEmit(done)
        }
        RideTrail.flush(force = true)
        _current.value = null
        savedAt = 0L
        prefs(app).edit().remove("trip").apply()
        if (restart) {
            followed = null
            LocationFeed.resetTrip()
        }
    }

    private fun summary(trip: TripState, ongoing: Boolean): DriveSummary {
        val car = CarProfileStore.current
        // Where the car is when the drive closes: the commute history tells home and work by it.
        val here = if (ongoing) null else LocationFeed.location.value
        return DriveLogRules.summary(
            trip, DriveLogRules.ecoFor(trip, CarCare.state.value), car, ongoing,
            use = FuelLog.litersPer100() ?: car.typicalUse,
            price = FuelLog.lastPrice() ?: car.fuelPrice,
            endLat = here?.latitude, endLng = here?.longitude
        )
    }

    private fun log(drive: DriveSummary) {
        val app = appContext ?: return
        RideTrail.archive(drive.startedAt)
        _drives.value = DriveSummaries.merge(_drives.value, drive, DriveLogRules.MAX_ITEMS)
        prefs(app).edit().putString("drives", DriveSummaries.encode(_drives.value)).apply()
    }

    private fun save(app: Context, trip: TripState) {
        savedAt = trip.updatedAt
        prefs(app).edit().putString("trip", tripJson(trip).toString()).apply()
    }

    private fun tripJson(t: TripState) = JSONObject()
        .put("started", t.startedAt).put("updated", t.updatedAt).put("m", t.distanceM)
        .put("moving", t.movingMs).put("max", t.maxSpeedKmh.toDouble())

    private fun tripFrom(o: JSONObject) = TripState(
        startedAt = o.getLong("started"), distanceM = o.getDouble("m"), movingMs = o.getLong("moving"),
        maxSpeedKmh = o.getDouble("max").toFloat(), updatedAt = o.getLong("updated")
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
