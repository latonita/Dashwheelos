package com.openauto.unitproto.canbox

import com.openauto.unitproto.canbox.CanBytes.biasedTemp
import com.openauto.unitproto.canbox.CanBytes.s16
import com.openauto.unitproto.canbox.CanBytes.u16
import com.openauto.unitproto.canbox.CanBytes.u24
import com.openauto.unitproto.canbox.CanBytes.u8

/**
 * The rebuilt JPF2 box (firmware H1N4JPF20L): the stock JPF2 stream, which it
 * keeps sending, plus a custom telemetry block in `0xB0`-`0xBD`.
 *
 * The block sits there on purpose. A custom firmware is built on top of the
 * vendor's, which keeps talking: JPF2 alone already uses `0x21`-`0x24`,
 * `0x10` and `0x30`, so the obvious low numbers would have been decoded as
 * two different things at once. `0xB0`-`0xBF` is the one contiguous block no
 * documented Hiworld command touches, and six spare bytes are left in it.
 *
 * It wraps the stock decoder rather than replacing it, and takes back two of
 * its values: the block carries revs and reverse itself (and its heartbeat
 * can withdraw them), so the same two out of stock `0x32` and `0x72` would
 * put them straight back after a withdrawal.
 *
 * The contract is `CUSTOM-TX-SPEC.md` of the canbox rebuild; both sides have
 * to change together, which is why every command lives in [Jpf20lCmd].
 */
class Jpf20lDecoder : HiworldJpf2Decoder() {
    override val id = "hiworld-jpf20l"

    override fun matches(version: String) = version.startsWith("H1N4JPF20L")

    override fun decode(frame: CanFrame): List<CanboxValue> {
        if (frame.cmd !in 0xB0..0xBF) {
            return super.decode(frame).filterNot { it is CanboxValue.Rpm || it is CanboxValue.Reverse }
        }
        val p = frame.payload
        return when (frame.cmd) {
            Jpf20lCmd.HEARTBEAT -> withdrawals(p)
            Jpf20lCmd.IGNITION -> {
                val bits = u8(p, 0) ?: return emptyList()
                listOf(
                    CanboxValue.Reverse(bits and Jpf20lSignals.IGNITION_REVERSE != 0),
                    CanboxValue.ParkingBrake(bits and Jpf20lSignals.IGNITION_PARKING_BRAKE != 0),
                    // The box's own crank/run state machine: it ranks ahead of any
                    // revs-over-a-threshold guess a consumer would make.
                    CanboxValue.EngineRunning(bits and Jpf20lSignals.IGNITION_ENGINE_RUNNING != 0)
                )
            }
            Jpf20lCmd.DRIVETRAIN -> listOfNotNull(
                Jpf20lSignals.speedKmh(p)?.let { CanboxValue.SpeedKmh(it) },
                Jpf20lSignals.rpm(p)?.let { CanboxValue.Rpm(it) },
                Jpf20lSignals.engineLoadPct(p)?.let { CanboxValue.EngineLoadPct(it) },
                Jpf20lSignals.throttlePct(p)?.let { CanboxValue.ThrottlePct(it) }
            )
            Jpf20lCmd.ENGINE -> listOfNotNull(
                Jpf20lSignals.voltage(p)?.let { CanboxValue.BatteryVolts(it) },
                Jpf20lSignals.coolantTempC(p)?.let { CanboxValue.CoolantC(it) },
                Jpf20lSignals.intakeTempC(p)?.let { CanboxValue.IntakeAirC(it) },
                Jpf20lSignals.fuelLevelPct(p)?.let { CanboxValue.FuelLevelPct(it) }
            )
            Jpf20lCmd.FUEL -> listOfNotNull(
                Jpf20lSignals.fuelLitres(p)?.let { CanboxValue.FuelLitres(it) },
                Jpf20lSignals.rangeKm(p)?.let { CanboxValue.RangeKm(it) }
            )
            Jpf20lCmd.CLIMATE -> listOfNotNull(Jpf20lSignals.outsideTempC(p)?.let { CanboxValue.OutsideTempC(it) })
            Jpf20lCmd.BODY -> listOfNotNull(Jpf20lSignals.doors(p)?.let { CanboxValue.Doors(it) })
            Jpf20lCmd.TYRE -> listOfNotNull(Jpf20lSignals.tyre(p)?.let { CanboxValue.Tyre(it) })
            Jpf20lCmd.ODOMETER -> listOfNotNull(Jpf20lSignals.odometerKm(p)?.let { CanboxValue.OdometerKm(it) })
            // Magnetic, and NOT a sign of life: the car's compass keeps
            // reporting after key-off. Ignition is 0xB2 bit 0, never this.
            Jpf20lCmd.COMPASS -> listOfNotNull(Jpf20lSignals.compass(p)?.let { CanboxValue.Compass(it) })
            else -> emptyList()
        }
    }

