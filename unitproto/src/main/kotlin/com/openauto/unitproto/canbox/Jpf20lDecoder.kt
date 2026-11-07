package com.openauto.unitproto.canbox

import com.openauto.unitproto.canbox.CanBytes.biasedTemp
import com.openauto.unitproto.canbox.CanBytes.s16
import com.openauto.unitproto.canbox.CanBytes.u16
import com.openauto.unitproto.canbox.CanBytes.u24
import com.openauto.unitproto.canbox.CanBytes.u8

/**
 * The rebuilt JPF2 box (firmware H1N4JPF20L): the stock JPF2 stream, which it keeps sending, plus the custom
 * telemetry block in `0xB0`-`0xB8` (layout "v2", `TELEMETRY-V2-SPEC.md` of the canbox rebuild).
 *
 * There is ONE layout and ONE decoder: no revision switch, nothing branches on the version byte of the heartbeat.
 * The firmware and this decoder change together.
 *
 * Staleness is the box's business: a field whose CAN source is dead arrives as its sentinel (so it decodes to a
 * null, a withdrawal), and a STATE bit whose source-alive bit (S6) is 0 is a null as well. The block takes back
 * what the stock frames also say (revs, reverse, doors, the selector), so the stale stock values cannot put
 * themselves back after a withdrawal.
 */
class Jpf20lDecoder : HiworldJpf2Decoder() {
    override val id = "hiworld-jpf20l"

    override fun matches(version: String) = version.startsWith("H1N4JPF20L")

    override fun decode(frame: CanFrame): List<CanboxValue> {
        if (frame.cmd !in 0xB0..0xBF) {
            return super.decode(frame).filterNot {
                it is CanboxValue.Rpm || it is CanboxValue.Reverse || it is CanboxValue.Doors || it is CanboxValue.Gear
            }
        }
        val p = frame.payload
        return when (frame.cmd) {
            Jpf20lCmd.HEARTBEAT -> listOfNotNull(Jpf20lSignals.heartbeat(p))
            Jpf20lCmd.STATE -> state(p)
            Jpf20lCmd.FAST -> if (p.size < Jpf20lSignals.FAST_SIZE) emptyList() else listOf(
                CanboxValue.SpeedKmh(Jpf20lSignals.speedKmh(p)),
                CanboxValue.Rpm(Jpf20lSignals.rpm(p)),
                CanboxValue.EngineLoadPct(Jpf20lSignals.engineLoadPct(p)),
                CanboxValue.FuelFlowLph(Jpf20lSignals.fuelFlowLph(p)),
                CanboxValue.OilPressureKpa(Jpf20lSignals.oilPressureKpa(p)),
                CanboxValue.ManifoldHpa(Jpf20lSignals.manifoldHpa(p))
            )
            Jpf20lCmd.ENGINE -> if (p.size < Jpf20lSignals.ENGINE_SIZE) emptyList() else listOf(
                CanboxValue.CoolantC(Jpf20lSignals.coolantTempC(p)),
                CanboxValue.BatteryVolts(Jpf20lSignals.voltage(p)),
                CanboxValue.FuelLevelPct(Jpf20lSignals.fuelLevelPct(p)),
                CanboxValue.FuelLitres(Jpf20lSignals.fuelLitres(p))
            )
            Jpf20lCmd.AMBIENT -> if (p.size < Jpf20lSignals.AMBIENT_SIZE) emptyList() else listOf(
                CanboxValue.OutsideTempC(Jpf20lSignals.outsideTempC(p)),
                CanboxValue.EngineOffMinutes(Jpf20lSignals.minutesSinceRun(p))
            )
            Jpf20lCmd.TYRE -> listOfNotNull(Jpf20lSignals.tyre(p)?.let { CanboxValue.Tyre(it) })
            Jpf20lCmd.TRIP -> if (p.size < Jpf20lSignals.TRIP_SIZE) emptyList() else listOf(
                CanboxValue.OdometerKm(Jpf20lSignals.odometerKm(p)),
                // Magnetic, and NOT a sign of life: the car's compass keeps reporting after key-off.
                CanboxValue.Compass(Jpf20lSignals.compass(p))
            )
            else -> emptyList()
        }
    }

