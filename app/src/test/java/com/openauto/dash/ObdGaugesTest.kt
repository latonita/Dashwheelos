package com.openauto.dash

import com.openauto.dash.vehicle.Gauges
import com.openauto.dash.vehicle.K
import com.openauto.dash.vehicle.Key
import com.openauto.dash.vehicle.Quality
import com.openauto.dash.vehicle.Source
import com.openauto.dash.vehicle.SourceKind
import com.openauto.dash.vehicle.VehicleBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the OBD poll asks for, and what it says on the bus about the answers. */
class ObdGaugesTest {
    private fun plan(wanted: Set<Key<*>>, tick: Int) = obdPollPlan(wanted, tick)
    private val everything: Set<Key<*>> = setOf(
        K.VEHICLE_SPEED, K.RPM, K.THROTTLE, K.ENGINE_LOAD, K.COOLANT_TEMP, K.INTAKE_TEMP, K.FUEL_LEVEL, K.BATTERY_VOLTS, K.FUEL_FLOW
    )

    // --- obdPollPlan --------------------------------------------------------------

    @Test
    fun nothingWantedPollsSpeedOnly() {
        for (tick in 0..11) assertEquals("tick $tick", listOf(Pid.SPEED), plan(emptySet(), tick))
    }

    @Test
    fun theFirstPollAsksForEveryWantedKey() {
        assertEquals(Pid.entries.toSet(), plan(everything, 0).toSet())
        assertEquals(listOf(Pid.SPEED, Pid.RPM, Pid.COOLANT), plan(setOf(K.RPM, K.COOLANT_TEMP), 0))
    }

    @Test
    fun speedAndRevsEveryPollThrottleAndLoadEveryFourthOnAlternatePolls() {
        for (tick in 1..12) {
            val p = plan(everything, tick)
            assertTrue("tick $tick", Pid.SPEED in p && Pid.RPM in p)
            assertEquals("throttle at $tick", tick % 4 == 0, Pid.THROTTLE in p)
            assertEquals("load at $tick", tick % 4 == 2, Pid.LOAD in p)
            assertEquals("flow at $tick", tick % 2 == 1, Pid.FUEL_FLOW in p)
        }
    }

    @Test
    fun theSlowReadingsTakeTurns() {
        val slow = listOf(Pid.COOLANT, Pid.INTAKE, Pid.FUEL_LEVEL, Pid.VOLTS)
        for (tick in 1..12) {
            val asked = plan(everything, tick).filter { it in slow }
            assertEquals("tick $tick", listOf(slow[tick % 4]), asked)
        }
    }

    @Test
    fun anUnwantedKeyIsNeverAsked() {
        for (tick in 0..11) {
            val p = plan(setOf(K.COOLANT_TEMP), tick)
            assertEquals(setOf(Pid.SPEED) + if (tick == 0 || tick % 4 == 0) setOf(Pid.COOLANT) else emptySet(), p.toSet())
        }
    }

    @Test
    fun everyWantedKeyIsAskedWithinOneRound() {
        // What a visible tile shows is always answered: each wanted key comes up within 4 polls.
        for (key in everything - K.VEHICLE_SPEED) {
            val pid = Pid.entries.single { it.key == key }
            assertTrue(key.id, (1..4).any { pid in plan(setOf(key), it) })
        }
    }

    // --- ObdGaugePublisher -----------------------------------------------------------

    private var t = 0L
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val bus = VehicleBus({ t }, scope)
    private val out = ObdGaugePublisher(bus.register(Source("obd", SourceKind.OBD, "OBD")))

    @After
    fun stop() = scope.cancel()

    @Test
    fun aKeyIsNullUntilTheAdapterAnswersIt() {
        out.publish(ObdData(speedKmh = 50, rpm = 2_000), setOf(Pid.SPEED, Pid.RPM))
        assertEquals(50.0, bus.now(K.VEHICLE_SPEED)!!, 0.0)
        assertEquals(2_000.0, bus.now(K.RPM)!!, 0.0)
        // The record's 0 for the rest is not an answer.
        assertNull(bus.now(K.COOLANT_TEMP))
        assertNull(bus.now(K.BATTERY_VOLTS))
        assertNull(bus.now(K.FUEL_LEVEL))
        assertEquals(setOf(Pid.SPEED, Pid.RPM), out.answered())
    }

    @Test
    fun aReadingNotAskedAgainIsNotRepublishedAndAgesOut() {
        out.publish(ObdData(speedKmh = 50, coolantTempC = 88), setOf(Pid.SPEED, Pid.COOLANT))
        t = 30_000
        out.publish(ObdData(speedKmh = 52, coolantTempC = 88), setOf(Pid.SPEED))
        bus.expireNow()
        assertEquals(88.0, bus.now(K.COOLANT_TEMP)!!, 0.0)
        t = 61_000
        bus.expireNow()
        assertNull(bus.now(K.COOLANT_TEMP))
    }

    @Test
    fun throttleAndLoadLiveLongerThanTheKeysThreeSeconds() {
        out.publish(ObdData(throttlePct = 20, engineLoadPct = 30), setOf(Pid.THROTTLE, Pid.LOAD))
        t = 8_000
        bus.expireNow()
        assertEquals(20.0, bus.now(K.THROTTLE)!!, 0.0)
        t = 10_001
        bus.expireNow()
        assertNull(bus.now(K.THROTTLE))
        assertNull(bus.now(K.ENGINE_LOAD))
    }

