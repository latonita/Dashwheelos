package com.openauto.dash.unit.xyauto

import com.openauto.dash.McuReader
import com.openauto.dash.SpeedCorrection
import com.openauto.dash.Tyre
import com.openauto.dash.TyrePos
import com.openauto.dash.unitBusOutsideTemp
import com.openauto.dash.unitBusRpm
import com.openauto.dash.unitBusSpeed
import com.openauto.dash.unitBusVolts
import com.openauto.dash.vehicle.FUEL_HOLD_MS
import com.openauto.dash.CarLight
import com.openauto.dash.vehicle.Cruise
import com.openauto.dash.vehicle.Gear
import com.openauto.dash.vehicle.IgnitionState
import com.openauto.dash.vehicle.Indicators
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Publisher
import com.openauto.dash.vehicle.Telltale
import com.openauto.dash.vehicle.TransferCase
import com.openauto.unitproto.canbox.CanboxValue
import com.openauto.unitproto.canbox.CruiseState
import com.openauto.unitproto.canbox.GearPosition
import com.openauto.unitproto.canbox.IgnitionPosition
import com.openauto.unitproto.canbox.LightBits
import com.openauto.unitproto.canbox.TransferCaseRange
import com.openauto.unitproto.canbox.TyreReading

/**
 * The one place a canbox's values become the vehicle bus's: what the decoders said ([CanboxValue]) in, keys on
 * the `unit.bus` publisher out. No decoder knows a key and no key knows a canbox.
 *
 * A null inside a value is the heartbeat's "sensor lost": the key is withdrawn (`put(key, null)`), so a dead
 * coolant sender does not leave its last reading on the gauge. A box that falls silent is the bus's business
 * ([com.openauto.dash.UnitBus] takes the whole source down), not this class's.
 *
 * Telemetry v2: the box repeats FAST (`0xB3`) every 100 ms and STATE (`0xB2`) every second, and puts a dead CAN
 * source's fields at their sentinels itself, so the nulls here are the box's own staleness. The time-to-live of a
 * value is only the backstop: [FAST_HOLD_MS] for the fast measurements, [SLOW_HOLD_MS] for the slow frames.
 */
