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
    /** The accelerator pedal, 0..100 %. */
    data class ThrottlePct(val pct: Int?) : CanboxValue
    data class CoolantC(val celsius: Int?) : CanboxValue
    data class OutsideTempC(val celsius: Double?) : CanboxValue
    data class BatteryVolts(val volts: Double?) : CanboxValue

    /** The tank as a percentage, where the box measures one. */
    data class FuelLevelPct(val pct: Int?) : CanboxValue
    /** The tank in litres, where the box measures litres: the percentage depends on a tank size only the app knows. */
    data class FuelLitres(val litres: Double?) : CanboxValue

    data class Doors(val doors: DoorBits?) : CanboxValue
    data class Tyre(val reading: TyreReading) : CanboxValue

    /** The box's ACC input (stock JPF2 `0x72` bit 0): the key is in ACC or on. */
    data class Acc(val on: Boolean) : CanboxValue
    /** Stock JPF2 `0x72` bit 4, the "raw ignition bit": decoded, meaning unproven, not published. */
    data class IgnBit(val on: Boolean) : CanboxValue
    data class Reverse(val on: Boolean?) : CanboxValue
    data class ParkingBrake(val on: Boolean?) : CanboxValue
    /** The box's own crank/run state. */
    data class EngineRunning(val running: Boolean?) : CanboxValue
    /** The panel lamps: side lights or headlights on. */
    data class Lights(val on: Boolean) : CanboxValue
    /** The car's instrument dimmer, 0..100 %; 0 is a reading. */
    data class Dimmer(val pct: Int?) : CanboxValue
    /** The selector lever; null when the box has no reading. [manual] is the AutoStick digit 1..4 with [GearPosition.MANUAL]. */
    data class Gear(val position: GearPosition?, val manual: Int? = null) : CanboxValue
    /** Fuel flow in L/h (the box's own 100 ms integral); 0.0 is a reading (fuel cut), null the box's "absent". */
    data class FuelFlowLph(val lph: Double?) : CanboxValue
    /** The car's odometer; null when the box has none (0xFFFFFF). */
    data class OdometerKm(val km: Double?) : CanboxValue
    data class Compass(val reading: CompassReading?) : CanboxValue

    /** The key position (STATE S0); null for an unknown code or a dead source. */
    data class Ignition(val position: IgnitionPosition?) : CanboxValue
    /** Accessory power (RAP) is up. */
    data class AccessoryPower(val on: Boolean?) : CanboxValue
    /** A valid key is in the lock. */
    data class KeyIn(val on: Boolean?) : CanboxValue
    data class BrakePedal(val on: Boolean?) : CanboxValue
    /** True = the driver's belt is NOT fastened. */
    data class DriverBelt(val unfastened: Boolean?) : CanboxValue
    /** Turn signals, hazard and exterior lamps, all from one CAN id, so one value; null = that source is dead. */
    data class BodyLights(val bits: LightBits?) : CanboxValue
    data class Cruise(val state: CruiseState?) : CanboxValue
    /** The A/C compressor is running (not the vendor HVAC panel). */
    data class AcCompressor(val on: Boolean?) : CanboxValue
    /** The warning lamps that are lit; null = their source is dead (an empty set means none lit). */
    data class Telltales(val lit: Set<Telltale>?) : CanboxValue
    data class TransferCase(val range: TransferCaseRange?) : CanboxValue
    /** Engine oil pressure, kPa gauge; 0 is a reading (engine off). */
    data class OilPressureKpa(val kpa: Double?) : CanboxValue
    /** Manifold absolute pressure, hPa. */
    data class ManifoldHpa(val hpa: Double?) : CanboxValue
    /** Minutes since the engine was last running (0..254). */
    data class EngineOffMinutes(val minutes: Int?) : CanboxValue
    /** The box's 1 Hz heartbeat: informs only, nothing branches on it. [counter] continues by +1 (mod 256). */
    data class Heartbeat(val layout: Int, val capabilities: Int, val counter: Int) : CanboxValue

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
 * [celsius] is null when it has no temperature, null when the
 * sensor gives none (FF).
 */
data class TyreReading(
    val position: Int,
    val kPa: Int?,
    val celsius: Int?,
    val sensorBatteryLow: Boolean,
    val leak: Boolean,
    val noSignal: Boolean,
    /** The car's own low-pressure warning for this tyre (the WCM's; lights the cluster lamp). */
    val lowPressureWarn: Boolean = false
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

/** The key position the car reports (`000` b0). */
enum class IgnitionPosition { NO_KEY, KEY_OFF, ACC, ON, CRANK }

enum class CruiseState { OFF, ARMED, ENGAGED }

enum class TransferCaseRange { HIGH, LOW, SHIFTING }

/** The warning lamps of `003`. */
enum class Telltale { ESP_OFF, MIL, OIL, CHARGE, ABS, ESP, GLOW, WATER_IN_FUEL }

/** The exterior lamps and turn signals from `006`; [left] and [right] are set too while [hazard] is. */
data class LightBits(
    val left: Boolean,
    val right: Boolean,
    val hazard: Boolean,
    val position: Boolean,
    val lowBeam: Boolean,
    val highBeam: Boolean,
    val frontFog: Boolean,
    val rearFog: Boolean
)
