package com.openauto.dash

import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Key
import com.openauto.dash.vehicle.Publisher
import com.openauto.dash.vehicle.Quality

/*
 * The adapter's side of the gauge keys: which PIDs to ask for, and what to say
 * on the bus about the ones that answered.
 */

/** A reading the poll can ask the engine computer for, and the key it feeds. */
internal enum class Pid(val key: Key<Double>) {
    SPEED(K.VEHICLE_SPEED),
    RPM(K.RPM),
    THROTTLE(K.THROTTLE),
    LOAD(K.ENGINE_LOAD),
    COOLANT(K.COOLANT_TEMP),
    INTAKE(K.INTAKE_TEMP),
    FUEL_LEVEL(K.FUEL_LEVEL),
    /** The battery: the engine computer's control-module voltage, else the adapter's own pin. */
    VOLTS(K.BATTERY_VOLTS),
    /** The engine's fuel rate, or the air flow worked out into one. */
    FUEL_FLOW(K.FUEL_FLOW)
}

/** The slow readings share this many polls, one each. */
internal const val SLOW_SLOTS = 4

/**
 * What poll number [tick] asks for, given the keys somebody wants. The same
 * rhythm as before, for the wanted ones only: speed every poll (always: it is
 * the keep-alive, and "the engine computer went quiet" is read off it), revs
 * every poll, throttle and load every fourth poll each (on alternate polls),
 * the fuel flow every other poll, and the four slow readings (coolant, intake,
 * fuel level, volts) one per poll in turn. The first poll asks for every wanted
 * one. Nothing wanted: speed alone.
 */
internal fun obdPollPlan(wanted: Set<Key<*>>, tick: Int): List<Pid> {
    val first = tick == 0
    val slot = tick % SLOW_SLOTS
    return buildList {
        add(Pid.SPEED)
        if (K.RPM in wanted) add(Pid.RPM)
        if (K.THROTTLE in wanted && (first || tick % 4 == 0)) add(Pid.THROTTLE)
        if (K.ENGINE_LOAD in wanted && (first || tick % 4 == 2)) add(Pid.LOAD)
        if (K.FUEL_FLOW in wanted && (first || tick % 2 == 1)) add(Pid.FUEL_FLOW)
        if (K.COOLANT_TEMP in wanted && (first || slot == 0)) add(Pid.COOLANT)
        if (K.INTAKE_TEMP in wanted && (first || slot == 1)) add(Pid.INTAKE)
        if (K.FUEL_LEVEL in wanted && (first || slot == 2)) add(Pid.FUEL_LEVEL)
        if (K.BATTERY_VOLTS in wanted && (first || slot == 3)) add(Pid.VOLTS)
    }
}

/**
 * The fuel flow for the record after a poll. The flow is asked only every other poll ([obdPollPlan]), so on the
 * polls that skip it the last value is carried (a null there would withdraw the key on every other poll, see
 * [ObdGaugePublisher.publish]); it is null only when nobody wants the key, or after the engine stopped.
 * [polled]: this poll asked for it; [flow]: what it answered (null: no answer).
 */
internal fun carriedFuelFlow(polled: Boolean, wanted: Set<Key<*>>, flow: Double?, previous: Double?): Double? = when {
    polled -> flow ?: previous
    K.FUEL_FLOW in wanted -> previous
    else -> null
}

/**
 * The adapter as a provider of the gauge keys. A key is put only after the
 * adapter has answered that PID since the link came up, and only when it is
 * read again (a value that is not asked for any more ages out by its key's
 * ttl): null before, never the record's 0. [withdrawAll] when the link goes.
 */
internal class ObdGaugePublisher(private val out: Publisher) {
    private val answered = HashSet<Pid>()

    /** The PIDs answered since the link came up. */
    @Synchronized
    fun answered(): Set<Pid> = answered.toSet()

    /**
     * Puts the [fresh] readings of [d]. [voltsFromEcu] false marks the volts DEGRADED (an adapter's
     * own pin, miscalibrated on clones), so the unit's or the engine computer's win from any rank.
     * [minTtlMs] lengthens the lifetime of every key for a source with a slower cadence than the poll (Deep OBD).
     */
    @Synchronized
    fun publish(d: ObdData, fresh: Set<Pid>, voltsFromEcu: Boolean = d.voltageFromEcu, minTtlMs: Long = 0L) {
        if (fresh.isNotEmpty()) out.up(true)
        for (pid in fresh) {
            // A tank the adapter reads as 0 % is no reading (clones and cars without the PID answer that): the level
            // comes from the next source, else is worked back from the range ([startRangeEstimate]).
            if (pid == Pid.FUEL_LEVEL && d.fuelLevelPct <= 0) {
                out.put(K.FUEL_LEVEL, null)
                answered.remove(pid)
                continue
            }
            val value = when (pid) {
                Pid.SPEED -> d.speedKmh.toDouble()
                Pid.RPM -> d.rpm.toDouble()
                Pid.THROTTLE -> d.throttlePct.toDouble()
                Pid.LOAD -> d.engineLoadPct.toDouble()
                Pid.COOLANT -> d.coolantTempC.toDouble()
                Pid.INTAKE -> d.intakeTempC.toDouble()
                Pid.FUEL_LEVEL -> d.fuelLevelPct.toDouble()
                Pid.VOLTS -> d.voltage
                Pid.FUEL_FLOW -> d.fuelFlowLh ?: continue
            }
            val quality = if (pid == Pid.VOLTS && !voltsFromEcu) Quality.DEGRADED else Quality.GOOD
            val ttl = listOfNotNull(ROTATED_TTL_MS.takeIf { pid in ROTATED }, minTtlMs.takeIf { it > 0 }).maxOrNull()
            out.put(pid.key, value, quality, ttlMs = ttl?.let { maxOf(it, (pid.key as? Key.Measurement<*>)?.ttlMs ?: 0L) })
            answered += pid
        }
        // No flow any more (the engine stopped, nobody asks): the key goes.
        if (d.fuelFlowLh == null && answered.remove(Pid.FUEL_FLOW)) out.put(K.FUEL_FLOW, null)
    }

    @Synchronized
    fun withdrawAll() {
        // The link is gone: every key the adapter put goes, and the source is down until it answers again.
        out.clear()
        out.up(false)
        answered.clear()
    }

    companion object {
        /** Asked only now and then (every 2nd to 4th poll, a poll can take a second on a slow adapter): the key's 3 s is too short. */
        const val ROTATED_TTL_MS = 10_000L
        private val ROTATED = setOf(Pid.THROTTLE, Pid.LOAD, Pid.FUEL_FLOW)
    }
}
