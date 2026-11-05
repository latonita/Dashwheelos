package com.openauto.dash

import kotlin.math.abs
import kotlin.math.roundToInt

// Citroën C4 Picasso rough figures, used where the car profile doesn't say better.
internal const val TANK_LITERS = 60.0

internal const val AVG_L_PER_100KM = 6.5

// Jeep Grand Cherokee WK 3.0 CRD.
internal const val JEEP_WK_TANK_LITERS = 77.6
internal const val JEEP_WK_L_PER_100KM = 12.0

/**
 * Fuel level and the range it gives, and where the level came from ("CANbox" or
 * "OBD"). [rangeFromCar] means [rangeKm] is the car's own trip-computer figure
 * rather than our estimate; [percentEstimated] means the level was worked back
 * from that range because no fuel signal is known.
 */
internal class FuelInfo(
    val percent: Int,
    val rangeKm: Int,
    val source: String,
    val liters: Double = percent / 100.0 * TANK_LITERS,
    val avgUse: Double = AVG_L_PER_100KM,
    val rangeFromCar: Boolean = false,
    val percentEstimated: Boolean = false,
    val tankL: Double = TANK_LITERS
)

/** Plausible average consumption; outside it the fuel reading and the range disagree. */
private val SANE_L_PER_100KM = 3.0..20.0

/**
 * Formats what's known: the fuel level in percent [fuelPct] (the resolved [com.openauto.dash.vehicle.K.FUEL_LEVEL]:
 * the learned CANbox byte, else the car app's litres, else the OBD PID, by rank) and the distance to empty [rangeKm]
 * (the resolved [com.openauto.dash.vehicle.K.RANGE]). [fromObd] says the level is the adapter's, for the label
 * under it. [tankL] and [typicalUse] are the car profile's tank and everyday consumption.
 *
 * It estimates nothing: when the car gives only one of the two, the `computed.range` provider publishes the other
 * as an estimate and says so ([rangeEstimated]: the range was worked from the level at the usual consumption;
 * [percentEstimated]: the level was worked back from the range). null while either is unknown.
 * Pure, so it's unit-tested.
 */
internal fun fuelInfo(
    fuelPct: Int?,
    rangeKm: Int?,
    tankL: Double = TANK_LITERS,
    typicalUse: Double = AVG_L_PER_100KM,
    fromObd: Boolean = false,
    percentEstimated: Boolean = false,
    rangeEstimated: Boolean = false
): FuelInfo? {
    val pct = fuelPct ?: return null
    val range = rangeKm ?: return null
    if (percentEstimated) {
        // Only the range: the level was worked back from it at the usual consumption.
        val liters = (range * typicalUse / 100).coerceAtMost(tankL)
        return FuelInfo(pct, range, "CANbox", liters, typicalUse, rangeFromCar = true, percentEstimated = true, tankL = tankL)
    }
    val source = if (fromObd) "OBD" else "CANbox"
    val liters = pct / 100.0 * tankL
    if (rangeEstimated) return FuelInfo(pct, range, source, liters, typicalUse, tankL = tankL)
    // The car's range over what's in the tank is the consumption it's counting on.
    val implied = if (range > 0) liters / range * 100 else null
    val avg = implied?.takeIf { it in SANE_L_PER_100KM } ?: typicalUse
    return FuelInfo(pct, range, source, liters, avg, rangeFromCar = true, tankL = tankL)
}

/**
 * The level a car source puts on the bus: an empty tank while the car still counts on driving on is
 * the car sending no level at all (its car app reads 0 L on some models), so it is left out
 * and the next source by rank speaks (the adapter's, else the level worked back from the range).
 * The one place this rule lives, for the providers that have it ([CarBox], [McuReader]).
 */
internal fun fuelPercentToPublish(percent: Int?, rangeKm: Int?): Int? =
    percent?.takeUnless { it == 0 && rangeKm != null && rangeKm > 0 }

/**
 * Where the car's distance to empty sits in the CANbox stream: a 16-bit word at
 * [index] (and index + 1) of frame [key], in [bigEndian] order, in units of
 * 1/[scale] km (the head unit shows "520.0km", so tenths are possible).
 */
internal data class RangeMapping(val key: String, val index: Int, val bigEndian: Boolean, val scale: Int) {

    /** The range in km from this frame's bytes, or null when absent or implausible. */
    fun decode(bytes: List<Int>): Int? {
        if (bytes.size <= index + 1) return null
        val hi = if (bigEndian) bytes[index] else bytes[index + 1]
        val lo = if (bigEndian) bytes[index + 1] else bytes[index]
        val raw = hi shl 8 or lo
        // 0xFFFF / 0x7FFF are the usual "not available" fillers.
        if (raw == 0xFFFF || raw == 0x7FFF) return null
        val km = (raw.toDouble() / scale).roundToInt()
        return km.takeIf { it <= MAX_RANGE_KM }
    }

    companion object {
        const val MAX_RANGE_KM = 2000

        /**
         * Every 16-bit word in [frames] that reads [km] (±1) in either byte order,
         * as km or tenths of a km. Exact hits first, then the usual encoding
         * (whole km, big-endian, as PSA frames are) ahead of the rest.
         */
        fun candidates(frames: Map<String, List<Int>>, km: Int): List<RangeMapping> {
            if (km !in 1..MAX_RANGE_KM) return emptyList()
            val out = ArrayList<Pair<RangeMapping, Double>>()
            for ((key, bytes) in frames) {
                for (i in 0 until bytes.size - 1) {
                    for (be in listOf(true, false)) {
                        for (scale in listOf(1, 10)) {
                            val m = RangeMapping(key, i, be, scale)
                            val hi = if (be) bytes[i] else bytes[i + 1]
                            val lo = if (be) bytes[i + 1] else bytes[i]
                            val raw = hi shl 8 or lo
                            if (raw == 0 || raw == 0xFFFF) continue
                            val err = abs(raw.toDouble() / scale - km)
                            if (err <= 1.0) out.add(m to err)
                        }
                    }
                }
            }
            return out.sortedWith(
                compareBy<Pair<RangeMapping, Double>>({ it.second }, { it.first.scale }, { !it.first.bigEndian })
            ).map { it.first }.take(12)
        }
    }
}