    /** STATE: every value, null where its source-alive bit says the source is dead. */
    private fun state(p: ByteArray): List<CanboxValue> {
        if (p.size < Jpf20lSignals.STATE_SIZE) return emptyList()
        val s = Jpf20lSignals
        return listOf(
            CanboxValue.Ignition(s.ignition(p)),
            CanboxValue.EngineRunning(s.engineRunning(p)),
            CanboxValue.AccessoryPower(s.accessoryPower(p)),
            CanboxValue.KeyIn(s.keyIn(p)),
            CanboxValue.Reverse(s.reverse(p)),
            CanboxValue.ParkingBrake(s.parkingBrake(p)),
            CanboxValue.BrakePedal(s.brakePedal(p)),
            CanboxValue.DriverBelt(s.beltUnfastened(p)),
            CanboxValue.Doors(s.doors(p)),
            CanboxValue.BodyLights(s.lights(p)),
            CanboxValue.Cruise(s.cruise(p)),
            CanboxValue.AcCompressor(s.acCompressor(p)),
            CanboxValue.Telltales(s.telltales(p)),
            CanboxValue.TransferCase(s.transferCase(p)),
            s.gear(p)
        )
    }

    override fun commandName(cmd: Int): String = Jpf20lCmd.name(cmd) ?: super.commandName(cmd)
}

/**
 * The command byte of each frame of the custom block (see [Jpf20lSignals] for the layouts).
 * `0xB1`, `0xB6`, `0xB9`-`0xBF` are never sent.
 */
object Jpf20lCmd {
    /** Box to host, 1 Hz: layout version, capability bitmap, rolling counter. Informs only. */
    const val HEARTBEAT = 0xB0
    /** Bitfields and small enums, on a change and every second. */
    const val STATE = 0xB2
    /** Speed, revs, load, fuel flow, oil pressure, boost; every 100 ms, always (the box's liveness). */
    const val FAST = 0xB3
    /** Coolant, battery volts, fuel % and litres. */
    const val ENGINE = 0xB4
    /** Outside temperature, minutes since the engine ran. */
    const val AMBIENT = 0xB5
    /** One tyre's pressure, temperature and sensor state; one frame per wheel. */
    const val TYRE = 0xB7
    /** Odometer and compass. */
    const val TRIP = 0xB8

    /** Every command of the block [Jpf20lDecoder] has a decoder for; always inside `0xB0`-`0xBF`. */
    val DECODED = setOf(HEARTBEAT, STATE, FAST, ENGINE, AMBIENT, TYRE, TRIP)

    /** A short name for the command, for logs and the Signal Finder; null outside the block. */
    fun name(cmd: Int): String? = when (cmd) {
        HEARTBEAT -> "heartbeat"; STATE -> "state"; FAST -> "fast"; ENGINE -> "engine"
        AMBIENT -> "ambient"; TYRE -> "tyre"; TRIP -> "trip"
        else -> null
    }
}