    /**
     * The once-a-second heartbeat, and the only way to tell "the box is alive
     * but has lost this sensor" from "this value simply is not changing".
     *
     * A cleared bit withdraws that group. Without this a dead coolant sender
     * leaves its last reading on the gauge for the rest of the drive, which is
     * indistinguishable from a healthy engine at that temperature.
     */
    private fun withdrawals(p: ByteArray): List<CanboxValue> = buildList {
        fun lost(cmd: Int) = Jpf20lSignals.heartbeatValid(p, cmd) == false
        if (lost(Jpf20lCmd.DRIVETRAIN)) {
            add(CanboxValue.SpeedKmh(null)); add(CanboxValue.Rpm(null))
            add(CanboxValue.EngineLoadPct(null)); add(CanboxValue.ThrottlePct(null))
        }
        if (lost(Jpf20lCmd.ENGINE)) {
            add(CanboxValue.CoolantC(null)); add(CanboxValue.IntakeAirC(null))
            add(CanboxValue.FuelLevelPct(null)); add(CanboxValue.BatteryVolts(null))
        }
        if (lost(Jpf20lCmd.IGNITION)) add(CanboxValue.EngineRunning(null))
        if (lost(Jpf20lCmd.FUEL)) { add(CanboxValue.FuelLitres(null)); add(CanboxValue.RangeKm(null)) }
        if (lost(Jpf20lCmd.CLIMATE)) add(CanboxValue.OutsideTempC(null))
        if (lost(Jpf20lCmd.COMPASS)) add(CanboxValue.Compass(null))
        // No withdrawal for the odometer: the mileage is a kept number, not a
        // live gauge, and a lost source just stops advancing it.
    }

    override fun commandName(cmd: Int): String = Jpf20lCmd.name(cmd) ?: super.commandName(cmd)
}

/**
 * The command byte of each signal group in the custom block, and the
 * layout of its payloads (see [Jpf20lSignals]).
 */
object Jpf20lCmd {
    /** Box to host, once a second: payload[0] = protocol revision, payload[1] = box state flags. */
    const val HEARTBEAT = 0xB0
    /** Box to host on request: payload is the firmware version as ASCII. */
    const val VERSION = 0xB1
    /** Box to host on change: payload[0] bit0 ACC, bit1 engine running, bit2 reverse, bit3 parking brake. */
    const val IGNITION = 0xB2

    /** Speed, revs, load and throttle. */
    const val DRIVETRAIN = 0xB3
    /** Coolant, intake, fuel level and battery voltage. */
    const val ENGINE = 0xB4
    /** Outside air temperature. */
    const val CLIMATE = 0xB5
    /** The door / lid bitmask. */
    const val BODY = 0xB6
    /** One tyre's pressure, temperature and sensor state; one frame per wheel. */
    const val TYRE = 0xB7
    /**
     * What is actually in the tank, and how far the car reckons it will go.
     * Litres rather than a percentage: a car that measures its tank knows
     * litres, and the dashboard turns them back into a level against the tank
     * size in the driver's car profile.
     */
    const val FUEL = 0xB8

    /** Box to host: a wheel or panel key; payload[0] = code, payload[1] = 1 down / 0 up. */
    const val KEY = 0xB9

    /**
     * The car's own odometer, to 100 m. No trip counter: a trip needs state
     * kept across power cycles, which the box does not keep.
     */
    const val ODOMETER = 0xBB
    /** The car's compass: the 8-way sector it shows, and the magnetic heading to 2 degrees. */
    const val COMPASS = 0xBD

    /** Every command of the block [Jpf20lDecoder] has a decoder for; always inside `0xB0`-`0xBF`. */
    val DECODED = setOf(HEARTBEAT, IGNITION, DRIVETRAIN, ENGINE, CLIMATE, BODY, TYRE, FUEL, ODOMETER, COMPASS)

    /** A short name for the command, for logs and the Signal Finder; null outside the block. */
    fun name(cmd: Int): String? = when (cmd) {
        HEARTBEAT -> "heartbeat"; VERSION -> "version"; IGNITION -> "ignition"
        DRIVETRAIN -> "drivetrain"; ENGINE -> "engine"; CLIMATE -> "climate"; BODY -> "body"; TYRE -> "tyre"
        FUEL -> "fuel"; ODOMETER -> "odometer"; COMPASS -> "compass"
        KEY -> "key"
        else -> null
    }
}

