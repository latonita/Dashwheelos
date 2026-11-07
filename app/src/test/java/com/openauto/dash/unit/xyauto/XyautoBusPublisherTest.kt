package com.openauto.dash.unit.xyauto

import com.openauto.dash.CarLight
import com.openauto.dash.McuReader.DoorState
import com.openauto.dash.TyrePos
import com.openauto.dash.vehicle.Cruise
import com.openauto.dash.vehicle.Gear
import com.openauto.dash.vehicle.IgnitionState
import com.openauto.dash.vehicle.Indicators
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Key
import com.openauto.dash.vehicle.Source
import com.openauto.dash.vehicle.SourceKind
import com.openauto.dash.vehicle.Telltale
import com.openauto.dash.vehicle.TransferCase
import com.openauto.dash.vehicle.VehicleBus
import com.openauto.unitproto.canbox.CanboxPipeline
import com.openauto.unitproto.canbox.CanboxValue
import com.openauto.unitproto.canbox.CompassReading
import com.openauto.unitproto.canbox.CruiseState
import com.openauto.unitproto.canbox.DoorBits
import com.openauto.unitproto.canbox.GearPosition
import com.openauto.unitproto.canbox.IgnitionPosition
import com.openauto.unitproto.canbox.LightBits
import com.openauto.unitproto.canbox.TransferCaseRange
import com.openauto.unitproto.canbox.Telltale as UnitTelltale
import com.openauto.unitproto.canbox.TyreReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The canbox's values on the vehicle bus: which key each one lands on, and what a heartbeat's "sensor lost" does. */
class XyautoBusPublisherTest {
    private var t = 0L
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val bus = VehicleBus({ t }, scope)
    private val out = bus.register(Source("unit.bus", SourceKind.CANBOX, "Unit bus"))
    private val publisher = XyautoBusPublisher(out)

    @After
    fun stop() = scope.cancel()

    private fun <T : Any> read(key: Key<T>): T? = bus.sample(key).value?.value
    private fun apply(vararg v: CanboxValue) = publisher.apply(v.toList())

    // --- The measurements ------------------------------------------------------------------------------------

    @Test
    fun theGaugesLandOnTheirKeys() {
        apply(
            CanboxValue.SpeedKmh(60), CanboxValue.Rpm(2000), CanboxValue.EngineLoadPct(40), CanboxValue.ThrottlePct(30),
            CanboxValue.CoolantC(90), CanboxValue.OutsideTempC(-12.3), CanboxValue.BatteryVolts(14.3),
            CanboxValue.OilPressureKpa(240.0), CanboxValue.ManifoldHpa(1152.0), CanboxValue.EngineOffMinutes(10)
        )
        assertEquals(60.0, read(K.VEHICLE_SPEED)!!, 0.0)
        assertEquals(2000.0, read(K.RPM)!!, 0.0)
        assertEquals(40.0, read(K.ENGINE_LOAD)!!, 0.0)
        assertEquals(30.0, read(K.THROTTLE)!!, 0.0)
        assertEquals(90.0, read(K.COOLANT_TEMP)!!, 0.0)
        assertEquals(-12.3, read(K.OUTSIDE_TEMP)!!, 0.0)
        assertEquals(14.3, read(K.BATTERY_VOLTS)!!, 0.0)
        assertEquals(240.0, read(K.OIL_PRESSURE)!!, 0.0)
        assertEquals(1152.0, read(K.MANIFOLD_PRESSURE)!!, 0.0)
        assertEquals(10.0, read(K.ENGINE_OFF_MINUTES)!!, 0.0)
        assertNull(read(K.RANGE))                      // the box has no range any more
        assertEquals("unit.bus", bus.sample(K.VEHICLE_SPEED).value!!.source.id)
    }