/**
 * The payload layout of each frame of the custom block, and the pure decoders over it.
 *
 * Offsets are into a [CanFrame]'s payload (the cmd byte is stripped); multi-byte fields are big-endian.
 * Every decoder returns null rather than a wrong number: a sentinel, a short payload or a value the car cannot
 * produce is "not available".
 *
 * ```
 * 0xB0 HEARTBEAT [0] layout (2)  [1..2] u16 capability bitmap (bit n = sends 0xB0+n)  [3] counter
 * 0xB2 STATE     [0] S0 POWER  bits 0-2 ignition (0 NoKey 1 KeyOff 2 ACC 3 ON 4 CRANK, 7 n/a), 3 engine running,
 *                              4 RAP, 5 key in, 6 reverse, 7 parking brake
 *                [1] S1 BODY   0 brake pedal, 1 belt UNfastened, 2 door FL, 3 FR, 4 RR, 5 RL, 6 hatch
 *                [2] S2 LIGHTS 0 left, 1 right, 2 hazard, 3 position, 4 low, 5 high, 6 front fog, 7 rear fog
 *                [3] S3        0 cruise on, 1 cruise engaged, 2 A/C compressor, 3 ESP off, 4 MIL, 5 oil, 6 charge, 7 ABS
 *                [4] S4        bits 0-2 4WD (1 4H, 2 4L, 3 shifting, 0 n/a), 3 ESP, 4 glow, 5 water in fuel
 *                [5] S5 gear   0 P 1 R 2 N 3 D 4..7 AutoStick 1..4, FF n/a
 *                [6] S6 SRC    source-alive bits: 0 `000`, 1 `002`, 2 `003`, 3 `006`, 4 `013`, 5 `0D0`, 6 `1B2`, 7 `012`
 * 0xB3 FAST      [0..1] speed 0.1 km/h  [2..3] rpm  [4] load %  [5..6] flow 0.01 L/h  [7] oil 4 kPa/count
 *                [8..9] boost/MAP hPa abs. Sentinels FF / FFFF
 * 0xB4 ENGINE    [0] coolant degC+40  [1] volts 0.1 V  [2] fuel %  [3..4] fuel 0.1 L. Sentinels FF / FFFF
 * 0xB5 AMBIENT   [0..1] s16 outside 0.1 degC (8000 n/a)  [2] minutes since RUN (FF n/a)
 * 0xB7 TYRE      [0] position  [1..2] kPa  [3] temp  [4] flags (see [tyre])
 * 0xB8 TRIP      [0..2] u24 odometer 0.1 km (FFFFFF n/a)  [3] compass sector (FF n/a)  [4..5] heading, degrees (FFFF n/a)
 * ```
 */
object Jpf20lSignals {
    const val STATE_SIZE = 7
    const val FAST_SIZE = 10
    const val ENGINE_SIZE = 5
    const val AMBIENT_SIZE = 3
    const val TRIP_SIZE = 6

    // --- HEARTBEAT
    const val OFF_HB_LAYOUT = 0
    const val OFF_HB_CAPS = 1
    const val OFF_HB_COUNTER = 3

    // --- STATE offsets
    const val OFF_S0 = 0
    const val OFF_S1 = 1
    const val OFF_S2 = 2
    const val OFF_S3 = 3
    const val OFF_S4 = 4
    const val OFF_S5 = 5
    const val OFF_S6 = 6

    // S0
    const val S0_ENGINE_RUNNING = 0x08
    const val S0_RAP = 0x10
    const val S0_KEY_IN = 0x20
    const val S0_REVERSE = 0x40
    const val S0_PARKING_BRAKE = 0x80
    // S1
    const val S1_BRAKE = 0x01
    const val S1_BELT_UNFASTENED = 0x02
    const val S1_DOOR_FL = 0x04
    const val S1_DOOR_FR = 0x08
    const val S1_DOOR_RR = 0x10
    const val S1_DOOR_RL = 0x20
    const val S1_HATCH = 0x40
    // S2
    const val S2_LEFT = 0x01
    const val S2_RIGHT = 0x02
    const val S2_HAZARD = 0x04
    const val S2_POSITION = 0x08
    const val S2_LOW = 0x10
    const val S2_HIGH = 0x20
    const val S2_FRONT_FOG = 0x40
    const val S2_REAR_FOG = 0x80
    // S3
    const val S3_CRUISE_ON = 0x01
    const val S3_CRUISE_ENGAGED = 0x02
    const val S3_AC = 0x04
    const val S3_ESP_OFF = 0x08
    const val S3_MIL = 0x10
    const val S3_OIL = 0x20
    const val S3_CHARGE = 0x40
    const val S3_ABS = 0x80
    // S4
    const val S4_4WD_MASK = 0x07
    const val S4_ESP = 0x08
    const val S4_GLOW = 0x10
    const val S4_WATER_IN_FUEL = 0x20

