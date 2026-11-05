package com.openauto.dash.vehicle

import com.openauto.dash.CarProfile
import com.openauto.dash.GearEstimator
import com.openauto.dash.GearTracker
import com.openauto.dash.gearEstimateFlow
import com.openauto.dash.gearOf
import com.openauto.dash.vehicleGearOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.openauto.dash.Gear as TileGear

/** The estimates are sources: pure functions, and the providers that run only while wanted and nothing better speaks. */
class ComputedTest {
    private var t = 0L
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val bus = VehicleBus({ t }, scope)
    private val carApp = bus.register(Source("qf.car_app", SourceKind.VEHICLE_APP, "QF car app"))
    private val obd = bus.register(Source("obd", SourceKind.OBD, "OBD"))
    private val canbox = bus.register(Source("unit.bus", SourceKind.CANBOX, "Unit bus"))
    private val profile = MutableStateFlow(CarProfile(name = "test"))

    @After
    fun stop() = scope.cancel()

    /** Somebody shows [key]: that is the demand an estimate runs on. */
    private fun watch(key: Key<*>): Job = scope.launch { bus.sample(key).collect { } }

    /** Lets the fast keys' conflation (10 Hz) release what was put a moment ago. */
    private fun settle() {
        t += 200
        bus.expireNow()
    }

    private fun ids(key: Key<*>): Set<String> = bus.candidates(key).value.map { it.source.id }.toSet()

    // --- Engine running -------------------------------------------------------------------------------------

    @Test
    fun theEngineRunsAboveTheUsualThreshold() {
        assertNull(engineRunning(null))
        assertEquals(false, engineRunning(0.0))
        assertEquals(false, engineRunning(500.0))
        assertEquals(true, engineRunning(501.0))
    }

    @Test
    fun theEngineIsEstimatedFromTheRevsWhileWanted() {
        startEngineEstimate(bus, scope)
        obd.put(K.RPM, 850.0)
        // Nobody wants it: nothing runs, and the revs are not asked for.
        assertNull(bus.now(K.ENGINE_RUNNING))
        assertFalse(K.RPM in bus.wanted.value)

        val w = watch(K.ENGINE_RUNNING)
        val s = bus.sample(K.ENGINE_RUNNING).value!!
        assertEquals(true, s.value)
        assertEquals("computed.engine", s.source.id)
        assertEquals(Quality.ESTIMATED, s.quality)
        assertTrue(K.RPM in bus.wanted.value)
        obd.put(K.RPM, 0.0)
        settle()
        assertEquals(false, bus.now(K.ENGINE_RUNNING))
        // The revs are gone: unknown, not "off".
        obd.put(K.RPM, null)
        settle()
        assertNull(bus.now(K.ENGINE_RUNNING))

        // Nobody wants it any more: the estimate is withdrawn and the revs let go.
        obd.put(K.RPM, 2000.0)
        settle()
        assertEquals(true, bus.now(K.ENGINE_RUNNING))
        w.cancel()
        assertNull(bus.now(K.ENGINE_RUNNING))
        assertFalse(K.RPM in bus.wanted.value)
    }

    @Test
    fun aCarThatSaysItIdlesTheEstimate() {
        startEngineEstimate(bus, scope)
        obd.put(K.RPM, 850.0)
        watch(K.ENGINE_RUNNING)
        assertEquals("computed.engine", bus.sample(K.ENGINE_RUNNING).value!!.source.id)

        canbox.put(K.ENGINE_RUNNING, false)
        assertEquals(false, bus.now(K.ENGINE_RUNNING))
        assertEquals(setOf("unit.bus"), ids(K.ENGINE_RUNNING))
        // Idle: its input is no longer asked for.
        assertFalse(K.RPM in bus.wanted.value)

        // The car goes quiet (its source down): the estimate takes over again.
        canbox.up(false)
        assertEquals(true, bus.now(K.ENGINE_RUNNING))
        assertTrue(K.RPM in bus.wanted.value)
    }

    // --- Range from the level, level from the range ---------------------------------------------------------

