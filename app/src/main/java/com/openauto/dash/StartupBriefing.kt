package com.openauto.dash

import com.openauto.dash.vehicle.fuelInfoNow
import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.res.Resources
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.CalendarContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** An appointment worth mentioning: its title and start time, already formatted. */
internal data class UpcomingEvent(val title: String, val time: String)

/** What the dashboard knows as the car starts; null = unknown, and left out. */
internal data class BriefingFacts(
    val hour: Int,
    val weather: Weather? = null,
    val fuel: FuelInfo? = null,
    /** Stored fault codes from this start's scan; null when the engine wasn't checked. */
    val faults: List<String>? = null,
    /** The names of [faults] the built-in table knows, as the voice says them ([spokenFaultTitles]). */
    val faultTitles: List<String> = emptyList(),
    /** The AI mechanic's sentence about [faults], when it has one. */
    val faultSummary: String? = null,
    /** The mechanic already announced these faults moments ago. */
    val faultsJustSaid: Boolean = false,
    val event: UpcomingEvent? = null,
    /** Servicing coming due (or overdue) that hasn't been announced at that stage yet. */
    val upkeep: List<UpkeepDue> = emptyList(),
    /** The driver's units, for the distances said. */
    val units: UnitSystem = UnitSystem()
)

/** Picks the briefing's sentences. Pure, so it's unit-tested. */
internal object BriefingLines {

    const val LOW_FUEL_PCT = 15
    const val ICE_BELOW_C = 3
    /** A briefing is a few words, not a report: what comes after this many lines waits for the status button. */
    const val MAX_LINES = 3

    /**
     * In speaking order, [MAX_LINES] at most and no greeting; empty, so the
     * start stays silent, unless something needs saying: ice, low fuel, the
     * appointment coming up, a fault, servicing due. Fine weather, a full tank
     * and a clean engine go without saying.
     */
    fun compose(f: BriefingFacts): List<SpokenLine> = buildList {
        f.weather?.let { w ->
            if (w.tempC.roundToInt() <= ICE_BELOW_C || w.code in FREEZING_CODES) add(SpokenLine(R.string.briefing_ice, emptyList()))
        }
        f.fuel?.let {
            if (it.percent <= LOW_FUEL_PCT) add(CarCare.fuelLowLine(it.rangeKm, f.units))
        }
        f.event?.let { add(SpokenLine(R.string.briefing_event, listOf(it.title, it.time))) }
        f.faults?.let { codes ->
            when {
                codes.isEmpty() || f.faultsJustSaid -> Unit
                f.faultSummary != null -> add(SpokenLine(R.string.briefing_verbatim, listOf(f.faultSummary)))
                else -> add(faultsLine(codes, f.faultTitles))
            }
        }
        f.upkeep.forEach { add(UpkeepRules.line(it, f.units)) }
    }.take(MAX_LINES)

    /** "Two engine faults stored: catalyst efficiency, particle filter": by name, never the codes' letters. */
    private fun faultsLine(codes: List<String>, titles: List<String>): SpokenLine =
        if (titles.isEmpty()) SpokenLine(R.plurals.briefing_faults_untitled, listOf(codes.size), quantity = codes.size)
        else SpokenLine(R.plurals.briefing_faults, listOf(codes.size, titles.joinToString(", ")), quantity = codes.size)