    // S6, the source-alive bits.
    const val SRC_IGNITION = 0x01   // 000
    const val SRC_ENGINE = 0x02     // 002
    const val SRC_ASSIST = 0x04     // 003
    const val SRC_LIGHTS = 0x08     // 006
    const val SRC_BODY = 0x10       // 013
    const val SRC_PARK_BRAKE = 0x20 // 0D0
    const val SRC_4WD = 0x40        // 1B2
    const val SRC_KEY = 0x80        // 012

    // --- FAST offsets
    const val OFF_SPEED = 0
    const val OFF_RPM = 2
    const val OFF_LOAD = 4
    const val OFF_FLOW = 5
    const val OFF_OIL = 7
    const val OFF_BOOST = 8
    const val MAX_SPEED_DKMH = 4000
    const val MAX_RPM = 12_000
    /** A flow no engine reaches; the largest legal field is 512.0 L/h. */
    const val MAX_FLOW_LPH = 512.0
    /** kPa per count of the oil pressure byte. */
    const val OIL_KPA_PER_COUNT = 4.0

    // --- ENGINE offsets
    const val OFF_COOLANT = 0
    const val OFF_VOLTS = 1
    const val OFF_FUEL_PCT = 2
    const val OFF_FUEL_DECILITRES = 3
    const val MAX_TANK_LITRES = 200.0

    // --- AMBIENT offsets
    const val OFF_OUTSIDE = 0
    const val OFF_MINUTES = 2
    /** s16 sentinel of the outside temperature (0x8000). */
    const val OUTSIDE_NOT_AVAILABLE = -32768

    // --- TRIP offsets
    const val OFF_ODOMETER = 0
    const val ODOMETER_NOT_AVAILABLE = 0xFFFFFF
    const val OFF_COMPASS_SECTOR = 3
    const val OFF_COMPASS_HEADING = 4
    const val COMPASS_SECTOR_MAX = 7
    /** The heading travels in 2-degree steps, so 358 is the top. */
    const val COMPASS_HEADING_MAX = 358

    // --- TYRE
    const val OFF_TYRE_POSITION = 0
    const val OFF_TYRE_PRESSURE = 1
    const val OFF_TYRE_TEMP = 3
    const val OFF_TYRE_FLAGS = 4
    const val TYRE_SENSOR_BATTERY_LOW = 0x01
    const val TYRE_LEAK = 0x02
    const val TYRE_NO_SIGNAL = 0x04
    const val TYRE_LOW_WARN = 0x08
    /** The highest wheel index the box may report ([TyreReading.SPARE]). */
    const val TYRE_POSITION_MAX = TyreReading.SPARE

    // ------------------------------------------------------------------ HEARTBEAT

    /** The heartbeat, or null for a short payload. Diagnostics only: nothing branches on it. */
    fun heartbeat(p: ByteArray?): CanboxValue.Heartbeat? {
        val layout = u8(p, OFF_HB_LAYOUT) ?: return null
        val caps = u16(p, OFF_HB_CAPS) ?: return null
        val counter = u8(p, OFF_HB_COUNTER) ?: return null
        return CanboxValue.Heartbeat(layout, caps, counter)
    }

    // ------------------------------------------------------------------ STATE

    /** Whether the CAN source [mask] of S6 is alive; a short payload says nothing is. */
    fun sourceAlive(p: ByteArray?, mask: Int): Boolean = (u8(p, OFF_S6) ?: 0) and mask != 0

    private fun bit(p: ByteArray?, off: Int, mask: Int, src: Int): Boolean? {
        if (!sourceAlive(p, src)) return null
        val b = u8(p, off) ?: return null
        return b and mask != 0
    }

