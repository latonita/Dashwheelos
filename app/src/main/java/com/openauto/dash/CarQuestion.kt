package com.openauto.dash

import com.openauto.dash.vehicle.fuelInfoNow
import com.openauto.dash.vehicle.gaugesNow
import java.util.Locale

/**
 * What the car knows right now, for a question asked about it. null (or
 * empty) means not known and is left out: the mechanic must never read a
 * missing reading as zero.
 */
internal data class CarSnapshot(
    /** [CarProfile.promptDescription]. */
    val car: String,
    /** The OBD adapter is reading the engine; without it the engine values below are unknown. */
    val obdLive: Boolean = false,
    val rpm: Int? = null,
    val speedKmh: Int? = null,
    val coolantC: Int? = null,
    /** What this engine runs at once warm ([CarProfile.hotC]). */
    val hotC: Int? = null,
    val volts: Double? = null,
    /** [volts] came from the engine computer, not the adapter's own pin (which reads low). */
    val voltsFromEcu: Boolean = false,
    val fuelPercent: Int? = null,
    val rangeKm: Int? = null,
    /** [rangeKm] is the car's own trip-computer figure, not our estimate. */
    val rangeFromCar: Boolean = false,
    val lampOn: Boolean? = null,
    /** Codes from the last scan; null when none ran. */
    val codes: List<String>? = null,
    val pending: Set<String> = emptySet(),
    val diagnosis: Diagnosis? = null,
    /** The built-in table's meaning of codes the AI hasn't explained. */
    val codeMeanings: Map<String, String> = emptyMap(),
    /** Short drives in a row since the particle filter's last long one; null without a filter. */
    val filterShortStreak: Int? = null,
    /** Servicing coming due or overdue. */
    val upkeep: List<UpkeepDue> = emptyList(),
    /** Extra engine readings found for this car ([PidExplorer]), already worded. */
    val extras: List<String> = emptyList(),
    /** The car's own dashboard warnings showing now (a bulb out, low coolant...), worded ([PsaWarnings.forPrompt]). */
    val carWarnings: List<String> = emptyList()
)

/**
 * A question about the car as it is now, asked out loud with no fault in
 * focus ("Is my battery OK?", "How far can I go?"). Gemini gets the live
 * readings with the recording. Pure, so it's unit-tested.
 */
internal object CarQuestionPrompt {

    fun build(s: CarSnapshot, language: AiLanguage, units: UnitSystem = UnitSystem(), previous: Exchange? = null): String = buildString {
        appendLine(MechanicPersona.of(s.car) + " You are talking with its driver, who may be driving.")
        appendLine("What the car reports right now; anything not listed is unknown:")
        readings(s).forEach { appendLine("- $it") }
        s.diagnosis?.let { d ->
            appendLine("What you already told the driver about the codes: ${d.summary} ${d.overview}".trim())
            QuestionPrompt.adviceLines(d).forEach { appendLine(it) }
        }
        previous?.let { appendLine(QuestionPrompt.previousLine(it)) }
        appendLine()
        QuestionPrompt.appendAnswerRules(this, language)
        if (units.imperial || units.fahrenheit) {
            val parts = listOfNotNull("miles and mph".takeIf { units.imperial }, "degrees Fahrenheit".takeIf { units.fahrenheit })
            appendLine("The readings are metric, but the driver uses ${parts.joinToString(" and ")}: convert when you give a figure.")
        }
        appendLine("Base the answer on these readings and never invent one that isn't listed; when the question needs something unknown, say so plainly.")
        append("If anything points to danger (overheating, the battery not charging, a fault you rated stop), tell the driver first to stop somewhere safe. Never tell the driver to look at or handle anything while driving.")
    }

