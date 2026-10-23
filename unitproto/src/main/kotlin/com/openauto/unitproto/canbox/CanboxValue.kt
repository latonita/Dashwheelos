package com.openauto.unitproto.canbox

/**
 * What a decoder says about the car, in no canbox's terms and no app's: a
 * value per quantity, in engineering units. A null inside a value is a
 * **withdrawal** - the box is alive and has lost that source - which a
 * consumer shows as "no reading" rather than as the last number it saw.
 *
 * Adding a quantity means adding a case here; whoever consumes the values
 * ignores the cases it has no use for yet.
 */
sealed interface CanboxValue {
    data class SpeedKmh(val kmh: Int?) : CanboxValue
    data class Rpm(val rpm: Int?) : CanboxValue
    data class EngineLoadPct(val pct: Int?) : CanboxValue
    data class ThrottlePct(val pct: Int?) : CanboxValue
    data class CoolantC(val celsius: Int?) : CanboxValue
    data class IntakeAirC(val celsius: Int?) : CanboxValue
    data class OutsideTempC(val celsius: Double?) : CanboxValue
    data class BatteryVolts(val volts: Double?) : CanboxValue

    /** The tank as a percentage, where the box measures one. */
    data class FuelLevelPct(val pct: Int?) : CanboxValue
    /** The tank in litres, where the box measures litres: the percentage depends on a tank size only the app knows. */
    data class FuelLitres(val litres: Double?) : CanboxValue
    data class RangeKm(val km: Int?) : CanboxValue

    data class Doors(val doors: DoorBits) : CanboxValue
    data class Tyre(val reading: TyreReading) : CanboxValue

    data class Reverse(val on: Boolean) : CanboxValue
    data class ParkingBrake(val on: Boolean) : CanboxValue
    /** The box's own crank/run state. */
    data class EngineRunning(val running: Boolean?) : CanboxValue
    /** The panel lamps: side lights or headlights on. */
    data class Lights(val on: Boolean) : CanboxValue
    /** The car's instrument dimmer, 0..100 %; 0 is a reading. */
    data class Dimmer(val pct: Int?) : CanboxValue
    /** The selector lever; null when the box has no reading. */
    data class Gear(val position: GearPosition?) : CanboxValue
    data class OdometerKm(val km: Double) : CanboxValue
    data class Compass(val reading: CompassReading?) : CanboxValue

    /** The box's firmware version, as it names itself. */
    data class Version(val text: String) : CanboxValue
}

/** The selector lever of an automatic; [MANUAL] is the driver's own range (AutoStick). */
enum class GearPosition { PARK, REVERSE, NEUTRAL, DRIVE, MANUAL }

/** A door / lid bitmask read out of a frame; true = open. */
data class DoorBits(
    val frontLeft: Boolean,
    val frontRight: Boolean,
    val rearLeft: Boolean,
    val rearRight: Boolean,
    val tailgate: Boolean,
    val bonnet: Boolean
) {
    /** True when anything at all is open - what the dashboard's warning lamp wants. */
    val anyOpen: Boolean
        get() = frontLeft || frontRight || rearLeft || rearRight || tailgate || bonnet
}

/**
 * The car's compass: [sector] is the car's own 8-way byte (0 N, 1 NE ... 7 NW),
 * forwarded rather than re-derived from [headingDeg] so a compass rose agrees
 * with the cluster at a boundary: the two legitimately disagree there (7/NW at
 * 292 degrees), and that is not an error. [headingDeg] is MAGNETIC, not true.
 */
data class CompassReading(val sector: Int, val headingDeg: Int)

/**
 * One wheel's TPMS reading. [kPa] is null when the box has no pressure for
 * the wheel (before its first poll of the WCM, or once that went stale);
 * [celsius] is null when it has no temperature, which on the WK is always:
 * the WCM's reply carries none.
 */
data class TyreReading(
    val position: Int,
    val kPa: Int?,
    val celsius: Int?,
    val sensorBatteryLow: Boolean,
    val leak: Boolean,
    val noSignal: Boolean
) {
    companion object {
        // The WCM's own order, which the box keeps: the rears are the other
        // way round from the usual (TPMS-POLL-SPEC.md, not yet proven by a
        // deflation test; swap them here if one ever says otherwise).
        const val FRONT_LEFT = 0
        const val FRONT_RIGHT = 1
        const val REAR_RIGHT = 2
        const val REAR_LEFT = 3
        const val SPARE = 4
    }
}
