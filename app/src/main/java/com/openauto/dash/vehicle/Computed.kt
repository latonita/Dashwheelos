package com.openauto.dash.vehicle

import android.content.Context
import com.openauto.dash.CarProfile
import com.openauto.dash.CarProfileStore
import com.openauto.dash.LiveWatch
import com.openauto.dash.startGearEstimate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * Estimates are sources, not a kind of data (docs/vehicle-bus.md): a COMPUTED provider publishes the same key
 * the car would, as Quality.ESTIMATED, and only when nothing better says it.
 *
 *   computed.gear    GEAR            from RPM and VEHICLE_SPEED (and REVERSE), the estimator's table learned on the road
 *   computed.range   RANGE           from FUEL_LEVEL at the usual consumption
 *                    FUEL_LEVEL      from RANGE, when the car gives no level
 *   computed.engine  ENGINE_RUNNING  from RPM
 *
 * Each runs only while its output is wanted and no other source (any that is not COMPUTED) has it, subscribing
 * to its inputs only then, so an adapter polls for them only for a reason. And an estimate never reads another
 * estimate: that is what keeps range and level from feeding each other.
 */

/** The revs above which the engine counts as running, as the alerts and the live watch have always judged it. */
internal const val ENGINE_RUNNING_RPM = LiveWatch.RUNNING_RPM

/** Whether the engine runs, from the revs; null while they are unknown. */
internal fun engineRunning(rpm: Double?): Boolean? = rpm?.let { it > ENGINE_RUNNING_RPM }

/** The range a [levelPct] of the tank gives at [typicalUse] litres per 100 km: the estimate when the car says no range. */
internal fun rangeFromLevelKm(levelPct: Int, tankL: Double, typicalUse: Double): Double =
    (levelPct / 100.0 * tankL / typicalUse * 100).toInt().toDouble()

/** The range [liters] in the tank give at [typicalUse] litres per 100 km: used when the box measures litres, with no tank size to guess. */
internal fun rangeFromLitresKm(liters: Double, typicalUse: Double): Double =
    (liters.coerceAtLeast(0.0) / typicalUse * 100).toInt().toDouble()

/** The range from the measured [litres] when there are any, else from the [levelPct] over the tank; null when neither is known. */
internal fun estimatedRangeKm(litres: Double?, levelPct: Int?, tankL: Double, typicalUse: Double): Double? =
    litres?.let { rangeFromLitresKm(it, typicalUse) } ?: levelPct?.let { rangeFromLevelKm(it, tankL, typicalUse) }

/** The level a [rangeKm] works back to at [typicalUse]: the estimate when the car says no level. */
internal fun levelFromRangePct(rangeKm: Int, tankL: Double, typicalUse: Double): Double {
    val liters = (rangeKm * typicalUse / 100).coerceAtMost(tankL)
    return (liters / tankL * 100).roundToInt().coerceIn(0, 100).toDouble()
}

/** What an estimate may read of [key]: the resolved value, but never one that is itself an estimate. */
internal fun <T : Any> VehicleBus.measured(key: Key<T>): Flow<Sample<T>?> =
    sample(key).map { s -> s?.takeUnless { it.source.kind == SourceKind.COMPUTED } }.distinctUntilChanged()

/**
 * Runs [estimate] into [out] as [key] while [key] is wanted and no source that is not COMPUTED has it ([isBetter]
 * says which of those count: a value the car sends as "no reading" does not). Collecting [estimate] is what asks
 * for the inputs. When it stops (nobody wants the key any more, or a better source speaks) the estimate is withdrawn.
 * [ttlMs] is the estimate's own lifetime for a Measurement key; it follows its inputs, so it can be long.
 */
internal fun <T : Any> estimateWhileWanted(
    scope: CoroutineScope,
    bus: VehicleBus,
    out: Publisher,
    key: Key<T>,
    ttlMs: Long? = null,
    isBetter: (Sample<T>) -> Boolean = { true },
    estimate: () -> Flow<T?>
): Job = scope.launch {
    val wanted = bus.wanted.map { key in it }.distinctUntilChanged()
    val better = bus.candidates(key).map { list -> list.any { it.source.kind != SourceKind.COMPUTED && isBetter(it) } }.distinctUntilChanged()
    combine(wanted, better) { w, b -> w && !b }.distinctUntilChanged().collectLatest { active ->
        if (!active) {
            out.put(key, null)
            return@collectLatest
        }
        try {
            estimate().collect { out.put(key, it, Quality.ESTIMATED, ttlMs = ttlMs) }
        } finally {
            out.put(key, null)
        }
    }
}

/** `computed.engine`: ENGINE_RUNNING from the revs. */
internal fun startEngineEstimate(bus: VehicleBus, scope: CoroutineScope): Job =
    estimateWhileWanted(scope, bus, bus.register(Source("computed.engine", SourceKind.COMPUTED, "Engine from revs")), K.ENGINE_RUNNING) {
        bus.measured(K.RPM).map { engineRunning(it?.value) }.distinctUntilChanged()
    }

/**
 * `computed.range`: RANGE from FUEL_LEVEL, and FUEL_LEVEL from RANGE, at the car profile's tank and everyday
 * consumption. Each is the one the car does not give; with both from the car, neither runs.
 */
internal fun startRangeEstimate(bus: VehicleBus, scope: CoroutineScope, car: Flow<CarProfile>): List<Job> {
    val out = bus.register(Source("computed.range", SourceKind.COMPUTED, "Range from fuel"))
    val range = estimateWhileWanted(scope, bus, out, K.RANGE, ttlMs = FUEL_HOLD_MS) {
        combine(bus.measured(K.FUEL_LEVEL), bus.measured(K.FUEL_LITRES), car) { level, litres, profile ->
            estimatedRangeKm(litres?.value, usableLevelPct(level), profile.tank, profile.typicalUse)
        }.distinctUntilChanged()
    }
    val level = estimateWhileWanted(scope, bus, out, K.FUEL_LEVEL, ttlMs = FUEL_HOLD_MS, isBetter = { usableLevelPct(it) != null }) {
        combine(bus.measured(K.RANGE), car) { range, profile ->
            range?.value?.roundToInt()?.let { levelFromRangePct(it, profile.tank, profile.typicalUse) }
        }.distinctUntilChanged()
    }
    return listOf(range, level)
}

/** The estimators, started with the app. */
object Computed {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        startGearEstimate(context.applicationContext, Vehicle.bus, scope)
        startRangeEstimate(Vehicle.bus, scope, CarProfileStore.profile)
        startEngineEstimate(Vehicle.bus, scope)
    }
}