    /**
     * The car's state when the driver asks for it (a learned wheel button).
     * Unlike the briefing, what is fine is said too, in a few words: the
     * answer to a question is never silence. [BriefingFacts.upkeep] is then
     * everything due or nearly, said before or not.
     */
    fun status(f: BriefingFacts): List<SpokenLine> = buildList {
        f.fuel?.let {
            add(
                if (it.percent <= LOW_FUEL_PCT) CarCare.fuelLowLine(it.rangeKm, f.units)
                else SpokenLine(if (f.units.imperial) R.string.units_voice_status_range_mi else R.string.voice_status_range, listOf(f.units.distance(it.rangeKm)))
            )
        }
        f.weather?.let { w ->
            if (w.tempC.roundToInt() <= ICE_BELOW_C || w.code in FREEZING_CODES) add(SpokenLine(R.string.briefing_ice, emptyList()))
        }
        f.faults?.let { codes ->
            when {
                codes.isEmpty() -> add(SpokenLine(R.string.voice_status_no_faults, emptyList()))
                f.faultSummary != null -> add(SpokenLine(R.string.briefing_verbatim, listOf(f.faultSummary)))
                else -> add(faultsLine(codes, f.faultTitles))
            }
        }
        f.upkeep.forEach { add(UpkeepRules.line(it, f.units)) }
        f.event?.let { add(SpokenLine(R.string.briefing_event, listOf(it.title, it.time))) }
        if (isEmpty()) add(SpokenLine(R.string.voice_status_nothing, emptyList()))
    }

    // Freezing drizzle / rain and snow: slippery whatever the thermometer says.
    private val FREEZING_CODES = setOf(56, 57, 66, 67, 71, 73, 75, 77, 85, 86)
}

/** One heartbeat: which boot, the uptime including deep sleep, and the wall clock. */
internal data class Heartbeat(val boot: Int, val elapsed: Long, val wall: Long)

/** Tells a car start from a running unit by the gap between heartbeats. Pure, so it's unit-tested. */
internal object CarStart {

    const val OFF_GAP_MS = 30 * 60_000L

    /**
     * Whether [now] follows a stretch with the unit off or asleep. After a
     * reboot the wall clock decides (unless it's clearly not set yet, as
     * units often boot with an old date); within one boot, uptime including
     * deep sleep does, immune to the clock being corrected mid-drive.
     */
    fun detected(last: Heartbeat?, now: Heartbeat): Boolean = when {
        last == null -> true
        now.boot != last.boot || now.elapsed < last.elapsed -> now.wall - last.wall !in 0 until OFF_GAP_MS
        else -> now.elapsed - last.elapsed >= OFF_GAP_MS
    }
}

/**
 * A few spoken sentences when the car starts, and only when something needs
 * saying: ice, low fuel, engine faults, an appointment in the next two hours.
 * Built from what the dashboard already knows, with no AI call, so it's
 * quick, works offline, and nothing about the driver's day is sent anywhere.
 *
 * "The car starts" means the unit was off or asleep for [CarStart.OFF_GAP_MS]. A
 * heartbeat taken every [TICK_MS] stops while the unit is off or in deep
 * sleep, so the first beat after power-on sees the gap. That works on any head
 * unit without its own ignition broadcast, and a short stop doesn't re-brief.
 * Within one run the last beat is kept in memory; it is only saved every
 * [SAVE_MS] (flash wears), so after a power cut the gap reads up to that much
 * longer than the unit was really off.
 */
object StartupBriefing {

    private const val TICK_MS = 20_000L
    private const val SAVE_MS = 5 * 60_000L
    // How long to wait for the OBD scan, a GPS fix, the CANbox fuel reading: the
    // briefing must be over before the car rolls, so what is known is said first
    // and the engine's result follows on its own when it lands.
    private const val WAIT_MS = 12_000L
    private const val SETTLE_MS = 6_000L
    // A scan finished this recently counts as this start's.
    private const val FRESH_SCAN_MS = 90_000L
    private const val EVENT_WINDOW_MS = 2 * 3_600_000L

    private const val PREFS = "startup_briefing"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    @Volatile private var lastScanAt = 0L
    /** When the last briefing began: the ignition and the heartbeat may both see the same start. */
    private var briefedAt = 0L

    /**
     * The unit announced the ignition after a real stop ([CarPower]): brief
     * now rather than at the heartbeat's next beat.
     */
    fun carStarted(context: Context) {
        scope.launch { brief(context.applicationContext) }
    }