    /** One line per known fact, in plain English with figures written the same in every language. */
    fun readings(s: CarSnapshot): List<String> = buildList {
        if (!s.obdLive) {
            add("no live engine readings: the OBD adapter is not connected")
        } else {
            val rpm = s.rpm
            when {
                rpm == null -> Unit
                rpm > 0 -> add("engine running at $rpm rpm" + (s.speedKmh?.let { ", speed $it km/h" } ?: ""))
                else -> add("engine not running")
            }
            s.coolantC?.let { c -> add("coolant $c °C" + (s.hotC?.let { " (this engine runs at about $it °C once warm)" } ?: "")) }
            s.volts?.let { v ->
                val where = if (s.voltsFromEcu) "" else " (read at the adapter, which reads about 0.4 V low)"
                val state = if (rpm != null && rpm > 0) "engine running" else "engine off"
                add(String.format(Locale.US, "battery %.1f V with the %s%s", v, state, where))
            }
        }
        if (s.fuelPercent != null || s.rangeKm != null) {
            val parts = listOfNotNull(
                s.fuelPercent?.let { "fuel $it %" },
                s.rangeKm?.let { "about $it km of range" + if (s.rangeFromCar) " (the car's own figure)" else " (estimated from the tank)" }
            )
            add(parts.joinToString(", "))
        }
        s.lampOn?.let { add("engine warning lamp " + if (it) "on" else "off") }
        if (s.carWarnings.isNotEmpty()) {
            add("warnings the car shows on its own dashboard right now (its body computer, not engine fault codes): " + s.carWarnings.joinToString("; "))
        }
        s.codes?.let { codes ->
            val stored = codes.filter { it !in s.pending }
            val pending = codes.filter { it in s.pending }
            fun named(list: List<String>) = list.joinToString(", ") { c -> s.codeMeanings[c]?.let { "$c ($it)" } ?: c }
            add(if (stored.isEmpty()) "no stored fault codes at the last scan" else "stored fault codes: ${named(stored)}")
            if (pending.isNotEmpty()) add("pending fault codes, not confirmed yet: ${named(pending)}")
        }
        s.filterShortStreak?.let { add("particulate filter: $it short drives in a row since the last long one") }
        if (s.upkeep.isNotEmpty()) add("servicing: " + s.upkeep.joinToString("; ") { upkeepLine(it) })
        if (s.extras.isNotEmpty()) add("other readings: " + s.extras.joinToString(", "))
    }

    /** "oil: due now, 300 km over" / "timing belt: soon, 800 km or 20 days left". */
    fun upkeepLine(d: UpkeepDue): String {
        val name = d.kind.name.lowercase(Locale.ROOT).replace('_', ' ')
        val left = listOfNotNull(d.kmLeft?.let { "$it km" }, d.daysLeft?.let { "$it days" })
        return if (d.stage == UpkeepStage.DUE) {
            val over = listOfNotNull(d.kmLeft?.takeIf { it < 0 }?.let { "${-it} km" }, d.daysLeft?.takeIf { it < 0 }?.let { "${-it} days" })
            "$name: due now" + if (over.isEmpty()) "" else ", ${over.joinToString(" and ")} over"
        } else {
            "$name: soon" + if (left.isEmpty()) "" else ", ${left.joinToString(" or ")} left"
        }
    }

    /** One extra reading in words: "soot load 45 %", "particle filter regeneration running". */
    fun extraLine(reading: ExtraReading, value: Double): String = when (reading) {
        ExtraReading.REGEN_ACTIVE -> if (value >= 0.5) "particle filter regeneration running" else "no particle filter regeneration running"
        else -> {
            val name = reading.name.lowercase(Locale.ROOT).replace('_', ' ')
            val figure = if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.US, "%.1f", value)
            "$name $figure ${reading.unit}".trim()
        }
    }
}

/** An extra reading older than this is no longer "now". */
private const val EXTRA_FRESH_MS = 2 * 60_000L

/**
 * The snapshot from what the dashboard already has: nothing is waited for (in the demo, its own drive).
 * The gauges are read from the bus by snapshot and only those the poll always reads ([VehicleMonitor.POLL_FLOOR]).
 */
internal fun currentCarSnapshot(now: Long = System.currentTimeMillis()): CarSnapshot {
    val car = CarProfileStore.current
    val live = DemoMode.isOn || ObdBluetoothManager.connectionState.value == ObdConnectionState.CONNECTED
    val g = gaugesNow(VehicleMonitor.POLL_FLOOR)
    val fuel = fuelInfoNow()
    val ai = AiMechanic.state.value
    val explained = ai.diagnosis?.codes?.map { it.code }.orEmpty()
    return CarSnapshot(
        car = car.promptDescription(),
        obdLive = live,
        rpm = g.rpm,
        speedKmh = g.speedKmh,
        coolantC = g.coolantTempC,
        hotC = car.hotC,
        volts = g.voltage,
        voltsFromEcu = g.voltsTrusted,
        fuelPercent = fuel?.takeUnless { it.percentEstimated }?.percent,
        rangeKm = fuel?.rangeKm,
        rangeFromCar = fuel?.rangeFromCar == true,
        lampOn = ObdBluetoothManager.lamp.value?.on,
        codes = ai.warningCodes,
        pending = ObdBluetoothManager.pending.value,
        diagnosis = ai.diagnosis,
        codeMeanings = ai.warningCodes.orEmpty().filter { it !in explained }.mapNotNull { c -> ObdCodes.tableTitle(c)?.let { c to it } }.toMap(),
        filterShortStreak = CarCare.state.value.filter.shortStreak.takeIf { car.particleFilter },
        upkeep = Maintenance.state.value.statuses(now).filter { it.stage == UpkeepStage.SOON || it.stage == UpkeepStage.DUE },
        extras = PidExplorer.readings.value
            .filter { (_, v) -> now - v.at in 0..EXTRA_FRESH_MS }
            .map { (r, v) -> CarQuestionPrompt.extraLine(r, v.value) },
        carWarnings = PsaWarnings.forPrompt()
    )
}