    @Test
    fun speedAndRevsLiveTheBusHoldAndThenExpire() {
        apply(CanboxValue.SpeedKmh(60), CanboxValue.Rpm(2000))
        t += 9_999
        assertEquals(60.0, bus.now(K.VEHICLE_SPEED)!!, 0.0)
        t += 2
        assertNull(bus.now(K.VEHICLE_SPEED))
        assertNull(bus.now(K.RPM))
    }

    @Test
    fun theTankPercentAndTheTankLitresAreTwoKeysAndNeverConverted() {
        apply(CanboxValue.FuelLitres(41.5))
        assertEquals(41.5, read(K.FUEL_LITRES)!!, 0.0)
        assertNull(read(K.FUEL_LEVEL))            // litres never become a percentage

        apply(CanboxValue.FuelLevelPct(63))
        assertEquals(63.0, read(K.FUEL_LEVEL)!!, 0.0)
        apply(CanboxValue.FuelLitres(40.0))
        assertEquals(63.0, read(K.FUEL_LEVEL)!!, 0.0)   // no second writer to flip it
    }

    // --- The states ------------------------------------------------------------------------------------------

    // --- The ignition: the canbox first, the vendor broadcast as the fallback ----------------------------------

    private val power = bus.register(Source("power", SourceKind.VEHICLE_APP, "Unit power"))

    @Test
    fun theCanboxAccOutranksTheBroadcastAndTheBroadcastTakesOverWhenTheBoxIsGone() {
        power.put(K.IGNITION, false, com.openauto.dash.vehicle.Quality.DEGRADED)    // as CarPower does on a unit with a canbox
        assertEquals(false, read(K.IGNITION))
        assertEquals("power", bus.sample(K.IGNITION).value!!.source.id)

        t = 10; apply(CanboxValue.Acc(true))
        assertEquals(true, read(K.IGNITION))
        assertEquals("unit.bus", bus.sample(K.IGNITION).value!!.source.id)

        // The broadcast speaking later does not win back (the newer-wins tie is broken by quality).
        t = 20; power.put(K.IGNITION, false, com.openauto.dash.vehicle.Quality.DEGRADED)
        assertEquals(true, read(K.IGNITION))

        // The box off: the source goes down, what it said is withdrawn, the broadcast stands.
        out.up(false)
        assertEquals(false, read(K.IGNITION))
        assertEquals("power", bus.sample(K.IGNITION).value!!.source.id)
    }

    @Test
    fun theCanboxSaysAccOffAndOnAgain() {
        power.put(K.IGNITION, true, com.openauto.dash.vehicle.Quality.DEGRADED)
        apply(CanboxValue.Acc(false))
        assertEquals(false, read(K.IGNITION))
        apply(CanboxValue.Acc(true))
        assertEquals(true, read(K.IGNITION))
    }

    @Test
    fun withNoCanboxTheBroadcastIsTheIgnition() {
        power.put(K.IGNITION, true, com.openauto.dash.vehicle.Quality.DEGRADED)
        assertEquals(true, read(K.IGNITION))
    }

    @Test
    fun theBodyStates() {
        apply(
            CanboxValue.Doors(DoorBits(frontLeft = true, frontRight = false, rearLeft = false, rearRight = true, tailgate = false, bonnet = true)),
            CanboxValue.Reverse(true), CanboxValue.ParkingBrake(true), CanboxValue.EngineRunning(true),
            CanboxValue.Lights(true), CanboxValue.Dimmer(0)
        )
        assertEquals(DoorState(frontLeft = true, rearRight = true, bonnet = true), read(K.DOORS))
        assertEquals(true, read(K.REVERSE))
        assertEquals(true, read(K.HANDBRAKE))
        assertEquals(true, read(K.ENGINE_RUNNING))
        assertEquals(true, read(K.HEADLIGHTS))
        assertEquals(0.0, read(K.INSTRUMENT_DIMMER)!!, 0.0)     // 0 is a reading

        apply(CanboxValue.Reverse(false), CanboxValue.ParkingBrake(false), CanboxValue.Lights(false))
        assertEquals(false, read(K.REVERSE))
        assertEquals(false, read(K.HANDBRAKE))
        assertEquals(false, read(K.HEADLIGHTS))
    }

