package com.openauto.dash

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import com.openauto.dash.link.DriveSummary
import com.openauto.dash.link.RecentCall
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Key
import com.openauto.dash.vehicle.Source
import com.openauto.dash.vehicle.SourceKind
import com.openauto.dash.vehicle.Vehicle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Demo mode: a made-up drive through Paris that feeds every live source the
 * tiles read (the car's data, through the vehicle bus as the `demo` source; fault codes, GPS position
 * and the trip computer, g-force, weather, turn-by-turn, music, notifications, the
 * phone's favourites, calls and agenda, fuel prices, the parking spot, the particle filter's soot load and the
 * car-care stats), so the whole dashboard can be shown off parked and
 * without an adapter. Started from Settings → Advanced.
 *
 * Whatever the bus carries is put by the demo source alone: it outranks every real one, so the real car's
 * values can keep arriving without showing, and nothing of the demo is left behind when it ends. What is
 * not on the bus (the position and the trip, g-force, fault codes and the mechanic's state, the car-care
 * stats, the PSA trip computer, the weather's picture, navigation, music, notifications, the phone) is
 * written to its own feed, which holds its real state back (each checks [isOn]) and puts it back when the
 * demo stops. Nothing is saved and nothing is spoken. It lives in memory only: a restart is always back on
 * real data.
 */
object DemoMode {

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()
    val isOn: Boolean get() = _active.value

    /** The fake music player's track, shown in place of the real media session. */
    private val _media = MutableStateFlow(MediaState())
    val media: StateFlow<MediaState> = _media.asStateFlow()

    private const val TICK_MS = 250L

    /**
     * The demo on the vehicle bus, for every value the bus carries: the speed, the gauges, the body, the fuel, the
     * tyres and the temperatures. A DEMO source outranks every real one, so the real car's values can keep arriving
     * without showing. It is up while the demo runs and, when it ends, cleared and down (it stays registered for
     * the next one).
     */
    private val busPublisher = Vehicle.bus.register(Source("demo", SourceKind.DEMO, "Demo"))
    /** The car's "left rear brake bulb out" message (PsaWarnings). */
    private const val DEMO_BULB = 0x84
    /** Place de la Concorde: where the fake drive sets off. */
    private const val START_LAT = 48.8656
    private const val START_LNG = 2.3212

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var before: Snapshot? = null
    /** What a fault-code scan finds; Clear empties it. */
    private var codes = listOf("P0401")
    /** The mechanic's advice on [codes], built when the demo starts. */
    private var diagnosis: Diagnosis? = null
    /** The language [diagnosis] is written in. */
    private var diagnosisLanguage: AiLanguage? = null

    fun toggle(context: Context) = if (isOn) stop() else start(context)

    fun start(context: Context) {
        if (isOn) return
        val app = context.applicationContext
        before = Snapshot.take()
        codes = listOf("P0401")
        diagnosisLanguage = AiSettings.load(app).language
        diagnosis = demoDiagnosis(app)
        player.reset()
        _active.value = true
        busPublisher.up(true)
        FuelPriceRepo.demoWrite(DEMO_STATIONS, null)
        val sim = Simulation(System.currentTimeMillis())
        // Where the car stood before the drive: the parking tile points back to it.
        ParkingStore.demoWrite(ParkingSpot(START_LAT, START_LNG, sim.startedAt - 38 * 60_000L))
        PhoneLink.demoWrite(demoPhoneLists(app, sim.startedAt))
        job = scope.launch {
            var tick = 0
            while (isActive) {
                sim.step(app, System.currentTimeMillis(), gps = tick % 4 == 0)
                player.tick()
                _media.value = player.state()
                tick++
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        if (!isOn) return
        job?.cancel()
        job = null
        // Off first: a real event from here on publishes itself, and the feeds
        // that kept their real state up to date meanwhile hand it back below.
        _active.value = false
        busPublisher.clear()
        busPublisher.up(false)
        before?.restore()
        before = null
        _media.value = MediaState()
    }

    /** The language changed during the demo: its advice is written again in [language]. */
    internal fun followLanguage(context: Context, language: AiLanguage) {
        if (!isOn || diagnosisLanguage == language) return
        diagnosisLanguage = language
        diagnosis = demoDiagnosis(context.applicationContext)
        AiMechanic.demoWrite(mechanicState(codes))
    }

    /**
     * The Now card's servicing line in the demo: an oil change 640 km away.
     * Read by the card alone, so the real plan and log stay untouched.
     */
    internal fun upkeepSoon() = UpkeepDue(UpkeepKind.OIL, kmLeft = 640, daysLeft = 41, stage = UpkeepStage.SOON)

    /** The fault-code tile's state for [codes]: the advice comes with them, as after a real scan. */
    internal fun mechanicState(codes: List<String>) =
        AiMechanic.State.ofCodes(codes, diagnosis?.takeIf { codes.isNotEmpty() })

    /**
     * The trip computer's count since the refuel: a fill-up three days before
     * the demo's [trip], 386 km since, going up as the fake drive goes on.
     * Worked out where it is shown, so nothing of it reaches the real count.
     */
    internal fun sinceFill(trip: TripState) = SinceFillState(
        fillAt = trip.startedAt - 3 * 86_400_000L,
        distanceM = DEMO_SINCE_FILL_M + trip.distanceM,
        movingMs = DEMO_SINCE_FILL_MOVING_MS + trip.movingMs
    )

    /**
     * The drive history's last week before [now], newest first: the commute
     * on weekdays and a longer outing at the weekend, at [car]'s usual
     * consumption and price. Shown in place of the log, never written to it,
     * so it never reaches the phone.
     */
    internal fun drives(now: Long, car: CarProfile): List<DriveSummary> {
        val day = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        // Days back, start time (minutes after midnight), km, minutes moving, top speed, eco score.
        val plan = listOf(
            DemoDrive(1, 18 * 60 + 12, 21.8, 27, 94, 81), DemoDrive(1, 8 * 60 + 5, 23.4, 31, 92, 74),
            DemoDrive(2, 17 * 60 + 48, 22.1, 29, 90, 79), DemoDrive(2, 7 * 60 + 58, 23.0, 33, 91, 70),
            DemoDrive(3, 15 * 60 + 30, 6.2, 14, 55, 88), DemoDrive(4, 10 * 60 + 20, 118.5, 84, 131, 76),
            DemoDrive(5, 18 * 60 + 2, 22.6, 30, 93, 77), DemoDrive(5, 8 * 60 + 11, 23.2, 32, 92, 72)
        )
        return plan.map { d ->
            val start = day - d.daysBack * 86_400_000L + d.startMin * 60_000L
            val moving = d.minutes * 60_000L
            val trip = TripState(startedAt = start, distanceM = d.km * 1000, movingMs = moving, maxSpeedKmh = d.topKmh.toFloat(), updatedAt = start + moving + 4 * 60_000L)
            DriveLogRules.summary(trip, null, car, ongoing = false).copy(ecoScore = d.eco)
        }.sortedByDescending { it.startedAt }
    }

    private class DemoDrive(val daysBack: Int, val startMin: Int, val km: Double, val minutes: Int, val topKmh: Int, val eco: Int)

    /**
     * The mechanic's advice on the demo's P0401 (EGR flow too low, a classic
     * on this diesel), in the mechanic's language like real advice, so the
     * tile shows what a scan leads to instead of a bare code.
     */
    private fun demoDiagnosis(context: Context): Diagnosis {
        val r = AiSettings.load(context).language.resources(context)
        return Diagnosis(
            severity = Severity.SOON,
            summary = r.getString(R.string.demo_p0401_summary),
            codes = listOf(
                CodeAdvice(
                    code = "P0401",
                    meaning = r.getString(R.string.demo_p0401_meaning),
                    causes = listOf(
                        r.getString(R.string.demo_p0401_cause_1),
                        r.getString(R.string.demo_p0401_cause_2),
                        r.getString(R.string.demo_p0401_cause_3)
                    ),
                    checkFirst = r.getString(R.string.demo_p0401_check_first),
                    explanation = r.getString(R.string.demo_p0401_explanation),
                    repair = r.getString(R.string.demo_p0401_repair),
                    cost = r.getString(R.string.demo_p0401_cost),
                    driving = r.getString(R.string.demo_p0401_driving)
                )
            )
        )
    }

    /** The demo's answer to a fault-code scan. */
    internal suspend fun scanCodes(): Result<List<String>> {
        delay(1_200)
        return Result.success(codes)
    }

    /** The demo's answer to Clear: the codes are gone until the next demo. */
    internal suspend fun clearCodes(): Result<Unit> {
        delay(800)
        codes = emptyList()
        return Result.success(Unit)
    }

    // --- Music -----------------------------------------------------------------

    internal fun playPause() = player.playPause()
    internal fun next() = player.skip(1)
    internal fun previous() = player.skip(-1)
    internal fun positionMs(): Long = player.positionMs()

    private val player = Player()

    /** Made-up tracks, each with its own gradient cover. */
    private class Track(val title: String, val artist: String, val durationMs: Long, val from: Int, val to: Int)

    private class Player {
        private val tracks = listOf(
            Track("Neon Boulevard", "Midnight Transit", 204_000, 0xFFFF4D8D.toInt(), 0xFF3A1C71.toInt()),
            Track("Coastal Highway", "The Overpass", 245_000, 0xFF00C9A7.toInt(), 0xFF1E3C72.toInt()),
            Track("Afterglow", "Luna Park", 228_000, 0xFFFFB347.toInt(), 0xFFB0306A.toInt()),
            Track("Kilometre Zero", "Echo Valley", 176_000, 0xFF7F7FD5.toInt(), 0xFF1D2671.toInt())
        )
        private val covers = HashMap<Int, Bitmap>()
        private var index = 0
        private var playing = true
        /** Position at [markAt] (elapsed realtime), extrapolated while playing. */
        private var markPos = 0L
        private var markAt = 0L

        fun reset() {
            index = 0
            playing = true
            markPos = 47_000
            markAt = SystemClock.elapsedRealtime()
        }

        fun positionMs(): Long =
            if (playing) markPos + (SystemClock.elapsedRealtime() - markAt) else markPos

        fun playPause() {
            markPos = positionMs()
            markAt = SystemClock.elapsedRealtime()
            playing = !playing
            _media.value = state()
        }

        fun skip(by: Int) {
            index = (index + by).mod(tracks.size)
            markPos = 0
            markAt = SystemClock.elapsedRealtime()
            _media.value = state()
        }

        fun tick() {
            if (positionMs() >= tracks[index].durationMs) skip(1)
        }

        fun state(): MediaState {
            val t = tracks[index]
            return MediaState(
                title = t.title,
                artist = t.artist,
                isPlaying = playing,
                hasMedia = true,
                durationMs = t.durationMs,
                artwork = covers.getOrPut(index) { cover(t.from, t.to) }
            )
        }

        private fun cover(from: Int, to: Int): Bitmap {
            val size = 320
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val s = size.toFloat()
            c.drawRect(0f, 0f, s, s, Paint().apply { shader = LinearGradient(0f, 0f, s, s, from, to, Shader.TileMode.CLAMP) })
            c.drawCircle(s * 0.7f, s * 0.35f, s * 0.45f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(s * 0.7f, s * 0.35f, s * 0.45f, 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            })
            return bmp
        }
    }

    // --- The drive -------------------------------------------------------------

    /** One manoeuvre of the fake route: the turn, the street it leads onto, and the leg before it. */
    private enum class Turn { RIGHT, LEFT, STRAIGHT }
    private class Leg(val turn: Turn, val street: String, val metres: Double)

    private val route = listOf(
        Leg(Turn.RIGHT, "Rue de Rivoli", 850.0),
        Leg(Turn.LEFT, "Boulevard de Sébastopol", 1_200.0),
        Leg(Turn.STRAIGHT, "Boulevard Saint-Michel", 1_500.0),
        Leg(Turn.RIGHT, "Quai de la Tournelle", 900.0),
        Leg(Turn.LEFT, "Avenue des Gobelins", 1_100.0)
    )
    /** The particle filter's soot load (%) and distance since its last clean-out when the demo starts. */
    private const val DEMO_SOOT = 64.0
    private const val DEMO_KM_SINCE_REGEN = 318.0

    /** The mileage and the trip computer's trip when the demo starts. */
    private const val DEMO_ODOMETER_KM = 148_372.0
    private const val DEMO_TRIP_KM = 23.4
    /** The trip computer's count since the last fill-up when the demo starts. */
    private const val DEMO_SINCE_FILL_M = 386_400.0
    private const val DEMO_SINCE_FILL_MOVING_MS = (5 * 60 + 41) * 60_000L
    /** The tyres' pressure (kPa); the front left one starts lower and keeps losing air. */
    private const val DEMO_TYRE_KPA = 232
    private const val DEMO_LEAKY_KPA = 196
    /** Seconds per kPa the front left one loses, down to [DEMO_LEAKY_FLOOR_KPA]: low (180 kPa) about a minute in. */
    private const val DEMO_LEAK_S_PER_KPA = 4.0
    private const val DEMO_LEAKY_FLOOR_KPA = 168

    /** The rest of the way after the last listed manoeuvre. */
    private const val FINAL_METRES = 6_400.0

    /**
     * Speed (km/h) over one 150 s loop: pull away, town traffic, a fast
     * stretch, slow down and stop at a light.
     */
    private val speedKeys = listOf(
        0.0 to 0.0, 6.0 to 0.0, 18.0 to 48.0, 40.0 to 52.0, 52.0 to 50.0, 62.0 to 88.0,
        100.0 to 92.0, 112.0 to 60.0, 124.0 to 34.0, 136.0 to 30.0, 146.0 to 0.0, 150.0 to 0.0
    )

    /** Revs per km/h in each gear of the C4 Picasso's six-speed box (index 0 unused). */
    private val rpmPerKmh = listOf(0.0, 118.0, 64.0, 41.0, 30.0, 23.0, 19.0)

    private fun speedAt(t: Double): Double {
        val x = t.mod(speedKeys.last().first)
        val i = speedKeys.indexOfLast { it.first <= x }.coerceAtMost(speedKeys.size - 2)
        val (t0, v0) = speedKeys[i]
        val (t1, v1) = speedKeys[i + 1]
        val f = ((x - t0) / (t1 - t0)).coerceIn(0.0, 1.0)
        val eased = f * f * (3 - 2 * f)
        val v = v0 + (v1 - v0) * eased
        // A little life in the steady stretches.
        return if (v > 20) v + 1.5 * sin(t / 3.1) else v
    }

    private fun headingAt(t: Double): Double = (60 + 35 * sin(t / 47) + 20 * sin(t / 13)).mod(360.0)

    private class Simulation(val startedAt: Long) {
        var lat = START_LAT
        var lng = START_LNG
        var lastAt = startedAt
        var tripM = 0.0
        var movingMs = 0L
        var legIndex = 0
        var legDone = 0.0
        var peakLat = 0f
        var peakLon = 0f
        var care = demoCare(startedAt)
        // The same every tick: built once, so the feeds see nothing new.
        // Rain from 9 minutes in, for an hour: something for the journey line to show.
        val weather = Weather(18.4, 17.1, 2, 11.0, 21.0, 12.0, startedAt, rainFromMs = startedAt + 9 * 60_000L, rainUntilMs = startedAt + 70 * 60_000L)
        var notifications: List<NotifItem>? = null
        val trip0 = TripState(startedAt = startedAt - 36 * 60_000L, distanceM = 23_400.0, movingMs = 29 * 60_000L, maxSpeedKmh = 92f)
        var trip = trip0

        fun step(context: Context, now: Long, gps: Boolean) {
            val t = (now - startedAt) / 1000.0
            val dt = ((now - lastAt) / 1000.0).coerceIn(0.0, 1.0)
            lastAt = now
            val kmh = speedAt(t)
            val accel = (speedAt(t + 0.5) - speedAt(t - 0.5)) / 3.6 // m/s²
            val metres = kmh / 3.6 * dt

            // Engine and gearbox.
            val gear = (6 downTo 1).firstOrNull { kmh * rpmPerKmh[it] >= if (accel > 1.0) 1_700 else 1_300 } ?: 1
            val rpm = if (kmh < 3) 820 + 15 * sin(t * 2) else max(900.0, kmh * rpmPerKmh[gear])
            val throttle = if (accel < -0.3) 0.0 else (12 + accel * 22 + kmh * 0.08).coerceIn(0.0, 85.0)
            val load = if (accel < -0.3) 8.0 else (22 + accel * 26 + kmh * 0.2).coerceIn(5.0, 95.0)
            val coolant = 80 + 10 * (1 - exp(-t / 50)) + sin(t / 20)
            // The gauges, on the bus; the record is what the car-care rules are written against.
            val data = ObdData(
                speedKmh = kmh.roundToInt(),
                rpm = rpm.roundToInt(),
                coolantTempC = coolant.roundToInt(),
                intakeTempC = if (kmh < 5) 27 else 23,
                throttlePct = throttle.roundToInt(),
                engineLoadPct = load.roundToInt(),
                fuelLevelPct = 58,
                voltage = 14.2 + 0.05 * sin(t / 4)
            )
            publishGauges(data)
            val pending = if (codes.isEmpty()) emptySet() else codes.toSet()
            ObdBluetoothManager.demoWrite(EngineLamp(on = false, storedCodes = 0), pending)
            AiMechanic.demoWrite(mechanicState(codes))
            care = CareRules.step(care, data, now, CarProfileStore.current).first
            CarCare.demoWrite(care)

            // Where the car is, and the forces on it.
            val heading = headingAt(t)
            val rad = heading * PI / 180
            lat += metres * cos(rad) / 111_320.0
            lng += metres * sin(rad) / (111_320.0 * cos(lat * PI / 180))
            tripM += metres
            if (kmh > 3) movingMs += (dt * 1000).toLong()
            val turnRate = (headingAt(t + 0.5) - headingAt(t - 0.5)) * PI / 180
            val lateral = (kmh / 3.6 * turnRate / 9.81).toFloat().coerceIn(-0.6f, 0.6f)
            val longitudinal = (accel / 9.81).toFloat().coerceIn(-0.6f, 0.6f)
            peakLat = max(peakLat, abs(lateral))
            peakLon = max(peakLon, abs(longitudinal))
            GForceFeed.demoWrite(GForce(lateral, longitudinal, peakLat, peakLon))
            trip = trip0.copy(
                distanceM = trip0.distanceM + tripM,
                movingMs = trip0.movingMs + movingMs,
                maxSpeedKmh = max(trip.maxSpeedKmh, kmh.toFloat())
            )
            if (gps) {
                val fix = Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = lat
                    longitude = lng
                    altitude = 42.0
                    speed = (kmh / 3.6).toFloat()
                    bearing = heading.toFloat()
                    accuracy = 4f
                    time = now
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
                LocationFeed.demoWrite(fix, trip, heading.toFloat())
                // The particle filter filling up slowly, hotter as the engine works.
                PidExplorer.demoWrite(
                    mapOf(
                        ExtraReading.SOOT_LOAD to ExtraValue((DEMO_SOOT + tripM / 4_000).roundToInt().toDouble(), now),
                        ExtraReading.DPF_TEMP to ExtraValue((260 + kmh * 2.6 + load).roundToInt().toDouble(), now),
                        ExtraReading.REGEN_ACTIVE to ExtraValue(0.0, now),
                        ExtraReading.KM_SINCE_REGEN to ExtraValue((DEMO_KM_SINCE_REGEN + tripM / 1000).roundToInt().toDouble(), now)
                    )
                )
            }

            // CANbox: doors shut, fuel and range going down with the kilometres.
            val rangeKm = (612 - tripM / 1000).roundToInt()
            busPublisher.put(K.DOORS, McuReader.DoorState())
            busPublisher.put(K.FUEL_LEVEL, 58.0)
            busPublisher.put(K.RANGE, rangeKm.toDouble())
            // On in the first phase of the 24 s cycle (the Indicators tile's parking brake).
            busPublisher.put(K.HANDBRAKE, (t / 6).toInt().mod(4) == 0)
            busPublisher.put(K.DRIVER_BELT, false)
            busPublisher.put(K.ODOMETER, DEMO_ODOMETER_KM + tripM / 1000)
            // A Peugeot or Citroën's own trip computer, with a brake bulb out: Car problems shows it on the car.
            PsaTripComputer.demoWrite(
                PsaTrip(instantL100 = 5.9f, rangeKm = rangeKm, warnings = listOf(DEMO_BULB), at = android.os.SystemClock.elapsedRealtime())
            )
            // The car's body: dipped beams on, nothing at a standstill or off the throttle (the injectors cut), more the
            // harder it pulls; the trip going up.
            // Every 24 s: nothing, the left signal, the right one, a fog light, so the Indicators tile has something to show.
            val demoStep = (t / 6).toInt().mod(4)
            busPublisher.put(K.LIGHTS, listOf(CarLight.DIPPED) + if (demoStep == 3) listOf(CarLight.FRONT_FOG, CarLight.REAR_FOG) else emptyList())
            busPublisher.put(K.INDICATORS, com.openauto.dash.vehicle.Indicators(left = demoStep == 1, right = demoStep == 2))
            busPublisher.put(K.FUEL_CONSUMPTION, if (kmh < 3 || accel < -0.3) 0.0 else 3.2 + load * 0.07)
            busPublisher.put(K.TRIP_DISTANCE, DEMO_TRIP_KM + tripM / 1000)
            // Tyres near 2.3 bar and warming a little; the front left one slowly going low.
            val warm = (4 * (1 - exp(-t / 120))).roundToInt()
            val leaky = (DEMO_LEAKY_KPA - (t / DEMO_LEAK_S_PER_KPA).toInt()).coerceAtLeast(DEMO_LEAKY_FLOOR_KPA)
            val heard = SystemClock.elapsedRealtime()
            val demoTyres = mapOf(
                TyrePos.FRONT_LEFT to Tyre(leaky, 22 + warm, false, false, false, heard),
                TyrePos.FRONT_RIGHT to Tyre(DEMO_TYRE_KPA + 2, 23 + warm, false, false, false, heard),
                TyrePos.REAR_LEFT to Tyre(DEMO_TYRE_KPA - 3, 21 + warm, false, false, false, heard),
                TyrePos.REAR_RIGHT to Tyre(DEMO_TYRE_KPA, 21 + warm, false, false, false, heard)
            )
            busPublisher.put(K.TYRES, demoTyres)
            WeatherRepo.demoWrite(weather, error = null)
            // The demo's outside temperature is the weather's, whatever the car's own sensor says.
            busPublisher.put(K.OUTSIDE_TEMP, weather.tempC)
            busPublisher.put(K.FORECAST_TEMP, weather.tempC)
            // Written once: one cleared or swiped away stays gone for the rest of the demo.
            if (notifications == null) NotificationFeed.demoWrite(demoNotifications(context, startedAt).also { notifications = it })
            navigate(context, metres, now)
        }

        /** The demo's gauges on the bus, as the tiles draw them (the fuel level is put with the fuel and the range). */
        private fun publishGauges(d: ObdData) {
            busPublisher.put(K.VEHICLE_SPEED, d.speedKmh.toDouble())
            busPublisher.put(K.RPM, d.rpm.toDouble())
            busPublisher.put(K.THROTTLE, d.throttlePct.toDouble())
            busPublisher.put(K.ENGINE_LOAD, d.engineLoadPct.toDouble())
            busPublisher.put(K.COOLANT_TEMP, d.coolantTempC.toDouble())
            busPublisher.put(K.INTAKE_TEMP, d.intakeTempC.toDouble())
            busPublisher.put(K.BATTERY_VOLTS, d.voltage)
        }

        /** Counts down to the next turn along [route], then on to the one after. */
        private fun navigate(context: Context, metres: Double, now: Long) {
            legDone += metres
            if (legDone >= route[legIndex].metres) {
                legDone = 0.0
                legIndex = (legIndex + 1) % route.size
            }
            val leg = route[legIndex]
            val toTurn = leg.metres - legDone
            val toGo = toTurn + route.drop(legIndex + 1).sumOf { it.metres } + FINAL_METRES
            val minutes = max(1, (toGo / 1000 / 32 * 60).roundToInt())
            val arrival = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now + minutes * 60_000L))
            val instruction = context.getString(
                when (leg.turn) {
                    Turn.RIGHT -> R.string.demo_nav_right
                    Turn.LEFT -> R.string.demo_nav_left
                    Turn.STRAIGHT -> R.string.demo_nav_straight
                },
                leg.street
            )
            NavDirections.demoWrite(
                NavState(
                    active = true,
                    instruction = instruction,
                    distance = distanceText(toTurn),
                    eta = String.format(Locale.getDefault(), "%d min · %.1f km · %s", minutes, toGo / 1000, arrival),
                    icon = arrow(leg.turn),
                    packageName = "com.google.android.apps.maps"
                )
            )
        }
    }

    private fun distanceText(m: Double): String =
        if (m >= 1_000) String.format(Locale.getDefault(), "%.1f km", m / 1000)
        else "${(m / 50).roundToInt().coerceAtLeast(1) * 50} m"

    /** The car-care picture of a drive already 36 minutes in, with a short-trip streak behind it. */
    private fun demoCare(now: Long): CareState {
        val min = 60_000L
        return CareState(
            drive = Drive(
                startedAt = now - 36 * min, lastAt = now, lastRunningAt = now,
                runningMs = 36 * min, movingMs = 29 * min, distanceKm = 23.4,
                hotFastMs = 9 * min, sweetMs = 21 * min, overRevMs = 20_000,
                hardAccel = 1, hardBrake = 0, peakCoolant = 90, lastSpeed = 0, lastSpeedAt = now
            ),
            lastDrive = Drive(
                startedAt = now - 26 * 60 * min, lastAt = now - 26 * 60 * min + 14 * min,
                runningMs = 14 * min, movingMs = 11 * min, distanceKm = 6.8,
                sweetMs = 8 * min, hardBrake = 1, peakCoolant = 78
            ),
            filter = FilterLog(shortStreak = 2, lastLongAt = now - 4 * 24 * 60 * min),
            rest = RestTimer(drivingMs = 36 * min, lastAt = now)
        )
    }

    private fun demoNotifications(context: Context, startedAt: Long): List<NotifItem> = listOf(
        NotifItem(
            key = "demo:message", packageName = "com.google.android.apps.messaging", appLabel = "Messages",
            title = "Camille", text = context.getString(R.string.demo_notif_message),
            postedAt = startedAt - 2 * 60_000L, icon = null, contentIntent = null
        ),
        NotifItem(
            key = "demo:event", packageName = "com.google.android.calendar", appLabel = "Calendar",
            title = context.getString(R.string.demo_notif_event_title), text = context.getString(R.string.demo_notif_event_text),
            postedAt = startedAt - 25 * 60_000L, icon = null, contentIntent = null
        )
    )

    /**
     * A linked phone's Quick dial and Agenda: a few favourites, a call from
     * Camille missed just before the drive (the Call back row), and the
     * dentist's appointment its notification announces, with an address to be
     * guided to. The numbers are in the range kept for fiction.
     */
    private fun demoPhoneLists(context: Context, startedAt: Long): PhoneLists {
        val dentist = java.util.Calendar.getInstance().apply {
            timeInMillis = startedAt
            add(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 9)
            set(java.util.Calendar.MINUTE, 30)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        return PhoneLists(
            favourites = listOf(
                Favourite("Camille Martin", "+33 6 39 98 00 01", null),
                Favourite("Léo Bernard", "+33 6 39 98 00 02", null),
                Favourite("Sophie Durand", "+33 6 39 98 00 03", null),
                Favourite("Garage Moreau", "+33 1 99 00 00 04", null)
            ),
            calls = listOf(
                RecentCall("+33 6 39 98 00 01", "Camille Martin", RecentCall.Type.MISSED, startedAt - 6 * 60_000L),
                RecentCall("+33 6 39 98 00 03", "Sophie Durand", RecentCall.Type.INCOMING, startedAt - 3 * 3_600_000L)
            ),
            agenda = listOf(
                AgendaEvent(context.getString(R.string.demo_notif_event_title), dentist, dentist + 45 * 60_000L, false, "12 Rue de Rivoli, Paris")
            )
        )
    }

    private val arrows = HashMap<Turn, Bitmap>()

    /** A white manoeuvre arrow like the ones Maps posts. */
    private fun arrow(turn: Turn): Bitmap = arrows.getOrPut(turn) {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 12f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val head = Paint(paint).apply { style = Paint.Style.FILL }
        when (turn) {
            Turn.STRAIGHT -> {
                c.drawLine(48f, 84f, 48f, 30f, paint)
                c.drawPath(Path().apply { moveTo(48f, 8f); lineTo(70f, 36f); lineTo(26f, 36f); close() }, head)
            }
            else -> {
                val dir = if (turn == Turn.RIGHT) 1f else -1f
                c.drawPath(Path().apply { moveTo(48f - 14f * dir, 88f); lineTo(48f - 14f * dir, 44f); lineTo(48f + 14f * dir, 44f) }, paint)
                c.drawPath(Path().apply { moveTo(48f + 38f * dir, 44f); lineTo(48f + 12f * dir, 22f); lineTo(48f + 12f * dir, 66f); close() }, head)
            }
        }
        bmp
    }

    /** A few stations around the fake drive, priced like a Paris day. */
    private val DEMO_STATIONS = listOf(
        FuelStation(1, "44, Rue De Rivoli", "Paris", 48.8573, 2.3494, mapOf(FuelGrade.GAZOLE to 1.789, FuelGrade.E10 to 1.849, FuelGrade.SP98 to 1.929), "Relais Rivoli"),
        FuelStation(2, "6, Avenue De La Grande Armée", "Paris", 48.8749, 2.2905, mapOf(FuelGrade.GAZOLE to 1.729, FuelGrade.E10 to 1.809), "Station Grande Armée"),
        FuelStation(3, "112, Boulevard Haussmann", "Paris", 48.8748, 2.3196, mapOf(FuelGrade.GAZOLE to 1.759, FuelGrade.SP95 to 1.859, FuelGrade.E85 to 0.899), "Relais Haussmann"),
        FuelStation(4, "28, Quai De La Rapée", "Paris", 48.8452, 2.3689, mapOf(FuelGrade.GAZOLE to 1.699, FuelGrade.E10 to 1.779, FuelGrade.GPLC to 0.959), "Station de la Rapée")
    )

    /**
     * What the real sources showed when the demo began, put back when it ends.
     * The feeds whose real state can change meanwhile (a route ending, a
     * message arriving, a fetch landing, the CANbox, the parking spot, the
     * particle filter's readings) keep it up to date themselves and hand
     * back their latest instead. What the bus carries needs no putting back.
     */
    private class Snapshot(
        val lamp: EngineLamp?,
        val pending: Set<String>,
        val location: Location?,
        val trip: TripState,
        val heading: Float?,
        val g: GForce,
        val care: CareState,
        val ai: AiMechanic.State,
        val psa: PsaTrip?
    ) {
        fun restore() {
            ObdBluetoothManager.endDemo(lamp, pending)
            LocationFeed.demoWrite(location, trip, heading)
            GForceFeed.demoWrite(g)
            WeatherRepo.endDemo()
            NavDirections.endDemo()
            NotificationFeed.endDemo()
            CarCare.demoWrite(care)
            AiMechanic.demoWrite(ai)
            FuelPriceRepo.endDemo()
            ParkingStore.endDemo()
            PhoneLink.endDemo()
            PidExplorer.endDemo()
            PsaTripComputer.demoWrite(psa)
        }

        companion object {
            fun take() = Snapshot(
                ObdBluetoothManager.lamp.value, ObdBluetoothManager.pending.value,
                LocationFeed.location.value, LocationFeed.trip.value, LocationFeed.headingDeg.value,
                GForceFeed.g.value,
                CarCare.state.value, AiMechanic.state.value,
                PsaTripComputer.state.value
            )
        }
    }
}