    @Test
    fun aStoppedEngineReadsZeroNotUnknown() {
        out.publish(ObdData(speedKmh = 0, rpm = 0, throttlePct = 0, engineLoadPct = 0), setOf(Pid.SPEED, Pid.RPM, Pid.THROTTLE, Pid.LOAD))
        assertEquals(0.0, bus.now(K.RPM)!!, 0.0)
        assertEquals(0.0, bus.now(K.VEHICLE_SPEED)!!, 0.0)
    }

    @Test
    fun withdrawAllTakesEverythingOffAndForgetsWhatWasAnswered() {
        out.publish(ObdData(speedKmh = 50, rpm = 2_000, coolantTempC = 90), setOf(Pid.SPEED, Pid.RPM, Pid.COOLANT))
        out.withdrawAll()
        assertNull(bus.now(K.VEHICLE_SPEED))
        assertNull(bus.now(K.RPM))
        assertNull(bus.now(K.COOLANT_TEMP))
        assertTrue(out.answered().isEmpty())
    }

    @Test
    fun theFuelFlowGoesWhenTheRecordHasNone() {
        out.publish(ObdData(fuelFlowLh = 6.5), setOf(Pid.FUEL_FLOW))
        assertEquals(6.5, bus.now(K.FUEL_FLOW)!!, 0.0)
        out.publish(ObdData(fuelFlowLh = null), emptySet())
        assertNull(bus.now(K.FUEL_FLOW))
    }

    @Test
    fun carriedFlowIsKeptOnPollsThatSkipIt() {
        val wanted: Set<Key<*>> = setOf(K.FUEL_FLOW)
        assertEquals(6.5, carriedFuelFlow(false, wanted, null, 6.5)!!, 0.0)   // not polled: carry
        assertEquals(7.0, carriedFuelFlow(true, wanted, 7.0, 6.5)!!, 0.0)     // polled, answered
        assertEquals(6.5, carriedFuelFlow(true, wanted, null, 6.5)!!, 0.0)    // polled, no answer: last (ages out by ttl)
        assertNull(carriedFuelFlow(false, emptySet(), null, 6.5))              // nobody wants it
        assertNull(carriedFuelFlow(true, wanted, null, null))
    }

    @Test
    fun theFuelFlowSurvivesTheAlternatePollsThatSkipIt() {
        var rec = ObdData()
        for (tick in 0..7) {
            val polled = Pid.FUEL_FLOW in plan(everything, tick)
            val answered = if (polled) 6.5 else null
            rec = rec.copy(speedKmh = 50, fuelFlowLh = carriedFuelFlow(polled, everything, answered, rec.fuelFlowLh))
            val fresh = buildSet { add(Pid.SPEED); if (answered != null) add(Pid.FUEL_FLOW) }
            out.publish(rec, fresh)
            if (tick >= 1) assertEquals("tick $tick", 6.5, bus.now(K.FUEL_FLOW)!!, 0.0)
        }
    }

    // --- Volts: the unit's against the adapter's, by plain resolution ---------------------

    private val mcu = Source("qf.mcu", SourceKind.MCU, "Unit MCU")

    @Test
    fun theUnitsVoltsWinOverTheAdaptersWheneverPresent() {
        val unit = bus.register(mcu)
        out.publish(ObdData(voltage = 14.3, voltageFromEcu = true), setOf(Pid.VOLTS))
        assertEquals("obd", bus.sample(K.BATTERY_VOLTS).value!!.source.id)
        unit.put(K.BATTERY_VOLTS, 14.2)                      // within 1 V of the adapter's: it wins all the same
        assertEquals("qf.mcu", bus.sample(K.BATTERY_VOLTS).value!!.source.id)
        unit.put(K.BATTERY_VOLTS, null)
        assertEquals("obd", bus.sample(K.BATTERY_VOLTS).value!!.source.id)
    }

    @Test
    fun anAdaptersOwnPinIsDegradedAndLosesToEverything() {
        val unit = bus.register(mcu)
        val other = bus.register(Source("canbox", SourceKind.CANBOX, "Canbox"))
        out.publish(ObdData(voltage = 16.9, voltageFromEcu = false), setOf(Pid.VOLTS))
        assertEquals(Quality.DEGRADED, bus.sample(K.BATTERY_VOLTS).value!!.quality)
        unit.put(K.BATTERY_VOLTS, 14.4)
        assertEquals(14.4, bus.now(K.BATTERY_VOLTS)!!, 0.0)
        unit.put(K.BATTERY_VOLTS, null)
        other.put(K.BATTERY_VOLTS, 14.1)
        assertEquals("canbox", bus.sample(K.BATTERY_VOLTS).value!!.source.id)
    }

    @Test
    fun theUnitsVoltsCanOutliveTheKeysDefaultWithTheirOwnTtl() {
        val unit = bus.register(mcu)
        unit.put(K.BATTERY_VOLTS, 12.6, ttlMs = 10 * 60_000L)
        t = 5 * 60_000L
        bus.expireNow()
        assertEquals(12.6, bus.now(K.BATTERY_VOLTS)!!, 0.0)
    }

    // --- Gauges --------------------------------------------------------------------

    @Test
    fun unknownBecomesTheOldDefaultOnlyAtTheEdge() {
        val g = Gauges(rpm = 800, coolantTempC = null)
        assertNull(g.coolantTempC)
        val d = g.toObdData()
        assertEquals(800, d.rpm)
        assertEquals(0, d.coolantTempC)
        assertEquals(0.0, d.voltage, 0.0)
        assertFalse(d.voltageFromEcu)
        assertNull(d.fuelFlowLh)
    }
}