internal class XyautoBusPublisher(
    private val out: Publisher,
    /** The driver's speed correction, as K.VEHICLE_SPEED gets it; the consumption divides by the same figure. */
    private val correctSpeed: (Int) -> Int = SpeedCorrection::corrected,
    /** Diagnostics: where the box's heartbeat counter did not continue by +1 (the box restarted, or frames were lost). */
    private val log: (String) -> Unit = {}
) {

    /** The last heartbeat counter, to notice one that does not continue by +1. */
    private var heartbeat: Int? = null

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
                speedKmh = v.kmh?.let(correctSpeed)?.toFloat()
                consumption()
            }
            is CanboxValue.Rpm -> unitBusRpm(out, v.rpm)
            is CanboxValue.EngineLoadPct -> out.put(K.ENGINE_LOAD, v.pct?.toDouble(), ttlMs = FAST_HOLD_MS)
            is CanboxValue.OilPressureKpa -> out.put(K.OIL_PRESSURE, v.kpa, ttlMs = FAST_HOLD_MS)
            is CanboxValue.ManifoldHpa -> out.put(K.MANIFOLD_PRESSURE, v.hpa, ttlMs = FAST_HOLD_MS)
            is CanboxValue.EngineOffMinutes -> out.put(K.ENGINE_OFF_MINUTES, v.minutes?.toDouble(), ttlMs = SLOW_HOLD_MS)
            is CanboxValue.CoolantC -> out.put(K.COOLANT_TEMP, v.celsius?.toDouble(), ttlMs = SLOW_HOLD_MS)
            is CanboxValue.OutsideTempC -> unitBusOutsideTemp(out, v.celsius)
            is CanboxValue.BatteryVolts -> v.volts?.let { unitBusVolts(out, it) } ?: out.put(K.BATTERY_VOLTS, null)
            // The box holds the fuel gate itself and sends the sentinel only before the first reading or when its source
            // is stale; the level and litres still stay for [FUEL_HOLD_MS] like every other car-fed fuel provider.
            // The percentage comes only from the box's percentage, the litres only from the litres: one writer each.
            // There is no range from the box any more: K.RANGE is Dashwheel's own estimate (litres over consumption).
            is CanboxValue.FuelLevelPct -> out.put(K.FUEL_LEVEL, v.pct?.toDouble(), ttlMs = FUEL_HOLD_MS)
            is CanboxValue.FuelLitres -> out.put(K.FUEL_LITRES, v.litres, ttlMs = FUEL_HOLD_MS)
            // The box's own integral over the last 100 ms, every 100 ms; 0.0 (fuel cut) is a reading.
            is CanboxValue.FuelFlowLph -> {
                out.put(K.FUEL_FLOW, v.lph, ttlMs = FAST_HOLD_MS)
                flowLph = v.lph
                consumption()
            }
            is CanboxValue.Doors -> out.put(
                K.DOORS,
                v.doors?.let {
                    McuReader.DoorState(
                        frontLeft = it.frontLeft, frontRight = it.frontRight,
                        rearLeft = it.rearLeft, rearRight = it.rearRight,
                        tailgate = it.tailgate, bonnet = it.bonnet
                    )
                }
            )
            is CanboxValue.Tyre -> tyre(v.reading)
            // The canbox is the ACC source on a unit that has one; CarPower's broadcast value is published DEGRADED
            // there, so this outranks it while the box talks, and the broadcast takes over when the source goes down.
            is CanboxValue.Acc -> out.put(K.IGNITION, v.on)
            // Position known, meaning not (set in ACC, crank and run alike): no key for it.
            is CanboxValue.IgnBit -> Unit
            is CanboxValue.Reverse -> out.put(K.REVERSE, v.on)
            is CanboxValue.ParkingBrake -> out.put(K.HANDBRAKE, v.on)
            is CanboxValue.EngineRunning -> out.put(K.ENGINE_RUNNING, v.running)
            is CanboxValue.Lights -> out.put(K.HEADLIGHTS, v.on)
            is CanboxValue.Dimmer -> out.put(K.INSTRUMENT_DIMMER, v.pct?.toDouble())
            is CanboxValue.Gear -> out.put(K.GEAR, v.position?.let { gear(it, v.manual) })
            is CanboxValue.OdometerKm -> out.put(K.ODOMETER, v.km)
            is CanboxValue.Ignition -> out.put(K.IGNITION_STATE, v.position?.let(::ignition))
            is CanboxValue.AccessoryPower -> out.put(K.ACCESSORY_POWER, v.on)
            is CanboxValue.KeyIn -> out.put(K.KEY_IN, v.on)
            is CanboxValue.BrakePedal -> out.put(K.BRAKE_PEDAL, v.on)
            is CanboxValue.DriverBelt -> out.put(K.DRIVER_BELT, v.unfastened)
            is CanboxValue.BodyLights -> lights(v.bits)
            is CanboxValue.Cruise -> out.put(K.CRUISE, v.state?.let(::cruise))
            is CanboxValue.AcCompressor -> out.put(K.AC_COMPRESSOR, v.on)
            is CanboxValue.Telltales -> out.put(K.TELLTALES, v.lit?.map { Telltale.valueOf(it.name) }?.toSet())
            is CanboxValue.TransferCase -> out.put(K.TRANSFER_CASE, v.range?.let(::transferCase))
            is CanboxValue.Heartbeat -> {
                val last = heartbeat
                if (last != null && v.counter != (last + 1) and 0xFF) {
                    log("heartbeat counter " + v.counter + " after " + last + ": box restarted or frames lost")
                }
                heartbeat = v.counter
            }
            is CanboxValue.Compass -> out.put(K.CAR_HEADING, v.reading?.headingDeg?.toDouble())
            is CanboxValue.Version -> out.put(K.CANBOX_VERSION, v.text)
        }
    }

    /**
     * Instant consumption (L/100 km) from the last flow and speed, so the Car status tile has it in every skin; the box
     * gives only the flow. Below [MIN_KMH], or with either input withdrawn, the key is withdrawn: at idle the quotient
     * is huge and means nothing. A flow of 0.0 (fuel cut) is a reading and gives 0.0. Speed arrives often, so this is
     * re-put on every fast frame; it lives [FAST_HOLD_MS], so it goes when the frames stop.
     */
    private fun consumption() {
        val flow = flowLph
        val kmh = speedKmh
        val value = if (flow == null || kmh == null || kmh < MIN_KMH) null else flow / kmh * 100.0
        out.put(K.FUEL_CONSUMPTION, value, ttlMs = FAST_HOLD_MS)
    }

    /**
     * The lever to the bus's [Gear]. In the AutoStick range the box says the digit the cluster shows (1..4):
     * [Gear.Forward] with it (whether that is the gear in use or only a held limit is the spec's open question 7);
     * a stock box that does not say it reads as Drive.
     */
    private fun gear(p: GearPosition, manual: Int?): Gear = when (p) {
        GearPosition.PARK -> Gear.Park
        GearPosition.REVERSE -> Gear.Reverse
        GearPosition.NEUTRAL -> Gear.Neutral
        GearPosition.DRIVE -> Gear.Drive
        GearPosition.MANUAL -> manual?.let { Gear.Forward(it) } ?: Gear.Drive
    }

    private fun ignition(p: IgnitionPosition) = when (p) {
        IgnitionPosition.NO_KEY -> IgnitionState.NO_KEY
        IgnitionPosition.KEY_OFF -> IgnitionState.KEY_OFF
        IgnitionPosition.ACC -> IgnitionState.ACC
        IgnitionPosition.ON -> IgnitionState.ON
        IgnitionPosition.CRANK -> IgnitionState.CRANK
    }

    private fun cruise(s: CruiseState) = when (s) {
        CruiseState.OFF -> Cruise.OFF
        CruiseState.ARMED -> Cruise.ARMED
        CruiseState.ENGAGED -> Cruise.ENGAGED
    }

    private fun transferCase(r: TransferCaseRange) = when (r) {
        TransferCaseRange.HIGH -> TransferCase.HIGH
        TransferCaseRange.LOW -> TransferCase.LOW
        TransferCaseRange.SHIFTING -> TransferCase.SHIFTING
    }

    /**
     * The turn signals and the lamps, all from one CAN id, so all or none: withdrawn together when it is dead. The
     * hazard lights both arrows; the lamp list is built the way the car app's is ([com.openauto.dash.lightsOn]):
     * the hazards, the strongest of beam / dipped / side lights, then the fogs.
     */
    private fun lights(b: LightBits?) {
        if (b == null) {
            out.put(K.INDICATORS, null)
            out.put(K.LIGHTS, null)
            return
        }
        out.put(K.INDICATORS, Indicators(left = b.left || b.hazard, right = b.right || b.hazard, hazard = b.hazard))
        out.put(K.LIGHTS, buildList {
            if (b.hazard) add(CarLight.HAZARD)
            when {
                b.highBeam -> add(CarLight.MAIN_BEAM)
                b.lowBeam -> add(CarLight.DIPPED)
                b.position -> add(CarLight.SIDELIGHTS)
            }
            if (b.frontFog) add(CarLight.FRONT_FOG)
            if (b.rearFog) add(CarLight.REAR_FOG)
        })
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
                celsius = r.celsius,   // degC, null when the box has none (FF)
                sensorBatteryLow = r.sensorBatteryLow, leak = r.leak, noSignal = r.noSignal,
                lowPressureWarn = r.lowPressureWarn,
                at = out.clock()
            )
        }
        out.put(K.TYRES, tyres.toMap().takeIf { it.isNotEmpty() })
    }

    private companion object {
        /** The same floor as the futuristic skin's drive stats. */
        const val MIN_KMH = 5f
        /** FAST (`0xB3`) comes every 100 ms: ten missed frames and the value is gone (the box also sends a sentinel). */
        const val FAST_HOLD_MS = 1_000L
        /** The slow lane comes within 1.44 s; STATE every second. */
        const val SLOW_HOLD_MS = 5_000L
    }
}
