package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which edge the bar stands on, and what follows from it. */
class BarPositionTest {

    @Test
    fun theStandardBarMovesAndTheSkinsKeepTheirOwn() {
        assertEquals(BarPosition.TOP, barPositionFor(BarPosition.TOP, DashSkin.STANDARD))
        assertEquals(BarPosition.TOP, barPositionFor(BarPosition.TOP, DashSkin.CANVAS))
        assertEquals(BarPosition.BOTTOM, barPositionFor(BarPosition.BOTTOM, DashSkin.STANDARD))
        DashSkin.entries.filter { !barCanMove(it) }.forEach {
            assertEquals("$it", BarPosition.BOTTOM, barPositionFor(BarPosition.TOP, it))
        }
    }

    @Test
    fun onlyStandardAndCanvasCanMove() {
        assertEquals(setOf(DashSkin.STANDARD, DashSkin.CANVAS), DashSkin.entries.filter { barCanMove(it) }.toSet())
    }

    @Test
    fun theHintStaysAwayFromTheBarsEdge() {
        // Button at y 0..60 on a 600 tall window, a hint 40 tall.
        assertEquals(68, hintTop(BarPosition.TOP, anchorTop = 0, anchorBottom = 60, hintHeight = 40, gap = 8, windowHeight = 600))
        // Button at y 540..600: the hint goes above it.
        assertEquals(492, hintTop(BarPosition.BOTTOM, anchorTop = 540, anchorBottom = 600, hintHeight = 40, gap = 8, windowHeight = 600))
    }

    @Test
    fun theHintStaysInsideTheWindow() {
        assertEquals(0, hintTop(BarPosition.BOTTOM, anchorTop = 10, anchorBottom = 70, hintHeight = 40, gap = 8, windowHeight = 600))
        assertEquals(560, hintTop(BarPosition.TOP, anchorTop = 520, anchorBottom = 590, hintHeight = 40, gap = 8, windowHeight = 600))
    }

    @Test
    fun theRevealBandIsAlongTheBarsEdge() {
        assertTrue(inBarEdge(BarPosition.BOTTOM, y = 580f, height = 600, edge = 48f))
        assertFalse(inBarEdge(BarPosition.BOTTOM, y = 20f, height = 600, edge = 48f))
        assertTrue(inBarEdge(BarPosition.TOP, y = 20f, height = 600, edge = 48f))
        assertFalse(inBarEdge(BarPosition.TOP, y = 580f, height = 600, edge = 48f))
    }

    @Test
    fun theBarIsPulledInFromItsEdgeTowardTheMiddle() {
        // Up is negative on the screen.
        assertEquals(30f, barPull(BarPosition.BOTTOM, -30f), 0f)
        assertEquals(30f, barPull(BarPosition.TOP, 30f), 0f)
        assertTrue(barPull(BarPosition.BOTTOM, 30f) < 0f)
        assertTrue(barPull(BarPosition.TOP, -30f) < 0f)
    }
}