    @Test
    fun theEstimatesKeepTodaysNumbers() {
        // fuelInfo's old arithmetic: 50 % of 60 L at 6.5 L/100 km, the range cut to whole km.
        assertEquals(461.0, rangeFromLevelKm(50, 60.0, 6.5), 0.0)
        assertEquals(0.0, rangeFromLevelKm(0, 60.0, 6.5), 0.0)
        assertEquals(1_000.0, rangeFromLevelKm(100, 50.0, 5.0), 0.0)
        // 520 km at 6.5 L/100 km is 33.8 L of a 60 L tank: 56 %; a range longer than the tank holds is a full tank.
        assertEquals(56.0, levelFromRangePct(520, 60.0, 6.5), 0.0)
        assertEquals(100.0, levelFromRangePct(5_000, 60.0, 6.5), 0.0)
        assertEquals(0.0, levelFromRangePct(0, 60.0, 6.5), 0.0)
    }

    @Test
    fun theRangeIsWorkedFromTheLevelWhenTheCarGivesNone() {
        startRangeEstimate(bus, scope, profile)
        carApp.put(K.FUEL_LEVEL, 50.0)
        assertNull(bus.now(K.RANGE))
        assertFalse(K.FUEL_LEVEL in bus.wanted.value)

        val w = watch(K.RANGE)
        val s = bus.sample(K.RANGE).value!!
        assertEquals(461.0, s.value, 0.0)
        assertEquals("computed.range", s.source.id)
        assertEquals(Quality.ESTIMATED, s.quality)
        assertTrue(K.FUEL_LEVEL in bus.wanted.value)

        carApp.put(K.FUEL_LEVEL, 25.0)
        assertEquals(230.0, bus.now(K.RANGE)!!, 0.0)
        // The profile's tank and consumption count.
        profile.value = CarProfile(name = "big", tankL = 80.0, consumptionL100 = 8.0)
        assertEquals(250.0, bus.now(K.RANGE)!!, 0.0)
        w.cancel()
        assertNull(bus.now(K.RANGE))
    }

    @Test
    fun theRangeComesFromMeasuredLitresWhenTheBoxGivesThem() {
        assertEquals(646.0, rangeFromLitresKm(77.6, 12.0), 0.0)
        assertEquals(0.0, rangeFromLitresKm(-1.0, 12.0), 0.0)
        assertEquals(350.0, estimatedRangeKm(42.0, 90, 77.6, 12.0)!!, 0.0)     // litres win over the percentage
        assertEquals(646.0, estimatedRangeKm(null, 100, 77.6, 12.0)!!, 0.0)    // else level x tank
        assertNull(estimatedRangeKm(null, null, 77.6, 12.0))
    }

    @Test
    fun theRangeEstimateReadsLitresAndFallsBackToThePercentage() {
        profile.value = CarProfile(name = "jeep", tankL = 77.6, consumptionL100 = 12.0)
        startRangeEstimate(bus, scope, profile)
        canbox.put(K.FUEL_LEVEL, 50.0)
        val w = watch(K.RANGE)
        assertEquals(323.0, bus.now(K.RANGE)!!, 0.0)              // 50 % of 77.6 L at 12
        canbox.put(K.FUEL_LITRES, 24.0)
        assertEquals(200.0, bus.now(K.RANGE)!!, 0.0)              // 24 L at 12
        canbox.put(K.FUEL_LITRES, null)
        assertEquals(323.0, bus.now(K.RANGE)!!, 0.0)
        w.cancel()
    }

    @Test
    fun theJeepWkHasItsOwnTankAndConsumptionUnlessTheProfileSaysOtherwise() {
        val jeep = CarProfile(name = "x", vin = "1J8HCE8M45Y123456")
        assertEquals(77.6, jeep.tank, 0.0)
        assertEquals(12.0, jeep.typicalUse, 0.0)
        assertEquals(77.6, CarProfile.JEEP_WK.tank, 0.0)
        assertEquals(12.0, CarProfile.JEEP_WK.typicalUse, 0.0)
        assertEquals(60.0, CarProfile(name = "x", make = "Jeep", model = "Grand Cherokee", tankL = 60.0).tank, 0.0)
        val other = CarProfile(name = "c4")
        assertEquals(60.0, other.tank, 0.0)
        assertEquals(6.5, other.typicalUse, 0.0)
    }