    /** S0 bits 0-2; 5, 6, 7 (n/a) and a dead `000` are null. */
    fun ignition(p: ByteArray?): IgnitionPosition? {
        if (!sourceAlive(p, SRC_IGNITION)) return null
        return when ((u8(p, OFF_S0) ?: return null) and 0x07) {
            0 -> IgnitionPosition.NO_KEY
            1 -> IgnitionPosition.KEY_OFF
            2 -> IgnitionPosition.ACC
            3 -> IgnitionPosition.ON
            4 -> IgnitionPosition.CRANK
            else -> null
        }
    }

    fun engineRunning(p: ByteArray?) = bit(p, OFF_S0, S0_ENGINE_RUNNING, SRC_ENGINE)
    fun accessoryPower(p: ByteArray?) = bit(p, OFF_S0, S0_RAP, SRC_LIGHTS)
    fun keyIn(p: ByteArray?) = bit(p, OFF_S0, S0_KEY_IN, SRC_KEY)
    fun reverse(p: ByteArray?) = bit(p, OFF_S0, S0_REVERSE, SRC_ASSIST)
    fun parkingBrake(p: ByteArray?) = bit(p, OFF_S0, S0_PARKING_BRAKE, SRC_PARK_BRAKE)
    fun brakePedal(p: ByteArray?) = bit(p, OFF_S1, S1_BRAKE, SRC_ASSIST)
    fun beltUnfastened(p: ByteArray?) = bit(p, OFF_S1, S1_BELT_UNFASTENED, SRC_BODY)

    /** Doors and hatch (CAN order FL, FR, RR, RL); there is no bonnet signal, so [DoorBits.bonnet] is always false. */
    fun doors(p: ByteArray?): DoorBits? {
        if (!sourceAlive(p, SRC_BODY)) return null
        val b = u8(p, OFF_S1) ?: return null
        return DoorBits(
            frontLeft = b and S1_DOOR_FL != 0,
            frontRight = b and S1_DOOR_FR != 0,
            rearLeft = b and S1_DOOR_RL != 0,
            rearRight = b and S1_DOOR_RR != 0,
            tailgate = b and S1_HATCH != 0,
            bonnet = false
        )
    }

    /** Turn signals (already debounced by the box), hazard and the exterior lamps; null when `006` is dead. */
    fun lights(p: ByteArray?): LightBits? {
        if (!sourceAlive(p, SRC_LIGHTS)) return null
        val b = u8(p, OFF_S2) ?: return null
        return LightBits(
            left = b and S2_LEFT != 0,
            right = b and S2_RIGHT != 0,
            hazard = b and S2_HAZARD != 0,
            position = b and S2_POSITION != 0,
            lowBeam = b and S2_LOW != 0,
            highBeam = b and S2_HIGH != 0,
            frontFog = b and S2_FRONT_FOG != 0,
            rearFog = b and S2_REAR_FOG != 0
        )
    }

    /** Engaged wins over armed. */
    fun cruise(p: ByteArray?): CruiseState? {
        if (!sourceAlive(p, SRC_ASSIST)) return null
        val b = u8(p, OFF_S3) ?: return null
        return when {
            b and S3_CRUISE_ENGAGED != 0 -> CruiseState.ENGAGED
            b and S3_CRUISE_ON != 0 -> CruiseState.ARMED
            else -> CruiseState.OFF
        }
    }

    fun acCompressor(p: ByteArray?) = bit(p, OFF_S3, S3_AC, SRC_ASSIST)

