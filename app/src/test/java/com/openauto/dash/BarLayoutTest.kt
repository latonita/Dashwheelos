package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarLayoutTest {
    private fun no(n: Int) = List(n) { false }

    @Test
    fun withoutAStretch_theBarIsCentred() {
        // 100 of readouts in 200: 50 each side.
        assertEquals(listOf(50, 90), barPositions(listOf(40, 60), no(2), 200))
    }

    @Test
    fun aFixedSpaceIsJustAnotherWidth() {
        assertEquals(listOf(40, 80, 104), barPositions(listOf(40, 24, 56), no(3), 200))
    }

    @Test
    fun oneStretch_pushesItsNeighboursToTheEdges() {
        // A, stretch, B in 200: A at the left edge, B ending exactly at the right one.
        val xs = barPositions(listOf(40, 0, 60), listOf(false, true, false), 200)
        assertEquals(listOf(0, 40, 140), xs)
        assertEquals(200, xs[2] + 60)
    }

    @Test
    fun severalStretches_shareTheRoomEvenly_theOddPixelsFirst() {
        // 101 left over between three stretches: 34, 34, 33.
        val xs = barPositions(listOf(0, 33, 0, 33, 0, 33), listOf(true, false, true, false, true, false), 200)
        assertEquals(listOf(0, 34, 67, 101, 134, 167), xs)
        assertEquals(200, xs.last() + 33)
    }

    @Test
    fun aStretchAtTheStart_pushesEverythingRight() {
        assertEquals(listOf(0, 100, 150), barPositions(listOf(0, 50, 50), listOf(true, false, false), 200))
    }

    @Test
    fun whatDoesNotFitIsLeftOut_andTheStretchesShareWhatIsLeft() {
        // The third readout doesn't fit, so it and everything after it go.
        val xs = barPositions(listOf(80, 0, 80, 80), listOf(false, true, false, false), 200)
        assertEquals(listOf(0, 80, 120), xs)
    }

    @Test
    fun unbounded_packsFromTheLeftWithEmptyStretches() {
        assertEquals(listOf(0, 40, 40), barPositions(listOf(40, 0, 60), listOf(false, true, false), null))
    }

    @Test
    fun onlyTheSpacersMayRepeat() {
        assertTrue(BarItem.SPACE.spacer)
        assertTrue(BarItem.STRETCH.spacer)
        assertEquals(setOf(BarItem.SPACE, BarItem.STRETCH), BarItem.entries.filter { it.spacer }.toSet())
        assertFalse(BarItem.CLOCK.spacer)
    }
}
