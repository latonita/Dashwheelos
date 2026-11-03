package com.openauto.dash.unit.xyauto

import com.openauto.dash.McuReader
import com.openauto.dash.Tyre
import com.openauto.dash.UnitBus
import com.openauto.dash.TyrePos
import com.openauto.dash.unitBusOutsideTemp
import com.openauto.dash.unitBusRpm
import com.openauto.dash.unitBusSpeed
import com.openauto.dash.unitBusVolts
import com.openauto.dash.vehicle.Gear
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Publisher
import com.openauto.unitproto.canbox.CanboxValue
import com.openauto.unitproto.canbox.GearPosition
import com.openauto.unitproto.canbox.TyreReading

/**
 * The one place a canbox's values become the vehicle bus's: what the decoders said ([CanboxValue]) in, keys on
 * the `unit.bus` publisher out. No decoder knows a key and no key knows a canbox.
 *
 * A null inside a value is the heartbeat's "sensor lost": the key is withdrawn (`put(key, null)`), so a dead
 * coolant sender does not leave its last reading on the gauge. A box that falls silent is the bus's business
 * ([com.openauto.dash.UnitBus] takes the whole source down), not this class's.
 *
 * Every value has a key. Speed and revs live [com.openauto.dash.UnitBus.SAY_HOLD_MS] (the box repeats each frame
 * within about 10 s even when nothing changes); the other measurements keep their key's own time, which is longer.
 */
internal class XyautoBusPublisher(private val out: Publisher) {

    /** The wheels with a pressure; a wheel the box has none for is not in it. */
    private val tyres = HashMap<TyrePos, Tyre>()

    /** The last flow (L/h) and speed (km/h) the box said; null is withdrawn. [consumption] works from these two. */
    private var flowLph: Double? = null
    private var speedKmh: Float? = null

    fun apply(values: List<CanboxValue>) = values.forEach(::apply)

    fun apply(v: CanboxValue) {
        when (v) {
            is CanboxValue.SpeedKmh -> {
                unitBusSpeed(out, v.kmh?.toFloat())
                speedKmh = v.kmh?.toFloat()
                consumption()
            }
            is CanboxValue.Rpm -> unitBusRpm(out, v.rpm)
            is CanboxValue.EngineLoadPct -> out.put(K.ENGINE_LOAD, v.pct?.toDouble())
            is CanboxValue.ThrottlePct -> out.put(K.THROTTLE, v.pct?.toDouble())
            is CanboxValue.CoolantC -> out.put(K.COOLANT_TEMP, v.celsius?.toDouble())
            is CanboxValue.IntakeAirC -> out.put(K.INTAKE_TEMP, v.celsius?.toDouble())
            is CanboxValue.OutsideTempC -> unitBusOutsideTemp(out, v.celsius)
            is CanboxValue.BatteryVolts -> v.volts?.let { unitBusVolts(out, it) } ?: out.put(K.BATTERY_VOLTS, null)
            // The percentage comes only from the box's percentage (0xB4), the litres only from the litres (0xB8):
            // one writer each, and no guess at a tank size.
            is CanboxValue.FuelLevelPct -> out.put(K.FUEL_LEVEL, v.pct?.toDouble())
            is CanboxValue.FuelLitres -> out.put(K.FUEL_LITRES, v.litres)
            is CanboxValue.RangeKm -> out.put(K.RANGE, v.km?.toDouble())
            // The box repeats an unchanged flow only about every 11.5 s, so the key's 5 s would drop a steady reading;
            // the box withdraws it itself (0xFFFF) within about a second of the engine or bus stopping.
            is CanboxValue.FuelFlowLph -> {
                out.put(K.FUEL_FLOW, v.lph, ttlMs = UnitBus.FLOW_HOLD_MS)
                flowLph = v.lph
                consumption()
            }
            is CanboxValue.Doors -> out.put(
                K.DOORS,
                McuReader.DoorState(
                    frontLeft = v.doors.frontLeft, frontRight = v.doors.frontRight,
                    rearLeft = v.doors.rearLeft, rearRight = v.doors.rearRight,
                    tailgate = v.doors.tailgate, bonnet = v.doors.bonnet
                )
            )
            is CanboxValue.Tyre -> tyre(v.reading)
            is CanboxValue.Reverse -> out.put(K.REVERSE, v.on)
            is CanboxValue.ParkingBrake -> out.put(K.HANDBRAKE, v.on)
            is CanboxValue.EngineRunning -> out.put(K.ENGINE_RUNNING, v.running)
            is CanboxValue.Lights -> out.put(K.HEADLIGHTS, v.on)
            is CanboxValue.Dimmer -> out.put(K.INSTRUMENT_DIMMER, v.pct?.toDouble())
            is CanboxValue.Gear -> out.put(K.GEAR, v.position?.let(::gear))
            is CanboxValue.OdometerKm -> out.put(K.ODOMETER, v.km)
            is CanboxValue.Compass -> out.put(K.CAR_HEADING, v.reading?.headingDeg?.toDouble())
            is CanboxValue.Version -> out.put(K.CANBOX_VERSION, v.text)
        }
    }