    @Test
    fun theLeverBecomesAGear_andSIsDrive() {
        val expected = mapOf(
            GearPosition.PARK to Gear.Park, GearPosition.REVERSE to Gear.Reverse,
            GearPosition.NEUTRAL to Gear.Neutral, GearPosition.DRIVE to Gear.Drive,
            // AutoStick without a digit (a stock box): no Forward(n) is made up.
            GearPosition.MANUAL to Gear.Drive
        )
        for ((lever, gear) in expected) {
            apply(CanboxValue.Gear(lever))
            assertEquals(lever.name, gear, read(K.GEAR))
        }
        apply(CanboxValue.Gear(null))                    // no reading: withdrawn
        assertNull(read(K.GEAR))
    }

    @Test
    fun autoStickWithItsDigitIsAForwardGear() {
        for (n in 1..4) {
            apply(CanboxValue.Gear(GearPosition.MANUAL, n))
            assertEquals(Gear.Forward(n), read(K.GEAR))
        }
    }

    @Test
    fun fuelFlow_zeroIsAReading_nullWithdraws_andTheFastFrameKeepsItAliveOnlyBriefly() {
        apply(CanboxValue.FuelFlowLph(7.4))
        assertEquals(7.4, read(K.FUEL_FLOW)!!, 0.0)
        t += 900                                         // the box repeats it every 100 ms
        assertEquals(7.4, read(K.FUEL_FLOW)!!, 0.0)
        t += 200                                         // ... so a second of silence drops it
        bus.expireNow()
        assertNull(read(K.FUEL_FLOW))
        apply(CanboxValue.FuelFlowLph(7.4))
        apply(CanboxValue.FuelFlowLph(0.0))              // overrun fuel cut: zero, not blank
        assertEquals(0.0, read(K.FUEL_FLOW)!!, 0.0)
        apply(CanboxValue.FuelFlowLph(null))             // 0xFFFF: the engine or bus stopped
        assertNull(read(K.FUEL_FLOW))
    }

    @Test
    fun consumption_isFlowOverSpeedTimes100() {
        apply(CanboxValue.FuelFlowLph(6.0), CanboxValue.SpeedKmh(80))
        assertEquals(7.5, read(K.FUEL_CONSUMPTION)!!, 1e-9)
    }

    @Test
    fun consumption_belowFiveKmhIsWithdrawn() {
        apply(CanboxValue.FuelFlowLph(1.0), CanboxValue.SpeedKmh(60))
        assertTrue(read(K.FUEL_CONSUMPTION) != null)
        apply(CanboxValue.SpeedKmh(4))
        assertNull(read(K.FUEL_CONSUMPTION))
        apply(CanboxValue.SpeedKmh(0))
        assertNull(read(K.FUEL_CONSUMPTION))
        apply(CanboxValue.SpeedKmh(5))
        assertEquals(20.0, read(K.FUEL_CONSUMPTION)!!, 1e-9)
    }

    @Test
    fun consumption_aFlowWithdrawalWithdrawsIt() {
        apply(CanboxValue.FuelFlowLph(6.0), CanboxValue.SpeedKmh(80))
        apply(CanboxValue.FuelFlowLph(null))
        assertNull(read(K.FUEL_CONSUMPTION))
    }

    @Test
    fun consumption_usesTheCorrectedSpeedLikeTheSpeedKey() {
        val corrected = XyautoBusPublisher(out, correctSpeed = { it + 5 })
        corrected.apply(listOf(CanboxValue.FuelFlowLph(6.0), CanboxValue.SpeedKmh(75)))
        assertEquals(7.5, read(K.FUEL_CONSUMPTION)!!, 1e-9)   // 6 / 80 * 100, not 6 / 75 * 100
    }