    @Test
    fun theCarsOwnRangeIdlesTheEstimateAndTheLevelIsLetGo() {
        startRangeEstimate(bus, scope, profile)
        obd.put(K.FUEL_LEVEL, 50.0)
        watch(K.RANGE)
        assertEquals("computed.range", bus.sample(K.RANGE).value!!.source.id)

        carApp.put(K.RANGE, 480.0)
        assertEquals(480.0, bus.now(K.RANGE)!!, 0.0)
        assertEquals(Quality.GOOD, bus.sample(K.RANGE).value!!.quality)
        assertEquals(setOf("qf.car_app"), ids(K.RANGE))
        assertFalse(K.FUEL_LEVEL in bus.wanted.value)
    }

    @Test
    fun theLevelIsWorkedBackFromTheRangeWhenTheCarGivesNone() {
        startRangeEstimate(bus, scope, profile)
        carApp.put(K.RANGE, 520.0)
        watch(K.FUEL_LEVEL)
        val s = bus.sample(K.FUEL_LEVEL).value!!
        assertEquals(56.0, s.value, 0.0)
        assertEquals("computed.range", s.source.id)
        assertEquals(Quality.ESTIMATED, s.quality)
        // A level from the car or the adapter takes over, and the range is let go.
        obd.put(K.FUEL_LEVEL, 40.0)
        assertEquals("obd", bus.sample(K.FUEL_LEVEL).value!!.source.id)
        assertEquals(setOf("obd"), ids(K.FUEL_LEVEL))
        assertFalse(K.RANGE in bus.wanted.value)
    }

    @Test
    fun anEstimateNeverFeedsAnEstimate() {
        startRangeEstimate(bus, scope, profile)
        // Both wanted, and the car gives neither: nothing to work from, nothing made up.
        watch(K.RANGE)
        watch(K.FUEL_LEVEL)
        assertNull(bus.now(K.RANGE))
        assertNull(bus.now(K.FUEL_LEVEL))
        // The level alone: the range is estimated from it, and the level is not estimated back from that range.
        carApp.put(K.FUEL_LEVEL, 50.0)
        assertEquals("computed.range", bus.sample(K.RANGE).value!!.source.id)
        assertEquals(setOf("qf.car_app"), ids(K.FUEL_LEVEL))
        assertEquals(50.0, bus.now(K.FUEL_LEVEL)!!, 0.0)
    }

    @Test
    fun anEstimatedLevelGivesWayToARealOne() {
        startRangeEstimate(bus, scope, profile)
        carApp.put(K.RANGE, 520.0)
        watch(K.FUEL_LEVEL)
        assertEquals(Quality.ESTIMATED, bus.sample(K.FUEL_LEVEL).value!!.quality)
        obd.put(K.FUEL_LEVEL, 70.0)
        assertEquals(Quality.GOOD, bus.sample(K.FUEL_LEVEL).value!!.quality)
    }

    // --- Gear -----------------------------------------------------------------------------------------------

    private val table = GearEstimator.seeds(6)

    @Test
    fun theTrackerReadsTheGearAsTheTilesAlwaysDid() {
        val g = GearTracker(table, learning = { false })
        assertEquals(Gear.Forward(3), g.step(false, 2050, 50, 0))          // 41 rpm per km/h
        assertEquals(Gear.Reverse, g.step(true, 900, 3, 10_000))
        assertNull(g.step(false, 800, 0, 20_000))                           // standing: no gear to say
        assertEquals(Gear.Neutral, g.step(false, 820, 70, 30_000))          // idle revs at 70 km/h
        assertNull(g.step(false, null, 50, 40_000))                         // no revs
    }

    @Test
    fun aMomentBetweenGearsKeepsTheLastOne() {
        val g = GearTracker(table, learning = { false })
        assertEquals(Gear.Forward(3), g.step(false, 2050, 50, 0))
        // The clutch slipping mid-shift: no gear matches; the last stays for a moment...
        assertEquals(Gear.Forward(3), g.step(false, 2550, 50, 1_000))
        // ...and not for long.
        assertNull(g.step(false, 2550, 50, 1_600))
    }