    /** The lamps that are lit (S3 and S4, both from `003`); null when `003` is dead, empty when none is lit. */
    fun telltales(p: ByteArray?): Set<Telltale>? {
        if (!sourceAlive(p, SRC_ASSIST)) return null
        val s3 = u8(p, OFF_S3) ?: return null
        val s4 = u8(p, OFF_S4) ?: return null
        return buildSet {
            if (s3 and S3_ESP_OFF != 0) add(Telltale.ESP_OFF)
            if (s3 and S3_MIL != 0) add(Telltale.MIL)
            if (s3 and S3_OIL != 0) add(Telltale.OIL)
            if (s3 and S3_CHARGE != 0) add(Telltale.CHARGE)
            if (s3 and S3_ABS != 0) add(Telltale.ABS)
            if (s4 and S4_ESP != 0) add(Telltale.ESP)
            if (s4 and S4_GLOW != 0) add(Telltale.GLOW)
            if (s4 and S4_WATER_IN_FUEL != 0) add(Telltale.WATER_IN_FUEL)
        }
    }

    /** S4 bits 0-2: 1 4H, 2 4L, 3 shifting; 0 and the rest are unavailable. */
    fun transferCase(p: ByteArray?): TransferCaseRange? {
        if (!sourceAlive(p, SRC_4WD)) return null
        return when ((u8(p, OFF_S4) ?: return null) and S4_4WD_MASK) {
            1 -> TransferCaseRange.HIGH
            2 -> TransferCaseRange.LOW
            3 -> TransferCaseRange.SHIFTING
            else -> null
        }
    }

    /**
     * S5: P R N D, or AutoStick 1..4 (4..7). FF, an unknown code or a dead `003` withdraws the lever (so the
     * consumer reads "no gear", not "park").
     */
    fun gear(p: ByteArray?): CanboxValue.Gear {
        val v = u8(p, OFF_S5)
        if (v == null || !sourceAlive(p, SRC_ASSIST)) return CanboxValue.Gear(null)
        return when (v) {
            0 -> CanboxValue.Gear(GearPosition.PARK)
            1 -> CanboxValue.Gear(GearPosition.REVERSE)
            2 -> CanboxValue.Gear(GearPosition.NEUTRAL)
            3 -> CanboxValue.Gear(GearPosition.DRIVE)
            in 4..7 -> CanboxValue.Gear(GearPosition.MANUAL, v - 3)
            else -> CanboxValue.Gear(null)
        }
    }

    // ------------------------------------------------------------------ FAST

    /** 0xB3 speed: 0.1 km/h to whole km/h (rounded), null for FFFF. */
    fun speedKmh(p: ByteArray?): Int? =
        u16(p, OFF_SPEED)?.takeIf { it in 0..MAX_SPEED_DKMH }?.let { (it + 5) / 10 }

    fun rpm(p: ByteArray?): Int? = u16(p, OFF_RPM)?.takeIf { it in 0..MAX_RPM }

    /** Engine load, 0..100 %; 0 is a real reading (overrun), FF withdraws. */
    fun engineLoadPct(p: ByteArray?): Int? = u8(p, OFF_LOAD)?.takeIf { it in 0..100 }

    /** Fuel flow, 0.01 L/h to L/h; 0 is a real reading (fuel cut), FFFF withdraws. */
    fun fuelFlowLph(p: ByteArray?): Double? =
        u16(p, OFF_FLOW)?.takeIf { it != 0xFFFF }?.let { it / 100.0 }?.takeIf { it <= MAX_FLOW_LPH }

    /** Oil pressure, kPa gauge (4 per count); 0 is a real reading (engine off), FF withdraws. */
    fun oilPressureKpa(p: ByteArray?): Double? =
        u8(p, OFF_OIL)?.takeIf { it != 0xFF }?.let { it * OIL_KPA_PER_COUNT }

    /** Manifold absolute pressure in hPa; FFFF withdraws. */
    fun manifoldHpa(p: ByteArray?): Double? = u16(p, OFF_BOOST)?.takeIf { it != 0xFFFF }?.toDouble()

    // ------------------------------------------------------------------ ENGINE

    /** Coolant, degC; the byte is degC+40 and FF is "not available" (so -40..214). */
    fun coolantTempC(p: ByteArray?): Int? = biasedTemp(p, OFF_COOLANT)