    @Test
    fun fuelLevelAndLitresStaySixHours() {
        apply(CanboxValue.FuelLevelPct(63), CanboxValue.FuelLitres(48.9))
        t += 3 * 3_600_000L
        bus.expireNow()
        assertEquals(63.0, read(K.FUEL_LEVEL)!!, 0.0)
        assertEquals(48.9, read(K.FUEL_LITRES)!!, 0.0)
        t += 3 * 3_600_000L + 1_000
        bus.expireNow()
        assertNull(read(K.FUEL_LEVEL))
        assertNull(read(K.FUEL_LITRES))
    }

    @Test
    fun consumption_zeroFlowIsZero() {
        apply(CanboxValue.SpeedKmh(90), CanboxValue.FuelFlowLph(0.0))
        assertEquals(0.0, read(K.FUEL_CONSUMPTION)!!, 0.0)
    }

    @Test
    fun consumption_aSpeedUpdateRecomputesWithTheHeldFlow() {
        apply(CanboxValue.FuelFlowLph(6.0), CanboxValue.SpeedKmh(60))
        assertEquals(10.0, read(K.FUEL_CONSUMPTION)!!, 1e-9)
        t += 500                                         // flow not repeated yet; speed keeps coming
        apply(CanboxValue.SpeedKmh(120))
        assertEquals(5.0, read(K.FUEL_CONSUMPTION)!!, 1e-9)
        t += 900
        assertEquals(5.0, read(K.FUEL_CONSUMPTION)!!, 1e-9)
        t += 200
        bus.expireNow()
        assertNull(read(K.FUEL_CONSUMPTION))             // nobody refreshed it for a second
    }

    @Test
    fun odometerCompassAndVersion() {
        apply(CanboxValue.OdometerKm(123_456.7), CanboxValue.Compass(CompassReading(sector = 6, headingDeg = 266)), CanboxValue.Version("H1N4JPF20L-1"))
        assertEquals(123_456.7, read(K.ODOMETER)!!, 0.0)
        assertEquals(266.0, read(K.CAR_HEADING)!!, 0.0)
        assertEquals("H1N4JPF20L-1", read(K.CANBOX_VERSION))
        apply(CanboxValue.OdometerKm(123_400.0))         // a counter does not go back
        assertEquals(123_456.7, read(K.ODOMETER)!!, 0.0)
        apply(CanboxValue.Compass(null))
        assertNull(read(K.CAR_HEADING))
    }

    // --- Withdrawals ------------------------------------------------------------------------------------------

    @Test
    fun aNullInsideAValueWithdrawsItsKey() {
        apply(
            CanboxValue.SpeedKmh(60), CanboxValue.Rpm(2000), CanboxValue.EngineLoadPct(40), CanboxValue.ThrottlePct(30),
            CanboxValue.CoolantC(90), CanboxValue.FuelLevelPct(50), CanboxValue.BatteryVolts(14.0),
            CanboxValue.EngineRunning(true), CanboxValue.FuelLitres(40.0), CanboxValue.OutsideTempC(10.0),
            CanboxValue.Dimmer(40), CanboxValue.OilPressureKpa(200.0), CanboxValue.ManifoldHpa(1000.0),
            CanboxValue.EngineOffMinutes(3), CanboxValue.OdometerKm(100.0)
        )
        apply(
            CanboxValue.SpeedKmh(null), CanboxValue.Rpm(null), CanboxValue.EngineLoadPct(null), CanboxValue.ThrottlePct(null),
            CanboxValue.CoolantC(null), CanboxValue.FuelLevelPct(null), CanboxValue.BatteryVolts(null),
            CanboxValue.EngineRunning(null), CanboxValue.FuelLitres(null), CanboxValue.OutsideTempC(null),
            CanboxValue.Dimmer(null), CanboxValue.OilPressureKpa(null), CanboxValue.ManifoldHpa(null),
            CanboxValue.EngineOffMinutes(null), CanboxValue.OdometerKm(null)
        )
        listOf(
            K.VEHICLE_SPEED, K.RPM, K.ENGINE_LOAD, K.THROTTLE, K.COOLANT_TEMP, K.FUEL_LEVEL, K.BATTERY_VOLTS,
            K.ENGINE_RUNNING, K.FUEL_LITRES, K.OUTSIDE_TEMP, K.INSTRUMENT_DIMMER,
            K.OIL_PRESSURE, K.MANIFOLD_PRESSURE, K.ENGINE_OFF_MINUTES, K.ODOMETER
        ).forEach { assertNull(it.id, read(it)) }
    }