/**
 * The payload layout of each command of the custom block, and the pure
 * decoders over it.
 *
 * Offsets are into a [CanFrame]'s payload - the cmd byte is already stripped.
 * Every multi-byte field is big-endian, and every temperature travels as a
 * byte biased by +40 so the box's 8-bit MCU never has to deal with a sign:
 *
 * ```
 * 0xB3 DRIVETRAIN  [0..1] u16  speed      km/h
 *                  [2..3] u16  revs       rpm
 *                  [4]    u8   load       %
 *                  [5]    u8   throttle   %
 * 0xB4 ENGINE      [0]    u8   coolant    degC + 40
 *                  [1]    u8   intake     degC + 40
 *                  [2]    u8   fuel       %
 *                  [3..4] u16  battery    centivolts (1234 = 12.34 V)
 * 0xB5 CLIMATE     [0..1] s16  outside    decidegrees C (-123 = -12.3 degC)
 * 0xB6 BODY        [0]    bits 0 FL, 1 FR, 2 RL, 3 RR, 4 tailgate, 5 bonnet; 1 = open
 * 0xB7 TYRE        [0]    u8   position   0 FL, 1 FR, 2 RR, 3 RL, 4 spare
 *                  [1..2] u16  pressure   kPa; FFFF = not available
 *                  [3]    u8   temp       degC + 40; FF = not available
 *                  [4]    bits 0 sensor battery low, 1 leak, 2 no signal
 * 0xB8 FUEL        [0..1] u16  tank       0.1 L; FFFF = not available
 *                  [2..3] u16  range      km; FFFF = not available
 * 0xBB ODOMETER    [0..2] u24  odometer   0.1 km; FFFFFF = not available
 * 0xBD COMPASS     [0]    u8   sector     0 N .. 7 NW; FF = not available
 *                  [1..2] u16  heading    magnetic degrees, 0..358 in 2s; FFFF = not available
 * ```
 *
 * Every decoder returns null rather than a wrong number: a payload too short
 * for its field, or a value outside what the car can physically produce, is
 * "not available". That is deliberate - the box firmware sends 0xFF fillers
 * for the signals a given car does not have, and for most fields that filler
 * falls out of range by construction. The exceptions are the two engine
 * temperatures, whose full byte maps onto a legal -40..215 degC, which is why
 * [CanBytes.TEMP_NOT_AVAILABLE] is reserved.
 */
object Jpf20lSignals {
    // Offsets, named so the box firmware's own header can be generated from them.
    const val OFF_SPEED = 0
    const val OFF_RPM = 2
    const val OFF_LOAD = 4
    const val OFF_THROTTLE = 5

    const val OFF_COOLANT = 0
    const val OFF_INTAKE = 1
    const val OFF_FUEL = 2
    const val OFF_VOLTAGE = 3

    const val OFF_OUTSIDE_TEMP = 0

    const val OFF_DOORS = 0

    /** [Jpf20lCmd.FUEL]: tenths of a litre, then whole kilometres. */
    const val OFF_FUEL_DECILITRES = 0
    const val OFF_FUEL_RANGE = 2

    /** [Jpf20lCmd.ODOMETER]: tenths of a kilometre, 24-bit. */
    const val OFF_ODOMETER = 0
    /** All-ones: the car has not said, or has stopped saying. Never 1 677 721.5 km. */
    const val ODOMETER_NOT_AVAILABLE = 0xFFFFFF

    /** [Jpf20lCmd.COMPASS]: the car's sector byte, then the heading in whole degrees. */
    const val OFF_COMPASS_SECTOR = 0
    const val OFF_COMPASS_HEADING = 1
    /** The last of the eight sectors (NW). */
    const val COMPASS_SECTOR_MAX = 7
    /** The heading travels at 2-degree resolution, so 358 is the top. */
    const val COMPASS_HEADING_MAX = 358

    /** No production car carries this much; past it the frame is wrong, not thirsty. */
    const val MAX_TANK_LITRES = 200.0
    /** The dashboard rejects anything further as implausible, so there is no point sending it. */
    const val MAX_RANGE_KM = 2000

    const val OFF_TYRE_POSITION = 0
    const val OFF_TYRE_PRESSURE = 1
    const val OFF_TYRE_TEMP = 3
    const val OFF_TYRE_FLAGS = 4