    /** Starts the heartbeat (once per process). */
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch { AiMechanic.state.collect { if (it.codes != null) lastScanAt = System.currentTimeMillis() } }
        scope.launch {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // The saved beat only matters for the first one of this run.
            var last = if (prefs.contains("boot")) {
                Heartbeat(prefs.getInt("boot", -1), prefs.getLong("elapsed", 0L), prefs.getLong("wall", 0L))
            } else {
                null
            }
            var saved: Heartbeat? = null
            while (true) {
                val now = Heartbeat(bootCount(app), SystemClock.elapsedRealtime(), System.currentTimeMillis())
                val start = CarStart.detected(last, now)
                if (start || saved == null || now.elapsed - saved.elapsed !in 0 until SAVE_MS) {
                    prefs.edit().putInt("boot", now.boot).putLong("elapsed", now.elapsed).putLong("wall", now.wall).apply()
                    saved = now
                }
                last = now
                if (start) launch { brief(app) }
                delay(TICK_MS)
            }
        }
    }

    private fun bootCount(context: Context): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)

    private suspend fun brief(context: Context) {
        // Main thread (the scope's), so the check and the mark can't interleave.
        val now = System.currentTimeMillis()
        if (now - briefedAt in 0 until CarStart.OFF_GAP_MS) return
        briefedAt = now
        if (!SpokenEvents.isOn(context, SpokenEvent.BRIEFING)) return
        val startedAt = System.currentTimeMillis()
        val locale = InAppNav.locale(context)
        val resources = AppLanguage.wrap(context).resources
        // What the dashboard knows, or learns within seconds, first.
        val facts = gather(context, locale, startedAt)
        val lines = BriefingLines.compose(facts)
        if (lines.isNotEmpty()) {
            CarVoice.announce(lines.joinToString(" ") { it.text(resources) }, locale)
            // Only the servicing that made it into the three lines counts as said.
            Maintenance.markSpoken(facts.upkeep.filter { UpkeepRules.line(it, facts.units) in lines })
        }
        // Then the engine, as a sentence of its own when its scan lands.
        val engine = engine(startedAt) ?: return
        val codes = engine.warningCodes ?: return
        val about = BriefingFacts(
            hour = facts.hour,
            faults = codes,
            faultTitles = spokenFaultTitles(resources, codes),
            faultSummary = engine.diagnosis?.summary,
            faultsJustSaid = justSaid(codes, engine.diagnosis?.summary, spokenFaultTitles(resources, codes), resources, startedAt),
            units = facts.units
        )
        val said = BriefingLines.compose(about)
        if (said.isNotEmpty()) CarVoice.announce(said.joinToString(" ") { it.text(resources) }, locale)
    }

    /**
     * Says how the car is, now, from what is already known (a learned wheel
     * button): nothing is waited for, so the answer comes at once.
     */
    fun sayStatus(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val locale = InAppNav.locale(app)
            val resources = AppLanguage.wrap(app).resources
            val now = System.currentTimeMillis()
            val engine = AiMechanic.state.value
            val facts = BriefingFacts(
                hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                weather = WeatherRepo.weather.value?.takeIf { now - it.fetchedAt < 3 * 3_600_000L },
                fuel = fuelInfoNow(),
                faults = engine.warningCodes,
                faultTitles = engine.warningCodes?.let { spokenFaultTitles(resources, it) }.orEmpty(),
                faultSummary = engine.diagnosis?.summary,
                event = withContext(Dispatchers.IO) { nextEvent(app, locale) },
                upkeep = Maintenance.state.value.statuses(now).filter { it.stage == UpkeepStage.SOON || it.stage == UpkeepStage.DUE },
                units = Units.current.value
            )
            CarVoice.setContext(app)
            // Asked for: said whatever the switches for what the car says by itself.
            CarVoice.speak(BriefingLines.status(facts).joinToString(" ") { it.text(resources) }, locale)
        }
    }

    /** What is known without the engine: the weather, the fuel, the diary, the servicing. */
    private suspend fun gather(context: Context, locale: Locale, startedAt: Long): BriefingFacts = coroutineScope {
        val weather = async { weather(context) }
        val fuel = async { fuel() }
        BriefingFacts(
            hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
            weather = weather.await(),
            fuel = fuel.await(),
            event = withContext(Dispatchers.IO) { nextEvent(context, locale) },
            upkeep = Maintenance.dueForBriefing(startedAt),
            units = Units.current.value
        )
    }

    /** Current conditions at the car, if it has a position and the weather service answers. */
    private suspend fun weather(context: Context): Weather? {
        if (!LocationFeed.acquire(context)) return null
        try {
            val here = withTimeoutOrNull(WAIT_MS) { LocationFeed.location.filterNotNull().first() } ?: return null
            WeatherRepo.refresh(here.latitude, here.longitude)
        } finally {
            LocationFeed.release()
        }
        // An old reading (no connection since yesterday) would be misleading.
        return WeatherRepo.weather.value?.takeIf { System.currentTimeMillis() - it.fetchedAt < 3 * 3_600_000L }
    }

    /**
     * Fuel and range from the CANbox, when their signals have been learned (this
     * car's OBD doesn't report fuel). Waits for each learned one, and makes do
     * with whichever arrived if the other stays silent.
     */
    private suspend fun fuel(): FuelInfo? {
        val wantFuel = McuReader.fuelConfigured
        val wantRange = McuReader.rangeConfigured
        if (!wantFuel && !wantRange) return null
        McuReader.start()
        try {
            withTimeoutOrNull(WAIT_MS) {
                combine(McuReader.fuelPercent, McuReader.rangeKm) { f, r -> (!wantFuel || f != null) && (!wantRange || r != null) }
                    .first { it }
            }
            return fuelInfoNow()
        } finally {
            McuReader.stop()
        }
    }

    /** This start's fault-code scan (the mechanic runs it on connect), once its advice is in. */
    private suspend fun engine(startedAt: Long): AiMechanic.State? {
        if (ObdBluetoothManager.savedDeviceAddress() == null && !withContext(Dispatchers.IO) { ObdBluetoothManager.usesUsb() }) return null
        // Codes from before the car was switched off don't count: wait for this start's scan.
        if (lastScanAt < startedAt - FRESH_SCAN_MS) {
            val stale = AiMechanic.state.value
            withTimeoutOrNull(WAIT_MS) { AiMechanic.state.first { it !== stale && it.codes != null } } ?: return null
        }
        return withTimeoutOrNull(SETTLE_MS) {
            AiMechanic.state.first { s ->
                s.warningCodes?.isEmpty() == true || (!s.thinking && (s.diagnosis != null || s.note != null))
            }
        } ?: AiMechanic.state.value.takeIf { it.codes != null }
    }

    /** Whether the mechanic has just announced these codes itself (by Gemini's sentence or by name), so the briefing doesn't repeat it. */
    private fun justSaid(codes: List<String>, summary: String?, titles: List<String>, resources: Resources, since: Long): Boolean {
        val said = CarVoice.saidSince(since - FRESH_SCAN_MS)
        val byName = MechanicLines.newCodes(codes, titles).text(resources)
        return said.any { text ->
            (summary != null && text.contains(summary)) || titles.any { text.contains(it) } || text == byName
        }
    }

    /** The next timed appointment in the coming hours, if calendars may be read. */
    private fun nextEvent(context: Context, locale: Locale): UpcomingEvent? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val now = System.currentTimeMillis()
        val uri = ContentUris.appendId(
            ContentUris.appendId(CalendarContract.Instances.CONTENT_URI.buildUpon(), now), now + EVENT_WINDOW_MS
        ).build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY
        )
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(0).orEmpty().trim()
                    val begin = c.getLong(1)
                    if (title.isEmpty() || c.getInt(2) == 1 || begin < now) continue
                    return@use UpcomingEvent(title, DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(begin)))
                }
                null
            }
        }.getOrNull()
    }
}