    @Test
    fun theTrackerLearnsAndSavesNowAndThen() {
        var saved: List<Double>? = null
        val g = GearTracker(table, saveEvery = 10, onSave = { saved = it })
        repeat(9) { g.step(false, 2150, 50, it * 100L) }
        assertNull(saved)
        g.step(false, 2150, 50, 1_000)
        assertTrue(saved!![2] < table[2] + 1.0 && saved!![2] > table[2])        // 43 rpm per km/h pulled the table up from 41
        // Learning is off in the demo: nothing is learned or saved.
        var touched = false
        val off = GearTracker(table, learning = { false }, saveEvery = 1, onSave = { touched = true })
        repeat(5) { off.step(false, 2150, 50, it * 100L) }
        assertFalse(touched)
    }

    @Test
    fun theGearIsEstimatedFromRevsAndSpeedWhileWanted() {
        val out = bus.register(Source("computed.gear", SourceKind.COMPUTED, "Gear"))
        estimateWhileWanted(scope, bus, out, K.GEAR) { gearEstimateFlow(bus, GearTracker(table, learning = { false }), { t }) }
        obd.put(K.RPM, 2050.0)
        canbox.put(K.VEHICLE_SPEED, 50.0)
        assertNull(bus.now(K.GEAR))
        assertFalse(K.VEHICLE_SPEED in bus.wanted.value)

        val w = watch(K.GEAR)
        val s = bus.sample(K.GEAR).value!!
        assertEquals(Gear.Forward(3), s.value)
        assertEquals("computed.gear", s.source.id)
        assertEquals(Quality.ESTIMATED, s.quality)
        assertTrue(K.RPM in bus.wanted.value && K.VEHICLE_SPEED in bus.wanted.value)

        // Reverse, as the head unit says it.
        carApp.put(K.REVERSE, true)
        assertEquals(Gear.Reverse, bus.now(K.GEAR))
        w.cancel()
        assertNull(bus.now(K.GEAR))
        assertFalse(K.RPM in bus.wanted.value)
    }

    @Test
    fun aCarThatSaysItsGearIdlesTheEstimate() {
        val out = bus.register(Source("computed.gear", SourceKind.COMPUTED, "Gear"))
        estimateWhileWanted(scope, bus, out, K.GEAR) { gearEstimateFlow(bus, GearTracker(table, learning = { false }), { t }) }
        obd.put(K.RPM, 2050.0)
        canbox.put(K.VEHICLE_SPEED, 50.0)
        watch(K.GEAR)
        assertEquals("computed.gear", bus.sample(K.GEAR).value!!.source.id)

        canbox.put(K.GEAR, Gear.Drive)
        val s = bus.sample(K.GEAR).value!!
        assertEquals(Gear.Drive, s.value)
        assertEquals("unit.bus", s.source.id)
        assertEquals(Quality.GOOD, s.quality)
        assertEquals(setOf("unit.bus"), ids(K.GEAR))
        assertFalse(K.RPM in bus.wanted.value)
    }

    @Test
    fun theGearGoesToTheTilesAsTheyKnowIt() {
        assertEquals(TileGear.Reverse, gearOf(Gear.Reverse, 3))
        assertEquals(TileGear.Forward(4), gearOf(Gear.Forward(4), 70))
        assertEquals(TileGear.Free, gearOf(Gear.Neutral, 70))
        assertEquals(TileGear.Stopped, gearOf(Gear.Park, 0))
        assertEquals(TileGear.Unknown, gearOf(Gear.Drive, 40))
        // No gear said: standing is told from the speed, as it was.
        assertEquals(TileGear.Stopped, gearOf(null, 0))
        assertEquals(TileGear.Unknown, gearOf(null, 40))
        assertEquals(TileGear.Unknown, gearOf(null, null))
        // The estimate leaves "stopped" and "unknown" out, which the tile gets back from the speed.
        assertNull(vehicleGearOf(TileGear.Stopped))
        assertNull(vehicleGearOf(TileGear.Unknown))
    }
}
