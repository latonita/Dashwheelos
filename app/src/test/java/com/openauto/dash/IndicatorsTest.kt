package com.openauto.dash

import com.openauto.dash.vehicle.Indicators
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicatorsTest {
    private fun states(i: TellInputs) = tellStates(i)

    @Test
    fun nothingHeardIsNoDataEverywhere() {
        val s = states(TellInputs())
        assertEquals(Tell.entries.toSet(), s.keys)
        assertTrue(s.values.all { it == TellState.NO_DATA })
    }

    @Test
    fun leftSignalLightsOnlyTheLeftLamp() {
        val s = states(TellInputs(indicators = Indicators(left = true, right = false)))
        assertEquals(TellState.ON, s[Tell.LEFT])
        assertEquals(TellState.OFF, s[Tell.RIGHT])
        assertEquals(TellState.OFF, s[Tell.HAZARD])
        // The lights were never heard: still no data, not "off".
        assertEquals(TellState.NO_DATA, s[Tell.LOW_BEAM])
    }

    @Test
    fun hazardLightsBothSignalsAndItsOwnTriangle() {
        val s = states(TellInputs(indicators = Indicators(left = false, right = false, hazard = true)))
        assertEquals(TellState.ON, s[Tell.LEFT])
        assertEquals(TellState.ON, s[Tell.RIGHT])
        assertEquals(TellState.ON, s[Tell.HAZARD])
    }

    @Test
    fun hazardFromTheLightsListAloneStillCounts() {
        val s = states(TellInputs(lights = listOf(CarLight.HAZARD)))
        assertEquals(TellState.ON, s[Tell.HAZARD])
        assertEquals(TellState.OFF, s[Tell.LOW_BEAM])
    }

    @Test
    fun dippedBeamIsLowBeamAndParkingLights() {
        val s = states(TellInputs(lights = listOf(CarLight.DIPPED)))
        assertEquals(TellState.ON, s[Tell.LOW_BEAM])
        assertEquals(TellState.ON, s[Tell.PARKING])
        assertEquals(TellState.OFF, s[Tell.HIGH_BEAM])
    }

    @Test
    fun mainBeamIsHighBeamNotLowBeam() {
        val s = states(TellInputs(lights = listOf(CarLight.MAIN_BEAM)))
        assertEquals(TellState.ON, s[Tell.HIGH_BEAM])
        assertEquals(TellState.OFF, s[Tell.LOW_BEAM])
        assertEquals(TellState.ON, s[Tell.PARKING])
    }

    @Test
    fun sidelightsAloneAreOnlyTheParkingLamp() {
        val s = states(TellInputs(lights = listOf(CarLight.SIDELIGHTS)))
        assertEquals(TellState.ON, s[Tell.PARKING])
        assertEquals(TellState.OFF, s[Tell.LOW_BEAM])
    }

    @Test
    fun foggsAreIndependentOfTheBeams() {
        val s = states(TellInputs(lights = listOf(CarLight.DIPPED, CarLight.REAR_FOG)))
        assertEquals(TellState.OFF, s[Tell.FRONT_FOG])
        assertEquals(TellState.ON, s[Tell.REAR_FOG])
        assertEquals(TellState.OFF, states(TellInputs(lights = emptyList()))[Tell.REAR_FOG])
    }

    @Test
    fun handbrakeAndBelt() {
        val s = states(TellInputs(handbrake = true, beltUnfastened = false))
        assertEquals(TellState.ON, s[Tell.HANDBRAKE])
        assertEquals(TellState.OFF, s[Tell.SEAT_BELT])
    }

    @Test
    fun conventionalColours() {
        assertEquals(TellColour.GREEN, Tell.LEFT.colour)
        assertEquals(TellColour.GREEN, Tell.LOW_BEAM.colour)
        assertEquals(TellColour.BLUE, Tell.HIGH_BEAM.colour)
        assertEquals(TellColour.GREEN, Tell.FRONT_FOG.colour)
        assertEquals(TellColour.AMBER, Tell.REAR_FOG.colour)
        // The parking brake is red (the (P) lamp); the parking lights are green.
        assertEquals(TellColour.RED, Tell.HANDBRAKE.colour)
        assertEquals(TellColour.GREEN, Tell.PARKING.colour)
    }

    @Test
    fun blinkPhaseIsOneAndAHalfHertz() {
        assertTrue(blinkPhaseOn(0))
        assertFalse(blinkPhaseOn(BLINK_PERIOD_MS / 2))
        assertTrue(blinkPhaseOn(BLINK_PERIOD_MS))
        val lit = (0 until BLINK_PERIOD_MS * 3).count { blinkPhaseOn(it) }
        assertEquals(BLINK_PERIOD_MS * 3 / 2.0, lit.toDouble(), 3.0)
    }

    @Test
    fun steadyOnFlashesAndSteadyOffDoesNot() {
        val latch = BlinkLatch()
        latch.observe(true, 1_000)
        assertTrue(latch.engaged(true, 1_000))
        // Lit in the on phase only.
        assertTrue(lampLit(Tell.LEFT, TellState.ON, true, 0))
        assertFalse(lampLit(Tell.LEFT, TellState.ON, true, BLINK_PERIOD_MS / 2))
        // Never set: dark in either phase.
        assertFalse(latch.let { BlinkLatch().engaged(false, 5_000) })
        assertFalse(lampLit(Tell.LEFT, TellState.OFF, false, 0))
    }

    @Test
    fun busPulsesKeepTheFlashingGoingThroughTheGapsButNotAfterTheLeverIsReleased() {
        val latch = BlinkLatch()
        // The car's own flashing: 330 ms on, 330 ms off.
        latch.observe(true, 0)
        assertTrue(latch.engaged(false, 330))
        latch.observe(true, 667)
        assertTrue(latch.engaged(false, 667 + 330))
        // Lever released: the pulses stop.
        assertFalse(latch.engaged(false, 667 + BlinkLatch.HOLD_MS + 1))
    }

    @Test
    fun otherLampsFollowTheBusDirectly() {
        assertTrue(lampLit(Tell.LOW_BEAM, TellState.ON, false, BLINK_PERIOD_MS / 2))
        assertFalse(lampLit(Tell.LOW_BEAM, TellState.NO_DATA, false, 0))
    }

    @Test
    fun gridPicksTheBiggestCell() {
        assertEquals(1 to 10, tellGrid(10, 800f, 70f))
        val (rows, cols) = tellGrid(10, 200f, 200f)
        assertTrue(rows >= 2 && cols >= 3 && rows * cols >= 10)
    }
}