    /**
     * Instant consumption (L/100 km) from the last flow and speed, so the Car status tile has it in every skin; the box
     * gives only the flow. Below [MIN_KMH], or with either input withdrawn, the key is withdrawn: at idle the quotient
     * is huge and means nothing. A flow of 0.0 (fuel cut) is a reading and gives 0.0. Speed arrives often, so this is
     * re-put on every frame with the flow's hold, which keeps a steady cruise alive between the box's flow repeats.
     */
    private fun consumption() {
        val flow = flowLph
        val kmh = speedKmh
        val value = if (flow == null || kmh == null || kmh < MIN_KMH) null else flow / kmh * 100.0
        out.put(K.FUEL_CONSUMPTION, value, ttlMs = UnitBus.FLOW_HOLD_MS)
    }

    /**
     * The lever to the bus's [Gear]. S is the AutoStick range, in which the driver holds a gear from 1 to 4 that the
     * box does not say; [Gear.Forward] needs the number, and a guess would show a wrong gear, so S reads as Drive
     * (the automatic is in a forward range, which is all that is known).
     */
    private fun gear(p: GearPosition): Gear = when (p) {
        GearPosition.PARK -> Gear.Park
        GearPosition.REVERSE -> Gear.Reverse
        GearPosition.NEUTRAL -> Gear.Neutral
        GearPosition.DRIVE, GearPosition.MANUAL -> Gear.Drive
    }

    private fun tyre(r: TyreReading) {
        val pos = when (r.position) {
            TyreReading.FRONT_LEFT -> TyrePos.FRONT_LEFT
            TyreReading.FRONT_RIGHT -> TyrePos.FRONT_RIGHT
            TyreReading.REAR_LEFT -> TyrePos.REAR_LEFT
            TyreReading.REAR_RIGHT -> TyrePos.REAR_RIGHT
            TyreReading.SPARE -> TyrePos.SPARE
            else -> return
        }
        val kPa = r.kPa
        if (kPa == null) {
            // No pressure (not polled yet, or lost): the wheel is absent, not a stale number.
            if (tyres.remove(pos) == null) return
        } else {
            tyres[pos] = Tyre(
                kPa = kPa,
                celsius = r.celsius,   // null on the WK: the WCM's reply carries no temperature
                sensorBatteryLow = r.sensorBatteryLow, leak = r.leak, noSignal = r.noSignal,
                at = out.clock()
            )
        }
        out.put(K.TYRES, tyres.toMap().takeIf { it.isNotEmpty() })
    }

    private companion object {
        /** The same floor as the futuristic skin's drive stats. */
        const val MIN_KMH = 5f
    }
}
