package com.openauto.dash.unit.xyauto

import com.openauto.dash.McuReader.DoorState
import com.openauto.dash.TyrePos
import com.openauto.dash.vehicle.Gear
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Key
import com.openauto.dash.vehicle.Source
import com.openauto.dash.vehicle.SourceKind
import com.openauto.dash.vehicle.VehicleBus
import com.openauto.unitproto.canbox.CanboxPipeline
import com.openauto.unitproto.canbox.CanboxValue
import com.openauto.unitproto.canbox.CompassReading
import com.openauto.unitproto.canbox.DoorBits
import com.openauto.unitproto.canbox.GearPosition
import com.openauto.unitproto.canbox.TyreReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
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
            CanboxValue.CoolantC(90), CanboxValue.IntakeAirC(25), CanboxValue.OutsideTempC(-12.3),
            CanboxValue.BatteryVolts(14.3), CanboxValue.RangeKm(412)
        )
        assertEquals(60.0, read(K.VEHICLE_SPEED)!!, 0.0)
        assertEquals(2000.0, read(K.RPM)!!, 0.0)
        assertEquals(40.0, read(K.ENGINE_LOAD)!!, 0.0)
        assertEquals(30.0, read(K.THROTTLE)!!, 0.0)
        assertEquals(90.0, read(K.COOLANT_TEMP)!!, 0.0)
        assertEquals(25.0, read(K.INTAKE_TEMP)!!, 0.0)
        assertEquals(-12.3, read(K.OUTSIDE_TEMP)!!, 0.0)
        assertEquals(14.3, read(K.BATTERY_VOLTS)!!, 0.0)
        assertEquals(412.0, read(K.RANGE)!!, 0.0)
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
            // AutoStick: the held gear is not said, so no Forward(n) is made up.
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
            CanboxValue.CoolantC(90), CanboxValue.IntakeAirC(25), CanboxValue.FuelLevelPct(50), CanboxValue.BatteryVolts(14.0),
            CanboxValue.EngineRunning(true), CanboxValue.FuelLitres(40.0), CanboxValue.RangeKm(300), CanboxValue.OutsideTempC(10.0),
            CanboxValue.Dimmer(40)
        )
        apply(
            CanboxValue.SpeedKmh(null), CanboxValue.Rpm(null), CanboxValue.EngineLoadPct(null), CanboxValue.ThrottlePct(null),
            CanboxValue.CoolantC(null), CanboxValue.IntakeAirC(null), CanboxValue.FuelLevelPct(null), CanboxValue.BatteryVolts(null),
            CanboxValue.EngineRunning(null), CanboxValue.FuelLitres(null), CanboxValue.RangeKm(null), CanboxValue.OutsideTempC(null),
            CanboxValue.Dimmer(null)
        )
        listOf(
            K.VEHICLE_SPEED, K.RPM, K.ENGINE_LOAD, K.THROTTLE, K.COOLANT_TEMP, K.INTAKE_TEMP, K.FUEL_LEVEL, K.BATTERY_VOLTS,
            K.ENGINE_RUNNING, K.FUEL_LITRES, K.RANGE, K.OUTSIDE_TEMP, K.INSTRUMENT_DIMMER
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

    @Test
    fun aHeartbeatThatLosesTheEngineGroupWithdrawsItsKeysOnly() {
        val pipeline = box()
        fun feed(f: ByteArray) = publisher.apply(pipeline.feed(f)!!.values)
        feed(frame(0xB3, 0, 60, 0x07, 0xD0, 0, 30))
        feed(frame(0xB4, 130, 65, 50, 0x05, 0x96))
        feed(frame(0xB0, 0x01, 0x2F))                     // everything live
        assertEquals(60.0, read(K.VEHICLE_SPEED)!!, 0.0)
        assertEquals(90.0, read(K.COOLANT_TEMP)!!, 0.0)
        assertEquals(25.0, read(K.INTAKE_TEMP)!!, 0.0)
        assertEquals(50.0, read(K.FUEL_LEVEL)!!, 0.0)
        assertEquals(14.3, read(K.BATTERY_VOLTS)!!, 0.0)

        feed(frame(0xB0, 0x01, 0x2B))                     // 0xB4's bit cleared: the engine group is lost
        listOf(K.COOLANT_TEMP, K.INTAKE_TEMP, K.FUEL_LEVEL, K.BATTERY_VOLTS).forEach { assertNull(it.id, read(it)) }
        assertEquals(60.0, read(K.VEHICLE_SPEED)!!, 0.0)  // the drivetrain is still live
        assertEquals(2000.0, read(K.RPM)!!, 0.0)

        feed(frame(0xB0, 0x01, 0x29))                     // ... and now 0xB3's as well
        assertNull(read(K.VEHICLE_SPEED))
        assertNull(read(K.RPM))
        assertNull(read(K.THROTTLE))
    }

    @Test
    fun speedAndRevsComeFromTheFrameAsFromThePluginsBroadcast() {
        // The plugin's pre-decoded speed is a CanboxValue like the 0xB3 frame's: one path, one key.
        apply(CanboxValue.SpeedKmh(80))
        assertEquals(80.0, read(K.VEHICLE_SPEED)!!, 0.0)
        t += 200                                          // past the 10 Hz conflation
        val pipeline = box()
        publisher.apply(pipeline.feed(frame(0xB3, 0, 90, 0x03, 0xE8, 0, 0))!!.values)
        assertEquals(90.0, read(K.VEHICLE_SPEED)!!, 0.0)
        assertEquals(1000.0, read(K.RPM)!!, 0.0)
    }

    // --- Tyres -------------------------------------------------------------------------------------------------

    private fun tyre(pos: Int, kPa: Int?, leak: Boolean = false) =
        CanboxValue.Tyre(TyreReading(pos, kPa, celsius = null, sensorBatteryLow = false, leak = leak, noSignal = false))

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