    /** [Jpf20lCmd.HEARTBEAT]: revision, then the per-command validity bitmask. */
    const val OFF_HEARTBEAT_REV = 0
    const val OFF_HEARTBEAT_VALID = 1

    // [Jpf20lCmd.IGNITION] bits. Bit 0, ACC, is deliberately not read: the
    // dashboard's power-up detection has its own sources.
    const val IGNITION_ENGINE_RUNNING = 0x02
    const val IGNITION_REVERSE = 0x04
    const val IGNITION_PARKING_BRAKE = 0x08

    // Door bits.
    const val DOOR_FRONT_LEFT = 0x01
    const val DOOR_FRONT_RIGHT = 0x02
    const val DOOR_REAR_LEFT = 0x04
    const val DOOR_REAR_RIGHT = 0x08
    const val DOOR_TAILGATE = 0x10
    const val DOOR_BONNET = 0x20

    // Tyre flag bits.
    const val TYRE_SENSOR_BATTERY_LOW = 0x01
    const val TYRE_LEAK = 0x02
    const val TYRE_NO_SIGNAL = 0x04

    /** The highest wheel index the box may report ([TyreReading.SPARE]). */
    const val TYRE_POSITION_MAX = TyreReading.SPARE

    /** 0xB3 [OFF_SPEED]: road speed in km/h, 0..400. */
    fun speedKmh(payload: ByteArray?): Int? = u16(payload, OFF_SPEED)?.takeIf { it in 0..400 }

    /** 0xB3 [OFF_RPM]: engine speed, 0..12000. */
    fun rpm(payload: ByteArray?): Int? = u16(payload, OFF_RPM)?.takeIf { it in 0..12_000 }

    /** 0xB3 [OFF_LOAD]: calculated engine load, 0..100 %. */
    fun engineLoadPct(payload: ByteArray?): Int? = u8(payload, OFF_LOAD)?.takeIf { it in 0..100 }

    /** 0xB3 [OFF_THROTTLE]: throttle position, 0..100 %. */
    fun throttlePct(payload: ByteArray?): Int? = u8(payload, OFF_THROTTLE)?.takeIf { it in 0..100 }

    /** 0xB4 [OFF_COOLANT]: coolant temperature, -40..215 degC. */
    fun coolantTempC(payload: ByteArray?): Int? = biasedTemp(payload, OFF_COOLANT)?.takeIf { it in -40..215 }

    /** 0xB4 [OFF_INTAKE]: intake air temperature, -40..215 degC. */
    fun intakeTempC(payload: ByteArray?): Int? = biasedTemp(payload, OFF_INTAKE)?.takeIf { it in -40..215 }

    /** 0xB4 [OFF_FUEL]: fuel level, 0..100 %. */
    fun fuelLevelPct(payload: ByteArray?): Int? = u8(payload, OFF_FUEL)?.takeIf { it in 0..100 }

    /** 0xB4 [OFF_VOLTAGE]: battery voltage from centivolts, 0.0..30.0 V. */
    fun voltage(payload: ByteArray?): Double? =
        u16(payload, OFF_VOLTAGE)?.let { it / 100.0 }?.takeIf { it in 0.0..30.0 }

    /** 0xB5 [OFF_OUTSIDE_TEMP]: outside air temperature from decidegrees, -50.0..80.0 degC. */
    fun outsideTempC(payload: ByteArray?): Double? =
        s16(payload, OFF_OUTSIDE_TEMP)?.let { it / 10.0 }?.takeIf { it in -50.0..80.0 }

    /**
     * 0xB8: litres in the tank, to a tenth. A car that has no gauge reading
     * sends no frame rather than a zero: an empty tank and an unknown one are
     * different things, and the dashboard treats a flat zero as the latter.
     */
    fun fuelLitres(payload: ByteArray?): Double? =
        u16(payload, OFF_FUEL_DECILITRES)
            ?.takeIf { it != 0xFFFF }
            ?.let { it / 10.0 }
            ?.takeIf { it in 0.0..MAX_TANK_LITRES }

    /** 0xB8: the car's own distance to empty. 0xFFFF is its "not available". */
    fun rangeKm(payload: ByteArray?): Int? =
        u16(payload, OFF_FUEL_RANGE)
            ?.takeIf { it != 0xFFFF && it != 0x7FFF }
            ?.takeIf { it in 0..MAX_RANGE_KM }

