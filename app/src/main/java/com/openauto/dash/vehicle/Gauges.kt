package com.openauto.dash.vehicle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.openauto.dash.ObdData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/*
 * The engine gauges as the screens read them: whole numbers in the units the
 * tiles always drew, and null where the bus has no value (unknown is never 0).
 * A reader asks for the keys it shows and only those: the subscription is
 * what tells the OBD poll to ask the car, so a tile that is not on screen
 * costs no round trip.
 */

/** The gauge keys a reader can ask for; every one is a [Key.Measurement] of [Double]. */
val GAUGE_KEYS: List<Key<Double>> = listOf(
    K.SPEED, K.RPM, K.THROTTLE, K.ENGINE_LOAD, K.COOLANT_TEMP, K.INTAKE_TEMP, K.FUEL_LEVEL, K.BATTERY_VOLTS, K.FUEL_FLOW
)

/** What the asked-for gauges say; the others are null. [voltsTrusted]: the volts are not the adapter's own (clone) ATRV. */
@Immutable
data class Gauges(
    val speedKmh: Int? = null,
    val rpm: Int? = null,
    val throttlePct: Int? = null,
    val engineLoadPct: Int? = null,
    val coolantTempC: Int? = null,
    val intakeTempC: Int? = null,
    val fuelLevelPct: Int? = null,
    val voltage: Double? = null,
    val voltsTrusted: Boolean = false,
    val fuelFlowLh: Double? = null
) {
    /** The old readings record, with 0 for what is unknown: for the rules that were written against it ([com.openauto.dash.CareRules], [com.openauto.dash.LiveWatch]). */
    fun toObdData(): ObdData = ObdData(
        speedKmh = speedKmh ?: 0, rpm = rpm ?: 0, coolantTempC = coolantTempC ?: 0, intakeTempC = intakeTempC ?: 0,
        throttlePct = throttlePct ?: 0, engineLoadPct = engineLoadPct ?: 0, fuelLevelPct = fuelLevelPct ?: 0,
        voltage = voltage ?: 0.0, voltageFromEcu = voltsTrusted, fuelFlowLh = fuelFlowLh
    )
}

/** A gauge is what the car answered: an estimate (the level worked back from the range) is not one. */
private fun Sample<Double>?.int(): Int? = this?.takeUnless { it.quality == Quality.ESTIMATED }?.value?.roundToInt()

private fun gaugesOf(s: Map<Key<Double>, Sample<Double>?>): Gauges = Gauges(
    speedKmh = s[K.SPEED].int(),
    rpm = s[K.RPM].int(),
    throttlePct = s[K.THROTTLE].int(),
    engineLoadPct = s[K.ENGINE_LOAD].int(),
    coolantTempC = s[K.COOLANT_TEMP].int(),
    intakeTempC = s[K.INTAKE_TEMP].int(),
    fuelLevelPct = s[K.FUEL_LEVEL].int(),
    voltage = s[K.BATTERY_VOLTS]?.value,
    // Degraded = an adapter's own pin, not the engine computer's or the unit's measurement.
    voltsTrusted = s[K.BATTERY_VOLTS]?.let { it.quality != Quality.DEGRADED } ?: false,
    fuelFlowLh = s[K.FUEL_FLOW]?.value
)

/** Whether any gauge has a value: the panel has something to show, adapter or not. */
val Gauges.any: Boolean
    get() = speedKmh != null || rpm != null || throttlePct != null || engineLoadPct != null || coolantTempC != null ||
        intakeTempC != null || fuelLevelPct != null || voltage != null || fuelFlowLh != null

/** The asked-for gauges now; null for the rest. A snapshot reader that needs it fresh holds the keys ([holdAll]). */
fun gaugesNow(keys: Collection<Key<Double>> = GAUGE_KEYS): Gauges =
    gaugesOf(keys.associateWith { Vehicle.bus.sample(it).value })

/** The asked-for gauges as they change. Collecting it is demand for [keys]. */
fun gaugesFlow(keys: Collection<Key<Double>>): Flow<Gauges> {
    val list = keys.distinct()
    if (list.isEmpty()) return kotlinx.coroutines.flow.flowOf(Gauges())
    return combine(list.map { Vehicle.bus.sample(it) }) { samples -> gaugesOf(list.indices.associate { list[it] to samples[it] }) }
        .distinctUntilChanged()
}

/**
 * The gauges of [keys] for a screen; recomposes when one of them changes, and
 * asks the car for exactly these while it is composed.
 */
@Composable
fun rememberGauges(vararg keys: Key<Double>): Gauges {
    val list = keys.toList()
    val flow = remember(list) { gaugesFlow(list) }
    val g by flow.collectAsState(remember(list) { gaugesNow(list) })
    return g
}

/** One gauge, as a number in the key's unit; null while unknown, and for an estimate. */
@Composable
fun rememberGauge(key: Key<Double>): Double? {
    val flow = remember(key) { Vehicle.bus.sample(key).map { s -> s?.takeUnless { it.quality == Quality.ESTIMATED }?.value }.distinctUntilChanged() }
    val v by flow.collectAsState(null)
    return v
}

/** A gauge in whole numbers (temperatures, percentages, revs); null while unknown. */
@Composable
fun rememberGaugeInt(key: Key<Double>): Int? {
    val v = rememberGauge(key)
    return remember(v) { v?.roundToInt() }
}

/** Whether the engine is not running, from a revs sample: no answer, or 0. */
fun engineOffOf(rpm: Double?): Boolean = rpm == null || rpm.roundToInt() == 0

/**
 * Whether the engine is off (no revs, or 0). Unlike [rememberGaugeInt] it recomposes its reader
 * only when the answer flips, not on every change of the revs.
 */
@Composable
fun rememberEngineOff(): Boolean {
    val flow = remember {
        Vehicle.bus.sample(K.RPM).map { s -> engineOffOf(s?.takeUnless { it.quality == Quality.ESTIMATED }?.value) }.distinctUntilChanged()
    }
    val off by flow.collectAsState(remember { engineOffOf(Vehicle.bus.now(K.RPM)) })
    return off
}

/** Standing demand for all of [keys] (see [VehicleBus.hold]); release it when the owner stops. */
fun VehicleBus.holdAll(keys: Collection<Key<*>>): Hold {
    val holds = keys.map { hold(it) }
    return Hold { holds.forEach { it.release() } }
}

/**
 * The gauges of [keys] in the old readings record, with 0 for what is unknown: for the tiles that
 * were drawn against [ObdData] and map "no answer" to their old default at the edge. The speed is the
 * one the bar shows ([rememberDisplaySpeedKmh]), when [K.SPEED] is asked for.
 */
@Composable
fun rememberReadings(vararg keys: Key<Double>): ObdData {
    val rest = keys.filter { it != K.SPEED }.toTypedArray()
    val g = rememberGauges(*rest)
    val speed = if (K.SPEED in keys) rememberDisplaySpeedKmh() else null
    return g.copy(speedKmh = speed).toObdData()
}

/**
 * [rememberGauges] for the skins' panels: the speed is the one the bar shows ([rememberDisplaySpeedKmh]) when
 * [K.SPEED] is asked for, and every gauge the car does not answer stays null (a panel shows "--" for it, or leaves it out).
 */
@Composable
fun rememberPanelGauges(vararg keys: Key<Double>): Gauges {
    val rest = keys.filter { it != K.SPEED }.toTypedArray()
    val g = rememberGauges(*rest)
    val speed = if (K.SPEED in keys) rememberDisplaySpeedKmh() else null
    return if (speed != null) g.copy(speedKmh = speed) else g
}