    /** A real frame: `AA 55 LEN CMD payload CHK`, `CHK = LEN + CMD + sum(payload) - 1`. */
    private fun frame(cmd: Int, vararg payload: Int): ByteArray {
        val chk = (payload.size + cmd + payload.sum() - 1) and 0xFF
        return (listOf(0xAA, 0x55, payload.size, cmd) + payload.toList() + chk).map { it.toByte() }.toByteArray()
    }

    private fun box(): CanboxPipeline = CanboxPipeline().also {
        it.feed(frame(0xF0, *"H1N4JPF20L-1".map { c -> c.code }.toIntArray()))
    }

    // --- Telemetry v2 -----------------------------------------------------------------------------------------

    /** The spec's frames, through the real pipeline: bytes in, keys out. */
    private fun feedHex(pipeline: CanboxPipeline, hex: String) =
        publisher.apply(pipeline.feed(hex.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray())!!.values)

    @Test
    fun theStateFrameLandsOnTheBodyKeys() {
        val pipeline = box()
        feedHex(pipeline, "AA 55 07 B2 2B 04 19 10 01 03 FF 13")
        assertEquals(IgnitionState.ON, read(K.IGNITION_STATE))
        assertEquals(true, read(K.ENGINE_RUNNING))
        assertEquals(false, read(K.ACCESSORY_POWER))
        assertEquals(true, read(K.KEY_IN))
        assertEquals(false, read(K.REVERSE))
        assertEquals(false, read(K.HANDBRAKE))
        assertEquals(false, read(K.BRAKE_PEDAL))
        assertEquals(false, read(K.DRIVER_BELT))
        assertEquals(DoorState(frontLeft = true), read(K.DOORS))
        assertEquals(Indicators(left = true, right = false, hazard = false), read(K.INDICATORS))
        assertEquals(listOf(CarLight.DIPPED), read(K.LIGHTS))
        assertEquals(Cruise.OFF, read(K.CRUISE))
        assertEquals(false, read(K.AC_COMPRESSOR))
        assertEquals(setOf(Telltale.MIL), read(K.TELLTALES))
        assertEquals(TransferCase.HIGH, read(K.TRANSFER_CASE))
        assertEquals(Gear.Drive, read(K.GEAR))
    }

    @Test
    fun aDeadSourceWithdrawsItsKeysAndTheOthersStay() {
        val pipeline = box()
        feedHex(pipeline, "AA 55 07 B2 2B 04 19 10 01 03 FF 13")
        feedHex(pipeline, "AA 55 07 B2 23 04 19 10 01 03 FD 09")     // 002 dead
        assertNull(read(K.ENGINE_RUNNING))                              // unknown, not false
        assertEquals(IgnitionState.ON, read(K.IGNITION_STATE))
        assertEquals(Gear.Drive, read(K.GEAR))

        feedHex(pipeline, "AA 55 07 B2 A1 00 00 00 00 FF A1 F9")     // only 000, 0D0, 012 alive
        for (k in listOf<Key<*>>(
            K.ENGINE_RUNNING, K.ACCESSORY_POWER, K.REVERSE, K.BRAKE_PEDAL, K.DRIVER_BELT, K.DOORS, K.INDICATORS, K.LIGHTS,
            K.CRUISE, K.AC_COMPRESSOR, K.TELLTALES, K.TRANSFER_CASE, K.GEAR
        )) assertNull(k.id, bus.sample(k).value?.value)
        assertEquals(IgnitionState.KEY_OFF, read(K.IGNITION_STATE))
        assertEquals(true, read(K.KEY_IN))
        assertEquals(true, read(K.HANDBRAKE))
    }