    /** Battery volts, 0.1 V per count; FF withdraws. */
    fun voltage(p: ByteArray?): Double? = u8(p, OFF_VOLTS)?.takeIf { it != 0xFF }?.let { it / 10.0 }

    fun fuelLevelPct(p: ByteArray?): Int? = u8(p, OFF_FUEL_PCT)?.takeIf { it in 0..100 }

    /** Litres in the tank, to a tenth; FFFF withdraws. */
    fun fuelLitres(p: ByteArray?): Double? =
        u16(p, OFF_FUEL_DECILITRES)?.takeIf { it != 0xFFFF }?.let { it / 10.0 }?.takeIf { it <= MAX_TANK_LITRES }

    // ------------------------------------------------------------------ AMBIENT

    /** Outside temperature, 0.1 degC; 0x8000 withdraws. */
    fun outsideTempC(p: ByteArray?): Double? =
        s16(p, OFF_OUTSIDE)?.takeIf { it != OUTSIDE_NOT_AVAILABLE }?.let { it / 10.0 }?.takeIf { it in -50.0..80.0 }

    /** Minutes since the engine ran, 0..254; FF is the car's "re-init" and withdraws. */
    fun minutesSinceRun(p: ByteArray?): Int? = u8(p, OFF_MINUTES)?.takeIf { it != 0xFF }

    // ------------------------------------------------------------------ TRIP

    /** Odometer in km, to a tenth; 0.0 is a reading, FFFFFF withdraws. */
    fun odometerKm(p: ByteArray?): Double? =
        u24(p, OFF_ODOMETER)?.takeIf { it != ODOMETER_NOT_AVAILABLE }?.let { it / 10.0 }

    /** The compass, only when BOTH fields are good (sector 0..7, heading 0..358). 0 / 0 is due north. */
    fun compass(p: ByteArray?): CompassReading? {
        val sector = u8(p, OFF_COMPASS_SECTOR)?.takeIf { it in 0..COMPASS_SECTOR_MAX } ?: return null
        val heading = u16(p, OFF_COMPASS_HEADING)?.takeIf { it in 0..COMPASS_HEADING_MAX } ?: return null
        return CompassReading(sector, heading)
    }

    // ------------------------------------------------------------------ TYRE

    /** Sanity range for the converted tyre temperature, degC. */
    private val TYRE_CELSIUS_SANE = -50..150

    /** The one place that interprets the raw WCM tyre-temperature byte: degC = raw - 50 (confirmed on the car). */
    fun tyreRawToCelsius(raw: Int): Int = raw - 50

    /**
     * 0xB7 (unchanged by v2): one wheel's reading, or null when the payload is short or the position is not 0..4.
     * A pressure outside 0..700 kPa (FFFF among them) or a temperature that is FF or converts outside
     * -40..150 degC is null on its own: a missing temperature must not cost the pressure.
     */
    fun tyre(payload: ByteArray?): TyreReading? {
        val position = u8(payload, OFF_TYRE_POSITION)?.takeIf { it in 0..TYRE_POSITION_MAX } ?: return null
        val kPa = (u16(payload, OFF_TYRE_PRESSURE) ?: return null).takeIf { it in 0..700 }
        val celsius = u8(payload, OFF_TYRE_TEMP)?.takeIf { it != 0xFF }
            ?.let { tyreRawToCelsius(it) }?.takeIf { it in TYRE_CELSIUS_SANE }
        val flags = u8(payload, OFF_TYRE_FLAGS) ?: return null
        return TyreReading(
            position = position,
            kPa = kPa,
            celsius = celsius,
            sensorBatteryLow = flags and TYRE_SENSOR_BATTERY_LOW != 0,
            leak = flags and TYRE_LEAK != 0,
            noSignal = flags and TYRE_NO_SIGNAL != 0,
            lowPressureWarn = flags and TYRE_LOW_WARN != 0
        )
    }
}