    /**
     * Whether [cmd]'s data is live, per the validity bitmask in
     * [Jpf20lCmd.HEARTBEAT] payload[1]. A cleared bit means the box is alive
     * and has **lost** that source - the one case a consumer cannot tell from
     * a healthy quiet signal by timing alone.
     *
     * Null when the payload is short or [cmd] has no bit, which means "no
     * opinion": never treat that as invalid.
     */
    fun heartbeatValid(payload: ByteArray?, cmd: Int): Boolean? {
        val bit = when (cmd) {
            Jpf20lCmd.IGNITION -> 0
            Jpf20lCmd.DRIVETRAIN -> 1
            Jpf20lCmd.ENGINE -> 2
            Jpf20lCmd.CLIMATE -> 3
            Jpf20lCmd.TYRE -> 4
            Jpf20lCmd.FUEL -> 5
            // Allocation order, not command order. These two fill the byte:
            // the next command needs a second mask byte, which is a length
            // change to 0xB0 and moves together with the firmware.
            Jpf20lCmd.ODOMETER -> 6
            Jpf20lCmd.COMPASS -> 7
            else -> return null
        }
        val mask = u8(payload, OFF_HEARTBEAT_VALID) ?: return null
        return mask and (1 shl bit) != 0
    }

    /** The box's protocol revision, [Jpf20lCmd.HEARTBEAT] payload[0]. */
    fun heartbeatRevision(payload: ByteArray?): Int? = u8(payload, OFF_HEARTBEAT_REV)

    /**
     * 0xBB: the odometer in km, to a tenth, or null when the box sent
     * [ODOMETER_NOT_AVAILABLE] or the payload is short. A verbatim copy of the
     * car's own counter, so 0.0 is a reading (a new car), not a blank.
     */
    fun odometerKm(payload: ByteArray?): Double? =
        u24(payload, OFF_ODOMETER)?.takeIf { it != ODOMETER_NOT_AVAILABLE }?.let { it / 10.0 }

    /**
     * 0xBD: the compass, or null unless BOTH fields are good - the same gate
     * the firmware applies, so a sector of 9 is not read as anything, and a
     * heading of 0xFFFF is "not available", never 65535 degrees.
     *
     * 0 / 0 is due north, a real reading; the blank is FF FF FF.
     */
    fun compass(payload: ByteArray?): CompassReading? {
        val sector = u8(payload, OFF_COMPASS_SECTOR)?.takeIf { it in 0..COMPASS_SECTOR_MAX } ?: return null
        val heading = u16(payload, OFF_COMPASS_HEADING)?.takeIf { it in 0..COMPASS_HEADING_MAX } ?: return null
        return CompassReading(sector, heading)
    }

    /** 0xB6: which doors and lids are open, or null for a payload too short. */
    fun doors(payload: ByteArray?): DoorBits? {
        val bits = u8(payload, OFF_DOORS) ?: return null
        return DoorBits(
            frontLeft = bits and DOOR_FRONT_LEFT != 0,
            frontRight = bits and DOOR_FRONT_RIGHT != 0,
            rearLeft = bits and DOOR_REAR_LEFT != 0,
            rearRight = bits and DOOR_REAR_RIGHT != 0,
            tailgate = bits and DOOR_TAILGATE != 0,
            bonnet = bits and DOOR_BONNET != 0
        )
    }

    /**
     * 0xB7: one wheel's reading, or null when the payload is short or the
     * position is not 0..4. A pressure outside 0..700 kPa (FFFF among them)
     * or a temperature outside -40..120 degC (FF among them) is null on its
     * own: a missing temperature must not cost the pressure. The flag bits
     * are taken as they come - a sensor that reports no signal still has a
     * position worth showing as "--".
     */
    fun tyre(payload: ByteArray?): TyreReading? {
        val position = u8(payload, OFF_TYRE_POSITION)?.takeIf { it in 0..TYRE_POSITION_MAX } ?: return null
        val kPa = (u16(payload, OFF_TYRE_PRESSURE) ?: return null).takeIf { it in 0..700 }
        val celsius = biasedTemp(payload, OFF_TYRE_TEMP)?.takeIf { it in -40..120 }
        val flags = u8(payload, OFF_TYRE_FLAGS) ?: return null
        return TyreReading(
            position = position,
            kPa = kPa,
            celsius = celsius,
            sensorBatteryLow = flags and TYRE_SENSOR_BATTERY_LOW != 0,
            leak = flags and TYRE_LEAK != 0,
            noSignal = flags and TYRE_NO_SIGNAL != 0
        )
    }
}