    @Test
    fun hazardLightsBothArrowsAndListsTheHazard() {
        apply(CanboxValue.BodyLights(LightBits(left = true, right = true, hazard = true, position = true, lowBeam = false, highBeam = false, frontFog = true, rearFog = true)))
        assertEquals(Indicators(left = true, right = true, hazard = true), read(K.INDICATORS))
        assertEquals(listOf(CarLight.HAZARD, CarLight.SIDELIGHTS, CarLight.FRONT_FOG, CarLight.REAR_FOG), read(K.LIGHTS))
        apply(CanboxValue.BodyLights(LightBits(left = false, right = false, hazard = false, position = true, lowBeam = true, highBeam = true, frontFog = false, rearFog = false)))
        assertEquals(Indicators(left = false, right = false, hazard = false), read(K.INDICATORS))
        assertEquals(listOf(CarLight.MAIN_BEAM), read(K.LIGHTS))          // the strongest beam only, as the car app lists it
        apply(CanboxValue.BodyLights(LightBits(false, false, false, false, false, false, false, false)))
        assertEquals(emptyList<CarLight>(), read(K.LIGHTS))               // all off is a reading, not a withdrawal
        apply(CanboxValue.BodyLights(null))
        assertNull(read(K.INDICATORS))
        assertNull(read(K.LIGHTS))
    }

    @Test
    fun theFastFrameLandsOnItsKeys_andASentinelFrameWithdrawsThem() {
        val pipeline = box()
        feedHex(pipeline, "AA 55 0B B3 02 76 0A 8D 1B 04 B0 3C 04 80 22 7D")
        assertEquals(63.0, read(K.VEHICLE_SPEED)!!, 0.0)
        assertEquals(2701.0, read(K.RPM)!!, 0.0)
        assertEquals(27.0, read(K.ENGINE_LOAD)!!, 0.0)
        assertEquals(34.0, read(K.THROTTLE)!!, 0.0)
        assertEquals(12.0, read(K.FUEL_FLOW)!!, 0.0)
        assertEquals(240.0, read(K.OIL_PRESSURE)!!, 0.0)
        assertEquals(1152.0, read(K.MANIFOLD_PRESSURE)!!, 0.0)
        assertEquals(19.0, read(K.FUEL_CONSUMPTION)!!, 0.1)                 // 12 / 63 * 100
        feedHex(pipeline, "AA 55 0B B3 FF FF FF FF FF FF FF FF FF FF FF B2")
        listOf(K.VEHICLE_SPEED, K.RPM, K.ENGINE_LOAD, K.THROTTLE, K.FUEL_FLOW, K.OIL_PRESSURE, K.MANIFOLD_PRESSURE, K.FUEL_CONSUMPTION)
            .forEach { assertNull(it.id, read(it)) }
    }

    @Test
    fun theSlowFramesLandOnTheirKeys() {
        val pipeline = box()
        feedHex(pipeline, "AA 55 05 B4 85 8F 5B 02 C6 EF")
        assertEquals(93.0, read(K.COOLANT_TEMP)!!, 0.0)
        assertEquals(14.3, read(K.BATTERY_VOLTS)!!, 0.0)
        assertEquals(91.0, read(K.FUEL_LEVEL)!!, 0.0)
        assertEquals(71.0, read(K.FUEL_LITRES)!!, 0.0)
        feedHex(pipeline, "AA 55 05 B4 FF 8F 5B 02 C6 69")                // 02C stale: coolant alone goes
        assertNull(read(K.COOLANT_TEMP))
        assertEquals(14.3, read(K.BATTERY_VOLTS)!!, 0.0)
        feedHex(pipeline, "AA 55 03 B5 00 FA 0A BB")
        assertEquals(25.0, read(K.OUTSIDE_TEMP)!!, 0.0)
        assertEquals(10.0, read(K.ENGINE_OFF_MINUTES)!!, 0.0)
        feedHex(pipeline, "AA 55 06 B8 12 D6 87 06 01 0A 3D")
        assertEquals(123_456.7, read(K.ODOMETER)!!, 1e-6)
        assertEquals(266.0, read(K.CAR_HEADING)!!, 0.0)
        feedHex(pipeline, "AA 55 06 B8 FF FF FF FF FF FF B7")
        assertNull(read(K.CAR_HEADING))
    }

