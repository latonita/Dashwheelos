package com.openauto.dash.vehicle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.openauto.dash.CarProfile
import com.openauto.dash.CarProfileStore
import com.openauto.dash.FuelInfo
import com.openauto.dash.fuelInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlin.math.roundToInt

/*
 * The fuel and the range as the screens read them: [fuelInfo] over the two keys. The level is the
 * resolved FUEL_LEVEL (the learned CANbox byte, else the car app's litres over the profile's tank, else
 * the engine computer's PID, by rank), the range is RANGE (the car's own trip computer, learned or shared).
 */

/**
 * How long the car's own fuel level and range are believed without a new message. The car app and the
 * CANbox say them on a change and a parked car says nothing, while the tile has always shown the last one
 * until the next: the key's 2 minutes is far too short, so these providers put with this ttl.
 */
const val FUEL_HOLD_MS = 6 * 3_600_000L

/** The info for [level] (FUEL_LEVEL's resolved sample) and [range] (RANGE's); both from the bus, an estimate being a source like the others. */
internal fun fuelInfoOf(level: Sample<Double>?, range: Sample<Double>?, car: CarProfile = CarProfileStore.current): FuelInfo? =
    fuelInfo(
        usableLevelPct(level), range?.value?.roundToInt(), car.tank, car.typicalUse,
        fromObd = level?.source?.kind == SourceKind.OBD,
        percentEstimated = level?.quality == Quality.ESTIMATED,
        rangeEstimated = range?.quality == Quality.ESTIMATED
    )

/** The level in whole percent, or null: no reading (the adapter's 0 % is none, as it always was). */
internal fun usableLevelPct(level: Sample<Double>?): Int? =
    level?.value?.roundToInt()?.takeUnless { level.source.kind == SourceKind.OBD && it == 0 }

/**
 * The fuel and range now; null when nothing is known. A snapshot, for code that is not a flow: nobody may be
 * asking for the estimate right now (it runs only while its key is wanted), so when the car gives one of the two
 * and the bus has no estimate of the other yet, it is worked out here from the same functions the
 * `computed.range` provider uses, and marked the same way. Hold [K.FUEL_LEVEL] while you rely on it.
 */
internal fun fuelInfoNow(car: CarProfile = CarProfileStore.current): FuelInfo? {
    val level = Vehicle.bus.sample(K.FUEL_LEVEL).value
    val range = Vehicle.bus.sample(K.RANGE).value
    val pct = usableLevelPct(level)
    return when {
        pct != null && range == null ->
            fuelInfo(
                pct, estimatedRangeKm(Vehicle.bus.sample(K.FUEL_LITRES).value?.value, pct, car.tank, car.typicalUse)!!.roundToInt(), car.tank, car.typicalUse,
                fromObd = level?.source?.kind == SourceKind.OBD, rangeEstimated = true
            )
        pct == null && range != null && range.quality != Quality.ESTIMATED -> {
            val km = range.value.roundToInt()
            fuelInfo(levelFromRangePct(km, car.tank, car.typicalUse).roundToInt(), km, car.tank, car.typicalUse, percentEstimated = true)
        }
        else -> fuelInfoOf(level, range, car)
    }
}

/** [fuelInfoNow] as it changes; collecting it is demand for the fuel level and the range. */
internal fun fuelInfoFlow(): Flow<FuelInfo?> =
    combine(Vehicle.bus.sample(K.FUEL_LEVEL), Vehicle.bus.sample(K.RANGE), CarProfileStore.profile) { level, range, car ->
        fuelInfoOf(level, range, car)
    }

/** The fuel and range for a screen; null when nothing is known. */
@Composable
internal fun rememberFuelInfo(): FuelInfo? {
    val flow = remember { fuelInfoFlow() }
    val info by flow.collectAsState(remember { fuelInfoNow() })
    return info
}