    @Test
    fun slowValuesExpireWithoutTheirFrame_butTheFuelStays() {
        apply(CanboxValue.CoolantC(90), CanboxValue.EngineOffMinutes(1), CanboxValue.FuelLitres(50.0))
        t += 5_100
        bus.expireNow()
        assertNull(read(K.COOLANT_TEMP))
        assertNull(read(K.ENGINE_OFF_MINUTES))
        assertEquals(50.0, read(K.FUEL_LITRES)!!, 0.0)
    }

    @Test
    fun theNewEnumsMapOneToOne() {
        for (p in IgnitionPosition.values()) { apply(CanboxValue.Ignition(p)); assertEquals(p.name, read(K.IGNITION_STATE)!!.name) }
        for (c in CruiseState.values()) { apply(CanboxValue.Cruise(c)); assertEquals(c.name, read(K.CRUISE)!!.name) }
        for (r in TransferCaseRange.values()) { apply(CanboxValue.TransferCase(r)); assertEquals(r.name, read(K.TRANSFER_CASE)!!.name) }
        apply(CanboxValue.Telltales(UnitTelltale.values().toSet()))
        assertEquals(Telltale.values().toSet(), read(K.TELLTALES))
        apply(CanboxValue.Telltales(emptySet()))
        assertEquals(emptySet<Telltale>(), read(K.TELLTALES))
        apply(CanboxValue.Ignition(null), CanboxValue.Cruise(null), CanboxValue.TransferCase(null), CanboxValue.Telltales(null))
        for (k in listOf<Key<*>>(K.IGNITION_STATE, K.CRUISE, K.TRANSFER_CASE, K.TELLTALES)) assertNull(k.id, bus.sample(k).value?.value)
    }

    @Test
    fun theSimpleStatesAndTheirWithdrawals() {
        apply(CanboxValue.AccessoryPower(true), CanboxValue.KeyIn(true), CanboxValue.BrakePedal(true), CanboxValue.DriverBelt(true), CanboxValue.AcCompressor(true))
        for (k in listOf(K.ACCESSORY_POWER, K.KEY_IN, K.BRAKE_PEDAL, K.DRIVER_BELT, K.AC_COMPRESSOR)) assertEquals(k.id, true, read(k))
        apply(CanboxValue.AccessoryPower(null), CanboxValue.KeyIn(null), CanboxValue.BrakePedal(null), CanboxValue.DriverBelt(null), CanboxValue.AcCompressor(null))
        for (k in listOf(K.ACCESSORY_POWER, K.KEY_IN, K.BRAKE_PEDAL, K.DRIVER_BELT, K.AC_COMPRESSOR)) assertNull(k.id, read(k))
        apply(CanboxValue.Doors(null))
        assertNull(read(K.DOORS))
    }

    @Test
    fun theNewKeysAreInTheCatalogue() {
        val keys = listOf<Key<*>>(
            K.IGNITION_STATE, K.ACCESSORY_POWER, K.KEY_IN, K.BRAKE_PEDAL, K.AC_COMPRESSOR, K.CRUISE, K.TELLTALES,
            K.TRANSFER_CASE, K.OIL_PRESSURE, K.MANIFOLD_PRESSURE, K.ENGINE_OFF_MINUTES
        )
        for (k in keys) assertTrue(k.id, k in K.all)
        assertEquals(K.all.size, K.all.map { it.id }.toSet().size)
    }

    @Test
    fun aHeartbeatThatDoesNotContinueIsLoggedAndNothingElse() {
        val lines = ArrayList<String>()
        val p = XyautoBusPublisher(out, log = { lines += it })
        fun hb(n: Int) = p.apply(CanboxValue.Heartbeat(layout = 2, capabilities = 0x01BD, counter = n))
        hb(0xFD); hb(0xFE); hb(0xFF); hb(0x00)       // +1 each, wrapping
        assertTrue(lines.isEmpty())
        hb(0x05)                                     // a restart
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].contains("5") && lines[0].contains("0"))
        assertNull(read(K.CANBOX_VERSION))           // no key is touched by a heartbeat
    }

    // --- Tyres -------------------------------------------------------------------------------------------------

    private fun tyre(pos: Int, kPa: Int?, leak: Boolean = false) =
        CanboxValue.Tyre(TyreReading(pos, kPa, celsius = null, sensorBatteryLow = false, leak = leak, noSignal = false))

    @Test
    fun tyreTemperatureAndTheCarsLowPressureWarnTravelWithTheWheel() {
        apply(
            CanboxValue.Tyre(TyreReading(TyreReading.FRONT_RIGHT, 150, celsius = 38, sensorBatteryLow = true, leak = false, noSignal = false, lowPressureWarn = true)),
            tyre(TyreReading.REAR_LEFT, 230)
        )
        val m = read(K.TYRES)!!
        assertEquals(38, m[TyrePos.FRONT_RIGHT]!!.celsius)
        assertTrue(m[TyrePos.FRONT_RIGHT]!!.lowPressureWarn)
        assertTrue(m[TyrePos.FRONT_RIGHT]!!.sensorBatteryLow)
        assertFalse(m[TyrePos.REAR_LEFT]!!.lowPressureWarn)
    }

    @Test
    fun tyresAreOneMapAndAWheelWithNoPressureIsAbsent() {
        apply(tyre(TyreReading.FRONT_LEFT, 230), tyre(TyreReading.REAR_RIGHT, 220, leak = true))
        val map = read(K.TYRES)!!
        assertEquals(setOf(TyrePos.FRONT_LEFT, TyrePos.REAR_RIGHT), map.keys)
        assertEquals(230, map[TyrePos.FRONT_LEFT]!!.kPa)
        assertNull(map[TyrePos.FRONT_LEFT]!!.celsius)   // the box sends none: unknown, not 0
        assertTrue(map[TyrePos.REAR_RIGHT]!!.leak)

        apply(tyre(TyreReading.FRONT_LEFT, null))         // lost its pressure
        assertEquals(setOf(TyrePos.REAR_RIGHT), read(K.TYRES)!!.keys)
        apply(tyre(TyreReading.REAR_LEFT, null))          // never had one: nothing changes
        assertEquals(setOf(TyrePos.REAR_RIGHT), read(K.TYRES)!!.keys)
        apply(tyre(TyreReading.REAR_RIGHT, null))
        assertNull(read(K.TYRES))                         // no wheel at all: the key is withdrawn
    }

    @Test
    fun tyreRotationFillsAllFourWheels() {
        apply(
            tyre(TyreReading.FRONT_LEFT, 230), tyre(TyreReading.FRONT_RIGHT, 231),
            tyre(TyreReading.REAR_RIGHT, 232), tyre(TyreReading.REAR_LEFT, 233)
        )
        val m = read(K.TYRES)!!
        assertEquals(listOf(230, 231, 233, 232), listOf(TyrePos.FRONT_LEFT, TyrePos.FRONT_RIGHT, TyrePos.REAR_LEFT, TyrePos.REAR_RIGHT).map { m[it]!!.kPa })
    }
}
